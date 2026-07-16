@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.EditorTool
import com.qiao.dougrid.data.InventoryEntry
import com.qiao.dougrid.export.MaterialPlanner
import com.qiao.dougrid.export.PatternExporter
import com.qiao.dougrid.export.PngExportOptions
import com.qiao.dougrid.export.PngMode
import com.qiao.dougrid.ui.components.PatternCanvas
import com.qiao.dougrid.ui.components.PatternCanvasMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(
    project: BeadProject,
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onBack: () -> Unit,
    onOpenInventory: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = viewModel.palette(project.paletteId)
    var exportMenu by remember { mutableStateOf(false) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showMaterials by rememberSaveable { mutableStateOf(false) }
    var referenceAlpha by rememberSaveable { mutableFloatStateOf(0f) }
    var pendingPngMode by remember { mutableStateOf(PngMode.PIXEL_ART) }

    LaunchedEffect(state.materialSummaryRequestProjectId, project.id) {
        if (state.materialSummaryRequestProjectId == project.id) {
            showMaterials = true
            viewModel.consumeMaterialSummaryRequest(project.id)
        }
    }
    val reference = remember(project.sourcePath) {
        project.sourcePath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
    val safeName = project.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').ifBlank { "dougrid" }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { PatternExporter.exportPdf(project, palette, it) }
                        ?: error("无法写入文件")
                }
            }.onSuccess { viewModel.showMessage("PDF 已导出") }
                .onFailure { viewModel.showMessage(it.message ?: "PDF 导出失败") }
        }
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val mode = pendingPngMode
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        PatternExporter.exportPng(project, palette, it, PngExportOptions(mode = mode))
                    } ?: error("无法写入文件")
                }
            }.onSuccess { viewModel.showMessage("PNG 已导出") }
                .onFailure { viewModel.showMessage(it.message ?: "PNG 导出失败") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${project.grid.width} × ${project.grid.height} · ${project.grid.beadCount()} 颗 · ${project.boardCount} 板",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { viewModel.undo(project.id) }, enabled = viewModel.canUndo(project.id)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                    }
                    IconButton(onClick = { viewModel.redo(project.id) }, enabled = viewModel.canRedo(project.id)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                    }
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.FileDownload, "导出") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("PDF 图纸") },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = { exportMenu = false; pdfLauncher.launch("$safeName-图纸.pdf") },
                            )
                            DropdownMenuItem(
                                text = { Text("像素 PNG") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    exportMenu = false; pendingPngMode = PngMode.PIXEL_ART
                                    pngLauncher.launch("$safeName-像素图.png")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("网格 PNG") },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = {
                                    exportMenu = false; pendingPngMode = PngMode.GRID_SHEET
                                    pngLauncher.launch("$safeName-网格图.png")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分享采购单") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    exportMenu = false
                                    val text = MaterialPlanner.procurementListText(project, palette, state.inventory)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "${project.title}采购单")
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }, "分享采购单"))
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showRename = true }) { Icon(Icons.Default.MoreVert, "更多") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val expanded = maxWidth >= 840.dp
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    EditorCanvas(
                        project = project,
                        palette = palette,
                        state = state,
                        reference = reference,
                        referenceAlpha = referenceAlpha,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    EditorControls(
                        project = project,
                        palette = palette,
                        state = state,
                        referenceAvailable = reference != null,
                        referenceAlpha = referenceAlpha,
                        onReferenceAlpha = { referenceAlpha = it },
                        onOpenPalette = { showPalette = true },
                        onMaterials = { showMaterials = true },
                        viewModel = viewModel,
                        modifier = Modifier.width(330.dp).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EditorCanvas(
                        project = project,
                        palette = palette,
                        state = state,
                        reference = reference,
                        referenceAlpha = referenceAlpha,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    EditorControls(
                        project = project,
                        palette = palette,
                        state = state,
                        referenceAvailable = reference != null,
                        referenceAlpha = referenceAlpha,
                        onReferenceAlpha = { referenceAlpha = it },
                        onOpenPalette = { showPalette = true },
                        onMaterials = { showMaterials = true },
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showPalette) {
        PaletteSheet(
            palette = palette,
            selectedIndex = state.selectedEditorColor,
            onSelect = { viewModel.selectEditorColor(it); showPalette = false },
            onDismiss = { showPalette = false },
        )
    }
    if (showMaterials) {
        MaterialsSheet(
            project = project,
            palette = palette,
            inventory = state.inventory,
            onOpenInventory = {
                showMaterials = false
                onOpenInventory(project.id)
            },
            onDismiss = { showMaterials = false },
        )
    }
    if (showRename) {
        RenameDialog(
            current = project.title,
            onDismiss = { showRename = false },
            onRename = { viewModel.renameProject(project.id, it); showRename = false },
            onDuplicate = { viewModel.duplicateProject(project.id); showRename = false },
        )
    }
}

@Composable
private fun EditorCanvas(
    project: BeadProject,
    palette: BeadPalette,
    state: DouGridUiState,
    reference: androidx.compose.ui.graphics.ImageBitmap?,
    referenceAlpha: Float,
    viewModel: DouGridViewModel,
    modifier: Modifier,
) {
    PatternCanvas(
        grid = project.grid,
        palette = palette,
        revision = state.editorRevision,
        modifier = modifier,
        mode = PatternCanvasMode.EDIT,
        tool = state.editorTool,
        selectedColorIndex = state.selectedEditorColor,
        showColorCodes = state.settings.showColorCodes,
        highContrastGrid = state.settings.highContrastGrid,
        referenceImage = reference,
        referenceAlpha = referenceAlpha,
        onStroke = { viewModel.applyStroke(project.id, it) },
        onCellAction = { viewModel.applyToolAt(project.id, it) },
    )
}

@Composable
private fun EditorControls(
    project: BeadProject,
    palette: BeadPalette,
    state: DouGridUiState,
    referenceAvailable: Boolean,
    referenceAlpha: Float,
    onReferenceAlpha: (Float) -> Unit,
    onOpenPalette: () -> Unit,
    onMaterials: () -> Unit,
    viewModel: DouGridViewModel,
    modifier: Modifier,
) {
    val used = project.grid.colorCounts().entries.sortedByDescending { it.value }
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ToolButton(EditorTool.PENCIL, state.editorTool, Icons.Default.Edit, "画笔", viewModel::setEditorTool)
            ToolButton(EditorTool.ERASER, state.editorTool, Icons.AutoMirrored.Filled.Backspace, "橡皮", viewModel::setEditorTool)
            ToolButton(EditorTool.FILL, state.editorTool, Icons.Default.FormatColorFill, "填充", viewModel::setEditorTool)
            ToolButton(EditorTool.PICKER, state.editorTool, Icons.Default.Colorize, "吸色", viewModel::setEditorTool)
            ToolButton(EditorTool.REPLACE, state.editorTool, Icons.Default.FindReplace, "替换", viewModel::setEditorTool)
            ToolButton(EditorTool.PAN, state.editorTool, Icons.Default.PanTool, "移动", viewModel::setEditorTool)
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("常用颜色", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenPalette) {
                Icon(Icons.Default.Palette, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text("全部")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            items(used.take(18), key = { it.key }) { entry ->
                ColorSwatch(
                    color = Color(palette.colors.getOrNull(entry.key)?.opaqueArgb ?: 0xFF000000.toInt()),
                    code = palette.colors.getOrNull(entry.key)?.code.orEmpty(),
                    count = entry.value,
                    selected = state.selectedEditorColor == entry.key,
                    onClick = { viewModel.selectEditorColor(entry.key) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.mirror(project.id, true) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Flip, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("水平")
            }
            OutlinedButton(onClick = { viewModel.mirror(project.id, false) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("垂直")
            }
            OutlinedButton(onClick = onMaterials, modifier = Modifier.weight(1f)) { Text("用量") }
        }
        if (referenceAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("参考图", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${(referenceAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(value = referenceAlpha, onValueChange = onReferenceAlpha, valueRange = 0f..0.75f, steps = 14)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                palette.colors.getOrNull(state.selectedEditorColor)?.let {
                    if (it.name.equals(it.code, ignoreCase = true)) it.code else "${it.code} · ${it.name}"
                } ?: "未选择颜色",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${used.size} 色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolButton(
    tool: EditorTool,
    selected: EditorTool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onSelect: (EditorTool) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (tool == selected) {
            FilledTonalIconButton(onClick = { onSelect(tool) }, modifier = Modifier.size(40.dp)) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
        } else {
            IconButton(onClick = { onSelect(tool) }, modifier = Modifier.size(40.dp)) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ColorSwatch(color: Color, code: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        shape = MaterialTheme.shapes.small,
        color = color,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxSize().padding(5.dp), contentAlignment = Alignment.Center) {
            Text(code.take(4), style = MaterialTheme.typography.labelSmall, color = readableColor(color), fontWeight = FontWeight.Bold)
            Text(compactCount(count), style = MaterialTheme.typography.labelSmall, color = readableColor(color), modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun PaletteSheet(
    palette: BeadPalette,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val indices = palette.colors.indices.filter { index ->
        val color = palette.colors[index]
        search.isBlank() || color.code.contains(search, true) || color.name.contains(search, true) || color.group.contains(search, true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().height(520.dp).padding(horizontal = 16.dp)) {
            Text("${palette.title} · ${palette.colors.size} 色", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索色号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(68.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                itemsIndexed(indices, key = { _, index -> palette.colors[index].code }) { _, index ->
                    val color = palette.colors[index]
                    val swatch = Color(color.opaqueArgb)
                    Button(
                        onClick = { onSelect(index) },
                        modifier = Modifier.size(68.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(swatch),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(color.code.take(6), color = readableColor(swatch), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            if (index == selectedIndex) Text("✓", color = readableColor(swatch), modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialsSheet(
    project: BeadProject,
    palette: BeadPalette,
    inventory: List<InventoryEntry>,
    onOpenInventory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val materials = remember(project.modifiedAt, inventory) { MaterialPlanner.plan(project, palette, inventory) }
    val totalShortage = materials.sumOf { it.shortage }
    val shortageColors = materials.count { it.shortage > 0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 16.dp)) {
            Text("已识别豆子型号", style = MaterialTheme.typography.titleLarge)
            Text(
                "${project.grid.beadCount()} 颗 · ${materials.size} 色 · ${project.boardCount} 板",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (totalShortage > 0) "豆仓还缺 $totalShortage 颗，$shortageColors 个型号需要补货" else "豆仓库存已满足这张图纸",
                style = MaterialTheme.typography.bodyMedium,
                color = if (totalShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = onOpenInventory,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            ) {
                Icon(Icons.Default.Inventory2, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (totalShortage > 0) "去豆仓补货" else "查看豆仓")
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(materials, key = { it.color.code }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.small).background(Color(item.color.opaqueArgb)))
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${item.symbol} · ${item.color.code}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "需要 ${item.needed} · 现有 ${item.onHand}" +
                                    if (item.shortage > 0) " · 缺 ${item.shortage}" else " · 已够",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.shortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (item.bagsToBuy > 0) "买 ${item.bagsToBuy} 袋" else "无需购买",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (item.bagsToBuy > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作品设置") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("作品名称") }, singleLine = true) },
        confirmButton = { Button(onClick = { onRename(title) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDuplicate) { Text("创建副本") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun readableColor(color: Color): Color {
    val luma = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (luma > 0.55f) Color(0xFF101414) else Color.White
}

private fun compactCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 10_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1_000f)
    else -> "${count / 1_000}k"
}
