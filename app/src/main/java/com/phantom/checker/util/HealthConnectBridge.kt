package com.phantom.checker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * #381 Health Connect 生理數據整合橋接層（androidx.health.connect:connect-client:1.1.0）：
 * 1. 增強不依賴：未安裝 HC（含無 GMS 機型）或未授權時誠實降級，絕不 crash（getOrCreate 必 catch IllegalStateException）。
 * 2. 原始生理數值永不出本機：僅於本機 aggregate 聚合，透過固定查表式規則產出單句建議結論。
 * 3. 無 API key 時讀取與 Stats 7 日趨勢照常運作。
 */
object HealthConnectBridge {

    private const val HC_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    val REQUIRED_READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    enum class SdkAvailability {
        AVAILABLE,
        PROVIDER_UPDATE_REQUIRED,
        UNAVAILABLE
    }

    data class DailyHealthPoint(
        val dayLabel: String, // MM/dd
        val sleepHours: Float,
        val steps: Long,
        val avgHeartRateBpm: Long
    )

    data class MorningHealthSnapshot(
        val lastNightSleepHours: Float,
        val yesterdaySteps: Long,
        val yesterdayAvgHeartRate: Long,
        val baseline7dAvgHeartRate: Long
    )

    fun checkAvailability(ctx: Context): SdkAvailability {
        return try {
            when (HealthConnectClient.getSdkStatus(ctx.applicationContext, HC_PROVIDER_PACKAGE)) {
                HealthConnectClient.SDK_AVAILABLE -> SdkAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SdkAvailability.PROVIDER_UPDATE_REQUIRED
                else -> SdkAvailability.UNAVAILABLE
            }
        } catch (_: Throwable) {
            SdkAvailability.UNAVAILABLE
        }
    }

    /** 安全取得 HealthConnectClient：明確捕捉 IllegalStateException 與未裝 HC 之無 GMS ROM 例外 */
    fun getClientSafely(ctx: Context): HealthConnectClient? {
        if (checkAvailability(ctx) != SdkAvailability.AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(ctx.applicationContext)
        } catch (_: IllegalStateException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun hasAllReadPermissions(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val client = getClientSafely(ctx) ?: return@withContext false
        try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_READ_PERMISSIONS)
        } catch (_: Throwable) {
            false
        }
    }

    /** 誠實引導 Intent：Android 14+ 開啟系統 Health Connect 設定；Android 13 導向安裝頁 */
    fun buildInstallOrSettingsIntent(ctx: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sysIntent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (sysIntent.resolveActivity(ctx.packageManager) != null) return sysIntent
        }
        val playIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$HC_PROVIDER_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (playIntent.resolveActivity(ctx.packageManager) != null) return playIntent
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$HC_PROVIDER_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 本機聚合近 7 日睡眠、步數（不指定 DataOrigin 以自動涵蓋 Android 內建步數）與平均心率 */
    suspend fun readLast7DaysTrend(ctx: Context): List<DailyHealthPoint> = withContext(Dispatchers.IO) {
        val client = getClientSafely(ctx) ?: return@withContext emptyList()
        if (!hasAllReadPermissions(ctx)) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val labelFmt = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
        val out = mutableListOf<DailyHealthPoint>()

        for (offset in 6L downTo 0L) {
            val day = today.minusDays(offset)
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            // 睡眠窗口取前晚 18:00 至當日 12:00 以正確涵蓋跨午夜睡眠
            val sleepStart = day.minusDays(1).atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
            val sleepEnd = day.atTime(LocalTime.of(12, 0)).atZone(zone).toInstant()

            val sleepHours = runCatching {
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd)
                    )
                )
                val dur = res[SleepSessionRecord.SLEEP_DURATION_TOTAL]
                if (dur != null) (dur.toMinutes() / 60f) else 0f
            }.getOrDefault(0f)

            val steps = runCatching {
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                res[StepsRecord.COUNT_TOTAL] ?: 0L
            }.getOrDefault(0L)

            val avgHr = runCatching {
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(HeartRateRecord.BPM_AVG),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                res[HeartRateRecord.BPM_AVG] ?: 0L
            }.getOrDefault(0L)

            out.add(
                DailyHealthPoint(
                    dayLabel = day.format(labelFmt),
                    sleepHours = sleepHours,
                    steps = steps,
                    avgHeartRateBpm = avgHr
                )
            )
        }
        out
    }

    /** 晨間簡報用本機聚合快照（前一晚睡眠＋昨日步數與心率＋7 日基準心率） */
    suspend fun readMorningSnapshot(ctx: Context): MorningHealthSnapshot? = withContext(Dispatchers.IO) {
        val trend = readLast7DaysTrend(ctx)
        if (trend.isEmpty()) return@withContext null
        val todayPoint = trend.lastOrNull() ?: return@withContext null
        val yesterdayPoint = trend.getOrNull(trend.size - 2) ?: todayPoint
        val hrSamples = trend.mapNotNull { it.avgHeartRateBpm.takeIf { bpm -> bpm > 0L } }
        val baselineHr = if (hrSamples.size >= 3) hrSamples.average().toLong() else 0L
        MorningHealthSnapshot(
            lastNightSleepHours = todayPoint.sleepHours,
            yesterdaySteps = yesterdayPoint.steps,
            yesterdayAvgHeartRate = yesterdayPoint.avgHeartRateBpm,
            baseline7dAvgHeartRate = baselineHr
        )
    }

    /**
     * #381 查表式規則引擎（固定規則驅動，AI 不可自創建議；無命中回傳 null 寧缺勿濫；
     * 嚴格遵守紅線：輸出僅為規則結論一句話，絕不外送原始數值）。
     */
    fun evaluateLookupRule(
        snapshot: MorningHealthSnapshot,
        hasEarlyEventBefore9Am: Boolean
    ): String? {
        return when {
            snapshot.lastNightSleepHours in 0.1f..5.95f && hasEarlyEventBefore9Am ->
                "昨晚睡眠偏短且今早九點前有排定行程，建議今晚提早 30 分鐘就寢，並為早間行程預留緩衝。"
            snapshot.lastNightSleepHours in 0.1f..5.95f ->
                "昨晚睡眠時數偏短，建議今天中午安排短暫休息以維持專注力。"
            snapshot.yesterdayAvgHeartRate > 0L &&
                snapshot.baseline7dAvgHeartRate > 0L &&
                (snapshot.yesterdayAvgHeartRate - snapshot.baseline7dAvgHeartRate) >= 12L ->
                "近期靜止／平均心率較七日基準明顯偏高，建議今日放緩步調並多補充水分。"
            snapshot.yesterdaySteps in 1L..2999L ->
                "昨日活動量偏低，今日可趁空檔起身伸展或散步 10–15 分鐘。"
            snapshot.yesterdaySteps >= 10_000L ->
                "昨日達成萬步目標，保持相當不錯的日常活動節奏！"
            else -> null
        }
    }
}
