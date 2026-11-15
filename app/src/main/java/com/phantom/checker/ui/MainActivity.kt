package com.phantom.checker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.phantom.checker.data.AiSummaryEntity
import com.phantom.checker.data.AppRiskRow
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.network.AiClient
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.service.DailySummaryPolicy
import com.phantom.checker.service.NotificationCollectorService
import com.phantom.checker.service.RetentionPolicy
import com.phantom.checker.ui.components.FluidSwitch
import com.phantom.checker.ui.components.GlassCard
import com.phantom.checker.ui.components.LiquidGlassTabBar
import com.phantom.checker.ui.components.LiquidGlassTabBarItem
import com.phantom.checker.ui.components.glassSource
import com.phantom.checker.ui.components.pressableScale
import com.phantom.checker.util.MessageExporter
import com.phantom.checker.util.PhantomPrefs
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiClient.init(applicationContext)
        RetentionPolicy.scheduleOrUpdate(applicationContext)
        DailySummaryPolicy.schedule(applicationContext)
        setContent {
            PhantomCheckTheme {
                PhantomCheckApp()
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
}

private fun isPostNotificationsGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private enum class MainTab(val title: String) {
    MESSAGES("訊息"),
    SCAMS("詐騙警示"),
    STATS("統計"),
    SETTINGS("設定")
}

private data class AppGroupUi(
    val packageName: String,
    val appLabel: String,
    val totalCount: Int,
    val todayCount: Int,
    val lastTs: Long,
    val lastPreview: String
)

private data class DayCountUi(
    val label: String,
    val count: Int
)

private data class StatsUiState(
    val latestSummary: AiSummaryEntity? = null,
    val weeklyCounts: List<DayCountUi> = emptyList(),
    val safeCount: Int = 0,
    val suspiciousCount: Int = 0,
    val scamCount: Int = 0,
    val topRiskyApps: List<AppRiskRow> = emptyList()
)

@Composable
fun PhantomCheckApp() {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableStateOf(MainTab.MESSAGES) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // 訊息 Tab 狀態
    var todayCount by remember { mutableIntStateOf(0) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var todayScamCount by remember { mutableIntStateOf(0) }
    var appGroups by remember { mutableStateOf(listOf<AppGroupUi>()) }
    var favoritePackages by remember { mutableStateOf(PhantomPrefs.getFavoriteApps(appCtx)) }
    var searchResults by remember { mutableStateOf(listOf<MessageEntity>()) }
    var activeFilterKeywords by remember { mutableStateOf(listOf<String>()) }
    var scamMessages by remember { mutableStateOf(listOf<MessageEntity>()) }
    var statsState by remember { mutableStateOf(StatsUiState()) }
    var isLoading by remember { mutableStateOf(true) }
    var actionTargetApp by remember { mutableStateOf<AppGroupUi?>(null) }

    var isListenerEnabled by remember {
        mutableStateOf(isNotificationListenerEnabled(appCtx))
    }
    var hasPostNotifications by remember {
        mutableStateOf(isPostNotificationsGranted(appCtx))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPostNotifications = granted
    }

    // Android 13+ 啟動時主動請求 POST_NOTIFICATIONS 權限
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifications) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 回到前景時重新檢查權限、標記已讀並觸發統計通知更新
    val lifecycleOwner = ctx as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerEnabled = isNotificationListenerEnabled(appCtx)
                hasPostNotifications = isPostNotificationsGranted(appCtx)
                favoritePackages = PhantomPrefs.getFavoriteApps(appCtx)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseHolder.db(appCtx).messageDao().markAllRead()
                        NotificationCollectorService.requestStatsUpdate(appCtx)
                    }
                    refreshTrigger++
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    // 依分頁或搜尋詞載入資料
    LaunchedEffect(selectedTab, searchQuery, refreshTrigger) {
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            delay(300L)
        }
        isLoading = true
        withContext(Dispatchers.IO) {
            val db = AppDatabaseHolder.db(appCtx)
            val dao = db.messageDao()
            val startOfDay = NotificationCollectorService.startOfTodayMillis()

            if (q.isNotEmpty()) {
                val found = dao.search(q)
                val keywords = if (found.any { it.isFiltered }) {
                    db.filterDao().active().map { it.keyword }
                } else {
                    emptyList()
                }
                searchResults = found
                activeFilterKeywords = keywords
            } else {
                when (selectedTab) {
                    MainTab.MESSAGES -> {
                        todayCount = dao.todayCount(startOfDay)
                        unreadCount = dao.unreadCount()
                        todayScamCount = dao.todayScamCount(startOfDay)
                        val groups = dao.groupByApp()
                        val todayMap = dao.todayCountsByApp(startOfDay)
                            .associate { it.packageName to it.cnt }
                        val recentMsgs = dao.recent()
                        val latestPreviewByPkg = recentMsgs
                            .groupBy { it.packageName }
                            .mapValues { (_, list) -> list.maxByOrNull { it.timestamp }?.content.orEmpty() }
                        appGroups = groups.map { g ->
                            val preview = latestPreviewByPkg[g.packageName]
                                ?: dao.byApp(g.packageName).firstOrNull()?.content.orEmpty()
                            AppGroupUi(
                                packageName = g.packageName,
                                appLabel = g.appLabel.ifBlank { g.packageName },
                                totalCount = g.cnt,
                                todayCount = todayMap[g.packageName] ?: 0,
                                lastTs = g.lastTs,
                                lastPreview = preview
                            )
                        }
                    }
                    MainTab.SCAMS -> {
                        scamMessages = dao.scams()
                    }
                    MainTab.STATS -> {
                        val summary = db.aiSummaryDao().latest()
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                            add(Calendar.DAY_OF_YEAR, -6)
                        }
                        val sevenDaysAgo = cal.timeInMillis
                        val weekMsgs = dao.since(sevenDaysAgo)
                        val dayFormat = SimpleDateFormat("M/d", Locale.getDefault())
                        val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
                        val countsByDayKey = weekMsgs.groupBy { dayKeyFormat.format(Date(it.timestamp)) }
                        val weekly = (0..6).map { offset ->
                            val dayCal = Calendar.getInstance().apply {
                                timeInMillis = sevenDaysAgo
                                add(Calendar.DAY_OF_YEAR, offset)
                            }
                            val key = dayKeyFormat.format(dayCal.time)
                            DayCountUi(
                                label = dayFormat.format(dayCal.time),
                                count = countsByDayKey[key]?.size ?: 0
                            )
                        }
                        val safe = dao.countByRiskLabel("safe") + dao.countByRiskLabel("")
                        val suspicious = dao.countByRiskLabel("suspicious")
                        val scam = dao.countByRiskLabel("scam")
                        val topRisky = dao.topRiskyApps()
                        statsState = StatsUiState(
                            latestSummary = summary,
                            weeklyCounts = weekly,
                            safeCount = safe,
                            suspiciousCount = suspicious,
                            scamCount = scam,
                            topRiskyApps = topRisky
                        )
                    }
                    MainTab.SETTINGS -> Unit
                }
            }
        }
        isLoading = false
    }

    actionTargetApp?.let { target ->
        val isFav = target.packageName in favoritePackages
        AlertDialog(
            onDismissRequest = { actionTargetApp = null },
            title = { Text(target.appLabel) },
            text = { Text("選擇要對「${target.appLabel}」執行的操作：") },
            confirmButton = {
                TextButton(onClick = {
                    val pinned = PhantomPrefs.toggleFavoriteApp(appCtx, target.packageName)
                    favoritePackages = PhantomPrefs.getFavoriteApps(appCtx)
                    actionTargetApp = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (pinned) "已釘選「${target.appLabel}」至我的最愛" else "已取消釘選「${target.appLabel}」"
                        )
                    }
                }) {
                    Text(if (isFav) "取消最愛釘選" else "釘選至我的最愛")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    PhantomPrefs.setAppMuted(appCtx, target.packageName, true)
                    actionTargetApp = null
                    scope.launch {
                        snackbarHostState.showSnackbar("已靜音「${target.appLabel}」（不再記錄新通知）")
                    }
                }) {
                    Text("靜音此 App", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    val glassState = remember { HazeState() }

    Scaffold(
        bottomBar = {
            LiquidGlassTabBar(
                tabCount = MainTab.entries.size,
                selectedTabIndex = selectedTab.ordinal,
                glassState = glassState,
                onSelect = { index -> selectedTab = MainTab.entries[index] },
                tabs = {
                    MainTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            MainTab.MESSAGES -> Icons.Default.Email
                            MainTab.SCAMS -> Icons.Default.Warning
                            MainTab.STATS -> Icons.Default.Info
                            MainTab.SETTINGS -> Icons.Default.Settings
                        }
                        LiquidGlassTabBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = icon,
                            label = tab.title,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glassState)
                .padding(padding)
                .padding(12.dp)
        ) {
            // 頂部標題列：僅保留搜尋按鈕
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("幻訊 PhantomCheck", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar) searchQuery = ""
                }) {
                    Icon(
                        imageVector = if (showSearchBar) Icons.Default.Clear else Icons.Default.Search,
                        contentDescription = "搜尋"
                    )
                }
            }

            if (showSearchBar || searchQuery.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜尋寄件人或訊息內容…") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜尋"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除搜尋"
                                )
                            }
                        }
                    }
                )
            }

            if (!isListenerEnabled) {
                Spacer(Modifier.height(8.dp))
                NotificationListenerGuideCard(
                    onOpenSettings = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    glassState = glassState
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifications) {
                Spacer(Modifier.height(8.dp))
                PostNotificationGuideCard(
                    onRequestPermission = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    glassState = glassState
                )
            }

            Spacer(Modifier.height(10.dp))

            if (searchQuery.trim().isNotEmpty()) {
                SearchResultsSection(
                    query = searchQuery.trim(),
                    isLoading = isLoading,
                    results = searchResults,
                    activeFilterKeywords = activeFilterKeywords,
                    glassState = glassState
                )
            } else {
                when (selectedTab) {
                    MainTab.MESSAGES -> MessagesHomeTab(
                        todayCount = todayCount,
                        unreadCount = unreadCount,
                        todayScamCount = todayScamCount,
                        favoritePackages = favoritePackages,
                        appGroups = appGroups,
                        isLoading = isLoading,
                        glassState = glassState,
                        onOpenApp = { pkg, label ->
                            ctx.startActivity(AppThreadsActivity.createIntent(ctx, pkg, label))
                        },
                        onLongPressApp = { app ->
                            actionTargetApp = app
                        },
                        onLongPressFavorite = { pkg ->
                            PhantomPrefs.toggleFavoriteApp(appCtx, pkg)
                            favoritePackages = PhantomPrefs.getFavoriteApps(appCtx)
                            scope.launch {
                                snackbarHostState.showSnackbar("已取消最愛釘選")
                            }
                        }
                    )
                    MainTab.SCAMS -> ScamsTab(
                        scamMessages = scamMessages,
                        isLoading = isLoading,
                        glassState = glassState,
                        onOpenScamCenter = {
                            ctx.startActivity(Intent(ctx, ScamCenterActivity::class.java))
                        }
                    )
                    MainTab.STATS -> StatsTab(
                        state = statsState,
                        glassState = glassState,
                        onSummaryGenerated = { refreshTrigger++ },
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                    MainTab.SETTINGS -> SettingsTab(
                        glassState = glassState,
                        onOpenScamCenter = {
                            ctx.startActivity(Intent(ctx, ScamCenterActivity::class.java))
                        },
                        onOpenAiSettings = {
                            ctx.startActivity(Intent(ctx, ApiKeySettingsActivity::class.java))
                        },
                        onOpenFilterSettings = {
                            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                        },
                        onOpenAutoReply = {
                            ctx.startActivity(Intent(ctx, AutoReplySettingsActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsSection(
    query: String,
    isLoading: Boolean,
    results: List<MessageEntity>,
    activeFilterKeywords: List<String>,
    glassState: HazeState
) {
    when {
        isLoading && results.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        results.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "找不到符合「$query」的訊息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { m ->
                    val matchedKeywords = if (m.isFiltered) {
                        activeFilterKeywords.filter { kw ->
                            m.content.contains(kw, ignoreCase = true)
                        }
                    } else {
                        emptyList()
                    }
                    MessageCard(
                        message = m,
                        glassState = glassState,
                        highlightAsScam = m.riskLabel == "scam",
                        matchedKeywords = matchedKeywords
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessagesHomeTab(
    todayCount: Int,
    unreadCount: Int,
    todayScamCount: Int,
    favoritePackages: List<String>,
    appGroups: List<AppGroupUi>,
    isLoading: Boolean,
    glassState: HazeState,
    onOpenApp: (String, String) -> Unit,
    onLongPressApp: (AppGroupUi) -> Unit,
    onLongPressFavorite: (String) -> Unit
) {
    val ctx = LocalContext.current
    val groupMap = remember(appGroups) { appGroups.associateBy { it.packageName } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 頂部統計玻璃卡
        item {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "今日 $todayCount 則・未讀 $unreadCount・詐騙 $todayScamCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "點擊 App 進入對話總覽，長按可釘選最愛或靜音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 我的最愛 App 橫向列
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "我的最愛 App",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (favoritePackages.isEmpty()) {
                    Text(
                        text = "尚未釘選最愛 App（長按下方任一 App 即可釘選）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(favoritePackages, key = { it }) { pkg ->
                            val label = groupMap[pkg]?.appLabel ?: try {
                                ctx.packageManager.getApplicationLabel(
                                    ctx.packageManager.getApplicationInfo(pkg, 0)
                                ).toString()
                            } catch (_: Exception) {
                                pkg
                            }
                            GlassCard(
                                glassState = glassState,
                                modifier = Modifier
                                    .width(88.dp)
                                    .pressableScale()
                                    .combinedClickable(
                                        onClick = { onOpenApp(pkg, label) },
                                        onLongClick = { onLongPressFavorite(pkg) }
                                    ),
                                cornerRadius = 20.dp,
                                contentPadding = 10.dp
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AppIconImage(
                                        packageName = pkg,
                                        contentDescription = label,
                                        size = 38.dp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // App 分組清單
        when {
            isLoading && appGroups.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            appGroups.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "目前尚無攔截到的訊息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
            else -> {
                items(appGroups, key = { it.packageName }) { app ->
                    val timeText = remember(app.lastTs) {
                        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(app.lastTs))
                    }
                    GlassCard(
                        glassState = glassState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressableScale()
                            .combinedClickable(
                                onClick = { onOpenApp(app.packageName, app.appLabel) },
                                onLongClick = { onLongPressApp(app) }
                            ),
                        cornerRadius = 20.dp,
                        contentPadding = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconImage(
                                packageName = app.packageName,
                                contentDescription = app.appLabel,
                                size = 44.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = app.appLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (app.todayCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "今日 ${app.todayCount}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = app.lastPreview.ifBlank { "共 ${app.totalCount} 則訊息" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScamsTab(
    scamMessages: List<MessageEntity>,
    isLoading: Boolean,
    glassState: HazeState,
    onOpenScamCenter: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = onOpenScamCenter,
            modifier = Modifier
                .fillMaxWidth()
                .pressableScale()
        ) {
            Text("開啟詐騙警示中心（查看完整風險分析）")
        }
        Spacer(Modifier.height(8.dp))
        when {
            isLoading && scamMessages.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            scamMessages.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "目前尚無詐騙警示訊息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(scamMessages, key = { it.id }) { m ->
                        MessageCard(
                            message = m,
                            glassState = glassState,
                            highlightAsScam = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTab(
    state: StatsUiState,
    glassState: HazeState,
    onSummaryGenerated: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var isGeneratingSummary by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 每日 AI 摘要玻璃卡
        item {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "每日 AI 摘要",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                val summary = state.latestSummary
                if (summary != null) {
                    val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        .format(Date(summary.generatedAt))
                    Text(
                        text = summary.summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "更新於 $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "尚無摘要",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (isGeneratingSummary) return@Button
                        scope.launch {
                            isGeneratingSummary = true
                            val result = withContext(Dispatchers.IO) {
                                DailySummaryPolicy.generateTodaySummary(appCtx)
                            }
                            isGeneratingSummary = false
                            result.onSuccess {
                                onSummaryGenerated()
                                onShowMessage("今日 AI 摘要已生成")
                            }.onFailure { err ->
                                onShowMessage(err.message ?: "摘要生成失敗")
                            }
                        }
                    },
                    enabled = !isGeneratingSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    if (isGeneratingSummary) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("生成中…")
                    } else {
                        Text("立即生成今日摘要")
                    }
                }
            }
        }

        // 本週 7 日訊息量簡易 Bar Chart（純 Compose Canvas）
        item {
            val barColor = MaterialTheme.colorScheme.primary
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "本週 7 日訊息量",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                val maxCount = (state.weeklyCounts.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    val items = state.weeklyCounts
                    if (items.isEmpty()) return@Canvas
                    val barWidthPx = 12.dp.toPx()
                    val slotWidth = size.width / items.size
                    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

                    items.forEachIndexed { idx, day ->
                        val left = idx * slotWidth + (slotWidth - barWidthPx) / 2f
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(left, 0f),
                            size = Size(barWidthPx, size.height),
                            cornerRadius = radius
                        )
                        val ratio = day.count.toFloat() / maxCount.toFloat()
                        val barH = (size.height * ratio).coerceAtLeast(if (day.count > 0) 6.dp.toPx() else 0f)
                        if (barH > 0f) {
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(left, size.height - barH),
                                size = Size(barWidthPx, barH),
                                cornerRadius = radius
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    state.weeklyCounts.forEach { day ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${day.count}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 風險分布三色計數卡
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RiskCountCard(
                    title = "安全",
                    count = state.safeCount,
                    accentColor = MaterialTheme.colorScheme.primary,
                    glassState = glassState,
                    modifier = Modifier.weight(1f)
                )
                RiskCountCard(
                    title = "疑似",
                    count = state.suspiciousCount,
                    accentColor = Color(0xFFE6A100),
                    glassState = glassState,
                    modifier = Modifier.weight(1f)
                )
                RiskCountCard(
                    title = "詐騙",
                    count = state.scamCount,
                    accentColor = MaterialTheme.colorScheme.error,
                    glassState = glassState,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Top 5 高風險 App 清單
        item {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "Top 5 高風險 App",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (state.topRiskyApps.isEmpty()) {
                    Text(
                        text = "目前尚無疑似或詐騙紀錄",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.topRiskyApps.forEachIndexed { idx, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}. ",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                AppIconImage(
                                    packageName = row.packageName,
                                    contentDescription = row.appLabel,
                                    size = 24.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = row.appLabel.ifBlank { row.packageName },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${row.cnt} 則",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 匯出 CSV 按鈕
        item {
            OutlinedButton(
                onClick = {
                    if (isExporting) return@OutlinedButton
                    scope.launch {
                        isExporting = true
                        val result = MessageExporter.exportToDownloads(appCtx)
                        isExporting = false
                        result.onSuccess { fileName ->
                            onShowMessage("匯出成功：$fileName")
                        }.onFailure { err ->
                            val reason = err.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: err.javaClass.simpleName
                            onShowMessage("匯出失敗：$reason")
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
    }
}

@Composable
private fun RiskCountCard(
    title: String,
    count: Int,
    accentColor: Color,
    glassState: HazeState,
    modifier: Modifier = Modifier
) {
    GlassCard(
        glassState = glassState,
        modifier = modifier,
        cornerRadius = 20.dp,
        contentPadding = 12.dp
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsTab(
    glassState: HazeState,
    onOpenScamCenter: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenFilterSettings: () -> Unit,
    onOpenAutoReply: () -> Unit
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    var persistentStats by remember { mutableStateOf(PhantomPrefs.isPersistentStatsEnabled(appCtx)) }
    var recordOngoing by remember { mutableStateOf(PhantomPrefs.isRecordOngoingEnabled(appCtx)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "功能中心",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenScamCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("詐騙警示中心")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenAiSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("多 Provider AI 與 API Key 設定")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenFilterSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("過濾關鍵字、保留政策與 App 靜音管理")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenAutoReply,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("SMS 自動回覆設定")
                }
            }
        }

        item {
            GlassCard(
                glassState = glassState,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = 14.dp
            ) {
                Text(
                    text = "通知快速開關",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            "關閉時略過系統與前景服務持續刷新的通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    }
}
