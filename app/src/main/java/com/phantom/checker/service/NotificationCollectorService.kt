package com.phantom.checker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import com.phantom.checker.R
import com.phantom.checker.data.AppDatabase
import com.phantom.checker.data.FilterRule
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.network.AiClient
import com.phantom.checker.ui.MessageDetailActivity
import com.phantom.checker.ui.ScamCenterActivity
import com.phantom.checker.util.MuteMode
import com.phantom.checker.util.NotifClickBehavior
import com.phantom.checker.util.PhantomPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 核心：通知攔截服務。
 * 讀取通知純粹是「被動接收系統廣播」，不會開啟原 App，因此不觸發已讀。
 */
class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connectTime: Long = 0L

    private val dedupCache = object : LruCache<String, Long>(256) {}
    private val sameKeyLastTs = object : LruCache<String, Long>(256) {}
    private val appLabelCache = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        AiClient.init(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectTime = System.currentTimeMillis()
        StatsForegroundService.startOrRefresh(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        // 重播防護：忽略連線前 5 秒以前的系統重播通知
        val connectedAt = connectTime
        if (connectedAt > 0L && sbn.postTime < connectedAt - REPLAY_GRACE_MS) return

        // 兩段式 App 靜音檢查
        val isMuted = PhantomPrefs.isAppMuted(applicationContext, pkg)
        val muteMode = if (isMuted) PhantomPrefs.getAppMuteMode(applicationContext, pkg) else null
        if (muteMode == MuteMode.BLOCK_RECORD) return

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

        // 同 sbn.key 更新節流（例如 GlassWire 每分鐘更新但數字微變）
        val sbnKey = sbn.key
        val throttleSec = PhantomPrefs.getSameKeyThrottleSeconds(applicationContext)
        if (throttleSec > 0 && !sbnKey.isNullOrBlank()) {
            synchronized(sameKeyLastTs) {
                val prevKeyTs = sameKeyLastTs.get(sbnKey)
                if (prevKeyTs != null && (sbn.postTime - prevKeyTs) < throttleSec * 1000L) {
                    return
                }
            }
        }

        // 10 分鐘同內容去重
        val dedupKey = sha256("$pkg|$sbnKey|$title|$text")
        synchronized(dedupCache) {
            val lastTime = dedupCache.get(dedupKey)
            if (lastTime != null && (sbn.postTime - lastTime) < DEDUP_WINDOW_MS) {
                return
            }
            dedupCache.put(dedupKey, sbn.postTime)
        }
        if (!sbnKey.isNullOrBlank()) {
            synchronized(sameKeyLastTs) {
                sameKeyLastTs.put(sbnKey, sbn.postTime)
            }
        }

        scope.launch {
            val db = AppDatabaseHolder.db(applicationContext)
            val rules = db.filterDao().active()
            val matchedRule = rules.firstOrNull { rule ->
                if (rule.packageName != null && rule.packageName != pkg) return@firstOrNull false
                text.contains(rule.keyword, ignoreCase = true)
            }

            if (matchedRule?.action == FilterRule.ACTION_DROP) {
                return@launch
            }

            val isRuleScam = matchedRule?.action == FilterRule.ACTION_SCAM
            val isRuleFiltered = matchedRule != null
            val silentMuted = muteMode == MuteMode.SILENT_RECORD

            val id = db.messageDao().insert(
                MessageEntity(
                    packageName = pkg,
                    appLabel = cachedAppLabel(pkg),
                    sender = title,
                    content = text,
                    timestamp = sbn.postTime,
                    riskScore = if (isRuleScam) 95 else 0,
                    riskLabel = if (isRuleScam) "scam" else "",
                    riskReason = if (isRuleScam) "命中自訂詐騙過濾規則：${matchedRule?.keyword}" else "",
                    isFiltered = isRuleFiltered,
                    isRead = silentMuted,
                    dedupKey = dedupKey
                )
            )

            if (!silentMuted) {
                StatsForegroundService.startOrRefresh(applicationContext)
            }

            // 即時提醒關鍵字檢查（與過濾規則獨立）
            if (!silentMuted) {
                val alertKeywords = PhantomPrefs.getAlertKeywords(applicationContext)
                val hitAlertKw = alertKeywords.firstOrNull { kw ->
                    text.contains(kw, ignoreCase = true) || title.contains(kw, ignoreCase = true)
                }
                if (hitAlertKw != null) {
                    alertImportant(applicationContext, id, pkg, title, text)
                }
            }

            if (isRuleScam && !silentMuted) {
                alertScam(
                    applicationContext,
                    id,
                    pkg,
                    title,
                    "命中自訂詐騙過濾規則：${matchedRule?.keyword}"
                )
                return@launch
            }

            // AI 風險分析：未命中過濾規則、AI 開關開啟、內文 >= 10 字元、未達每日上限
            if (!isRuleFiltered &&
                !silentMuted &&
                PhantomPrefs.isAiRiskEnabled(applicationContext) &&
                text.length >= MIN_AI_CONTENT_LENGTH &&
                PhantomPrefs.canUseAiTodayAndIncrement(applicationContext)
            ) {
                AiClient.analyzeRisk(title, text, applicationContext)?.let { (risk, label, reason) ->
                    val msg = db.messageDao().byId(id)
                    if (msg != null) {
                        db.messageDao().update(
                            msg.copy(riskScore = risk, riskLabel = label, riskReason = reason)
                        )
                    }
                    if (label == "scam") {
                        alertScam(applicationContext, id, pkg, title, reason)
                        StatsForegroundService.startOrRefresh(applicationContext)
                    }
                }
            }
        }
    }

    private fun cachedAppLabel(pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val resolved = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            pkg
        }
        appLabelCache[pkg] = resolved
        return resolved
    }

    private fun buildClickPendingIntent(
        ctx: Context,
        requestCode: Int,
        msgId: Long,
        packageName: String
    ): PendingIntent {
        val behavior = PhantomPrefs.getNotifClickBehavior(ctx)
        val intent = if (behavior == NotifClickBehavior.OPEN_ORIGINAL_APP) {
            ctx.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            } ?: Intent(ctx, ScamCenterActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            MessageDetailActivity.createIntent(ctx, msgId).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alertScam(
        ctx: Context,
        msgId: Long,
        pkg: String,
        sender: String,
        reason: String
    ) {
        if (!PhantomPrefs.isScamAlertNotifEnabled(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel("scam_alert", "詐騙警示", NotificationManager.IMPORTANCE_HIGH)
        )
        val notifId = 2000 + ((msgId % 40000L).toInt())
        val pendingIntent = buildClickPendingIntent(ctx, notifId, msgId, pkg)
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

    private fun alertImportant(
        ctx: Context,
        msgId: Long,
        pkg: String,
        sender: String,
        content: String
    ) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel("important_alert", "重要訊息提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val notifId = 50000 + ((msgId % 40000L).toInt())
        val pendingIntent = buildClickPendingIntent(ctx, notifId, msgId, pkg)
        val notif = Notification.Builder(ctx, "important_alert")
            .setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentTitle("🔔 重要訊息：$sender")
            .setContentText(content.take(120))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notif)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 10L * 60L * 1000L
        private const val REPLAY_GRACE_MS = 5000L
        private const val MIN_AI_CONTENT_LENGTH = 10

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
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            ).build().also { instance = it }
        }
}
