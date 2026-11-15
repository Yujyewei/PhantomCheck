package com.phantom.checker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phantom.checker.data.FilterRule
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.service.NotificationCollectorService
import com.phantom.checker.service.RetentionConfig
import com.phantom.checker.service.RetentionPolicy
import com.phantom.checker.ui.components.FluidSwitch
import com.phantom.checker.ui.components.GlassCard
import com.phantom.checker.ui.components.glassSource
import com.phantom.checker.ui.components.pressableScale
import com.phantom.checker.util.MessageExporter
import com.phantom.checker.util.PhantomPrefs
import com.phantom.checker.util.PresetKeywords
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhantomCheckTheme {
                FilterSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var rules by remember { mutableStateOf(listOf<FilterRule>()) }
    var newKeyword by remember { mutableStateOf("") }
    var isAllApps by remember { mutableStateOf(true) }
    var targetPackageName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var isImportingPresets by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableIntStateOf(RetentionConfig.getRetentionDays(appCtx)) }
    var persistentStats by remember { mutableStateOf(PhantomPrefs.isPersistentStatsEnabled(appCtx)) }
    var recordOngoing by remember { mutableStateOf(PhantomPrefs.isRecordOngoingEnabled(appCtx)) }
    var mutedApps by remember { mutableStateOf(PhantomPrefs.getMutedApps(appCtx).sorted()) }
    var newMutedPkg by remember { mutableStateOf("") }
    val currentThemeMode = ThemePreferences.getThemeMode(appCtx)

    LaunchedEffect(Unit) {
        isLoading = true
        rules = withContext(Dispatchers.IO) {
            AppDatabaseHolder.db(appCtx).filterDao().all()
        }
        isLoading = false
    }

    if (showPresetDialog) {
        val existingGlobal = rules
            .filter { it.packageName == null }
            .map { it.keyword.lowercase() }
            .toSet()
        val newCount = PresetKeywords.keywords.count { it.lowercase() !in existingGlobal }
        val skipCount = PresetKeywords.keywords.size - newCount

        AlertDialog(
            onDismissRequest = { if (!isImportingPresets) showPresetDialog = false },
            title = { Text("匯入預設詐騙關鍵字") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "共 ${PresetKeywords.keywords.size} 個常見詐騙關鍵字（將新增 $newCount 個，略過 $skipCount 個已存在項目）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = PresetKeywords.keywords.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isImportingPresets) return@Button
                        scope.launch {
                            isImportingPresets = true
                            val (added, skipped, updatedRules) = withContext(Dispatchers.IO) {
                                val dao = AppDatabaseHolder.db(appCtx).filterDao()
                                val currentRules = dao.all()
                                val existingSet = currentRules
                                    .filter { it.packageName == null }
                                    .map { it.keyword.lowercase() }
                                    .toSet()
                                var addedCount = 0
                                var skippedCount = 0
                                for (kw in PresetKeywords.keywords) {
                                    if (kw.lowercase() in existingSet) {
                                        skippedCount++
                                    } else {
                                        dao.insert(
                                            FilterRule(
                                                keyword = kw,
                                                isActive = true,
                                                packageName = null
                                            )
                                        )
                                        addedCount++
                                    }
                                }
                                Triple(addedCount, skippedCount, dao.all())
                            }
                            rules = updatedRules
                            isImportingPresets = false
                            showPresetDialog = false
                            val msg = if (added > 0) {
                                "已匯入 $added 個預設關鍵字（略過 $skipped 個重複）"
                            } else {
                                "所有預設關鍵字皆已存在，未新增項目"
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    enabled = !isImportingPresets
                ) {
                    Text(if (isImportingPresets) "匯入中…" else "確認匯入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPresetDialog = false },
                    enabled = !isImportingPresets
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("過濾與進階設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        val glassState = remember { HazeState() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glassState)
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(Intent(ctx, AutoReplySettingsActivity::class.java))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("SMS 自動回覆設定")
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        if (isExporting) return@OutlinedButton
                        scope.launch {
                            isExporting = true
                            val result = MessageExporter.exportToDownloads(appCtx)
                            isExporting = false
                            result.onSuccess { fileName ->
                                snackbarHostState.showSnackbar("匯出成功：$fileName")
                            }.onFailure { err ->
                                val reason = err.localizedMessage?.takeIf { it.isNotBlank() }
                                    ?: err.javaClass.simpleName
                                snackbarHostState.showSnackbar("匯出失敗：$reason")
                            }
                        }
                    },
                    enabled = !isExporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text(if (isExporting) "匯出中…" else "匯出訊息 CSV")
                }
            }

            item {
                GlassCard(
                    glassState = glassState,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = "通知攔截與統計設定",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("常駐統計通知", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "在通知欄即時顯示今日訊息、未讀與詐騙數量",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FluidSwitch(
                            checked = persistentStats,
                            onCheckedChange = { checked ->
                                persistentStats = checked
                                PhantomPrefs.setPersistentStatsEnabled(appCtx, checked)
                                NotificationCollectorService.requestStatsUpdate(appCtx)
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("記錄持續性通知", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "關閉時略過系統/前景服務等持續刷新通知（如流量監控）",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FluidSwitch(
                            checked = recordOngoing,
                            onCheckedChange = { checked ->
                                recordOngoing = checked
                                PhantomPrefs.setRecordOngoingEnabled(appCtx, checked)
                            }
                        )
                    }
                }
            }

            item {
                GlassCard(
                    glassState = glassState,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = "App 管理（靜音清單）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "靜音名單內的 App 通知將完全不記錄；也可於主畫面長按 App 卡片快速切換。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newMutedPkg,
                            onValueChange = { newMutedPkg = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("輸入 Package 名稱（例如 com.example.app）") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val pkg = newMutedPkg.trim()
                                if (pkg.isNotEmpty()) {
                                    PhantomPrefs.setAppMuted(appCtx, pkg, true)
                                    mutedApps = PhantomPrefs.getMutedApps(appCtx).sorted()
                                    newMutedPkg = ""
                                }
                            },
                            enabled = newMutedPkg.isNotBlank()
                        ) {
                            Text("靜音")
                        }
                    }
                    if (mutedApps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        mutedApps.forEach { pkg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    PhantomPrefs.setAppMuted(appCtx, pkg, false)
                                    mutedApps = PhantomPrefs.getMutedApps(appCtx).sorted()
                                }) {
                                    Text("解除靜音")
                                }
                            }
                        }
                    }
                }
            }

            item {
                GlassCard(
                    glassState = glassState,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = "外觀主題",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = currentThemeMode == mode,
                                onClick = { ThemePreferences.setThemeMode(appCtx, mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(
                    glassState = glassState,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = 12.dp
                ) {
                    Text(
                        text = "訊息保留天數",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "超過保留天數的舊訊息將每日自動清理（詐騙警示記錄將永久保留）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RetentionConfig.OPTIONS.forEach { days ->
                            FilterChip(
                                selected = retentionDays == days,
                                onClick = {
                                    retentionDays = days
                                    RetentionConfig.setRetentionDays(appCtx, days)
                                    RetentionPolicy.scheduleOrUpdate(appCtx)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "已設定訊息保留：${RetentionConfig.labelForDays(days)}"
                                        )
                                    }
                                },
                                label = { Text(RetentionConfig.labelForDays(days)) }
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showPresetDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("匯入預設關鍵字")
                }
            }

            item {
                Column {
                    Text(
                        text = "符合以下啟用關鍵字的通知將直接標記為已過濾，不送交 AI 分析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newKeyword,
                            onValueChange = { newKeyword = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("輸入過濾關鍵字…") }
                        )
                        Spacer(Modifier.width(8.dp))
                        val trimmedKeyword = newKeyword.trim()
                        val trimmedPkg = targetPackageName.trim()
                        val resolvedPkg = if (isAllApps) null else trimmedPkg
                        val hasValidScope = isAllApps || trimmedPkg.isNotEmpty()
                        val canAdd = trimmedKeyword.isNotEmpty() &&
                            hasValidScope &&
                            rules.none {
                                it.keyword.equals(trimmedKeyword, ignoreCase = true) &&
                                    it.packageName == resolvedPkg
                            }
                        Button(
                            onClick = {
                                scope.launch {
                                    rules = withContext(Dispatchers.IO) {
                                        val dao = AppDatabaseHolder.db(appCtx).filterDao()
                                        dao.insert(
                                            FilterRule(
                                                keyword = trimmedKeyword,
                                                isActive = true,
                                                packageName = resolvedPkg
                                            )
                                        )
                                        dao.all()
                                    }
                                    newKeyword = ""
                                    targetPackageName = ""
                                }
                            },
                            enabled = canAdd
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "新增"
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("新增")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = isAllApps,
                            onClick = { isAllApps = true },
                            label = { Text("全部 App") }
                        )
                        FilterChip(
                            selected = !isAllApps,
                            onClick = { isAllApps = false },
                            label = { Text("指定 App") }
                        )
                    }

                    if (!isAllApps) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = targetPackageName,
                            onValueChange = { targetPackageName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("來源 App Package 名稱") },
                            placeholder = { Text("例如：com.android.mms") }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "查找方式：手機「設定 → 應用程式 → 選擇 App → 應用程式資訊」即可查看套件名稱（如 com.android.mms）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            when {
                isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                rules.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "目前尚無過濾關鍵字",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    }
                }
                else -> {
                    items(
                        rules,
                        key = { "${it.id}_${it.keyword}_${it.packageName.orEmpty()}" }
                    ) { rule ->
                        FilterRuleCard(
                            rule = rule,
                            glassState = glassState,
                            onToggleActive = { checked ->
                                scope.launch {
                                    val updated = rule.copy(isActive = checked)
                                    withContext(Dispatchers.IO) {
                                        AppDatabaseHolder.db(appCtx).filterDao().update(updated)
                                    }
                                    rules = rules.map {
                                        if (it.id == rule.id) updated else it
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AppDatabaseHolder.db(appCtx).filterDao().delete(rule)
                                    }
                                    rules = rules.filterNot {
                                        it.id == rule.id
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRuleCard(
    rule: FilterRule,
    glassState: HazeState,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        glassState = glassState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        cornerRadius = 18.dp,
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.keyword,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (rule.isActive) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                Text(
                    text = if (rule.packageName.isNullOrBlank()) {
                        "適用範圍：全部 App"
                    } else {
                        "適用範圍：${rule.packageName}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            FluidSwitch(
                checked = rule.isActive,
                onCheckedChange = onToggleActive
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "刪除關鍵字",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
