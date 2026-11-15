package com.phantom.checker.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.phantom.checker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.security.MessageDigest
import java.util.UUID

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String = "mistral-small-latest",
    val messages: List<ChatMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 200,
    val temperature: Double = 0.2
)

data class ChatChoice(val message: ChatMessage)
data class ChatResponse(val choices: List<ChatChoice>)

interface ChatApi {
    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body body: ChatRequest
    ): ChatResponse
}

data class AiProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

data class AiProviderPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String
)

enum class ApiKeySource {
    ENCRYPTED_PREFS,
    BUILD_CONFIG,
    NONE
}

/**
 * 多 Provider AI 客戶端（OpenAI 相容 v1/chat/completions 介面）。
 *
 * 讀取順序：
 * 1. EncryptedSharedPreferences 中的 active profile（首次升級自動遷移舊版 mistral_api_key）
 * 2. BuildConfig.MISTRAL_API_KEY（local.properties 注入）
 * 3. 空值（回傳 null）
 */
object AiClient {
    private const val FALLBACK_URL = "https://api.mistral.ai/"
    private const val FALLBACK_MODEL = "mistral-small-latest"
    private const val PREFS_NAME = "phantom_secure_prefs"
    private const val KEY_LEGACY_MISTRAL_API_KEY = "mistral_api_key"
    private const val KEY_AI_PROVIDERS = "ai_providers"
    private const val KEY_ACTIVE_PROVIDER = "ai_active_provider"
    private const val RISK_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    val PRESETS = listOf(
        AiProviderPreset("Mistral", "https://api.mistral.ai/", "mistral-small-latest"),
        AiProviderPreset("OpenAI", "https://api.openai.com/", "gpt-4o-mini"),
        AiProviderPreset("Groq", "https://api.groq.com/openai/", "llama-3.3-70b-versatile"),
        AiProviderPreset("OpenRouter", "https://openrouter.ai/api/", "openai/gpt-4o-mini"),
        AiProviderPreset("Gemini", "https://generativelanguage.googleapis.com/", "gemini-2.0-flash"),
        AiProviderPreset("DeepSeek", "https://api.deepseek.com/", "deepseek-chat")
    )

    private val gson = Gson()

    @Volatile
    private var appContext: Context? = null

    private val apiCache = object : LruCache<String, ChatApi>(16) {}

    private data class CachedRisk(
        val result: Triple<Int, String, String>,
        val cachedAt: Long
    )

