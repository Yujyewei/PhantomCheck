package com.phantom.checker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun drawableToBitmap(drawable: Drawable, sizePx: Int = 96): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
    return try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        drawableToBitmap(drawable).asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/**
 * 將最多 5 個最近通知 App 的圖示繪入水平透明 Bitmap（每格 24dp），供常駐統計通知 setLargeIcon 使用。
 */
fun buildRecentAppsStripBitmap(
    context: Context,
    packageNames: List<String>,
    iconSizeDp: Int = 24,
    spacingDp: Int = 4
): Bitmap? {
    val pkgs = packageNames.distinct().take(5)
    if (pkgs.isEmpty()) return null
    val density = context.resources.displayMetrics.density
    val iconPx = (iconSizeDp * density).toInt().coerceAtLeast(1)
    val spacingPx = (spacingDp * density).toInt().coerceAtLeast(0)
    val drawables = pkgs.mapNotNull { pkg ->
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }
    if (drawables.isEmpty()) return null
    val totalWidth = drawables.size * iconPx + (drawables.size - 1) * spacingPx
    val bitmap = Bitmap.createBitmap(totalWidth, iconPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawables.forEachIndexed { idx, drawable ->
        val left = idx * (iconPx + spacingPx)
        drawable.setBounds(left, 0, left + iconPx, iconPx)
        drawable.draw(canvas)
    }
    return bitmap
}

@Composable
fun AppIconImage(
    packageName: String,
    contentDescription: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val bitmap = remember(packageName) {
        loadAppIconBitmap(ctx, packageName)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}
