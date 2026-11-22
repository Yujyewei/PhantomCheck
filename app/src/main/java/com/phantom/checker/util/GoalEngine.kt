package com.phantom.checker.util

import android.content.Context
import com.phantom.checker.data.GoalEntity
import com.phantom.checker.data.GoalProgressEntity
import com.phantom.checker.data.TodoEntity
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.service.ProactiveEngine
import com.phantom.checker.service.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * #379 長程目標追蹤引擎：
 * - 結構訊號前置過濾（時間跨度／重複性詞＋習慣意圖）＋ 置信門檻（<60 全靜默，≥60 必經確認卡，永不自動建）。
 * - 確認後拆解週期子 todo，複用 ReminderScheduler；進度歸因僅限明確關聯子 todo（[目標#id]），絕不模糊歸因。
 * - 只降不升動態調整：連 3 次逾期未互動自動降頻一級（1→7→14→30 天）並告知一次；升頻僅限使用者手動調整。
 * - 無 API key 時手動建立與全部追蹤流程 100% 可用。
 */
object GoalEngine {

    private val GOAL_TAG_REGEX = Regex("""^\[目標#(\d+)]\s*""")

    private val CADENCE_PATTERNS: List<Pair<Regex, Int>> = listOf(
        Regex("(?:每天|每日|天天|一天一次)") to 1,
        Regex("(?:每週|每周|每星期|每禮拜|一週一次|一周一次)") to 7,
        Regex("(?:每兩週|每两周|雙週|隔週|兩週一次)") to 14,
        Regex("(?:每月|每個月|一個月一次)") to 30
    )

    private val GOAL_INTENT_KEYWORDS = listOf(
        "想養成", "養成", "目標", "習慣", "堅持", "連續", "打卡", "定期", "固定", "計畫", "計劃", "練習", "運動", "跑步", "背單字", "閱讀", "喝水", "早睡"
    )

    data class GoalCandidate(
        val title: String,
        val cadenceDays: Int,
        val confidence: Int,
        val messageHash: String
    )

    data class GoalDisplayItem(
        val goal: GoalEntity,
        val completedCount: Int,
        val streakDays: Int,
        val nextMilestoneDate: String
    )

    fun cadenceLabel(cadenceDays: Int): String = when {
        cadenceDays <= 1 -> "每天"
        cadenceDays <= 7 -> "每週一次"
        cadenceDays <= 14 -> "每兩週一次"
        else -> "每月一次"
    }

    fun nextLowerCadence(currentDays: Int): Int? = when {
        currentDays <= 1 -> 7
        currentDays <= 7 -> 14
        currentDays <= 14 -> 30
        else -> null
    }

    fun hasGoalStructuralSignal(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 5 || clean.length > 200) return false
        val hasCadence = CADENCE_PATTERNS.any { it.first.containsMatchIn(clean) }
        val hasIntent = GOAL_INTENT_KEYWORDS.any { clean.contains(it, ignoreCase = true) }
        return hasCadence && hasIntent
    }

    /**
     * 偵測聊天訊息中的目標候選：
     * - 未命中結構訊號或已按「不感興趣」→ 直接回傳 null（零 UI、零 token）。
     * - confidence < 60 → 全鏈靜默回傳 null；≥60 回傳候選供前端渲染確認卡（永不自動建立）。
     */
    fun detectGoalCandidate(ctx: Context, userMessage: String): GoalCandidate? {
        val appCtx = ctx.applicationContext
        if (!PhantomPrefs.isGoalTrackingEnabled(appCtx)) return null
        if (!hasGoalStructuralSignal(userMessage)) return null

        val clean = userMessage.trim()
        val msgHash = sha256Short(clean)
        if (PhantomPrefs.isGoalCandidateDismissed(appCtx, msgHash)) return null

        val cadenceDays = CADENCE_PATTERNS.firstOrNull { it.first.containsMatchIn(clean) }?.second ?: 7
        var score = 50
        if (clean.contains("目標") || clean.contains("養成") || clean.contains("習慣")) score += 20
        if (clean.contains("堅持") || clean.contains("連續") || clean.contains("打卡") || clean.contains("計畫") || clean.contains("計劃")) score += 15
        if (CADENCE_PATTERNS.any { it.first.containsMatchIn(clean) }) score += 10
        // 排除純抱怨／問句
        if (clean.endsWith("?") || clean.endsWith("？")) score -= 25

        if (score < 60) return null

        val title = clean
            .replace(Regex("""^(?:我)?(?:想要|想|決定|打算|準備|希望能|希望)?(?:養成|建立|開始)?"""), "")
            .replace(Regex("""(?:的習慣|這個目標|目標|習慣)$"""), "")
            .trim()
            .ifBlank { clean }
            .take(36)

        return GoalCandidate(
            title = title,
            cadenceDays = cadenceDays,
            confidence = score.coerceIn(60, 95),
            messageHash = msgHash
        )
    }

    /** 僅在使用者於確認卡點擊「同意建立」或手動建立按鈕時呼叫；絕不自動執行 */
    suspend fun createGoalAndScheduleNextTodo(
        ctx: Context,
        title: String,
        cadenceDays: Int,
        targetCount: Int = 12
    ): GoalEntity = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        val db = AppDatabaseHolder.db(appCtx)
        val today = LocalDate.now()
        val safeCadence = when {
            cadenceDays <= 1 -> 1
            cadenceDays <= 7 -> 7
            cadenceDays <= 14 -> 14
            else -> 30
        }
        val entity = GoalEntity(
            title = title.trim().take(40),
            cadenceDays = safeCadence,
            startDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            milestoneJson = "[3,7,14,30]",
            targetCount = targetCount.coerceIn(3, 365),
            status = GoalEntity.STATUS_ACTIVE,
            missedPushStreak = 0,
            createdAt = System.currentTimeMillis()
        )
        val goalId = db.goalDao().insertGoal(entity)
        val saved = entity.copy(id = goalId)
        spawnNextChildTodo(appCtx, saved, baseDate = today)
        PhantomSettingsBus.notifyChanged()
        saved
    }

    /** 拆解並排程下一期明確關聯的子 todo（標題帶 [目標#id] 標籤），提醒走既有 ReminderScheduler */
    suspend fun spawnNextChildTodo(
        ctx: Context,
        goal: GoalEntity,
        baseDate: LocalDate = LocalDate.now()
    ): Long = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        val db = AppDatabaseHolder.db(appCtx)
        val zone = ZoneId.systemDefault()
        val nextDate = baseDate.plusDays(goal.cadenceDays.toLong().coerceAtLeast(1L))
        val dueMs = nextDate.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
        val childTitle = "[目標#${goal.id}] ${goal.title}"
        val todoId = db.todoDao().insert(
            TodoEntity(
                title = childTitle,
                dueAt = dueMs,
                isDone = false,
                createdAt = System.currentTimeMillis()
            )
        )
        ReminderScheduler.scheduleTodo(appCtx, todoId, dueMs)
        todoId
    }

    /** 保守進度歸因：僅明確帶有 [目標#id] 的子 todo 完成時才計入進度並排程下一期 */
    suspend fun onTodoCompleted(ctx: Context, todo: TodoEntity): Boolean = withContext(Dispatchers.IO) {
        val match = GOAL_TAG_REGEX.find(todo.title) ?: return@withContext false
        val goalId = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@withContext false
        val appCtx = ctx.applicationContext
        val db = AppDatabaseHolder.db(appCtx)
        val goal = db.goalDao().byId(goalId) ?: return@withContext false
        if (goal.status != GoalEntity.STATUS_ACTIVE) return@withContext false

        val inserted = db.goalDao().insertProgress(
            GoalProgressEntity(
                goalId = goalId,
                todoId = todo.id,
                completedAt = todo.completedAt ?: System.currentTimeMillis()
            )
        )
        if (inserted <= 0L) return@withContext false

        // 有互動即歸零連續忽略計數（絕不自動升頻）
        if (goal.missedPushStreak != 0) {
            db.goalDao().updateGoal(goal.copy(missedPushStreak = 0))
        }
        val progressCount = db.goalDao().progressForGoal(goalId).size
        if (progressCount >= goal.targetCount) {
            db.goalDao().updateGoal(goal.copy(status = GoalEntity.STATUS_COMPLETED, missedPushStreak = 0))
        } else {
            spawnNextChildTodo(appCtx, goal, LocalDate.now())
        }
        PhantomSettingsBus.notifyChanged()
        true
    }

    /**
     * 只降不升動態調整：檢查逾期未互動超過 12 小時的目標子 todo，
     * 連續 3 次未互動自動降頻一級並發一張可滑除告知卡（沿用 ProactiveEngine 里程碑去重）。
     */
    suspend fun evaluateMissedGoalPushes(ctx: Context, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        if (!PhantomPrefs.isGoalTrackingEnabled(appCtx)) return@withContext
        val db = AppDatabaseHolder.db(appCtx)
        val pendingTodos = runCatching { db.todoDao().pendingTodos() }.getOrDefault(emptyList())
        val overdueThresholdMs = nowMs - 12L * 60L * 60L * 1000L
        val dayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        for (todo in pendingTodos) {
            if (todo.dueAt > overdueThresholdMs) continue
            val match = GOAL_TAG_REGEX.find(todo.title) ?: continue
            val goalId = match.groupValues.getOrNull(1)?.toLongOrNull() ?: continue
            val seenKey = "goal_miss:${todo.id}:$dayKey"
            if (PhantomPrefs.isProactiveMilestoneDelivered(appCtx, seenKey, dayKey)) continue

            val goal = db.goalDao().byId(goalId) ?: continue
            if (goal.status != GoalEntity.STATUS_ACTIVE) continue

            PhantomPrefs.markProactiveMilestonesDelivered(appCtx, listOf(seenKey), dayKey)
            val nextStreak = goal.missedPushStreak + 1
            if (nextStreak >= 3) {
                val lowerCadence = nextLowerCadence(goal.cadenceDays)
                if (lowerCadence != null) {
                    db.goalDao().updateGoal(goal.copy(cadenceDays = lowerCadence, missedPushStreak = 0))
                    ProactiveEngine.deliverProactiveAlert(
                        ctx = appCtx,
                        title = "長程目標頻率自動調降",
                        body = "目標「${goal.title}」連續 3 期未互動，已自動將提醒頻率調整為「${cadenceLabel(lowerCadence)}」以減少打擾；若需調高頻率可至夥伴分頁手動修改。",
                        nowMs = nowMs,
                        milestoneKeys = listOf("goal_downscale:${goal.id}:$dayKey")
                    )
                } else {
                    db.goalDao().updateGoal(goal.copy(missedPushStreak = 0))
                }
            } else {
                db.goalDao().updateGoal(goal.copy(missedPushStreak = nextStreak))
            }
        }
    }

    suspend fun loadGoalDisplayItems(ctx: Context): List<GoalDisplayItem> = withContext(Dispatchers.IO) {
        val db = AppDatabaseHolder.db(ctx.applicationContext)
        val goals = runCatching { db.goalDao().allGoals() }.getOrDefault(emptyList())
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("MM/dd")
        goals.map { g ->
            val progress = runCatching { db.goalDao().progressForGoal(g.id) }.getOrDefault(emptyList())
            val distinctDates = progress
                .map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                .distinct()
                .sortedDescending()
            var streak = 0
            if (distinctDates.isNotEmpty()) {
                var cursor = distinctDates.first()
                for (d in distinctDates) {
                    if (java.time.temporal.ChronoUnit.DAYS.between(d, cursor) <= g.cadenceDays.toLong() + 1L) {
                        streak++
                        cursor = d
                    } else {
                        break
                    }
                }
            }
            val nextMilestone = LocalDate.now().plusDays(g.cadenceDays.toLong()).format(fmt)
            GoalDisplayItem(
                goal = g,
                completedCount = progress.size,
                streakDays = streak,
                nextMilestoneDate = nextMilestone
            )
        }
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
