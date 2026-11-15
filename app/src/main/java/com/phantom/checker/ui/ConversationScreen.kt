package com.phantom.checker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.service.NotificationCollectorService
import com.phantom.checker.ui.components.GlassCard
import com.phantom.checker.ui.components.glassSource
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { packageName }
        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        setContent {
            PhantomCheckTheme {
                ConversationScreen(
                    packageName = packageName,
                    appLabel = appLabel,
                    sender = sender,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_APP_LABEL = "extra_app_label"
        private const val EXTRA_SENDER = "extra_sender"

        fun createIntent(
            ctx: Context,
            packageName: String,
            appLabel: String,
            sender: String
        ): Intent {
            return Intent(ctx, ConversationActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_SENDER, sender)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    packageName: String,
    appLabel: String,
    sender: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    var messages by remember { mutableStateOf(listOf<MessageEntity>()) }
    var isLoading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val launchIntent = remember(packageName) {
        try {
            ctx.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(packageName, sender) {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            val dao = AppDatabaseHolder.db(appCtx).messageDao()
            dao.markConversationRead(packageName, sender)
            NotificationCollectorService.requestStatsUpdate(appCtx)
            dao.byConversation(packageName, sender)
        }
        messages = loaded
        isLoading = false
        if (loaded.isNotEmpty()) {
            listState.scrollToItem(loaded.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = sender,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = appLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (launchIntent != null) {
                        TextButton(onClick = {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launchIntent)
                        }) {
                            Text("開啟原 App")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val glassState = remember { HazeState() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glassState)
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when {
                isLoading && messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "尚無對話訊息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatBubbleItem(
                                message = msg,
                                glassState = glassState
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: MessageEntity,
    glassState: HazeState
) {
    val timeText = remember(message.timestamp) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    val riskDotColor: Color? = when (message.riskLabel) {
        "scam" -> MaterialTheme.colorScheme.error
        "suspicious" -> Color(0xFFE6A100)
        else -> null
    }

    // 聊天氣泡：左側 GlassCard 氣泡、右側留白
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        GlassCard(
            glassState = glassState,
            modifier = Modifier.fillMaxWidth(0.84f),
            cornerRadius = 16.dp,
            contentPadding = 12.dp
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium
            )
            if (message.riskReason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.riskReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (riskDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(riskDotColor)
                    )
                }
            }
        }
    }
}