    private val riskCache = object : LruCache<String, CachedRisk>(256) {}

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun resolveContext(context: Context?): Context? {
        if (context != null) return context.applicationContext
        appContext?.let { return it }
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
            app?.applicationContext?.also { appContext = it }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getEncryptedPrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun migrateLegacyIfNeeded(prefs: SharedPreferences) {
        val existingJson = prefs.getString(KEY_AI_PROVIDERS, null)
        val legacyKey = prefs.getString(KEY_LEGACY_MISTRAL_API_KEY, null)?.trim()
        if (existingJson.isNullOrBlank() && !legacyKey.isNullOrEmpty()) {
            val migrated = AiProviderProfile(
                id = UUID.randomUUID().toString(),
                name = "Mistral",
                baseUrl = FALLBACK_URL,
                model = FALLBACK_MODEL,
                apiKey = legacyKey
            )
            prefs.edit()
                .putString(KEY_AI_PROVIDERS, gson.toJson(listOf(migrated)))
                .putString(KEY_ACTIVE_PROVIDER, migrated.id)
                .remove(KEY_LEGACY_MISTRAL_API_KEY)
                .apply()
        }
    }

    fun getProfiles(context: Context): List<AiProviderProfile> {
        init(context)
        val prefs = getEncryptedPrefs(context) ?: return emptyList()
        migrateLegacyIfNeeded(prefs)
        val raw = prefs.getString(KEY_AI_PROVIDERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiProviderProfile>>() {}.type
            gson.fromJson<List<AiProviderProfile>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getActiveProviderId(context: Context): String? {
        val prefs = getEncryptedPrefs(context) ?: return null
        migrateLegacyIfNeeded(prefs)
        return prefs.getString(KEY_ACTIVE_PROVIDER, null)?.takeIf { it.isNotBlank() }
    }

    fun saveProfiles(context: Context, profiles: List<AiProviderProfile>, activeId: String?) {
        init(context)
        val prefs = getEncryptedPrefs(context) ?: return
        val resolvedActiveId = when {
            activeId != null && profiles.any { it.id == activeId } -> activeId
            else -> profiles.firstOrNull()?.id
        }
        val editor = prefs.edit()
            .putString(KEY_AI_PROVIDERS, gson.toJson(profiles))
        if (resolvedActiveId != null) {
            editor.putString(KEY_ACTIVE_PROVIDER, resolvedActiveId)
        } else {
            editor.remove(KEY_ACTIVE_PROVIDER)
        }
        editor.apply()
    }

    fun setActiveProvider(context: Context, id: String) {
        init(context)
        val prefs = getEncryptedPrefs(context) ?: return
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, id).apply()
    }

    fun upsertProfile(context: Context, profile: AiProviderProfile, setActive: Boolean = false) {
        val current = getProfiles(context).toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        val activeId = if (setActive || current.size == 1) profile.id else getActiveProviderId(context)
        saveProfiles(context, current, activeId)
    }

    fun deleteProfile(context: Context, id: String) {
        val remaining = getProfiles(context).filterNot { it.id == id }
        val currentActive = getActiveProviderId(context)
        val nextActive = if (currentActive == id) remaining.firstOrNull()?.id else currentActive
        saveProfiles(context, remaining, nextActive)
    }

    fun getBuildConfigApiKey(): String? {
        return try {
            BuildConfig.MISTRAL_API_KEY.trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun getActiveProfile(context: Context? = null): AiProviderProfile? {
        val ctx = resolveContext(context)
        if (ctx != null) {
            val profiles = getProfiles(ctx)
            if (profiles.isNotEmpty()) {
                val activeId = getActiveProviderId(ctx)
                val selected = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
                if (selected.apiKey.isNotBlank()) return selected
            }
        }
        val buildKey = getBuildConfigApiKey() ?: return null
        val buildBaseUrl = try {
            BuildConfig.MISTRAL_BASE_URL.trim().ifEmpty { FALLBACK_URL }
        } catch (_: Exception) {
            FALLBACK_URL
        }
        return AiProviderProfile(
            id = "build_config",
            name = "Mistral (local.properties)",
            baseUrl = buildBaseUrl,
            model = FALLBACK_MODEL,
            apiKey = buildKey
        )
    }

    fun getKeySource(context: Context): ApiKeySource {
        val profiles = getProfiles(context)
        if (profiles.any { it.apiKey.isNotBlank() }) return ApiKeySource.ENCRYPTED_PREFS
        if (getBuildConfigApiKey() != null) return ApiKeySource.BUILD_CONFIG
        return ApiKeySource.NONE
    }

    fun getMaskedKeyTail(key: String?): String? {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.length > 4) "••••${trimmed.takeLast(4)}" else "••••"
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().ifEmpty { FALLBACK_URL }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun getApi(baseUrl: String): ChatApi {
        val normalized = normalizeBaseUrl(baseUrl)
        synchronized(apiCache) {
            apiCache.get(normalized)?.let { return it }
            val created = Retrofit.Builder()
                .baseUrl(normalized)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ChatApi::class.java)
            apiCache.put(normalized, created)
            return created
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val SYSTEM_PROMPT = """
        你是防詐分析引擎。分析使用者收到的訊息，判斷是否為詐騙/釣魚。
        只輸出 JSON：{"risk": 0-100, "label": "safe|suspicious|scam", "reason": "一句話說明"}
    """.trimIndent()

    /** 使用指定的設定發送 max_tokens=5 的最小請求以驗證連線 */
    suspend fun testConnection(
        baseUrl: String,
        model: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("請先輸入 API Key"))
        }
        val resolvedModel = model.trim().ifEmpty { FALLBACK_MODEL }
        try {
            val res = getApi(baseUrl).chat(
                auth = "Bearer $trimmedKey",
                body = ChatRequest(
                    model = resolvedModel,
                    messages = listOf(ChatMessage("user", "Hi")),
                    maxTokens = 5,
                    temperature = 0.0
                )
            )
            if (res.choices.isNotEmpty()) {
                Result.success("連線成功！$resolvedModel 驗證通過")
            } else {
                Result.failure(IllegalStateException("API 回應為空"))
            }
        } catch (e: HttpException) {
            Result.failure(Exception("HTTP ${e.code()}：驗證失敗，請確認 Base URL、Model 與 API Key"))
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: "連線失敗"))
        }
    }

    suspend fun testConnection(profile: AiProviderProfile): Result<String> =
        testConnection(profile.baseUrl, profile.model, profile.apiKey)

    /** 回傳 (分數, 標籤, 原因)；未設定 key 或解析失敗回 null；同內容 24 小時內命中快取 */
    suspend fun analyzeRisk(
        sender: String,
        content: String,
        context: Context? = null
    ): Triple<Int, String, String>? = withContext(Dispatchers.IO) {
        val contentHash = sha256(content)
        val now = System.currentTimeMillis()
        synchronized(riskCache) {
            val cached = riskCache.get(contentHash)
            if (cached != null && (now - cached.cachedAt) < RISK_CACHE_TTL_MS) {
                return@withContext cached.result
            }
        }

        try {
            val profile = getActiveProfile(context) ?: return@withContext null
            val res = getApi(profile.baseUrl).chat(
                auth = "Bearer ${profile.apiKey.trim()}",
                body = ChatRequest(
                    model = profile.model.trim().ifEmpty { FALLBACK_MODEL },
                    messages = listOf(
                        ChatMessage("system", SYSTEM_PROMPT),
                        ChatMessage("user", "寄件者: $sender\n內容: $content")
                    )
                )
            )
            val text = res.choices.firstOrNull()?.message?.content ?: return@withContext null
            val json = text.substringAfter("{").substringBeforeLast("}")
            val risk = Regex("\"risk\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0
            val label = Regex("\"label\"\\s*:\\s*\"(\\w+)\"").find(json)?.groupValues?.get(1) ?: "safe"
            val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val result = Triple(risk, label, reason)
            synchronized(riskCache) {
                riskCache.put(contentHash, CachedRisk(result, now))
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /** 每日 AI 摘要生成；未設定 key 或失敗回 null */
    suspend fun summarizeDaily(
        prompt: String,
        context: Context? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val profile = getActiveProfile(context) ?: return@withContext null
            val res = getApi(profile.baseUrl).chat(
                auth = "Bearer ${profile.apiKey.trim()}",
                body = ChatRequest(
                    model = profile.model.trim().ifEmpty { FALLBACK_MODEL },
                    messages = listOf(
                        ChatMessage("system", "你是個人通訊助理，請用簡潔的繁體中文整理今日訊息重點與詐騙風險提醒。"),
                        ChatMessage("user", prompt)
                    ),
                    maxTokens = 400,
                    temperature = 0.3
                )
            )
            res.choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
