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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.phantom.checker.data.AppGroupRow
import com.phantom.checker.data.AppRiskRow
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.data.TagEntity
import com.phantom.checker.network.AiClient
import com.phantom.checker.service.AppDatabaseHolder
import com.phantom.checker.service.DailySummaryPolicy
import com.phantom.checker.service.NotificationCollectorService
import com.phantom.checker.service.RetentionPolicy
import com.phantom.checker.service.StatsForegroundService
import com.phantom.checker.ui.components.FluidSwitch
import com.phantom.checker.ui.components.GlassCard
import com.phantom.checker.ui.components.LiquidGlassTabBar
import com.phantom.checker.ui.components.LiquidGlassTabBarItem
import com.phantom.checker.ui.components.glassSource
import com.phantom.checker.ui.components.pressableScale
import com.phantom.checker.util.MessageExporter
import com.phantom.checker.util.MuteMode
import com.phantom.checker.util.PhantomPrefs
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PAGE_SIZE = 100

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiClient.init(applicationContext)
        RetentionPolicy.scheduleOrUpdate(applicationContext)
        DailySummaryPolicy.schedule(applicationContext)
        StatsForegroundService.startOrRefresh(applicationContext)
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

private sealed interface MessageFilter {
    data object All : MessageFilter
    data object Unread : MessageFilter
    data object Starred : MessageFilter
    data object Scams : MessageFilter
    data object Filtered : MessageFilter
    data class Tag(val tag: TagEntity) : MessageFilter
}

private enum class SearchRiskFilter(val label: String) {
    ALL("全部風險"),
    SCAM("僅詐騙"),
    SUSPICIOUS("僅疑似"),
    STARRED("僅星標")
}

