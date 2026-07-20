@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CropFree
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.CropRegion
import com.qiao.dougrid.core.InventoryMode
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.image.BitmapPatternConverter
import com.qiao.dougrid.image.ImageImportOptions
import com.qiao.dougrid.image.PhotoSamplingMode
import com.qiao.dougrid.ui.components.PatternThumbnail
import com.qiao.dougrid.ui.components.CropSelectionOverlay
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

private enum class ImportPreviewMode { PATTERN, ORIGINAL, CROP }

internal class LeasedResource<T>(
    val value: T,
    private val disposer: (T) -> Unit,
) {
    private val lock = Any()
    private var leaseCount = 0
    private var retired = false
    private var disposed = false

    fun acquire(): Lease<T>? = synchronized(lock) {
        if (retired || disposed) return@synchronized null
        leaseCount += 1
        Lease(value) { release() }
    }

    fun retire() {
        val disposeNow = synchronized(lock) {
            retired = true
            markDisposedIfReady()
        }
        if (disposeNow) disposer(value)
    }

    private fun release() {
        val disposeNow = synchronized(lock) {
            check(leaseCount > 0) { "资源租约已全部释放" }
            leaseCount -= 1
            markDisposedIfReady()
        }
        if (disposeNow) disposer(value)
    }

    private fun markDisposedIfReady(): Boolean {
        if (!retired || leaseCount != 0 || disposed) return false
        disposed = true
        return true
    }

    class Lease<T> internal constructor(
        val value: T,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}

@Composable
fun ImageImportScreen(
    uri: Uri,
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onBack: () -> Unit,
) {
    var previewOwner by remember(uri) { mutableStateOf<LeasedResource<Bitmap>?>(null) }
    val previewOwnerSlot = remember(uri) { AtomicReference<LeasedResource<Bitmap>?>(null) }
    val preview = previewOwner?.value
    var previewError by remember(uri) { mutableStateOf<String?>(null) }
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
    var inventoryModeName by rememberSaveable { mutableStateOf("OFF") }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    var saturation by rememberSaveable { mutableFloatStateOf(1f) }
    var cropLeft by rememberSaveable(uri.toString()) { mutableFloatStateOf(0f) }
    var cropTop by rememberSaveable(uri.toString()) { mutableFloatStateOf(0f) }
    var cropRight by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
    var cropBottom by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
    var cropInitialized by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var lastCropAspect by rememberSaveable(uri.toString()) { mutableFloatStateOf(Float.NaN) }
    var samplingName by rememberSaveable { mutableStateOf(PhotoSamplingMode.AVERAGE.name) }
    var edgeStrength by rememberSaveable { mutableFloatStateOf(0.2f) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var previewModeName by rememberSaveable { mutableStateOf(ImportPreviewMode.PATTERN.name) }
    var patternPreview by remember(uri) { mutableStateOf<PatternGrid?>(null) }
    var patternPreviewError by remember(uri) { mutableStateOf<String?>(null) }
    var isPreviewing by remember(uri) { mutableStateOf(false) }
    val patternPreviewGeneration = remember(uri) { AtomicLong(0L) }
    val mode = ConversionMode.valueOf(modeName)
    val samplingMode = PhotoSamplingMode.valueOf(samplingName)
    val inventoryColorCount = state.inventory.count { it.paletteId == paletteId && it.onHand > 0 }
    val inventoryEntries = state.inventory.filter { it.paletteId == paletteId && it.onHand > 0 }
    val targetPalette = viewModel.palette(paletteId)
    val inventoryMode = inventoryModeName.takeUnless { it == "OFF" }?.let(InventoryMode::valueOf)
    val previewMode = ImportPreviewMode.valueOf(previewModeName)
    val dimensionsValid = widthText.toIntOrNull()?.let { it in 8..256 } == true &&
        heightText.toIntOrNull()?.let { it in 8..256 } == true
    val cropRegion = CropRegion(cropLeft, cropTop, cropRight, cropBottom).normalized()
    val updateCropRegion: (CropRegion) -> Unit = { updated ->
        val safe = updated.normalized()
        cropLeft = safe.left
        cropTop = safe.top
        cropRight = safe.right
        cropBottom = safe.bottom
    }
    val resetCrop: () -> Unit = {
        preview?.let { source ->
            updateCropRegion(CropRegion.forAspect(source.width, source.height, width.toFloat() / height.coerceAtLeast(1)))
        }
    }
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
        cropRegion = cropRegion,
        inventoryMode = inventoryMode,
        photoSamplingMode = samplingMode,
        edgeStrength = edgeStrength,
    )

    LaunchedEffect(uri) {
        previewError = null
        var loaded: Bitmap? = null
        try {
            loaded = BitmapPatternConverter.loadPreview(viewModel.getApplication(), uri)
            currentCoroutineContext().ensureActive()
            val owner = LeasedResource(checkNotNull(loaded)) { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            loaded = null
            previewOwnerSlot.getAndSet(owner)?.retire()
            previewOwner = owner
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            previewError = error.message ?: "无法预览图片"
        } finally {
            loaded?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
    DisposableEffect(previewOwnerSlot) {
        onDispose {
            previewOwnerSlot.getAndSet(null)?.retire()
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelImageImport() }
    }
    LaunchedEffect(preview?.width, preview?.height, width.toFloat() / height.coerceAtLeast(1)) {
        val source = preview ?: return@LaunchedEffect
        val targetAspect = width.toFloat() / height.coerceAtLeast(1)
        when {
            !cropInitialized -> {
                updateCropRegion(CropRegion.forAspect(source.width, source.height, targetAspect))
                cropInitialized = true
            }
            lastCropAspect.isFinite() && abs(lastCropAspect - targetAspect) >= 0.001f -> {
                updateCropRegion(cropRegion.withAspectAroundCenter(source.width, source.height, targetAspect))
            }
        }
        lastCropAspect = targetAspect
    }
    LaunchedEffect(previewOwner, paletteId, options, inventoryEntries) {
        val owner = previewOwner ?: return@LaunchedEffect
        val generation = patternPreviewGeneration.incrementAndGet()
        if (width !in 8..256 || height !in 8..256) {
            if (patternPreviewGeneration.get() == generation && previewOwner === owner) {
                patternPreview = null
                patternPreviewError = "尺寸需要在 8–256 之间"
                isPreviewing = false
            }
            return@LaunchedEffect
        }
        isPreviewing = true
        patternPreviewError = null
        try {
            delay(220)
            val capacities = inventoryMode?.let { viewModel.inventoryPaletteCapacities(paletteId) }
            if (capacities != null && capacities.sumOf { it.toLong() } == 0L) {
                error("豆仓里还没有当前色卡的库存")
            }
            val lease = owner.acquire() ?: return@LaunchedEffect
            lease.use {
                val converted = BitmapPatternConverter.convertBitmap(
                    source = lease.value,
                    palette = targetPalette,
                    options = options,
                    paletteCapacities = capacities,
                )
                if (patternPreviewGeneration.get() == generation && previewOwner === owner) {
                    patternPreview = converted
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            if (patternPreviewGeneration.get() == generation && previewOwner === owner) {
                patternPreview = null
                patternPreviewError = "图片太大，预览内存不足。请缩小图片尺寸后重试。"
            }
        } catch (error: Exception) {
            if (patternPreviewGeneration.get() == generation && previewOwner === owner) {
                patternPreview = null
                patternPreviewError = error.message ?: "预览生成失败"
            }
        } finally {
            if (patternPreviewGeneration.get() == generation && previewOwner === owner) {
                isPreviewing = false
            }
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
            Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Button(
                    onClick = {
                        viewModel.importImage(
                            uri = uri,
                            title = title,
                            paletteId = paletteId,
                            options = options,
                        )
                    },
                    enabled = !state.isProcessingImage && !isPreviewing && patternPreview != null && dimensionsValid,
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
                        previewMode = previewMode,
                        onPreviewMode = { previewModeName = it.name },
                        width = width,
                        height = height,
                        boardSize = state.settings.defaultBoardSize,
                        cropRegion = cropRegion,
                        onCropRegion = updateCropRegion,
                        onResetCrop = resetCrop,
                        modifier = Modifier.weight(0.46f).fillMaxSize().padding(16.dp),
                    )
                    ImportControls(
                        modifier = Modifier.weight(0.54f).fillMaxSize().verticalScroll(rememberScrollState()),
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
                        onPalette = { paletteId = it; inventoryModeName = "OFF" },
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
                        inventoryMode = inventoryMode,
                        onInventoryMode = { inventoryModeName = it?.name ?: "OFF" },
                        inventoryColorCount = inventoryColorCount,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        onBrightness = { brightness = it },
                        onContrast = { contrast = it },
                        onSaturation = { saturation = it },
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
                            previewMode = previewMode,
                            onPreviewMode = { previewModeName = it.name },
                            width = width,
                            height = height,
                            boardSize = state.settings.defaultBoardSize,
                            cropRegion = cropRegion,
                            onCropRegion = updateCropRegion,
                            onResetCrop = resetCrop,
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
                            onPalette = { paletteId = it; inventoryModeName = "OFF" },
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
                            inventoryMode = inventoryMode,
                            onInventoryMode = { inventoryModeName = it?.name ?: "OFF" },
                            inventoryColorCount = inventoryColorCount,
                            brightness = brightness,
                            contrast = contrast,
                            saturation = saturation,
                            onBrightness = { brightness = it },
                            onContrast = { contrast = it },
                            onSaturation = { saturation = it },
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
    previewError: String?,
    patternPreviewError: String?,
    isPreviewing: Boolean,
    previewMode: ImportPreviewMode,
    onPreviewMode: (ImportPreviewMode) -> Unit,
    width: Int,
    height: Int,
    boardSize: Int,
    cropRegion: CropRegion,
    onCropRegion: (CropRegion) -> Unit,
    onResetCrop: () -> Unit,
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
            listOf(
                ImportPreviewMode.PATTERN to "图纸",
                ImportPreviewMode.ORIGINAL to "原图",
                ImportPreviewMode.CROP to "裁剪",
            ).forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = previewMode == mode,
                    onClick = { onPreviewMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
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
            val sourceAspect = sourcePreview?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: targetAspect
            val displayAspect = if (previewMode == ImportPreviewMode.PATTERN) targetAspect else sourceAspect
            val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val viewportModifier = if (displayAspect >= containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(displayAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(displayAspect)
            }
            Box(
                modifier = viewportModifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    previewError != null -> Text(
                        previewError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    previewMode != ImportPreviewMode.PATTERN && sourcePreview != null -> Image(
                        bitmap = sourcePreview.asImageBitmap(),
                        contentDescription = "原始图片",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    previewMode == ImportPreviewMode.PATTERN && patternPreview != null -> PatternThumbnail(
                        grid = patternPreview,
                        palette = palette,
                        revision = patternPreview.hashCode().toLong(),
                        modifier = Modifier.fillMaxSize(),
                        boardSize = boardSize,
                    )
                    patternPreviewError != null -> Text(
                        patternPreviewError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> CircularProgressIndicator()
                }
                if (previewMode == ImportPreviewMode.CROP && sourcePreview != null && previewError == null) {
                    CropSelectionOverlay(
                        region = cropRegion,
                        sourceWidth = sourcePreview.width,
                        sourceHeight = sourcePreview.height,
                        targetAspect = targetAspect,
                        onRegionChange = onCropRegion,
                        modifier = Modifier.fillMaxSize(),
                    )
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
        if (previewMode == ImportPreviewMode.CROP && sourcePreview != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(7.dp))
                Text(
                    "拖动框内移动，拖动四角缩放；框外拖动可重画",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetCrop) { Text("重置") }
            }
        }
        if (previewMode == ImportPreviewMode.PATTERN && recognizedColors.isNotEmpty()) {
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
    inventoryMode: InventoryMode?,
    onInventoryMode: (InventoryMode?) -> Unit,
    inventoryColorCount: Int,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
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
            onValueChange = { onTitle(it.take(512)) },
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
                    selected = widthText == size.toString() && heightText == size.toString(),
                    onClick = { onDimensions(size, size, size.toString(), size.toString()) },
                    label = { Text("$size") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("宽", widthText, heightText, width, height, true, onDimensions, modifier = Modifier.weight(1f))
            DimensionField("高", heightText, widthText, width, height, false, onDimensions, modifier = Modifier.weight(1f))
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
        LabeledRow("库存策略 · $inventoryColorCount 色") {
            FilterChip(
                selected = inventoryMode == null,
                onClick = { onInventoryMode(null) },
                label = { Text("不限") },
            )
            FilterChip(
                selected = inventoryMode == InventoryMode.BEST_EFFORT,
                onClick = { onInventoryMode(InventoryMode.BEST_EFFORT) },
                label = { Text("优先") },
                enabled = inventoryColorCount > 0,
            )
            FilterChip(
                selected = inventoryMode == InventoryMode.STRICT,
                onClick = { onInventoryMode(InventoryMode.STRICT) },
                label = { Text("严格") },
                enabled = inventoryColorCount > 0,
            )
        }
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
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun DimensionField(
    label: String,
    text: String,
    otherText: String,
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
            if (isWidth) onDimensions(number, height, filtered, otherText)
            else onDimensions(width, number, otherText, filtered)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
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
