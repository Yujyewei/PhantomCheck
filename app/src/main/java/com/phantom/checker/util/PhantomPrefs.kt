package com.phantom.checker.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MuteMode(val prefValue: String, val label: String) {
    BLOCK_RECORD("block", "完全不記錄"),
    SILENT_RECORD("silent", "記錄但不進統計提醒");

    companion object {
        fun fromPref(value: String?): MuteMode =
            entries.firstOrNull { it.prefValue == value } ?: BLOCK_RECORD
    }
}

enum class NotifClickBehavior(val prefValue: String, val label: String) {
    OPEN_PHANTOM("phantom", "開啟幻訊對話"),
    OPEN_ORIGINAL_APP("original", "開啟原 App");

    companion object {
        fun fromPref(value: String?): NotifClickBehavior =
            entries.firstOrNull { it.prefValue == value } ?: OPEN_PHANTOM
    }
}

enum class FontScaleMode(val prefValue: String, val label: String, val scale: Float) {
    SMALL("small", "小", 0.9f),
    NORMAL("normal", "標準", 1.0f),
    LARGE("large", "大", 1.15f);

    companion object {
        fun fromPref(value: String?): FontScaleMode =
            entries.firstOrNull { it.prefValue == value } ?: NORMAL
    }
}

/**
 * 幻訊 App 通用偏好設定（SharedPreferences）。
 */
object PhantomPrefs {
    private const val PREFS_NAME = "phantom_app_prefs"
    private const val KEY_RECORD_ONGOING = "record_ongoing_notifications"
    private const val KEY_PERSISTENT_STATS = "persistent_stats_notification"
    private const val KEY_FAVORITE_APPS = "favorite_apps"
    private const val KEY_MUTED_APPS = "muted_apps"
    private const val KEY_MUTE_MODES = "muted_app_modes"
    private const val KEY_SCAM_ALERT_NOTIF = "scam_alert_notif_enabled"
    private const val KEY_AI_RISK_ENABLED = "ai_risk_enabled"
    private const val KEY_DAILY_AI_LIMIT = "daily_ai_limit"
    private const val KEY_AI_USAGE_DATE = "ai_usage_date"
    private const val KEY_AI_USAGE_COUNT = "ai_usage_count"
    private const val KEY_DAILY_SUMMARY_ENABLED = "daily_summary_enabled"
    private const val KEY_DAILY_SUMMARY_HOUR = "daily_summary_hour"
    private const val KEY_DAILY_SUMMARY_MINUTE = "daily_summary_minute"
    private const val KEY_NOTIF_CLICK_BEHAVIOR = "notif_click_behavior"
    private const val KEY_SAME_KEY_THROTTLE_SEC = "same_key_throttle_sec"
    private const val KEY_RETENTION_MAX_COUNT = "retention_max_count"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color_enabled"
    private const val KEY_FONT_SCALE = "font_scale_mode"
    private const val KEY_ALERT_KEYWORDS = "instant_alert_keywords"

    const val MAX_ALERT_KEYWORDS = 20

