@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.qiao.dougrid.export.MaterialSubstitution
import com.qiao.dougrid.export.PatternExporter
import com.qiao.dougrid.export.PdfExportOptions
import com.qiao.dougrid.export.PdfPageOrientation
import com.qiao.dougrid.export.PngExportOptions
import com.qiao.dougrid.export.PngMode
import com.qiao.dougrid.ui.components.PatternCanvas
import com.qiao.dougrid.ui.components.PatternCanvasMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class EditorExportKind { PDF, PIXEL_PNG, GRID_PNG }

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
    var showControlsSheet by rememberSaveable(project.id) { mutableStateOf(false) }
    var showExportOptions by rememberSaveable { mutableStateOf(false) }
    var exportKindName by rememberSaveable { mutableStateOf(EditorExportKind.PDF.name) }
    var referenceAlpha by rememberSaveable { mutableFloatStateOf(0f) }
    var pendingPngModeName by rememberSaveable(project.id) { mutableStateOf(PngMode.PIXEL_ART.name) }
    var pendingPngCellSize by rememberSaveable(project.id) { mutableStateOf(24) }
    var pendingPngTransparent by rememberSaveable(project.id) { mutableStateOf(true) }
    var pendingPdfBoardSize by rememberSaveable(project.id) { mutableStateOf<Int?>(project.boardSize) }
    var pendingPdfSymbols by rememberSaveable(project.id) { mutableStateOf(true) }
    var pendingPdfColorCodes by rememberSaveable(project.id) { mutableStateOf(true) }
    var pendingPdfCalibration by rememberSaveable(project.id) { mutableStateOf(false) }
    var pendingPdfOrientationName by rememberSaveable(project.id) { mutableStateOf(PdfPageOrientation.PORTRAIT.name) }
    var pendingPdfPhysicalCellSizeMm by rememberSaveable(project.id) { mutableStateOf<Float?>(null) }
    val beadCount = remember(project.id, state.editorStatsRevision) { project.grid.beadCount() }
    val usedColors = remember(project.id, state.editorStatsRevision) {
        project.grid.colorCounts().entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    LaunchedEffect(state.materialSummaryRequestProjectId, project.id) {
        if (state.materialSummaryRequestProjectId == project.id) {
            showMaterials = true
            viewModel.consumeMaterialSummaryRequest(project.id)
        }
    }
    val reference = rememberReferenceImage(project.id, project.sourcePath)
    val safeName = project.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').ifBlank { "dougrid" }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val exportProject = project.copy(grid = project.grid.deepCopy())
        val options = PdfExportOptions(
            boardSize = pendingPdfBoardSize,
            showSymbols = pendingPdfSymbols,
            showColorCodes = pendingPdfColorCodes,
            showCalibrationMark = pendingPdfCalibration,
            orientation = PdfPageOrientation.entries.firstOrNull { it.name == pendingPdfOrientationName }
                ?: PdfPageOrientation.PORTRAIT,
            physicalCellSizeMm = pendingPdfPhysicalCellSizeMm,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        PatternExporter.exportPdf(exportProject, palette, it, options)
                    }
                        ?: error("无法写入文件")
                }
            }.onSuccess { viewModel.showMessage("PDF 已导出") }
                .onFailure { viewModel.showMessage(it.message ?: "PDF 导出失败") }
        }
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val exportProject = project.copy(grid = project.grid.deepCopy())
        val options = PngExportOptions(
            mode = PngMode.entries.firstOrNull { it.name == pendingPngModeName } ?: PngMode.PIXEL_ART,
            requestedCellSizePx = pendingPngCellSize,
            transparentEmptyCells = pendingPngTransparent,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        PatternExporter.exportPng(exportProject, palette, it, options)
                    } ?: error("无法写入文件")
                }
            }.onSuccess { viewModel.showMessage("PNG 已导出") }
                .onFailure { viewModel.showMessage(it.message ?: "PNG 导出失败") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${project.grid.width} × ${project.grid.height} · $beadCount 颗 · ${project.boardCount} 板",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("PDF 图纸") },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = {
                                    exportMenu = false
                                    exportKindName = EditorExportKind.PDF.name
                                    showExportOptions = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("像素 PNG") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    exportMenu = false
                                    exportKindName = EditorExportKind.PIXEL_PNG.name
                                    showExportOptions = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("网格 PNG") },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = {
                                    exportMenu = false
                                    exportKindName = EditorExportKind.GRID_PNG.name
                                    showExportOptions = true
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
                            DropdownMenuItem(
                                text = { Text("作品设置") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    exportMenu = false
                                    showRename = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val expanded = maxWidth >= 840.dp
            val shortHeight = maxHeight < 560.dp
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
                        usedColors = usedColors,
                        referenceAvailable = reference != null,
                        referenceAlpha = referenceAlpha,
                        onReferenceAlpha = { referenceAlpha = it },
                        onOpenPalette = { showPalette = true },
                        onMaterials = { showMaterials = true },
                        viewModel = viewModel,
                        modifier = Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
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
                    if (shortHeight) {
                        EditorCompactToolbar(
                            selectedTool = state.editorTool,
                            onSelectTool = viewModel::setEditorTool,
                            onOpenTools = { showControlsSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        EditorControls(
                            project = project,
                            palette = palette,
                            state = state,
                            usedColors = usedColors,
                            referenceAvailable = reference != null,
                            referenceAlpha = referenceAlpha,
                            onReferenceAlpha = { referenceAlpha = it },
                            onOpenPalette = { showPalette = true },
                            onMaterials = { showMaterials = true },
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }

    if (showControlsSheet) {
        ModalBottomSheet(onDismissRequest = { showControlsSheet = false }) {
            EditorControls(
                project = project,
                palette = palette,
                state = state,
                usedColors = usedColors,
                referenceAvailable = reference != null,
                referenceAlpha = referenceAlpha,
                onReferenceAlpha = { referenceAlpha = it },
                onOpenPalette = {
                    showControlsSheet = false
                    showPalette = true
                },
                onMaterials = {
                    showControlsSheet = false
                    showMaterials = true
                },
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState()),
            )
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
            beadCount = beadCount,
            statsRevision = state.editorStatsRevision,
            onOpenInventory = {
                showMaterials = false
                onOpenInventory(project.id)
            },
            onSubstitute = { source, replacement ->
                viewModel.substituteProjectColor(project.id, source, replacement)
            },
            onDismiss = { showMaterials = false },
        )
    }
    if (showRename) {
        ProjectSettingsDialog(
            project = project,
            onDismiss = { showRename = false },
            onSave = { title, folder, tags, boardSize ->
                viewModel.updateProjectMetadata(project.id, title, folder, tags, boardSize)
                showRename = false
            },
            onDuplicate = { viewModel.duplicateProject(project.id); showRename = false },
        )
    }
    if (showExportOptions) {
        ExportOptionsDialog(
            project = project,
            initialKind = EditorExportKind.valueOf(exportKindName),
            onDismiss = { showExportOptions = false },
            onExport = { kind, pdfOptions, pngOptions ->
                showExportOptions = false
                when (kind) {
                    EditorExportKind.PDF -> {
                        pendingPdfBoardSize = pdfOptions.boardSize
                        pendingPdfSymbols = pdfOptions.showSymbols
                        pendingPdfColorCodes = pdfOptions.showColorCodes
                        pendingPdfCalibration = pdfOptions.showCalibrationMark
                        pendingPdfOrientationName = pdfOptions.orientation.name
                        pendingPdfPhysicalCellSizeMm = pdfOptions.physicalCellSizeMm
                        pdfLauncher.launch("$safeName-图纸.pdf")
                    }
                    EditorExportKind.PIXEL_PNG -> {
                        pendingPngModeName = PngMode.PIXEL_ART.name
                        pendingPngCellSize = pngOptions.requestedCellSizePx
                        pendingPngTransparent = pngOptions.transparentEmptyCells
                        pngLauncher.launch("$safeName-像素图.png")
                    }
                    EditorExportKind.GRID_PNG -> {
                        pendingPngModeName = PngMode.GRID_SHEET.name
                        pendingPngCellSize = pngOptions.requestedCellSizePx
                        pendingPngTransparent = pngOptions.transparentEmptyCells
                        pngLauncher.launch("$safeName-网格图.png")
                    }
                }
            },
        )
    }
}

@Composable
private fun ExportOptionsDialog(
    project: BeadProject,
    initialKind: EditorExportKind,
    onDismiss: () -> Unit,
    onExport: (EditorExportKind, PdfExportOptions, PngExportOptions) -> Unit,
) {
    var kindName by rememberSaveable(project.id, initialKind) { mutableStateOf(initialKind.name) }
    var boardSizeText by rememberSaveable(project.id) { mutableStateOf(project.boardSize.toString()) }
    var showSymbols by rememberSaveable(project.id) { mutableStateOf(true) }
    var showColorCodes by rememberSaveable(project.id) { mutableStateOf(true) }
    var showCalibration by rememberSaveable(project.id) { mutableStateOf(true) }
    var orientationName by rememberSaveable(project.id) { mutableStateOf(PdfPageOrientation.PORTRAIT.name) }
    var printOneToOne by rememberSaveable(project.id) { mutableStateOf(false) }
    var cellSizeText by rememberSaveable(project.id) { mutableStateOf("24") }
    var transparentEmpty by rememberSaveable(project.id) { mutableStateOf(true) }
    val kind = EditorExportKind.valueOf(kindName)
    val boardSize = boardSizeText.toIntOrNull()
    val cellSize = cellSizeText.toIntOrNull()
    val orientation = PdfPageOrientation.valueOf(orientationName)
    val maxOneToOneBoardSize = if (orientation == PdfPageOrientation.PORTRAIT) 33 else 30
    val valid = when (kind) {
        EditorExportKind.PDF -> boardSize?.let {
            it in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE &&
                (!printOneToOne || it <= maxOneToOneBoardSize)
        } == true
        EditorExportKind.PIXEL_PNG, EditorExportKind.GRID_PNG -> cellSize?.let { it in 1..256 } == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item {
                        FilterChip(
                            selected = kind == EditorExportKind.PDF,
                            onClick = { kindName = EditorExportKind.PDF.name },
                            label = { Text("PDF") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = kind == EditorExportKind.PIXEL_PNG,
                            onClick = { kindName = EditorExportKind.PIXEL_PNG.name },
                            label = { Text("像素 PNG") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = kind == EditorExportKind.GRID_PNG,
                            onClick = { kindName = EditorExportKind.GRID_PNG.name },
                            label = { Text("网格 PNG") },
                        )
                    }
                }
                if (kind == EditorExportKind.PDF) {
                    OutlinedTextField(
                        value = boardSizeText,
                        onValueChange = { boardSizeText = it.filter(Char::isDigit).take(2) },
                        label = { Text("每板边长（8–64）") },
                        singleLine = true,
                        isError = boardSize?.let { it !in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE } != false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = orientation == PdfPageOrientation.PORTRAIT,
                            onClick = { orientationName = PdfPageOrientation.PORTRAIT.name },
                            label = { Text("竖版") },
                        )
                        FilterChip(
                            selected = orientation == PdfPageOrientation.LANDSCAPE,
                            onClick = { orientationName = PdfPageOrientation.LANDSCAPE.name },
                            label = { Text("横版") },
                        )
                    }
                    ExportToggle("按 5 mm 标准豆 1:1 打印", printOneToOne) { printOneToOne = it }
                    if (printOneToOne && (boardSize ?: 0) > maxOneToOneBoardSize) {
                        Text(
                            "当前方向最多容纳 $maxOneToOneBoardSize × $maxOneToOneBoardSize；请缩小每板边长或关闭 1:1。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ExportToggle("显示定位符号", showSymbols) { showSymbols = it }
                    ExportToggle("显示品牌色号", showColorCodes) { showColorCodes = it }
                    ExportToggle("打印 25 mm 校准标记", showCalibration) { showCalibration = it }
                } else {
                    OutlinedTextField(
                        value = cellSizeText,
                        onValueChange = { cellSizeText = it.filter(Char::isDigit).take(3) },
                        label = { Text("单格像素（1–256）") },
                        supportingText = { Text("超出图片尺寸上限时会自动缩小") },
                        singleLine = true,
                        isError = cellSize?.let { it !in 1..256 } != false,
                    )
                    if (kind == EditorExportKind.PIXEL_PNG) {
                        ExportToggle("空白格透明", transparentEmpty) { transparentEmpty = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onExport(
                        kind,
                        PdfExportOptions(
                            boardSize = boardSize,
                            showSymbols = showSymbols,
                            showColorCodes = showColorCodes,
                            showCalibrationMark = showCalibration,
                            orientation = orientation,
                            physicalCellSizeMm = if (printOneToOne) 5f else null,
                        ),
                        PngExportOptions(
                            requestedCellSizePx = cellSize ?: 24,
                            transparentEmptyCells = transparentEmpty,
                        ),
                    )
                },
            ) { Text("选择保存位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ExportToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private data class LoadedReferenceImage(
    val owner: Bitmap,
    val image: ImageBitmap,
)

@Composable
private fun rememberReferenceImage(projectId: String, path: String?): ImageBitmap? {
    val loadedState = remember(projectId, path) { mutableStateOf<LoadedReferenceImage?>(null) }

    LaunchedEffect(projectId, path, loadedState) {
        if (path == null) return@LaunchedEffect
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                decoded = decodeReferenceBitmap(path)
            }
            ensureActive()
            val bitmap = decoded ?: return@LaunchedEffect
            loadedState.value = LoadedReferenceImage(bitmap, bitmap.asImageBitmap())
            decoded = null
        } finally {
            decoded?.recycle()
        }
    }
    DisposableEffect(loadedState) {
        onDispose {
            val bitmap = loadedState.value?.owner
            loadedState.value = null
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    return loadedState.value?.image
}

private fun decodeReferenceBitmap(path: String): Bitmap? = try {
    BitmapFactory.decodeFile(path)
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

@Composable
private fun EditorCanvas(
    project: BeadProject,
    palette: BeadPalette,
    state: DouGridUiState,
    reference: ImageBitmap?,
    referenceAlpha: Float,
    viewModel: DouGridViewModel,
    modifier: Modifier,
) {
    PatternCanvas(
        grid = project.grid,
        palette = palette,
        revision = state.editorRevision,
        modifier = modifier.testTag("editor_pattern_canvas"),
        mode = PatternCanvasMode.EDIT,
        tool = state.editorTool,
        selectedColorIndex = state.selectedEditorColor,
        showColorCodes = state.settings.showColorCodes,
        highContrastGrid = state.settings.highContrastGrid,
        boardSize = project.boardSize,
        referenceImage = reference,
        referenceAlpha = referenceAlpha,
        selection = state.editorSelection,
        onStrokeStart = { viewModel.beginEditorStroke(project.id) },
        onStroke = { viewModel.extendEditorStroke(project.id, it) },
        onStrokeEnd = { viewModel.endEditorStroke(project.id) },
        onCellAction = { viewModel.applyToolAt(project.id, it) },
        onSelectionChange = { viewModel.setEditorSelection(project.id, it) },
    )
}

@Composable
private fun EditorCompactToolbar(
    selectedTool: EditorTool,
    onSelectTool: (EditorTool) -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { CompactToolButton(EditorTool.PENCIL, selectedTool, Icons.Default.Edit, "画笔", onSelectTool) }
            item { CompactToolButton(EditorTool.ERASER, selectedTool, Icons.AutoMirrored.Filled.Backspace, "橡皮", onSelectTool) }
            item { CompactToolButton(EditorTool.FILL, selectedTool, Icons.Default.FormatColorFill, "填充", onSelectTool) }
            item { CompactToolButton(EditorTool.PICKER, selectedTool, Icons.Default.Colorize, "吸色", onSelectTool) }
            item { CompactToolButton(EditorTool.REPLACE, selectedTool, Icons.Default.FindReplace, "替换", onSelectTool) }
            item { CompactToolButton(EditorTool.SELECT, selectedTool, Icons.Default.CropFree, "框选", onSelectTool) }
            item { CompactToolButton(EditorTool.PAN, selectedTool, Icons.Default.PanTool, "移动", onSelectTool) }
        }
        IconButton(onClick = onOpenTools, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Palette, contentDescription = "打开编辑工具")
        }
    }
}

@Composable
private fun CompactToolButton(
    tool: EditorTool,
    selectedTool: EditorTool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onSelectTool: (EditorTool) -> Unit,
) {
    if (tool == selectedTool) {
        FilledTonalIconButton(onClick = { onSelectTool(tool) }, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label)
        }
    } else {
        IconButton(onClick = { onSelectTool(tool) }, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun EditorControls(
    project: BeadProject,
    palette: BeadPalette,
    state: DouGridUiState,
    usedColors: List<Pair<Int, Int>>,
    referenceAvailable: Boolean,
    referenceAlpha: Float,
    onReferenceAlpha: (Float) -> Unit,
    onOpenPalette: () -> Unit,
    onMaterials: () -> Unit,
    viewModel: DouGridViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ToolButton(EditorTool.PENCIL, state.editorTool, Icons.Default.Edit, "画笔", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.ERASER, state.editorTool, Icons.AutoMirrored.Filled.Backspace, "橡皮", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.FILL, state.editorTool, Icons.Default.FormatColorFill, "填充", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.PICKER, state.editorTool, Icons.Default.Colorize, "吸色", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.REPLACE, state.editorTool, Icons.Default.FindReplace, "替换", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.SELECT, state.editorTool, Icons.Default.CropFree, "框选", viewModel::setEditorTool) }
            item { ToolButton(EditorTool.PAN, state.editorTool, Icons.Default.PanTool, "移动", viewModel::setEditorTool) }
        }
        state.editorSelection?.let { region ->
            SelectionControls(
                width = region.width,
                height = region.height,
                onCopy = { viewModel.copyEditorSelection(project.id) },
                onPaste = { viewModel.pasteEditorSelection(project.id) },
                onClear = { viewModel.clearEditorSelection(project.id) },
                onRotateClockwise = { viewModel.rotateEditorSelection(project.id, clockwise = true) },
                onRotateCounterClockwise = { viewModel.rotateEditorSelection(project.id, clockwise = false) },
                onMirrorHorizontal = { viewModel.mirrorEditorSelection(project.id, horizontal = true) },
                onMirrorVertical = { viewModel.mirrorEditorSelection(project.id, horizontal = false) },
                onMove = { column, row -> viewModel.moveEditorSelection(project.id, column, row) },
            )
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
            items(usedColors.take(18), key = { it.first }) { entry ->
                ColorSwatch(
                    color = Color(palette.colors.getOrNull(entry.first)?.opaqueArgb ?: 0xFF000000.toInt()),
                    code = palette.colors.getOrNull(entry.first)?.code.orEmpty(),
                    count = entry.second,
                    selected = state.selectedEditorColor == entry.first,
                    onClick = { viewModel.selectEditorColor(entry.first) },
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
            Text("${usedColors.size} 色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelectionControls(
    width: Int,
    height: Int,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onRotateClockwise: () -> Unit,
    onRotateCounterClockwise: () -> Unit,
    onMirrorHorizontal: () -> Unit,
    onMirrorVertical: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选区 $width × $height", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMove(-1, 0) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左移选区")
            }
            IconButton(onClick = { onMove(0, -1) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "上移选区")
            }
            IconButton(onClick = { onMove(0, 1) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "下移选区")
            }
            IconButton(onClick = { onMove(1, 0) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右移选区")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item { IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "复制选区") } }
            item { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "粘贴选区") } }
            item { IconButton(onClick = onRotateCounterClockwise) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "逆时针旋转选区") } }
            item { IconButton(onClick = onRotateClockwise) { Icon(Icons.AutoMirrored.Filled.RotateRight, "顺时针旋转选区") } }
            item { IconButton(onClick = onMirrorHorizontal) { Icon(Icons.Default.Flip, "水平镜像选区") } }
            item { IconButton(onClick = onMirrorVertical) { Icon(Icons.Default.SwapVert, "垂直镜像选区") } }
            item { IconButton(onClick = onClear) { Icon(Icons.Default.DeleteSweep, "清除选区") } }
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
            FilledTonalIconButton(onClick = { onSelect(tool) }, modifier = Modifier.size(48.dp)) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
        } else {
            IconButton(onClick = { onSelect(tool) }, modifier = Modifier.size(48.dp)) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
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
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
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
                modifier = Modifier.weight(1f),
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
    beadCount: Int,
    statsRevision: Long,
    onOpenInventory: () -> Unit,
    onSubstitute: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingSubstitution by remember { mutableStateOf<MaterialSubstitution?>(null) }
    val materials = remember(project.id, statsRevision, inventory) { MaterialPlanner.plan(project, palette, inventory) }
    val substitutions = remember(project.id, statsRevision, inventory) {
        MaterialPlanner.substitutionSuggestions(project, palette, inventory, maxSuggestionsPerColor = 1)
    }
    val totalShortage = materials.sumOf { it.shortage }
    val shortageColors = materials.count { it.shortage > 0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 16.dp)) {
            Text("已识别豆子型号", style = MaterialTheme.typography.titleLarge)
            Text(
                "$beadCount 颗 · ${materials.size} 色 · ${project.boardCount} 板",
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
                    substitutions[item.colorIndex]?.firstOrNull()?.let { suggestion ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(MaterialTheme.shapes.small)
                                    .background(Color(suggestion.replacement.opaqueArgb)),
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                "可用 ${suggestion.replacement.code} 替代",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { pendingSubstitution = suggestion },
                            ) { Text("预览") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    pendingSubstitution?.let { suggestion ->
        val sourceShortage = materials.firstOrNull { it.colorIndex == suggestion.sourceColorIndex }?.shortage ?: 0
        AlertDialog(
            onDismissRequest = { pendingSubstitution = null },
            title = { Text("确认整色替换") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${suggestion.source.code} → ${suggestion.replacement.code}，共 ${suggestion.beadCount} 颗")
                    Text(
                        "总缺口 $totalShortage → ${(totalShortage - sourceShortage).coerceAtLeast(0)} 颗",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("替换后 ${suggestion.replacement.code} 仍余 ${suggestion.replacementSurplus - suggestion.beadCount} 颗")
                    Text(
                        "色差评分 ${String.format(Locale.ROOT, "%.3f", suggestion.perceptualDistance)}，数值越低越接近。替换后仍可撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSubstitute(suggestion.sourceColorIndex, suggestion.replacementColorIndex)
                        pendingSubstitution = null
                    },
                ) { Text("确认替换") }
            },
            dismissButton = { TextButton(onClick = { pendingSubstitution = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectSettingsDialog(
    project: BeadProject,
    onDismiss: () -> Unit,
    onSave: (String, String?, List<String>, Int) -> Unit,
    onDuplicate: () -> Unit,
) {
    var title by rememberSaveable(project.id) { mutableStateOf(project.title) }
    var folder by rememberSaveable(project.id) { mutableStateOf(project.folder.orEmpty()) }
    var tags by rememberSaveable(project.id) { mutableStateOf(project.tags.joinToString("，")) }
    var boardSize by rememberSaveable(project.id) { mutableStateOf(project.boardSize.toString()) }
    val parsedBoardSize = boardSize.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作品设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(512) },
                    label = { Text("作品名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it.take(120) },
                    label = { Text("文件夹") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it.take(240) },
                    label = { Text("标签，用逗号分隔") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = boardSize,
                    onValueChange = { boardSize = it.filter(Char::isDigit).take(2) },
                    label = { Text("实体板边长（8–64）") },
                    supportingText = { Text("当前图纸将按此尺寸重新分板") },
                    singleLine = true,
                    isError = parsedBoardSize?.let { it !in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE } != false,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title,
                        folder.takeIf(String::isNotBlank),
                        tags.split(',', '，').map(String::trim).filter(String::isNotEmpty),
                        checkNotNull(parsedBoardSize),
                    )
                },
                enabled = title.isNotBlank() &&
                    parsedBoardSize?.let { it in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE } == true,
            ) { Text("保存") }
        },
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
