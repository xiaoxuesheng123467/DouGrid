@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.EditorTool
import com.qiao.dougrid.ui.components.GridWindow
import com.qiao.dougrid.ui.components.PatternCanvas
import com.qiao.dougrid.ui.components.PatternCanvasMode
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CraftScreen(state: DouGridUiState, viewModel: DouGridViewModel) {
    val candidates = remember(state.projects, state.editorStatsRevision) {
        state.projects.filter { it.grid.beadCount() > 0 }
    }
    val active = candidates.firstOrNull { it.id == state.activeCraftProjectId } ?: candidates.firstOrNull()
    if (active == null) {
        EmptyCraftScreen()
        return
    }
    val palette = viewModel.palette(active.paletteId)
    var projectMenu by remember { mutableStateOf(false) }
    var boardIndex by rememberSaveable(active.id) { mutableIntStateOf(active.lastCraftBoardIndex) }
    var highlightedColor by rememberSaveable(active.id) { mutableStateOf<Int?>(null) }
    var hideCompleted by rememberSaveable(active.id) { mutableStateOf(false) }
    var showComplete by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable(active.id) { mutableStateOf(false) }
    var elapsed by rememberSaveable(active.id) { mutableLongStateOf(active.craftElapsedSeconds) }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val safeBoardIndex = boardIndex.coerceIn(0, active.boardCount - 1)
    val boardColumn = safeBoardIndex % active.boardColumns
    val boardRow = safeBoardIndex / active.boardColumns
    val window = GridWindow(
        startColumn = boardColumn * active.boardSize,
        startRow = boardRow * active.boardSize,
        width = min(active.boardSize, active.grid.width - boardColumn * active.boardSize),
        height = min(active.boardSize, active.grid.height - boardRow * active.boardSize),
    )
    val overallStats = remember(active.id, state.editorStatsRevision) {
        val total = active.grid.beadCount()
        val completed = active.grid.completedCount()
        BoardCraftStats(
            total = total,
            completed = completed,
            progress = if (total == 0) 0f else completed.toFloat() / total,
        )
    }
    val usedColors = remember(active.id, state.editorStatsRevision) {
        active.grid.colorCounts().entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    val completedColorCounts = remember(active.id, state.editorStatsRevision) {
        buildMap {
            active.grid.cells.indices.forEach { index ->
                val colorIndex = active.grid.cells[index]
                if (colorIndex != EMPTY_CELL && active.grid.completed[index].toInt() != 0) {
                    put(colorIndex, (get(colorIndex) ?: 0) + 1)
                }
            }
        }
    }
    val boardStats = remember(active.id, active.boardSize, active.boardCount, state.editorRevision) {
        List(active.boardCount) { index -> calculateBoardStats(active, index) }
    }
    val currentBoardStats = boardStats[safeBoardIndex]
    val bulkTargetStats = remember(
        active.id,
        active.boardSize,
        safeBoardIndex,
        highlightedColor,
        state.editorRevision,
    ) {
        calculateBoardStats(active, safeBoardIndex, highlightedColor)
    }
    val nextUnfinishedBoard = remember(safeBoardIndex, boardStats) {
        (1 until active.boardCount)
            .map { offset -> (safeBoardIndex + offset) % active.boardCount }
            .firstOrNull { index -> boardStats[index].total > boardStats[index].completed }
    }
    val latestElapsed by rememberUpdatedState(elapsed)
    val timerRunning = overallStats.progress < 1f

    val selectBoard: (Int) -> Unit = { index ->
        val selected = index.coerceIn(0, active.boardCount - 1)
        boardIndex = selected
        viewModel.selectCraftBoard(active.id, selected)
    }

    DisposableEffect(state.settings.keepScreenOnInCraftMode) {
        val previous = view.keepScreenOn
        if (state.settings.keepScreenOnInCraftMode) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(active.id) {
        onDispose { viewModel.recordCraftTime(active.id, latestElapsed) }
    }
    LaunchedEffect(active.id, timerRunning, lifecycleOwner) {
        if (!timerRunning) {
            viewModel.recordCraftTime(active.id, elapsed)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    delay(1_000)
                    elapsed++
                    if (elapsed % 30L == 0L) viewModel.recordCraftTime(active.id, elapsed)
                }
            } finally {
                viewModel.recordCraftTime(active.id, elapsed)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(onClick = { projectMenu = true }) {
                            Text(active.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                        }
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            candidates.forEach { project ->
                                DropdownMenuItem(
                                    text = { Text(project.title) },
                                    onClick = {
                                        projectMenu = false
                                        viewModel.recordCraftTime(active.id, elapsed)
                                        viewModel.selectCraftProject(project.id)
                                    },
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(formatDuration(elapsed), style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val shortHeight = maxHeight < 560.dp
            Column(Modifier.fillMaxSize()) {
                if (shortHeight) {
                    CompactCraftProgress(
                        project = active,
                        stats = overallStats,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                } else {
                    CraftProgressOverview(
                        project = active,
                        overallStats = overallStats,
                        boardStats = boardStats,
                        selectedBoard = safeBoardIndex,
                        onSelectBoard = selectBoard,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val expanded = maxWidth >= 840.dp
                    if (expanded) {
                        Row(Modifier.fillMaxSize()) {
                            CraftCanvas(
                                project = active,
                                palette = palette,
                                revision = state.editorRevision,
                                showColorCodes = state.settings.showColorCodes,
                                highContrastGrid = state.settings.highContrastGrid,
                                highlightedColor = highlightedColor,
                                hideCompleted = hideCompleted,
                                window = window,
                                onCellAction = { index ->
                                    val cellColor = active.grid.cells.getOrNull(index) ?: EMPTY_CELL
                                    if (cellColor != EMPTY_CELL && (highlightedColor == null || highlightedColor == cellColor)) {
                                        viewModel.toggleCraftCell(active.id, index)
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            VerticalDivider()
                            CraftControls(
                                project = active,
                                palette = palette,
                                selectedBoard = safeBoardIndex,
                                currentBoardStats = currentBoardStats,
                                bulkTargetStats = bulkTargetStats,
                                highlightedColor = highlightedColor,
                                hideCompleted = hideCompleted,
                                usedColors = usedColors,
                                completedColorCounts = completedColorCounts,
                                nextUnfinishedBoard = nextUnfinishedBoard,
                                onSelectBoard = selectBoard,
                                onHighlightColor = { highlightedColor = it },
                                onHideCompleted = { hideCompleted = it },
                                onCompleteBoard = {
                                    viewModel.recordCraftTime(active.id, elapsed)
                                    viewModel.completeCraftBoard(active.id, safeBoardIndex, highlightedColor)
                                },
                                onReset = { showReset = true },
                                onCompleteProject = { showComplete = true },
                                modifier = Modifier.width(360.dp).fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            CraftCanvas(
                                project = active,
                                palette = palette,
                                revision = state.editorRevision,
                                showColorCodes = state.settings.showColorCodes,
                                highContrastGrid = state.settings.highContrastGrid,
                                highlightedColor = highlightedColor,
                                hideCompleted = hideCompleted,
                                window = window,
                                onCellAction = { index ->
                                    val cellColor = active.grid.cells.getOrNull(index) ?: EMPTY_CELL
                                    if (cellColor != EMPTY_CELL && (highlightedColor == null || highlightedColor == cellColor)) {
                                        viewModel.toggleCraftCell(active.id, index)
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            HorizontalDivider()
                            if (shortHeight) {
                                CompactCraftToolbar(
                                    project = active,
                                    selectedBoard = safeBoardIndex,
                                    currentBoardStats = currentBoardStats,
                                    canCompleteBoard = bulkTargetStats.total > bulkTargetStats.completed,
                                    canGoNext = nextUnfinishedBoard != null,
                                    onNext = { nextUnfinishedBoard?.let(selectBoard) },
                                    onCompleteBoard = {
                                        viewModel.recordCraftTime(active.id, elapsed)
                                        viewModel.completeCraftBoard(active.id, safeBoardIndex, highlightedColor)
                                    },
                                    onOpenTools = { showControlsSheet = true },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                CraftControls(
                                    project = active,
                                    palette = palette,
                                    selectedBoard = safeBoardIndex,
                                    currentBoardStats = currentBoardStats,
                                    bulkTargetStats = bulkTargetStats,
                                    highlightedColor = highlightedColor,
                                    hideCompleted = hideCompleted,
                                    usedColors = usedColors,
                                    completedColorCounts = completedColorCounts,
                                    nextUnfinishedBoard = nextUnfinishedBoard,
                                    onSelectBoard = selectBoard,
                                    onHighlightColor = { highlightedColor = it },
                                    onHideCompleted = { hideCompleted = it },
                                    onCompleteBoard = {
                                        viewModel.recordCraftTime(active.id, elapsed)
                                        viewModel.completeCraftBoard(active.id, safeBoardIndex, highlightedColor)
                                    },
                                    onReset = { showReset = true },
                                    onCompleteProject = { showComplete = true },
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 270.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showControlsSheet) {
        ModalBottomSheet(onDismissRequest = { showControlsSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState()),
            ) {
                CraftProgressOverview(
                    project = active,
                    overallStats = overallStats,
                    boardStats = boardStats,
                    selectedBoard = safeBoardIndex,
                    onSelectBoard = selectBoard,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                HorizontalDivider()
                CraftControls(
                    project = active,
                    palette = palette,
                    selectedBoard = safeBoardIndex,
                    currentBoardStats = currentBoardStats,
                    bulkTargetStats = bulkTargetStats,
                    highlightedColor = highlightedColor,
                    hideCompleted = hideCompleted,
                    usedColors = usedColors,
                    completedColorCounts = completedColorCounts,
                    nextUnfinishedBoard = nextUnfinishedBoard,
                    onSelectBoard = selectBoard,
                    onHighlightColor = { highlightedColor = it },
                    onHideCompleted = { hideCompleted = it },
                    onCompleteBoard = {
                        viewModel.recordCraftTime(active.id, elapsed)
                        viewModel.completeCraftBoard(active.id, safeBoardIndex, highlightedColor)
                    },
                    onReset = {
                        showControlsSheet = false
                        showReset = true
                    },
                    onCompleteProject = {
                        showControlsSheet = false
                        showComplete = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showComplete) {
        var deduct by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showComplete = false },
            title = { Text("完成作品") },
            text = {
                if (state.settings.confirmInventoryDeduction && !active.inventoryDeducted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("按实际用量扣减豆仓", modifier = Modifier.weight(1f))
                        Switch(checked = deduct, onCheckedChange = { deduct = it })
                    }
                } else {
                    Text(if (active.inventoryDeducted) "制作进度将标记为完成。" else "完成后将按实际用量扣减豆仓。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.recordCraftTime(active.id, elapsed)
                        viewModel.completeProject(active.id, deduct)
                        showComplete = false
                    },
                ) { Text("确认完成") }
            },
            dismissButton = { TextButton(onClick = { showComplete = false }) { Text("取消") } },
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("重置制作进度") },
            text = { Text("所有逐豆完成标记将清零。") },
            confirmButton = { Button(onClick = { viewModel.resetCraftProgress(active.id); showReset = false }) { Text("重置") } },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CompactCraftProgress(
    project: BeadProject,
    stats: BoardCraftStats,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "总进度 · ${project.boardCount} 板",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${stats.completed} / ${stats.total} · ${(stats.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(progress = { stats.progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CompactCraftToolbar(
    project: BeadProject,
    selectedBoard: Int,
    currentBoardStats: BoardCraftStats,
    canCompleteBoard: Boolean,
    canGoNext: Boolean,
    onNext: () -> Unit,
    onCompleteBoard: () -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "板 ${selectedBoard + 1} / ${project.boardCount}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "${currentBoardStats.completed} / ${currentBoardStats.total} · " +
                    "${(currentBoardStats.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "下一未完成板")
        }
        IconButton(onClick = onCompleteBoard, enabled = canCompleteBoard) {
            Icon(Icons.Default.DoneAll, contentDescription = "完成当前板")
        }
        IconButton(onClick = onOpenTools) {
            Icon(Icons.Default.GridView, contentDescription = "打开拼板工具")
        }
    }
}

@Composable
private fun CraftProgressOverview(
    project: BeadProject,
    overallStats: BoardCraftStats,
    boardStats: List<BoardCraftStats>,
    selectedBoard: Int,
    onSelectBoard: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(project.id, selectedBoard) {
        listState.animateScrollToItem(selectedBoard)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("总进度", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${project.boardSize} × ${project.boardSize} 拼板 · 共 ${project.boardCount} 板",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${overallStats.completed} / ${overallStats.total} · ${(overallStats.progress * 100).roundToInt()}%",
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(progress = { overallStats.progress }, modifier = Modifier.fillMaxWidth())
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(project.boardCount, key = { it }) { index ->
                val stats = boardStats[index]
                val leadingIcon = when {
                    stats.total > 0 && stats.completed == stats.total -> Icons.Default.CheckCircle
                    selectedBoard == index -> Icons.Default.GridView
                    else -> null
                }
                FilterChip(
                    selected = selectedBoard == index,
                    onClick = { onSelectBoard(index) },
                    label = {
                        Text(
                            if (stats.total == 0) "板 ${index + 1} · 空"
                            else "板 ${index + 1} · ${(stats.progress * 100).roundToInt()}%",
                            maxLines = 1,
                        )
                    },
                    leadingIcon = leadingIcon?.let { icon ->
                        { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CraftCanvas(
    project: BeadProject,
    palette: BeadPalette,
    revision: Long,
    showColorCodes: Boolean,
    highContrastGrid: Boolean,
    highlightedColor: Int?,
    hideCompleted: Boolean,
    window: GridWindow,
    onCellAction: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PatternCanvas(
        grid = project.grid,
        palette = palette,
        revision = revision,
        modifier = modifier,
        mode = PatternCanvasMode.CRAFT,
        tool = EditorTool.PAN,
        showColorCodes = showColorCodes,
        highContrastGrid = highContrastGrid,
        boardSize = project.boardSize,
        highlightColorIndex = highlightedColor,
        hideCompleted = hideCompleted,
        window = window,
        onCellAction = onCellAction,
    )
}

@Composable
private fun CraftControls(
    project: BeadProject,
    palette: BeadPalette,
    selectedBoard: Int,
    currentBoardStats: BoardCraftStats,
    bulkTargetStats: BoardCraftStats,
    highlightedColor: Int?,
    hideCompleted: Boolean,
    usedColors: List<Pair<Int, Int>>,
    completedColorCounts: Map<Int, Int>,
    nextUnfinishedBoard: Int?,
    onSelectBoard: (Int) -> Unit,
    onHighlightColor: (Int?) -> Unit,
    onHideCompleted: (Boolean) -> Unit,
    onCompleteBoard: () -> Unit,
    onReset: () -> Unit,
    onCompleteProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightedCode = highlightedColor?.let { palette.colors.getOrNull(it)?.code }
    val bulkLabel = if (highlightedCode == null) "完成本板" else "完成板内 $highlightedCode"

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前板 ${selectedBoard + 1} / ${project.boardCount}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${currentBoardStats.completed} / ${currentBoardStats.total} · " +
                    "${(currentBoardStats.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(progress = { currentBoardStats.progress }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { nextUnfinishedBoard?.let(onSelectBoard) },
                enabled = nextUnfinishedBoard != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text("下一未完成", maxLines = 1)
            }
            Button(
                onClick = onCompleteBoard,
                enabled = bulkTargetStats.total > bulkTargetStats.completed,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text(bulkLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("同色高亮", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilterChip(
                selected = hideCompleted,
                onClick = { onHideCompleted(!hideCompleted) },
                label = { Text("隐藏已拼") },
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            item {
                FilterChip(
                    selected = highlightedColor == null,
                    onClick = { onHighlightColor(null) },
                    label = { Text("全部") },
                )
            }
            items(usedColors, key = { it.first }) { item ->
                val color = palette.colors.getOrNull(item.first) ?: return@items
                val remaining = item.second - (completedColorCounts[item.first] ?: 0)
                FilterChip(
                    selected = highlightedColor == item.first,
                    onClick = { onHighlightColor(item.first) },
                    label = { Text("${color.code} $remaining") },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(Color(color.opaqueArgb)),
                        )
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("重置进度")
            }
            Button(onClick = onCompleteProject, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("完成作品")
            }
        }
    }
}

private data class BoardCraftStats(
    val total: Int,
    val completed: Int,
    val progress: Float,
)

private fun calculateBoardStats(
    project: BeadProject,
    boardIndex: Int,
    colorIndex: Int? = null,
): BoardCraftStats {
    if (boardIndex !in 0 until project.boardCount) return BoardCraftStats(0, 0, 0f)
    val boardColumn = boardIndex % project.boardColumns
    val boardRow = boardIndex / project.boardColumns
    val startColumn = boardColumn * project.boardSize
    val startRow = boardRow * project.boardSize
    val endColumn = min(startColumn + project.boardSize, project.grid.width)
    val endRow = min(startRow + project.boardSize, project.grid.height)
    var total = 0
    var completed = 0
    for (row in startRow until endRow) {
        for (column in startColumn until endColumn) {
            val index = project.grid.indexOf(column, row)
            val cellColor = project.grid.cells[index]
            if (cellColor == EMPTY_CELL || (colorIndex != null && cellColor != colorIndex)) continue
            total++
            if (project.grid.completed[index].toInt() != 0) completed++
        }
    }
    val progress = if (colorIndex == null) {
        project.boardProgress(boardIndex)
    } else if (total == 0) {
        0f
    } else {
        completed.toFloat() / total
    }
    return BoardCraftStats(total, completed, progress.coerceIn(0f, 1f))
}

@Composable
private fun EmptyCraftScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("暂无可制作的图纸", style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}
