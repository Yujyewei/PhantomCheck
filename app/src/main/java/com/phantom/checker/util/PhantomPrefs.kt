package com.phantom.checker.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 幻訊 App 通用偏好設定（SharedPreferences）。
 */
object PhantomPrefs {
    private const val PREFS_NAME = "phantom_app_prefs"
    private const val KEY_RECORD_ONGOING = "record_ongoing_notifications"
    private const val KEY_PERSISTENT_STATS = "persistent_stats_notification"
    private const val KEY_FAVORITE_APPS = "favorite_apps"
    private const val KEY_MUTED_APPS = "muted_apps"

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

    /** App 靜音清單（不記錄通知的 packageName 集合） */
    fun getMutedApps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_MUTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun isAppMuted(ctx: Context, packageName: String): Boolean =
        packageName in getMutedApps(ctx)

    fun setAppMuted(ctx: Context, packageName: String, muted: Boolean) {
        val updated = getMutedApps(ctx).toMutableSet()
        if (muted) updated.add(packageName) else updated.remove(packageName)
        prefs(ctx).edit().putStringSet(KEY_MUTED_APPS, updated).apply()
    }
}
