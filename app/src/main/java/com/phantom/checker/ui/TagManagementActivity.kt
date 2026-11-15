package com.phantom.checker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.phantom.checker.data.TagEntity
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.ui.components.FluidSwitch
import com.phantom.checker.ui.components.GlassCard
import com.phantom.checker.ui.components.glassSource
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAG_COLOR_PALETTE = listOf(
    "#7C3AED", // 品牌紫
    "#2563EB", // 藍
    "#059669", // 綠
    "#D97706", // 琥珀
    "#DC2626", // 紅
    "#DB2777", // 粉紅
    "#0891B2", // 青
    "#4B5563"  // 灰
)

class TagManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhantomCheckTheme {
                TagManagementScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tags by remember { mutableStateOf(listOf<TagEntity>()) }
    var showEditor by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteCandidate by remember { mutableStateOf<TagEntity?>(null) }

    suspend fun reloadTags() {
        tags = withContext(Dispatchers.IO) {
            AppDatabaseHolder.db(appCtx).tagDao().all()
        }
    }

    LaunchedEffect(Unit) {
        reloadTags()
    }

    if (showEditor) {
        TagEditorDialog(
            initial = editingTag,
            onDismiss = {
                showEditor = false
                editingTag = null
            },
            onSave = { name, colorHex, aiHint, isAiManaged ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabaseHolder.db(appCtx).tagDao()
                        val existing = editingTag
                        if (existing == null) {
                            val nextOrder = (tags.maxOfOrNull { it.sortOrder } ?: -1) + 1
                            dao.insert(
                                TagEntity(
                                    name = name,
                                    colorHex = colorHex,
                                    sortOrder = nextOrder,
                                    aiHint = aiHint,
                                    isAiManaged = isAiManaged
                                )
                            )
                        } else {
                            dao.update(
                                existing.copy(
                                    name = name,
                                    colorHex = colorHex,
                                    aiHint = aiHint,
                                    isAiManaged = isAiManaged
                                )
                            )
                        }
                    }
                    showEditor = false
                    editingTag = null
                    reloadTags()
                }
            }
        )
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("刪除標籤") },
            text = { Text("確定要刪除標籤「${target.name}」嗎？這將一併移除所有訊息上的此標籤關聯。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val db = AppDatabaseHolder.db(appCtx)
                                db.withTransaction {
                                    db.tagDao().deleteCrossRefsByTag(target.id)
                                    db.tagDao().deleteById(target.id)
                                }
                            }
                            deleteCandidate = null
                            reloadTags()
                            snackbarHostState.showSnackbar("已刪除標籤「${target.name}」")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("確認刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自訂標籤管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingTag = null
                    showEditor = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增標籤")
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        val glassState = remember { HazeState() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glassState)
                .padding(padding)
                .padding(12.dp)
        ) {
            if (tags.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.Add,
                    title = "尚無自訂標籤",
                    description = "點擊右下角「+」建立工作、銀行、包裹等分類標籤。",
                    actionLabel = "新增標籤",
                    onAction = {
                        editingTag = null
                        showEditor = true
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(tags, key = { _, item -> item.id }) { idx, tag ->
                        GlassCard(
                            glassState = glassState,
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            contentPadding = 12.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(parseTagColor(tag.colorHex))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = tag.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (tag.isAiManaged) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = "AI",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        if (tag.aiHint.isNotBlank()) {
                                            Text(
                                                text = tag.aiHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        enabled = idx > 0,
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    val prev = tags[idx - 1]
                                                    val dao = AppDatabaseHolder.db(appCtx).tagDao()
                                                    dao.update(tag.copy(sortOrder = prev.sortOrder))
                                                    dao.update(prev.copy(sortOrder = tag.sortOrder))
                                                }
                                                reloadTags()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                                    }
                                    IconButton(
                                        enabled = idx < tags.lastIndex,
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    val next = tags[idx + 1]
                                                    val dao = AppDatabaseHolder.db(appCtx).tagDao()
                                                    dao.update(tag.copy(sortOrder = next.sortOrder))
                                                    dao.update(next.copy(sortOrder = tag.sortOrder))
                                                }
                                                reloadTags()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
                                    }
                                    IconButton(onClick = {
                                        editingTag = tag
                                        showEditor = true
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "編輯")
                                    }
                                    IconButton(onClick = { deleteCandidate = tag }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "刪除",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorDialog(
    initial: TagEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var selectedColor by remember { mutableStateOf(initial?.colorHex ?: TAG_COLOR_PALETTE.first()) }
    var aiHint by remember { mutableStateOf(initial?.aiHint ?: "") }
    var isAiManaged by remember { mutableStateOf(initial?.isAiManaged ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增標籤" else "編輯標籤") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("標籤名稱") },
                    placeholder = { Text("例如：工作、帳單、物流") }
                )
                OutlinedTextField(
                    value = aiHint,
                    onValueChange = { aiHint = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    label = { Text("AI 判斷依據（選填）") },
                    placeholder = { Text("例如：銀行扣款、信用卡消費通知") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "允許 AI 自動指派此標籤",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    FluidSwitch(
                        checked = isAiManaged,
                        onCheckedChange = { isAiManaged = it }
                    )
                }
                Text("選擇顏色", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TAG_COLOR_PALETTE.forEach { hex ->
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseTagColor(hex))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 3.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), selectedColor, aiHint.trim(), isAiManaged) },
                enabled = name.isNotBlank()
            ) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
