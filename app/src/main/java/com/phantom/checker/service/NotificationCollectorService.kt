package com.phantom.checker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import com.phantom.checker.R
import com.phantom.checker.data.AppDatabase
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.network.AiClient
import com.phantom.checker.ui.MainActivity
import com.phantom.checker.ui.ScamCenterActivity
import com.phantom.checker.util.PhantomPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Calendar

/**
 * 核心：通知攔截服務。
 * 讀取通知純粹是「被動接收系統廣播」，不會開啟原 App，因此不觸發已讀。
 */
class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connectTime: Long = 0L

    private val dedupCache = object : LruCache<String, Long>(256) {}

    private val statsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_UPDATE_STATS) {
                scope.launch {
                    updateStatsNotification(applicationContext)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AiClient.init(applicationContext)
        val filter = IntentFilter(ACTION_UPDATE_STATS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsUpdateReceiver, filter)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statsUpdateReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectTime = System.currentTimeMillis()
        scope.launch {
            updateStatsNotification(applicationContext)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        // 重播防護：忽略連線前 5 秒以前的系統重播通知
        val connectedAt = connectTime
        if (connectedAt > 0L && sbn.postTime < connectedAt - REPLAY_GRACE_MS) return

        // App 靜音清單過濾
        if (PhantomPrefs.isAppMuted(applicationContext, pkg)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 持續性通知過濾（預設略過）
        val isOngoing = sbn.isOngoing ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (isOngoing && !PhantomPrefs.isRecordOngoingEnabled(applicationContext)) return

        // 群組摘要通知過濾
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
            extras.getBoolean("android.isGroupSummary", false)
        if (isGroupSummary) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return

        // 10 分鐘內容去重
        val dedupKey = sha256("$pkg|${sbn.key}|$title|$text")
        synchronized(dedupCache) {
            val lastTime = dedupCache.get(dedupKey)
            if (lastTime != null && (sbn.postTime - lastTime) < DEDUP_WINDOW_MS) {
                return
            }
            dedupCache.put(dedupKey, sbn.postTime)
        }

        scope.launch {
            val db = AppDatabaseHolder.db(applicationContext)
            val rules = db.filterDao().active()
            val filtered = rules.any { rule ->
                if (rule.packageName != null && rule.packageName != pkg) return@any false
                text.contains(rule.keyword, ignoreCase = true)
            }

            val id = db.messageDao().insert(
                MessageEntity(
                    packageName = pkg,
                    appLabel = appLabel(pkg),
                    sender = title,
                    content = text,
                    timestamp = sbn.postTime,
                    isFiltered = filtered,
                    dedupKey = dedupKey
                )
            )

            updateStatsNotification(applicationContext)

            if (!filtered) {
                AiClient.analyzeRisk(title, text, applicationContext)?.let { (risk, label, reason) ->
                    val msg = db.messageDao().byId(id)
                    if (msg != null) {
                        db.messageDao().update(
                            msg.copy(riskScore = risk, riskLabel = label, riskReason = reason)
                        )
                    }
                    if (label == "scam") {
                        alertScam(applicationContext, id, title, reason)
                        updateStatsNotification(applicationContext)
                    }
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun alertScam(ctx: Context, msgId: Long, sender: String, reason: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel("scam_alert", "詐騙警示", NotificationManager.IMPORTANCE_HIGH)
        )
        val notifId = ((msgId % Int.MAX_VALUE).toInt()).coerceAtLeast(2000)
        val intent = Intent(ctx, ScamCenterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(ctx, "scam_alert")
            .setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentTitle("⚠️ 偵測到高風險訊息：$sender")
            .setContentText(reason.ifBlank { "請前往詐騙警示中心查看風險分析" })
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notif)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_UPDATE_STATS = "com.phantom.checker.ACTION_UPDATE_STATS"
        private const val STATS_CHANNEL_ID = "stats_summary"
        private const val STATS_NOTIF_ID = 1001
        private const val DEDUP_WINDOW_MS = 10L * 60L * 1000L
        private const val REPLAY_GRACE_MS = 5000L

        fun requestStatsUpdate(ctx: Context) {
            val appCtx = ctx.applicationContext
            appCtx.sendBroadcast(
                Intent(ACTION_UPDATE_STATS).setPackage(appCtx.packageName)
            )
        }

        suspend fun updateStatsNotification(ctx: Context) {
            val appCtx = ctx.applicationContext
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
            if (!PhantomPrefs.isPersistentStatsEnabled(appCtx)) {
                nm.cancel(STATS_NOTIF_ID)
                return
            }

            nm.createNotificationChannel(
                NotificationChannel(
                    STATS_CHANNEL_ID,
                    "統計",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            val startOfDay = startOfTodayMillis()
            val dao = AppDatabaseHolder.db(appCtx).messageDao()
            val todayCount = dao.todayCount(startOfDay)
            val unreadCount = dao.unreadCount()
            val todayScamCount = dao.todayScamCount(startOfDay)

            val intent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                appCtx,
                STATS_NOTIF_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = Notification.Builder(appCtx, STATS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_phantom)
                .setContentTitle("幻訊・今日 $todayCount 則訊息")
                .setContentText("未讀 $unreadCount 則・詐騙警示 $todayScamCount 則")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build()

            try {
                nm.notify(STATS_NOTIF_ID, notif)
            } catch (_: SecurityException) {
            }
        }

        fun startOfTodayMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Database 單例 */
object AppDatabaseHolder {
    @Volatile private var instance: AppDatabase? = null
    fun db(ctx: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: androidx.room.Room.databaseBuilder(
                ctx.applicationContext, AppDatabase::class.java, "phantomcheck.db"
            ).addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3
            ).build().also { instance = it }
        }
}