private data class AppGroupUi(
    val packageName: String,
    val appLabel: String,
    val totalCount: Int,
    val todayCount: Int,
    val unreadTodayCount: Int,
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
    val topRiskyApps: List<AppRiskRow> = emptyList(),
    val rawAppGroups: List<AppGroupRow> = emptyList()
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
    var searchRiskFilter by remember { mutableStateOf(SearchRiskFilter.ALL) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // 訊息 Tab 狀態
    var todayCount by remember { mutableIntStateOf(0) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var todayScamCount by remember { mutableIntStateOf(0) }
    var appGroups by remember { mutableStateOf(listOf<AppGroupUi>()) }
    var favoritePackages by remember { mutableStateOf(PhantomPrefs.getFavoriteApps(appCtx)) }
    var mutedPackages by remember { mutableStateOf(PhantomPrefs.getMutedApps(appCtx)) }
    var availableTags by remember { mutableStateOf(listOf<TagEntity>()) }
    var activeFilter by remember { mutableStateOf<MessageFilter>(MessageFilter.All) }

    // 分頁清單狀態（非「全部」篩選時顯示）
    var filteredMessages by remember { mutableStateOf(listOf<MessageEntity>()) }
    var hasMoreFiltered by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // 搜尋 / 詐騙 / 統計狀態
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
        if (granted) {
            StatsForegroundService.startOrRefresh(appCtx)
        }
    }

    // Android 13+ 啟動時主動請求 POST_NOTIFICATIONS 權限
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifications) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 回到前景時重新檢查權限與偏好，不再自動把全部訊息標已讀
    val lifecycleOwner = ctx as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerEnabled = isNotificationListenerEnabled(appCtx)
                hasPostNotifications = isPostNotificationsGranted(appCtx)
                favoritePackages = PhantomPrefs.getFavoriteApps(appCtx)
                mutedPackages = PhantomPrefs.getMutedApps(appCtx)
                refreshTrigger++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    suspend fun queryFilteredPage(offset: Int): List<MessageEntity> = withContext(Dispatchers.IO) {
        val dao = AppDatabaseHolder.db(appCtx).messageDao()
        when (val f = activeFilter) {
            MessageFilter.All -> dao.paged(PAGE_SIZE, offset)
            MessageFilter.Unread -> dao.unreadPaged(PAGE_SIZE, offset)
            MessageFilter.Starred -> dao.starredPaged(PAGE_SIZE, offset)
            MessageFilter.Scams -> dao.scamsPaged(PAGE_SIZE, offset)
            MessageFilter.Filtered -> dao.filteredPaged(PAGE_SIZE, offset)
            is MessageFilter.Tag -> dao.byTagPaged(f.tag.id, PAGE_SIZE, offset)
        }
    }

    // 依分頁、篩選或搜尋詞載入資料（不清空現有清單避免切換閃爍）
    LaunchedEffect(selectedTab, searchQuery, activeFilter, refreshTrigger) {
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
                        val overview = dao.statsOverview(startOfDay)
                        todayCount = overview.today
                        unreadCount = overview.unread
                        todayScamCount = overview.scam
                        availableTags = db.tagDao().all()

                        if (activeFilter == MessageFilter.All) {
                            val groups = dao.groupByApp()
                            val todayMap = dao.todayCountsByApp(startOfDay)
                                .associate { it.packageName to it.cnt }
                            val unreadTodayMap = dao.todayUnreadCountsByApp(startOfDay)
                                .associate { it.packageName to it.cnt }
                            val recentMsgs = dao.paged(PAGE_SIZE, 0)
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
                                    unreadTodayCount = unreadTodayMap[g.packageName] ?: 0,
                                    lastTs = g.lastTs,
                                    lastPreview = preview
                                )
                            }
                        } else {
                            val page = queryFilteredPage(0)
                            filteredMessages = page
                            hasMoreFiltered = page.size >= PAGE_SIZE
                        }
                    }
                    MainTab.SCAMS -> {
                        scamMessages = dao.scamsPaged(PAGE_SIZE, 0)
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
                        val rawGroups = dao.groupByApp()
                        statsState = StatsUiState(
                            latestSummary = summary,
                            weeklyCounts = weekly,
                            safeCount = safe,
                            suspiciousCount = suspicious,
                            scamCount = scam,
                            topRiskyApps = topRisky,
                            rawAppGroups = rawGroups
                        )
                    }
                    MainTab.SETTINGS -> Unit
                }
            }
        }
        isLoading = false
    }

    // 長按 App 動作對話框（釘選最愛／靜音或解除靜音）
    actionTargetApp?.let { target ->
        val isFav = target.packageName in favoritePackages
        val isMuted = target.packageName in mutedPackages
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
                    if (isMuted) {
                        PhantomPrefs.setAppMuted(appCtx, target.packageName, false)
                        mutedPackages = PhantomPrefs.getMutedApps(appCtx)
                        actionTargetApp = null
                        scope.launch {
                            snackbarHostState.showSnackbar("已解除靜音「${target.appLabel}」")
                        }
                    } else {
                        PhantomPrefs.setAppMuted(
                            appCtx,
                            target.packageName,
                            true,
                            MuteMode.BLOCK_RECORD
                        )
                        mutedPackages = PhantomPrefs.getMutedApps(appCtx)
                        actionTargetApp = null
                        scope.launch {
                            snackbarHostState.showSnackbar("已靜音「${target.appLabel}」（不再記錄新通知）")
                        }
                    }
                }) {
                    Text(
                        text = if (isMuted) "解除靜音" else "靜音此 App",
                        color = if (isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
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
                    if (!showSearchBar) {
                        searchQuery = ""
                        searchRiskFilter = SearchRiskFilter.ALL
                    }
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
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchRiskFilter.entries) { filter ->
                        FilterChip(
                            selected = searchRiskFilter == filter,
                            onClick = { searchRiskFilter = filter },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }

            if (isLoading) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                val displayResults = remember(searchResults, searchRiskFilter) {
                    when (searchRiskFilter) {
                        SearchRiskFilter.ALL -> searchResults
                        SearchRiskFilter.SCAM -> searchResults.filter { it.riskLabel == "scam" }
                        SearchRiskFilter.SUSPICIOUS -> searchResults.filter { it.riskLabel == "suspicious" }
                        SearchRiskFilter.STARRED -> searchResults.filter { it.isStarred }
                    }
                }
                SearchResultsSection(
                    query = searchQuery.trim(),
                    isLoading = isLoading,
                    results = displayResults,
                    activeFilterKeywords = activeFilterKeywords,
                    glassState = glassState,
                    onOpenDetail = { msg ->
                        ctx.startActivity(MessageDetailActivity.createIntent(ctx, msg.id))
                    }
                )
            } else {
                when (selectedTab) {
                    MainTab.MESSAGES -> MessagesHomeTab(
                        todayCount = todayCount,
                        unreadCount = unreadCount,
                        todayScamCount = todayScamCount,
                        favoritePackages = favoritePackages,
                        mutedPackages = mutedPackages,
                        appGroups = appGroups,
                        availableTags = availableTags,
                        activeFilter = activeFilter,
                        filteredMessages = filteredMessages,
                        hasMoreFiltered = hasMoreFiltered,
                        isLoading = isLoading,
                        isLoadingMore = isLoadingMore,
                        isListenerEnabled = isListenerEnabled,
                        glassState = glassState,
                        onSelectFilter = { f -> activeFilter = f },
                        onMarkAllRead = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabaseHolder.db(appCtx).messageDao().markAllRead()
                                }
                                StatsForegroundService.startOrRefresh(appCtx)
                                refreshTrigger++
                                snackbarHostState.showSnackbar("已將全部訊息標記為已讀")
                            }
                        },
                        onLoadMore = {
                            if (!isLoadingMore && hasMoreFiltered) {
                                scope.launch {
                                    isLoadingMore = true
                                    val next = queryFilteredPage(filteredMessages.size)
                                    filteredMessages = filteredMessages + next
                                    hasMoreFiltered = next.size >= PAGE_SIZE
                                    isLoadingMore = false
                                }
                            }
                        },
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
                        },
                        onOpenMessageDetail = { msg ->
                            ctx.startActivity(MessageDetailActivity.createIntent(ctx, msg.id))
                        },
                        onToggleStar = { msg ->
                            scope.launch {
                                val nextStar = !msg.isStarred
                                withContext(Dispatchers.IO) {
                                    AppDatabaseHolder.db(appCtx).messageDao().setStarred(msg.id, nextStar)
                                }
                                filteredMessages = filteredMessages.map {
                                    if (it.id == msg.id) it.copy(isStarred = nextStar) else it
                                }
                                snackbarHostState.showSnackbar(if (nextStar) "已加入星標" else "已取消星標")
                            }
                        },
                        onOpenNotificationAccess = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                    MainTab.SCAMS -> ScamsTab(
                        scamMessages = scamMessages,
                        isLoading = isLoading,
                        glassState = glassState,
                        onOpenScamCenter = {
                            ctx.startActivity(Intent(ctx, ScamCenterActivity::class.java))
                        },
                        onOpenDetail = { msg ->
                            ctx.startActivity(MessageDetailActivity.createIntent(ctx, msg.id))
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
                        onOpenTagManagement = {
                            ctx.startActivity(Intent(ctx, TagManagementActivity::class.java))
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
    glassState: HazeState,
    onOpenDetail: (MessageEntity) -> Unit
) {
    when {
        isLoading && results.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        results.isEmpty() -> {
            EmptyStateView(
                icon = Icons.Default.Search,
                title = "找不到符合的訊息",
                description = "沒有符合「$query」或所選風險條件的訊息紀錄。"
            )
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
                        matchedKeywords = matchedKeywords,
                        modifier = Modifier.clickable { onOpenDetail(m) }
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
    mutedPackages: Set<String>,
    appGroups: List<AppGroupUi>,
    availableTags: List<TagEntity>,
    activeFilter: MessageFilter,
    filteredMessages: List<MessageEntity>,
    hasMoreFiltered: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    isListenerEnabled: Boolean,
    glassState: HazeState,
    onSelectFilter: (MessageFilter) -> Unit,
    onMarkAllRead: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenApp: (String, String) -> Unit,
    onLongPressApp: (AppGroupUi) -> Unit,
    onLongPressFavorite: (String) -> Unit,
    onOpenMessageDetail: (MessageEntity) -> Unit,
    onToggleStar: (MessageEntity) -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    val ctx = LocalContext.current
    val groupMap = remember(appGroups) { appGroups.associateBy { it.packageName } }
    val listState = rememberLazyListState()

    // 捲動到底部自動載入下一頁（非「全部」篩選模式）
    LaunchedEffect(listState, activeFilter, hasMoreFiltered, isLoadingMore) {
        if (activeFilter == MessageFilter.All || !hasMoreFiltered) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                    if (unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onMarkAllRead) {
                            Text("全部標已讀")
                        }
                    }
                }
            }
        }

        // 快速篩選 Chip 列
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = activeFilter == MessageFilter.All,
                        onClick = { onSelectFilter(MessageFilter.All) },
                        label = { Text("全部") }
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == MessageFilter.Unread,
                        onClick = { onSelectFilter(MessageFilter.Unread) },
                        label = { Text("未讀") }
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == MessageFilter.Starred,
                        onClick = { onSelectFilter(MessageFilter.Starred) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("星標") }
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == MessageFilter.Scams,
                        onClick = { onSelectFilter(MessageFilter.Scams) },
                        label = { Text("詐騙") }
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == MessageFilter.Filtered,
                        onClick = { onSelectFilter(MessageFilter.Filtered) },
                        label = { Text("已過濾") }
                    )
                }
                items(availableTags, key = { it.id }) { tag ->
                    val isSelected = (activeFilter as? MessageFilter.Tag)?.tag?.id == tag.id
                    val tagColor = parseTagColor(tag.colorHex)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectFilter(MessageFilter.Tag(tag)) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(tagColor)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tagColor.copy(alpha = 0.2f)
                        ),
                        label = { Text(tag.name) }
                    )
                }
            }
        }

        if (activeFilter == MessageFilter.All) {
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
                                val group = groupMap[pkg]
                                val label = group?.appLabel ?: try {
                                    ctx.packageManager.getApplicationLabel(
                                        ctx.packageManager.getApplicationInfo(pkg, 0)
                                    ).toString()
                                } catch (_: Exception) {
                                    pkg
                                }
                                val todayBadge = group?.todayCount ?: 0
                                GlassCard(
                                    glassState = glassState,
                                    modifier = Modifier
                                        .width(92.dp)
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
                                        Box {
                                            AppIconImage(
                                                packageName = pkg,
                                                contentDescription = label,
                                                size = 38.dp
                                            )
                                            if (todayBadge > 0) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.align(Alignment.TopEnd)
                                                ) {
                                                    Text(
                                                        text = if (todayBadge > 99) "99+" else "$todayBadge",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
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
                                .height(260.dp)
                        ) {
                            EmptyStateView(
                                icon = Icons.Default.Notifications,
                                title = "目前尚無攔截到的訊息",
                                description = if (isListenerEnabled) {
                                    "當支援的通訊 App 收到新通知時，將自動備份並顯示於此。"
                                } else {
                                    "請先開啟通知存取權限，幻訊才能即時備份通知並分析詐騙風險。"
                                },
                                actionLabel = if (!isListenerEnabled) "前往開啟通知存取" else null,
                                onAction = if (!isListenerEnabled) onOpenNotificationAccess else null
                            )
                        }
                    }
                }
                else -> {
                    items(appGroups, key = { it.packageName }) { app ->
                        val isMuted = app.packageName in mutedPackages
                        val timeText = remember(app.lastTs) {
                            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(app.lastTs))
                        }
                        GlassCard(
                            glassState = glassState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isMuted) 0.5f else 1f)
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
                                Box {
                                    AppIconImage(
                                        packageName = app.packageName,
                                        contentDescription = app.appLabel,
                                        size = 44.dp
                                    )
                                    if (app.unreadTodayCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .align(Alignment.TopEnd)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error)
                                        )
                                    }
                                }
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
                                            if (isMuted) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Text(
                                                        text = "靜音",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else if (app.unreadTodayCount > 0) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.errorContainer
                                                ) {
                                                    Text(
                                                        text = "未讀 ${app.unreadTodayCount}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else if (app.todayCount > 0) {
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
        } else {
            // 分頁篩選訊息清單（未讀 / 星標 / 詐騙 / 已過濾 / 標籤）
            when {
                isLoading && filteredMessages.isEmpty() -> {
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
                filteredMessages.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        ) {
                            EmptyStateView(
                                icon = Icons.Default.Email,
                                title = "此篩選條件下無訊息",
                                description = "目前沒有符合條件的訊息紀錄。",
                                actionLabel = "返回全部訊息",
                                onAction = { onSelectFilter(MessageFilter.All) }
                            )
                        }
                    }
                }
                else -> {
                    items(filteredMessages, key = { it.id }) { m ->
                        MessageCard(
                            message = m,
                            glassState = glassState,
                            highlightAsScam = m.riskLabel == "scam",
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenMessageDetail(m) },
                                onLongClick = { onToggleStar(m) }
                            )
                        )
                    }
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
    onOpenScamCenter: () -> Unit,
    onOpenDetail: (MessageEntity) -> Unit
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
                EmptyStateView(
                    icon = Icons.Default.Warning,
                    title = "目前尚無詐騙警示訊息",
                    description = "當 AI 或規則偵測到高風險詐騙訊息時，會集中顯示於此。"
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(scamMessages, key = { it.id }) { m ->
                        MessageCard(
                            message = m,
                            glassState = glassState,
                            highlightAsScam = true,
                            modifier = Modifier.clickable { onOpenDetail(m) }
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
    var showExportDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var summaryHistory by remember { mutableStateOf(listOf<AiSummaryEntity>()) }

    if (showExportDialog) {
        ExportCsvDialog(
            appGroups = state.rawAppGroups,
            onDismiss = { showExportDialog = false },
            onConfirm = { pkg, days ->
                showExportDialog = false
                if (isExporting) return@ExportCsvDialog
                scope.launch {
                    isExporting = true
                    val result = MessageExporter.exportToDownloads(
                        ctx = appCtx,
                        packageName = pkg,
                        daysBack = days
                    )
                    isExporting = false
                    result.onSuccess { fileName ->
                        onShowMessage("匯出成功：$fileName")
                    }.onFailure { err ->
                        val reason = err.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: err.javaClass.simpleName
                        onShowMessage("匯出失敗：$reason")
                    }
                }
            }
        )
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("近 7 日 AI 摘要歷史") },
            text = {
                if (summaryHistory.isEmpty()) {
                    Text("近 7 日尚無 AI 摘要紀錄。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        summaryHistory.forEach { item ->
                            val dateStr = item.date.toString().let { d ->
                                if (d.length == 8) "${d.substring(0, 4)}/${d.substring(4, 6)}/${d.substring(6, 8)}" else d
                            }
                            Column {
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = item.summary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("關閉")
                }
            }
        )
    }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "每日 AI 摘要",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = {
                        scope.launch {
                            summaryHistory = withContext(Dispatchers.IO) {
                                AppDatabaseHolder.db(appCtx).aiSummaryDao().byDateRange(
                                    DailySummaryPolicy.dateKeyDaysAgo(6),
                                    DailySummaryPolicy.todayDateKey()
                                )
                            }
                            showHistoryDialog = true
                        }
                    }) {
                        Text("查看歷史")
                    }
                }
                Spacer(Modifier.height(4.dp))
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
                onClick = { showExportDialog = true },
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
    onOpenTagManagement: () -> Unit,
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
                    onClick = onOpenTagManagement,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("訊息標籤管理")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenFilterSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressableScale()
                ) {
                    Text("全部設定（通知／AI／資料／外觀／保活）")
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
                            StatsForegroundService.startOrRefresh(appCtx)
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
