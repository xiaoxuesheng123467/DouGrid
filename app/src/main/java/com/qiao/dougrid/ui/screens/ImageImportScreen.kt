@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.image.BitmapPatternConverter
import com.qiao.dougrid.image.ImageImportOptions
import com.qiao.dougrid.image.PhotoSamplingMode
import com.qiao.dougrid.ui.components.PatternThumbnail
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun ImageImportScreen(
    uri: Uri,
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onBack: () -> Unit,
) {
    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var previewError by remember(uri) { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("图片图纸") }
    var modeName by rememberSaveable { mutableStateOf(ConversionMode.PHOTO.name) }
    var width by rememberSaveable { mutableIntStateOf(29) }
    var height by rememberSaveable { mutableIntStateOf(29) }
    var widthText by rememberSaveable { mutableStateOf("29") }
    var heightText by rememberSaveable { mutableStateOf("29") }
    var paletteId by rememberSaveable { mutableStateOf(state.settings.defaultPaletteId) }
    var maxColors by rememberSaveable { mutableIntStateOf(24) }
    var dither by rememberSaveable { mutableFloatStateOf(0f) }
    var cleanup by rememberSaveable { mutableIntStateOf(1) }
    var removeBackground by rememberSaveable { mutableStateOf(false) }
    var inventoryOnly by rememberSaveable { mutableStateOf(false) }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    var saturation by rememberSaveable { mutableFloatStateOf(1f) }
    var cropX by rememberSaveable { mutableFloatStateOf(0.5f) }
    var cropY by rememberSaveable { mutableFloatStateOf(0.5f) }
    var samplingName by rememberSaveable { mutableStateOf(PhotoSamplingMode.AVERAGE.name) }
    var edgeStrength by rememberSaveable { mutableFloatStateOf(0.2f) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var patternPreview by remember(uri) { mutableStateOf<PatternGrid?>(null) }
    var patternPreviewError by remember(uri) { mutableStateOf<String?>(null) }
    var isPreviewing by remember(uri) { mutableStateOf(false) }
    val mode = ConversionMode.valueOf(modeName)
    val samplingMode = PhotoSamplingMode.valueOf(samplingName)
    val inventoryColorCount = state.inventory.count { it.paletteId == paletteId && it.onHand > 0 }
    val inventoryEntries = state.inventory.filter { it.paletteId == paletteId && it.onHand > 0 }
    val targetPalette = viewModel.palette(paletteId)
    val options = ImageImportOptions(
        width = width.coerceIn(8, 256),
        height = height.coerceIn(8, 256),
        mode = mode,
        maxColors = maxColors,
        ditherStrength = if (mode == ConversionMode.PHOTO) dither else 0f,
        cleanupIslandSize = cleanup,
        removeLightBackground = removeBackground,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        cropX = cropX,
        cropY = cropY,
        useInventoryOnly = inventoryOnly,
        photoSamplingMode = samplingMode,
        edgeStrength = edgeStrength,
    )

    LaunchedEffect(uri) {
        previewError = false
        runCatching { BitmapPatternConverter.loadPreview(viewModel.getApplication(), uri) }
            .onSuccess { preview = it }
            .onFailure { previewError = true }
    }
    DisposableEffect(preview) {
        val bitmap = preview
        onDispose { bitmap?.recycle() }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelImageImport() }
    }
    LaunchedEffect(preview, paletteId, options, inventoryEntries) {
        val source = preview ?: return@LaunchedEffect
        if (width !in 8..256 || height !in 8..256) {
            patternPreview = null
            patternPreviewError = "尺寸需要在 8–256 之间"
            return@LaunchedEffect
        }
        isPreviewing = true
        patternPreviewError = null
        try {
            delay(220)
            val allowed = if (inventoryOnly) viewModel.inventoryPaletteIndices(paletteId) else null
            if (inventoryOnly && (allowed == null || allowed.size < 2)) {
                error("豆仓里至少要有两种当前色卡的颜色")
            }
            patternPreview = BitmapPatternConverter.convertBitmap(
                source = source,
                palette = targetPalette,
                options = options,
                allowedPaletteIndices = allowed,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            patternPreview = null
            patternPreviewError = error.message ?: "预览生成失败"
        } finally {
            isPreviewing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片转图纸") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    viewModel.importImage(
                        uri = uri,
                        title = title,
                        paletteId = paletteId,
                        options = options,
                        previewGrid = patternPreview,
                    )
                },
                enabled = !state.isProcessingImage && !isPreviewing && patternPreview != null && width in 8..256 && height in 8..256,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (state.isProcessingImage) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("正在生成")
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("生成图纸")
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val expanded = maxWidth >= 760.dp
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    ImportPreview(
                        sourcePreview = preview,
                        patternPreview = patternPreview,
                        palette = targetPalette,
                        previewError = previewError,
                        patternPreviewError = patternPreviewError,
                        isPreviewing = isPreviewing,
                        showOriginal = showOriginal,
                        onShowOriginal = { showOriginal = it },
                        width = width,
                        height = height,
                        cropX = cropX,
                        cropY = cropY,
                        modifier = Modifier.weight(0.46f).fillMaxSize().padding(16.dp),
                    )
                    ImportControls(
                        modifier = Modifier.weight(0.54f).fillMaxSize(),
                        title = title,
                        onTitle = { title = it },
                        mode = mode,
                        onMode = { modeName = it.name; if (it == ConversionMode.SPRITE) dither = 0f },
                        width = width,
                        height = height,
                        widthText = widthText,
                        heightText = heightText,
                        onDimensions = { w, h, wt, ht -> width = w; height = h; widthText = wt; heightText = ht },
                        paletteId = paletteId,
                        onPalette = { paletteId = it; inventoryOnly = false },
                        maxColors = maxColors,
                        onMaxColors = { maxColors = it },
                        dither = dither,
                        onDither = {
                            dither = it
                            if (it > 0f) cleanup = 0
                        },
                        cleanup = cleanup,
                        onCleanup = { cleanup = it },
                        removeBackground = removeBackground,
                        onRemoveBackground = { removeBackground = it },
                        inventoryOnly = inventoryOnly,
                        onInventoryOnly = { inventoryOnly = it },
                        inventoryColorCount = inventoryColorCount,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        cropX = cropX,
                        cropY = cropY,
                        onBrightness = { brightness = it },
                        onContrast = { contrast = it },
                        onSaturation = { saturation = it },
                        onCropX = { cropX = it },
                        onCropY = { cropY = it },
                        samplingMode = samplingMode,
                        onSamplingMode = { samplingName = it.name },
                        edgeStrength = edgeStrength,
                        onEdgeStrength = { edgeStrength = it },
                        advanced = advanced,
                        onAdvanced = { advanced = !advanced },
                        viewModel = viewModel,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        ImportPreview(
                            sourcePreview = preview,
                            patternPreview = patternPreview,
                            palette = targetPalette,
                            previewError = previewError,
                            patternPreviewError = patternPreviewError,
                            isPreviewing = isPreviewing,
                            showOriginal = showOriginal,
                            onShowOriginal = { showOriginal = it },
                            width = width,
                            height = height,
                            cropX = cropX,
                            cropY = cropY,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    item {
                        ImportControls(
                            modifier = Modifier.fillMaxWidth(),
                            title = title,
                            onTitle = { title = it },
                            mode = mode,
                            onMode = { modeName = it.name; if (it == ConversionMode.SPRITE) dither = 0f },
                            width = width,
                            height = height,
                            widthText = widthText,
                            heightText = heightText,
                            onDimensions = { w, h, wt, ht -> width = w; height = h; widthText = wt; heightText = ht },
                            paletteId = paletteId,
                            onPalette = { paletteId = it; inventoryOnly = false },
                            maxColors = maxColors,
                            onMaxColors = { maxColors = it },
                            dither = dither,
                            onDither = {
                                dither = it
                                if (it > 0f) cleanup = 0
                            },
                            cleanup = cleanup,
                            onCleanup = { cleanup = it },
                            removeBackground = removeBackground,
                            onRemoveBackground = { removeBackground = it },
                            inventoryOnly = inventoryOnly,
                            onInventoryOnly = { inventoryOnly = it },
                            inventoryColorCount = inventoryColorCount,
                            brightness = brightness,
                            contrast = contrast,
                            saturation = saturation,
                            cropX = cropX,
                            cropY = cropY,
                            onBrightness = { brightness = it },
                            onContrast = { contrast = it },
                            onSaturation = { saturation = it },
                            onCropX = { cropX = it },
                            onCropY = { cropY = it },
                            samplingMode = samplingMode,
                            onSamplingMode = { samplingName = it.name },
                            edgeStrength = edgeStrength,
                            onEdgeStrength = { edgeStrength = it },
                            advanced = advanced,
                            onAdvanced = { advanced = !advanced },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportPreview(
    sourcePreview: Bitmap?,
    patternPreview: PatternGrid?,
    palette: BeadPalette,
    previewError: Boolean,
    patternPreviewError: String?,
    isPreviewing: Boolean,
    showOriginal: Boolean,
    onShowOriginal: (Boolean) -> Unit,
    width: Int,
    height: Int,
    cropX: Float,
    cropY: Float,
    modifier: Modifier = Modifier,
) {
    val recognizedColors = remember(patternPreview, palette) {
        patternPreview?.colorCounts()
            ?.entries
            ?.sortedByDescending { it.value }
            ?.mapNotNull { (index, count) -> palette.colors.getOrNull(index)?.let { it to count } }
            .orEmpty()
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            listOf(false to "图纸", true to "原图").forEachIndexed { index, (original, label) ->
                SegmentedButton(
                    selected = showOriginal == original,
                    onClick = { onShowOriginal(original) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    label = { Text(label) },
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val targetAspect = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
            val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val viewportModifier = if (targetAspect >= containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(targetAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(targetAspect)
            }
            Box(
                modifier = viewportModifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    previewError -> Text("无法预览图片", color = MaterialTheme.colorScheme.error)
                    showOriginal && sourcePreview != null -> Image(
                        bitmap = sourcePreview.asImageBitmap(),
                        contentDescription = "原始图片",
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(
                            horizontalBias = cropX.coerceIn(0f, 1f) * 2f - 1f,
                            verticalBias = cropY.coerceIn(0f, 1f) * 2f - 1f,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    !showOriginal && patternPreview != null -> PatternThumbnail(
                        grid = patternPreview,
                        palette = palette,
                        revision = patternPreview.hashCode().toLong(),
                        modifier = Modifier.fillMaxSize(),
                    )
                    patternPreviewError != null -> Text(
                        patternPreviewError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> CircularProgressIndicator()
                }
            }
            if (isPreviewing) {
                LinearProgressIndicator(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        val stats = patternPreview?.let {
            "${it.width} × ${it.height}  ·  ${it.colorCounts().size} 色  ·  ${it.beadCount()} 颗"
        } ?: if (patternPreviewError == null) "正在生成预览" else "调整参数后重试"
        Text(
            text = if (isPreviewing && patternPreview != null) "$stats  ·  更新中" else stats,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (recognizedColors.isNotEmpty()) {
            Text(
                text = "自动识别型号",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recognizedColors, key = { it.first.code }) { (color, count) ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(Color(color.opaqueArgb)),
                            )
                            Column {
                                Text(color.code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("$count 颗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportControls(
    modifier: Modifier,
    title: String,
    onTitle: (String) -> Unit,
    mode: ConversionMode,
    onMode: (ConversionMode) -> Unit,
    width: Int,
    height: Int,
    widthText: String,
    heightText: String,
    onDimensions: (Int, Int, String, String) -> Unit,
    paletteId: String,
    onPalette: (String) -> Unit,
    maxColors: Int,
    onMaxColors: (Int) -> Unit,
    dither: Float,
    onDither: (Float) -> Unit,
    cleanup: Int,
    onCleanup: (Int) -> Unit,
    removeBackground: Boolean,
    onRemoveBackground: (Boolean) -> Unit,
    inventoryOnly: Boolean,
    onInventoryOnly: (Boolean) -> Unit,
    inventoryColorCount: Int,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    cropX: Float,
    cropY: Float,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onCropX: (Float) -> Unit,
    onCropY: (Float) -> Unit,
    samplingMode: PhotoSamplingMode,
    onSamplingMode: (PhotoSamplingMode) -> Unit,
    edgeStrength: Float,
    onEdgeStrength: (Float) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    viewModel: DouGridViewModel,
) {
    var paletteMenu by remember { mutableStateOf(false) }
    val summary = viewModel.paletteCatalog.summaries.firstOrNull { it.id == paletteId }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text("作品名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledRow("转换模式") {
            FilterChip(selected = mode == ConversionMode.PHOTO, onClick = { onMode(ConversionMode.PHOTO) }, label = { Text("照片") })
            FilterChip(selected = mode == ConversionMode.SPRITE, onClick = { onMode(ConversionMode.SPRITE) }, label = { Text("像素图") })
        }
        LabeledRow("尺寸预设") {
            listOf(29, 58, 87).forEach { size ->
                FilterChip(
                    selected = width == size && height == size,
                    onClick = { onDimensions(size, size, size.toString(), size.toString()) },
                    label = { Text("$size") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("宽", widthText, width, height, true, onDimensions, modifier = Modifier.weight(1f))
            DimensionField("高", heightText, width, height, false, onDimensions, modifier = Modifier.weight(1f))
        }
        Box {
            OutlinedButton(onClick = { paletteMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(summary?.let { "${it.title} · ${it.colorCount} 色" } ?: "选择色卡")
            }
            DropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                viewModel.paletteCatalog.summaries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text("${item.title} · ${item.colorCount} 色") },
                        onClick = { onPalette(item.id); paletteMenu = false },
                    )
                }
            }
        }
        ValueSlider("最多颜色", maxColors.toString(), maxColors.toFloat(), 4f..96f, 91, { onMaxColors(it.toInt()) })
        if (mode == ConversionMode.PHOTO) {
            LabeledRow("照片采样") {
                FilterChip(
                    selected = samplingMode == PhotoSamplingMode.AVERAGE,
                    onClick = { onSamplingMode(PhotoSamplingMode.AVERAGE) },
                    label = { Text("自然") },
                )
                FilterChip(
                    selected = samplingMode == PhotoSamplingMode.DOMINANT,
                    onClick = { onSamplingMode(PhotoSamplingMode.DOMINANT) },
                    label = { Text("插画") },
                )
            }
            ValueSlider("抖动强度", "${(dither * 100).toInt()}%", dither, 0f..1f, 20, onDither)
            if (samplingMode == PhotoSamplingMode.AVERAGE) {
                ValueSlider("边缘保留", "${(edgeStrength * 100).toInt()}%", edgeStrength, 0f..1f, 20, onEdgeStrength)
            }
        }
        LabeledRow("去杂点") {
            (0..3).forEach { value ->
                FilterChip(
                    selected = cleanup == value,
                    onClick = { onCleanup(value) },
                    label = { Text(if (value == 0) "关" else value.toString()) },
                    enabled = dither <= 0f || value == 0,
                )
            }
        }
        ToggleRow("去除浅色背景", removeBackground, onRemoveBackground)
        ToggleRow(
            label = "仅用豆仓颜色 · $inventoryColorCount 色",
            checked = inventoryOnly,
            onChecked = onInventoryOnly,
            enabled = inventoryColorCount >= 2,
        )
        HorizontalDivider()
        OutlinedButton(onClick = onAdvanced, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("图像调整")
            Spacer(Modifier.weight(1f))
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (advanced) {
            ValueSlider("亮度", String.format(Locale.US, "%+.2f", brightness), brightness, -0.35f..0.35f, 28, onBrightness)
            ValueSlider("对比度", String.format(Locale.US, "%.2f", contrast), contrast, 0.55f..1.55f, 20, onContrast)
            ValueSlider("饱和度", String.format(Locale.US, "%.2f", saturation), saturation, 0f..1.8f, 18, onSaturation)
            ValueSlider("水平取景", "${(cropX * 100).toInt()}%", cropX, 0f..1f, 20, onCropX)
            ValueSlider("垂直取景", "${(cropY * 100).toInt()}%", cropY, 0f..1f, 20, onCropY)
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun DimensionField(
    label: String,
    text: String,
    width: Int,
    height: Int,
    isWidth: Boolean,
    onDimensions: (Int, Int, String, String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit).take(3)
            val number = filtered.toIntOrNull() ?: if (isWidth) width else height
            if (isWidth) onDimensions(number, height, filtered, height.toString())
            else onDimensions(width, number, width.toString(), filtered)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}
