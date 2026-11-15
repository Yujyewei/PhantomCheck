package com.phantom.checker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.phantom.checker.R
import com.phantom.checker.ui.MainActivity
import com.phantom.checker.util.PhantomPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 獨立的前景服務（START_STICKY），負責維護通知欄常駐統計通知，
 * 避免 NotificationListenerService 在滑掉 App 卡片、重開機或 OEM 省電凍結時中斷統計更新。
 */
class StatsForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastUpdateMs = 0L

    @Volatile
    private var pendingScheduled = false

    @Volatile
    private var isForegroundStarted = false

    private val delayedRefreshRunnable = Runnable {
        pendingScheduled = false
        performRefresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PhantomPrefs.isPersistentStatsEnabled(applicationContext)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasPostNotificationsPermission(applicationContext)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // 首次啟動必須立即呼叫 startForeground 以符合 Android 8+ 規範
        if (!isForegroundStarted) {
            val initialNotif = buildNotification(today = 0, unread = 0, scam = 0)
            startForegroundCompat(initialNotif)
            isForegroundStarted = true
        }

        scheduleThrottledRefresh()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (PhantomPrefs.isPersistentStatsEnabled(applicationContext) &&
            hasPostNotificationsPermission(applicationContext)
        ) {
            startOrRefresh(applicationContext)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedRefreshRunnable)
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleThrottledRefresh() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateMs
        if (elapsed >= THROTTLE_MS) {
            mainHandler.removeCallbacks(delayedRefreshRunnable)
            pendingScheduled = false
            performRefresh()
        } else if (!pendingScheduled) {
            pendingScheduled = true
            mainHandler.postDelayed(delayedRefreshRunnable, THROTTLE_MS - elapsed)
        }
    }

    private fun performRefresh() {
        lastUpdateMs = System.currentTimeMillis()
        scope.launch {
            if (!PhantomPrefs.isPersistentStatsEnabled(applicationContext) ||
                !hasPostNotificationsPermission(applicationContext)
            ) {
                stopForegroundCompat()
                stopSelf()
                return@launch
            }
            try {
                val startOfDay = NotificationCollectorService.startOfTodayMillis()
                val stats = AppDatabaseHolder.db(applicationContext)
                    .messageDao()
                    .statsOverview(startOfDay)
                val notif = buildNotification(
                    today = stats.today,
                    unread = stats.unread,
                    scam = stats.scam
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(STATS_NOTIF_ID, notif)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                STATS_CHANNEL_ID,
                "統計",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(today: Int, unread: Int, scam: Int): Notification {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            STATS_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(applicationContext, STATS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentTitle("幻訊・今日 $today 則訊息")
            .setContentText("未讀 $unread 則・詐騙警示 $scam 則")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                STATS_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(STATS_NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
    }

    companion object {
        const val STATS_CHANNEL_ID = "stats_summary"
        const val STATS_NOTIF_ID = 1001
        private const val THROTTLE_MS = 5000L

        fun hasPostNotificationsPermission(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun startOrRefresh(ctx: Context) {
            val appCtx = ctx.applicationContext
            if (!PhantomPrefs.isPersistentStatsEnabled(appCtx)) {
                stop(appCtx)
                return
            }
            if (!hasPostNotificationsPermission(appCtx)) {
                return
            }
            val intent = Intent(appCtx, StatsForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            val appCtx = ctx.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, StatsForegroundService::class.java))
                appCtx.getSystemService(NotificationManager::class.java)?.cancel(STATS_NOTIF_ID)
            } catch (_: Exception) {
            }
        }
    }
}
