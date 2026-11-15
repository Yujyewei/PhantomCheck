package com.phantom.checker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phantom.checker.ui.components.GlassCard
import dev.chrisbanes.haze.HazeState

object OnboardingTargets {
    const val STATS_CARD = "stats_card"
    const val FIRST_APP_ROW = "first_app_row"
    const val FILTER_CHIPS = "filter_chips"
}

enum class OnboardingStep(val index: Int) {
    PERMISSION(1),
    HOME_APP(2),
    LONG_PRESS(3),
    FILTER_CHIPS(4),
    DONE(5)
}

@Composable
fun OnboardingOverlay(
    step: OnboardingStep,
    isListenerEnabled: Boolean,
    targetBounds: Map<String, Rect>,
    glassState: HazeState,
    onOpenNotificationSettings: () -> Unit,
    onNextStep: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val density = LocalDensity.current
    val expandPx = with(density) { 8.dp.toPx() }
    val cornerPx = with(density) { 12.dp.toPx() }

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardVisible by remember { mutableStateOf(false) }

    LaunchedEffect(step) {
        cardVisible = false
        cardVisible = true
    }

    // 步驟 1 若已開啟權限則自動跳過；步驟 2~4 若目標不存在則自動跳下一步
    LaunchedEffect(step, isListenerEnabled, targetBounds) {
        when (step) {
            OnboardingStep.PERMISSION -> {
                if (isListenerEnabled) onNextStep()
            }
            OnboardingStep.HOME_APP -> {
                val hasTarget = targetBounds.containsKey(OnboardingTargets.FIRST_APP_ROW) ||
                    targetBounds.containsKey(OnboardingTargets.STATS_CARD)
                if (!hasTarget && targetBounds.isNotEmpty()) {
                    onNextStep()
                }
            }
            OnboardingStep.LONG_PRESS -> {
                if (!targetBounds.containsKey(OnboardingTargets.FIRST_APP_ROW)) {
                    onNextStep()
                }
            }
            OnboardingStep.FILTER_CHIPS -> {
                if (!targetBounds.containsKey(OnboardingTargets.FILTER_CHIPS)) {
                    onNextStep()
                }
            }
            OnboardingStep.DONE -> Unit
        }
    }

    val rawHoleRect: Rect? = remember(step, targetBounds, overlayOrigin) {
        val target = when (step) {
            OnboardingStep.PERMISSION -> null
            OnboardingStep.HOME_APP -> {
                val stats = targetBounds[OnboardingTargets.STATS_CARD]
                val appRow = targetBounds[OnboardingTargets.FIRST_APP_ROW]
                when {
                    stats != null && appRow != null -> Rect(
                        left = minOf(stats.left, appRow.left),
                        top = minOf(stats.top, appRow.top),
                        right = maxOf(stats.right, appRow.right),
                        bottom = maxOf(stats.bottom, appRow.bottom)
                    )
                    appRow != null -> appRow
                    else -> stats
                }
            }
            OnboardingStep.LONG_PRESS -> targetBounds[OnboardingTargets.FIRST_APP_ROW]
            OnboardingStep.FILTER_CHIPS -> targetBounds[OnboardingTargets.FILTER_CHIPS]
            OnboardingStep.DONE -> null
        }
        target?.let {
            Rect(
                left = it.left - overlayOrigin.x - expandPx,
                top = it.top - overlayOrigin.y - expandPx,
                right = it.right - overlayOrigin.x + expandPx,
                bottom = it.bottom - overlayOrigin.y + expandPx
            )
        }
    }

    val hasHole = rawHoleRect != null
    val animLeft by animateFloatAsState(
        targetValue = rawHoleRect?.left ?: 0f,
        animationSpec = tween(durationMillis = 200),
        label = "holeLeft"
    )
    val animTop by animateFloatAsState(
        targetValue = rawHoleRect?.top ?: 0f,
        animationSpec = tween(durationMillis = 200),
        label = "holeTop"
    )
    val animRight by animateFloatAsState(
        targetValue = rawHoleRect?.right ?: 0f,
        animationSpec = tween(durationMillis = 200),
        label = "holeRight"
    )
    val animBottom by animateFloatAsState(
        targetValue = rawHoleRect?.bottom ?: 0f,
        animationSpec = tween(durationMillis = 200),
        label = "holeBottom"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayOrigin = coords.positionInRoot()
            }
    ) {
        // Canvas 不加 clickable / pointerInput，讓洞內外觸控全部穿透到底層 UI（鐵律 4）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                if (hasHole && animRight > animLeft && animBottom > animTop) {
                    addRoundRect(
                        RoundRect(
                            left = animLeft,
                            top = animTop,
                            right = animRight,
                            bottom = animBottom,
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )
                    )
                }
            }
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.6f)
            )
        }

        AnimatedVisibility(
            visible = cardVisible,
            enter = fadeIn(tween(200)) + slideInVertically(
                animationSpec = tween(200),
                initialOffsetY = { it / 2 }
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                contentPadding = 16.dp
            ) {
                val (title, body) = when (step) {
                    OnboardingStep.PERMISSION ->
                        "步驟 1 / 5・開啟權限" to "幻訊需要通知存取權才能開始記錄"
                    OnboardingStep.HOME_APP ->
                        "步驟 2 / 5・查看訊息" to "點擊 App 查看該 App 的所有訊息"
                    OnboardingStep.LONG_PRESS ->
                        "步驟 3 / 5・長按操作" to "長按看看：靜音、釘選、刪除都在這裡"
                    OnboardingStep.FILTER_CHIPS ->
                        "步驟 4 / 5・標籤篩選" to "用標籤快速過濾你想看的訊息"
                    OnboardingStep.DONE ->
                        "步驟 5 / 5・完成導覽" to "設定都在右上角齒輪，AI 標籤設定也在那裡"
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step != OnboardingStep.DONE) {
                        TextButton(onClick = onSkip) {
                            Text("跳過")
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step == OnboardingStep.PERMISSION) {
                            OutlinedButton(onClick = onOpenNotificationSettings) {
                                Text("前往開啟")
                            }
                        }
                        if (step == OnboardingStep.DONE) {
                            Button(onClick = onFinish) {
                                Text("完成")
                            }
                        } else {
                            Button(onClick = onNextStep) {
                                Text("下一步")
                            }
                        }
                    }
                }
            }
        }
    }
}