    private val gson = Gson()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否記錄持續性通知（預設關閉） */
    fun isRecordOngoingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECORD_ONGOING, false)

    fun setRecordOngoingEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RECORD_ONGOING, enabled).apply()
    }

    /** 是否顯示通知欄常駐統計通知（預設開啟） */
    fun isPersistentStatsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PERSISTENT_STATS, true)

    fun setPersistentStatsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PERSISTENT_STATS, enabled).apply()
    }

    /** 我的最愛 App 清單（依釘選順序保存 packageName） */
    fun getFavoriteApps(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_FAVORITE_APPS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isFavoriteApp(ctx: Context, packageName: String): Boolean =
        packageName in getFavoriteApps(ctx)

    /** 釘選或取消釘選我的最愛 App，回傳更新後是否為最愛 */
    fun toggleFavoriteApp(ctx: Context, packageName: String): Boolean {
        val current = getFavoriteApps(ctx).toMutableList()
        val added = if (packageName in current) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs(ctx).edit().putString(KEY_FAVORITE_APPS, gson.toJson(current)).apply()
        return added
    }

    /** App 靜音清單（packageName 集合） */
    fun getMutedApps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_MUTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun isAppMuted(ctx: Context, packageName: String): Boolean =
        packageName in getMutedApps(ctx)

    fun setAppMuted(ctx: Context, packageName: String, muted: Boolean, mode: MuteMode? = null) {
        val updated = getMutedApps(ctx).toMutableSet()
        if (muted) updated.add(packageName) else updated.remove(packageName)
        prefs(ctx).edit().putStringSet(KEY_MUTED_APPS, updated).apply()
        if (muted && mode != null) {
            setAppMuteMode(ctx, packageName, mode)
        }
    }

    fun getAppMuteModeMap(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_MUTE_MODES, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getAppMuteMode(ctx: Context, packageName: String): MuteMode =
        MuteMode.fromPref(getAppMuteModeMap(ctx)[packageName])

    fun setAppMuteMode(ctx: Context, packageName: String, mode: MuteMode) {
        val map = getAppMuteModeMap(ctx).toMutableMap()
        map[packageName] = mode.prefValue
        prefs(ctx).edit().putString(KEY_MUTE_MODES, gson.toJson(map)).apply()
    }

    /** 詐騙警示彈出通知開關（預設開） */
    fun isScamAlertNotifEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCAM_ALERT_NOTIF, true)

    fun setScamAlertNotifEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SCAM_ALERT_NOTIF, enabled).apply()
    }

    /** AI 風險分析開關（預設開） */
    fun isAiRiskEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_RISK_ENABLED, true)

    fun setAiRiskEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AI_RISK_ENABLED, enabled).apply()
    }

    /** 每日 AI 分析上限（預設 50；0 = 不限） */
    fun getDailyAiLimit(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DAILY_AI_LIMIT, 50)

    fun setDailyAiLimit(ctx: Context, limit: Int) {
        prefs(ctx).edit().putInt(KEY_DAILY_AI_LIMIT, limit.coerceAtLeast(0)).apply()
    }

    private fun todayDateStr(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun getTodayAiUsage(ctx: Context): Int {
        val p = prefs(ctx)
        val today = todayDateStr()
        return if (p.getString(KEY_AI_USAGE_DATE, "") == today) {
            p.getInt(KEY_AI_USAGE_COUNT, 0)
        } else {
            0
        }
    }

    /** 檢查今日 AI 額度並遞增計數；若超過上限回傳 false */
    @Synchronized
    fun canUseAiTodayAndIncrement(ctx: Context): Boolean {
        val limit = getDailyAiLimit(ctx)
        val p = prefs(ctx)
        val today = todayDateStr()
        val storedDate = p.getString(KEY_AI_USAGE_DATE, "")
        val currentCount = if (storedDate == today) p.getInt(KEY_AI_USAGE_COUNT, 0) else 0
        if (limit > 0 && currentCount >= limit) {
            return false
        }
        p.edit()
            .putString(KEY_AI_USAGE_DATE, today)
            .putInt(KEY_AI_USAGE_COUNT, currentCount + 1)
            .apply()
        return true
    }

    /** 每日 AI 摘要開關與排程時間（預設開、22:00） */
    fun isDailySummaryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DAILY_SUMMARY_ENABLED, true)

    fun setDailySummaryEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DAILY_SUMMARY_ENABLED, enabled).apply()
    }

    fun getDailySummaryHour(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DAILY_SUMMARY_HOUR, 22).coerceIn(0, 23)

    fun getDailySummaryMinute(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DAILY_SUMMARY_MINUTE, 0).coerceIn(0, 59)

    fun setDailySummaryTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit()
            .putInt(KEY_DAILY_SUMMARY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_DAILY_SUMMARY_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    /** 通知點擊行為（預設開啟幻訊） */
    fun getNotifClickBehavior(ctx: Context): NotifClickBehavior =
        NotifClickBehavior.fromPref(prefs(ctx).getString(KEY_NOTIF_CLICK_BEHAVIOR, null))

    fun setNotifClickBehavior(ctx: Context, behavior: NotifClickBehavior) {
        prefs(ctx).edit().putString(KEY_NOTIF_CLICK_BEHAVIOR, behavior.prefValue).apply()
    }

    /** 同 sbn.key 更新節流秒數（預設 60，0 = 關閉） */
    fun getSameKeyThrottleSeconds(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SAME_KEY_THROTTLE_SEC, 60).coerceIn(0, 300)

    fun setSameKeyThrottleSeconds(ctx: Context, seconds: Int) {
        prefs(ctx).edit().putInt(KEY_SAME_KEY_THROTTLE_SEC, seconds.coerceIn(0, 300)).apply()
    }

    /** 訊息保留則數上限（-1 = 不限，1000 / 5000） */
    fun getRetentionMaxCount(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RETENTION_MAX_COUNT, -1)

    fun setRetentionMaxCount(ctx: Context, maxCount: Int) {
        prefs(ctx).edit().putInt(KEY_RETENTION_MAX_COUNT, maxCount).apply()
    }

    /** Material You 動態色（Android 12+，預設關閉） */
    fun isDynamicColorEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColorEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    /** 字體大小設定 */
    fun getFontScaleMode(ctx: Context): FontScaleMode =
        FontScaleMode.fromPref(prefs(ctx).getString(KEY_FONT_SCALE, null))

    fun setFontScaleMode(ctx: Context, mode: FontScaleMode) {
        prefs(ctx).edit().putString(KEY_FONT_SCALE, mode.prefValue).apply()
    }

    /** 即時提醒關鍵字清單（最多 20 組，獨立於過濾規則） */
    fun getAlertKeywords(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_ALERT_KEYWORDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addAlertKeyword(ctx: Context, keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        val current = getAlertKeywords(ctx).toMutableList()
        if (current.size >= MAX_ALERT_KEYWORDS) return false
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        current.add(trimmed)
        prefs(ctx).edit().putString(KEY_ALERT_KEYWORDS, gson.toJson(current)).apply()
        return true
    }

    fun removeAlertKeyword(ctx: Context, keyword: String) {
        val updated = getAlertKeywords(ctx).filterNot { it.equals(keyword, ignoreCase = true) }
        prefs(ctx).edit().putString(KEY_ALERT_KEYWORDS, gson.toJson(updated)).apply()
    }
}
