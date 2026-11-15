package com.phantom.checker.service

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phantom.checker.util.PhantomPrefs
import java.util.concurrent.TimeUnit

/**
 * 每 15 分鐘檢查常駐統計通知是否仍存在，若遭系統清除或使用者滑掉則自動重生。
 */
class HeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!PhantomPrefs.isPersistentStatsEnabled(ctx) ||
            !StatsForegroundService.hasPostNotificationsPermission(ctx)
        ) {
            return Result.success()
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val hasStatsNotif = nm?.activeNotifications?.any {
            it.id == StatsForegroundService.STATS_NOTIF_ID
        } == true
        if (!hasStatsNotif) {
            StatsForegroundService.startOrRefresh(ctx)
        }
        return Result.success()
    }
}

object HeartbeatPolicy {
    private const val UNIQUE_WORK_NAME = "phantom_stats_heartbeat"

    fun schedule(ctx: Context) {
        val appCtx = ctx.applicationContext
        val workManager = WorkManager.getInstance(appCtx)
        if (!PhantomPrefs.isPersistentStatsEnabled(appCtx)) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
