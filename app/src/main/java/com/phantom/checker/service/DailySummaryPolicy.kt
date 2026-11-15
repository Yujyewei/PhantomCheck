package com.phantom.checker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phantom.checker.data.AiSummaryEntity
import com.phantom.checker.network.AiClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 每日 22:00 自動生成當日訊息 AI 摘要的排程 Worker。
 */
class DailySummaryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        DailySummaryPolicy.generateTodaySummary(applicationContext)
        return Result.success()
    }
}

object DailySummaryPolicy {
    private const val UNIQUE_WORK_NAME = "phantom_daily_ai_summary"

    fun schedule(ctx: Context) {
        val appCtx = ctx.applicationContext
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 22)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val initialDelayMs = (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)

        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(appCtx).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun todayDateKey(): Long {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return fmt.format(Date()).toLongOrNull() ?: 0L
    }

    suspend fun generateTodaySummary(ctx: Context): Result<AiSummaryEntity> {
        val appCtx = ctx.applicationContext
        AiClient.init(appCtx)
        if (AiClient.getActiveProfile(appCtx) == null) {
            return Result.failure(IllegalStateException("尚未設定 AI Provider API Key"))
        }

        val startOfDay = NotificationCollectorService.startOfTodayMillis()
        val db = AppDatabaseHolder.db(appCtx)
        val todayMessages = db.messageDao().since(startOfDay)

        if (todayMessages.isEmpty()) {
            return Result.failure(IllegalStateException("今日尚無訊息可供摘要"))
        }

        val appCounts = todayMessages
            .groupBy { it.appLabel.ifBlank { it.packageName } }
            .entries
            .sortedByDescending { it.value.size }
            .joinToString("、") { "${it.key} ${it.value.size} 則" }

        val riskyExcerpts = todayMessages
            .filter { it.riskLabel == "scam" || it.riskLabel == "suspicious" }
            .take(20)
            .mapIndexed { idx, msg ->
                val tag = if (msg.riskLabel == "scam") "詐騙" else "疑似"
                "${idx + 1}. [$tag][${msg.appLabel}] ${msg.sender}: ${msg.content.take(100)}"
            }

        val prompt = buildString {
            appendLine("請用繁體中文 5 句內摘要今日訊息重點與風險：")
            appendLine("今日總訊息數：${todayMessages.size} 則")
            appendLine("各 App 統計：$appCounts")
            if (riskyExcerpts.isNotEmpty()) {
                appendLine("高風險訊息節錄：")
                riskyExcerpts.forEach { appendLine(it) }
            } else {
                appendLine("今日無高風險（scam/suspicious）訊息。")
            }
        }

        val summaryText = AiClient.summarizeDaily(prompt, appCtx)
            ?: return Result.failure(IllegalStateException("AI 摘要生成失敗，請確認網路與 API 設定"))

        val entity = AiSummaryEntity(
            date = todayDateKey(),
            summary = summaryText,
            generatedAt = System.currentTimeMillis()
        )
        db.aiSummaryDao().insertOrReplace(entity)
        return Result.success(entity)
    }
}
