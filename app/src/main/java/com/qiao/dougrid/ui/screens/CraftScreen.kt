@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.EditorTool
import com.qiao.dougrid.ui.components.GridWindow
import com.qiao.dougrid.ui.components.PatternCanvas
import com.qiao.dougrid.ui.components.PatternCanvasMode
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun CraftScreen(state: DouGridUiState, viewModel: DouGridViewModel) {
    val candidates = state.projects.filter { it.grid.beadCount() > 0 }
    val active = candidates.firstOrNull { it.id == state.activeCraftProjectId } ?: candidates.firstOrNull()
    if (active == null) {
        EmptyCraftScreen()
        return
    }
    val palette = viewModel.palette(active.paletteId)
    var projectMenu by remember { mutableStateOf(false) }
    var boardIndex by rememberSaveable(active.id) { mutableIntStateOf(0) }
    var highlightedColor by rememberSaveable(active.id) { mutableStateOf<Int?>(null) }
    var hideCompleted by rememberSaveable(active.id) { mutableStateOf(false) }
    var showComplete by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    var elapsed by rememberSaveable(active.id) { mutableLongStateOf(0L) }
    val view = LocalView.current
    val colorCounts = active.grid.colorCounts()
    val usedColors = colorCounts.entries.sortedByDescending { it.value }
    val safeBoardIndex = boardIndex.coerceIn(0, active.boardCount - 1)
    val boardColumn = safeBoardIndex % active.boardColumns
    val boardRow = safeBoardIndex / active.boardColumns
    val window = GridWindow(
        startColumn = boardColumn * BeadProject.BOARD_SIZE,
        startRow = boardRow * BeadProject.BOARD_SIZE,
        width = min(BeadProject.BOARD_SIZE, active.grid.width - boardColumn * BeadProject.BOARD_SIZE),
        height = min(BeadProject.BOARD_SIZE, active.grid.height - boardRow * BeadProject.BOARD_SIZE),
    )

    DisposableEffect(state.settings.keepScreenOnInCraftMode) {
        val previous = view.keepScreenOn
        if (state.settings.keepScreenOnInCraftMode) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(active.id) {
        while (true) {
            delay(1_000)
            elapsed++
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
                                        boardIndex = 0
                                        highlightedColor = null
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("总进度", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("${active.grid.completedCount()} / ${active.grid.beadCount()} · ${(active.progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { active.progress }, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(active.boardCount) { index ->
                        FilterChip(
                            selected = safeBoardIndex == index,
                            onClick = { boardIndex = index },
                            label = { Text("板 ${index + 1}") },
                            leadingIcon = if (safeBoardIndex == index) ({ Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
            }
            PatternCanvas(
                grid = active.grid,
                palette = palette,
                revision = state.editorRevision,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                mode = PatternCanvasMode.CRAFT,
                tool = EditorTool.PAN,
                showColorCodes = state.settings.showColorCodes,
                highContrastGrid = state.settings.highContrastGrid,
                highlightColorIndex = highlightedColor,
                hideCompleted = hideCompleted,
                window = window,
                onCellAction = { index ->
                    val cellColor = active.grid.cells.getOrNull(index) ?: EMPTY_CELL
                    if (cellColor != EMPTY_CELL && (highlightedColor == null || highlightedColor == cellColor)) {
                        viewModel.toggleCraftCell(active.id, index)
                    }
                },
            )
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("同色高亮", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    FilterChip(selected = hideCompleted, onClick = { hideCompleted = !hideCompleted }, label = { Text("隐藏已拼") })
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = highlightedColor == null, onClick = { highlightedColor = null }, label = { Text("全部") })
                    }
                    items(usedColors, key = { it.key }) { item ->
                        val color = palette.colors.getOrNull(item.key) ?: return@items
                        val done = active.grid.cells.indices.count { active.grid.cells[it] == item.key && active.grid.completed[it].toInt() != 0 }
                        FilterChip(
                            selected = highlightedColor == item.key,
                            onClick = { highlightedColor = item.key },
                            label = { Text("${color.code} ${item.value - done}") },
                            leadingIcon = {
                                Box(Modifier.size(16.dp).clip(MaterialTheme.shapes.small).background(Color(color.opaqueArgb)))
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showReset = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.size(6.dp)); Text("重置进度")
                    }
                    Button(onClick = { showComplete = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.size(6.dp)); Text("完成作品")
                    }
                }
            }
        }
    }

    if (showComplete) {
        var deduct by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showComplete = false },
            title = { Text("完成作品") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("按实际用量扣减豆仓", modifier = Modifier.weight(1f))
                    Switch(checked = deduct, onCheckedChange = { deduct = it }, enabled = !active.inventoryDeducted)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.completeProject(active.id, deduct); showComplete = false }) { Text("确认完成") }
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
