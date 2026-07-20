package com.qiao.dougrid.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.CropRegion
import com.qiao.dougrid.core.InventoryMode
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.core.QuantizeOptions
import com.qiao.dougrid.core.Quantizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PhotoSamplingMode { AVERAGE, DOMINANT }

data class ImageImportOptions(
    val width: Int = 29,
    val height: Int = 29,
    val mode: ConversionMode = ConversionMode.PHOTO,
    val maxColors: Int = 24,
    val ditherStrength: Float = 0f,
    val cleanupIslandSize: Int = 1,
    val removeLightBackground: Boolean = false,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val cropRegion: CropRegion = CropRegion.FULL,
    val inventoryMode: InventoryMode? = null,
    val photoSamplingMode: PhotoSamplingMode = PhotoSamplingMode.AVERAGE,
    val edgeStrength: Float = 0.2f,
)

open class ImageImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class UnsupportedImageException(message: String, cause: Throwable? = null) : ImageImportException(message, cause)

class ImagePermissionException(cause: Throwable) : ImageImportException(
    "没有权限读取这张图片，请返回后重新选择文件。",
    cause,
)

class ImageUnavailableException(cause: Throwable) : ImageImportException(
    "找不到图片原文件。如果图片来自微信、网盘或云相册，请先下载原图再导入。",
    cause,
)

class ImageReadException(cause: Throwable) : ImageImportException(
    "读取图片时中断或文件已损坏，请确认原图已完整下载后重试。",
    cause,
)

class ImageTooLargeException(cause: Throwable) : ImageImportException(
    "图片太大，设备内存不足。请缩小图片尺寸或另存为 JPG、PNG、WebP 后重试。",
    cause,
)

class PreparedImageImport(
    val grid: PatternGrid,
    val referenceBitmap: Bitmap,
) : AutoCloseable {
    override fun close() {
        if (!referenceBitmap.isRecycled) referenceBitmap.recycle()
    }
}

object ImageFormatSupport {
    fun supportedMimeTypes(sdkInt: Int): Array<String> = buildList {
        add("image/jpeg")
        add("image/png")
        add("image/webp")
        add("image/gif")
        add("image/bmp")
        add("image/x-ms-bmp")
        add("image/heic")
        add("image/heif")
        add("image/heic-sequence")
        add("image/heif-sequence")
        if (sdkInt >= 31) add("image/avif")
    }.toTypedArray()

    fun supportedTypes(sdkInt: Int): String = buildString {
        append("JPG、JPEG、PNG、WebP、GIF、BMP、HEIC/HEIF")
        if (sdkInt >= 31) append("、AVIF") else append("；AVIF 需要 Android 12+")
    }

    fun failureMessage(mimeType: String?, sdkInt: Int): String {
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            mime == "image/avif" && sdkInt < 31 ->
                "AVIF 需要 Android 12 或更高版本，当前设备请先转为 JPG、PNG 或 WebP。"
            mime.contains("svg") ->
                "SVG 是矢量文件，暂不能直接转拼豆图纸，请先导出为 PNG 或 JPG。"
            mime.contains("raw") || mime.contains("dng") || mime.contains("photoshop") ->
                "暂不能直接解码 RAW、DNG 或 PSD，请先导出为 JPG、PNG 或 WebP。"
            else ->
                "无法解码这张图片。支持 ${supportedTypes(sdkInt)}。如果图片来自微信、网盘或 iCloud，请先下载原图再导入。"
        }
    }
}

object BitmapPatternConverter {
    private const val MAX_DECODE_PIXELS = 4_000_000L
    private const val MAX_DECODE_SIDE = 8_192L
    private const val CANCELLATION_CHECK_INTERVAL = 4_096

    private enum class DecodeMode { COVER_TARGET, FIT_TARGET }

    private class ImageDecodeFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    private data class RegionMapping(
        val orientedRect: Rect,
        val rawRect: Rect,
    )

    private suspend fun withBitmapHandoff(
        dispatcher: CoroutineDispatcher,
        block: suspend () -> Bitmap,
    ): Bitmap {
        val pending = AtomicReference<Bitmap?>(null)
        try {
            val bitmap = withContext(dispatcher) {
                block().also { decoded ->
                    pending.set(decoded)
                    currentCoroutineContext().ensureActive()
                }
            }
            check(pending.compareAndSet(bitmap, null)) { "Bitmap 所有权交接失败" }
            return bitmap
        } finally {
            pending.getAndSet(null)?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    suspend fun convert(
        context: Context,
        uri: Uri,
        palette: BeadPalette,
        options: ImageImportOptions,
        allowedPaletteIndices: IntArray? = null,
        paletteCapacities: IntArray? = null,
    ): PatternGrid {
        require(options.width in 8..256 && options.height in 8..256)
        val multiplier = if (options.mode == ConversionMode.PHOTO) 4 else 1
        try {
            val source = withBitmapHandoff(Dispatchers.IO) {
                decodeCroppedSampled(
                    context = context,
                    uri = uri,
                    targetWidth = options.width * multiplier,
                    targetHeight = options.height * multiplier,
                    targetAspect = options.width.toFloat() / options.height,
                    cropRegion = options.cropRegion,
                )
            }
            try {
                return convertBitmap(
                    source,
                    palette,
                    options.copy(cropRegion = CropRegion.FULL),
                    allowedPaletteIndices,
                    paletteCapacities,
                )
            } finally {
                source.recycle()
            }
        } catch (tooLarge: ImageTooLargeException) {
            throw tooLarge
        } catch (outOfMemory: OutOfMemoryError) {
            throw ImageTooLargeException(outOfMemory)
        }
    }

    suspend fun prepareImport(
        context: Context,
        uri: Uri,
        palette: BeadPalette,
        options: ImageImportOptions,
        allowedPaletteIndices: IntArray? = null,
        paletteCapacities: IntArray? = null,
        referenceMaxSide: Int = 1_600,
    ): PreparedImageImport {
        require(options.width in 8..256 && options.height in 8..256)
        require(referenceMaxSide > 0) { "参考图尺寸必须大于 0" }
        val targetAspect = options.width.toFloat() / options.height
        val referenceWidth = if (targetAspect >= 1f) {
            referenceMaxSide
        } else {
            (referenceMaxSide * targetAspect).roundToInt().coerceAtLeast(1)
        }
        val referenceHeight = if (targetAspect >= 1f) {
            (referenceMaxSide / targetAspect).roundToInt().coerceAtLeast(1)
        } else {
            referenceMaxSide
        }
        val multiplier = if (options.mode == ConversionMode.PHOTO) 4 else 1
        try {
            val source = withBitmapHandoff(Dispatchers.IO) {
                decodeCroppedSampled(
                    context = context,
                    uri = uri,
                    targetWidth = max(options.width * multiplier, referenceWidth),
                    targetHeight = max(options.height * multiplier, referenceHeight),
                    targetAspect = targetAspect,
                    cropRegion = options.cropRegion,
                )
            }
            try {
                val grid = convertBitmap(
                    source = source,
                    palette = palette,
                    options = options.copy(cropRegion = CropRegion.FULL),
                    allowedPaletteIndices = allowedPaletteIndices,
                    paletteCapacities = paletteCapacities,
                )
                val reference = withBitmapHandoff(Dispatchers.Default) {
                    copyScaledToFit(source, referenceWidth, referenceHeight)
                }
                return PreparedImageImport(grid, reference)
            } finally {
                source.recycle()
            }
        } catch (tooLarge: ImageTooLargeException) {
            throw tooLarge
        } catch (outOfMemory: OutOfMemoryError) {
            throw ImageTooLargeException(outOfMemory)
        }
    }

    suspend fun convertBitmap(
        source: Bitmap,
        palette: BeadPalette,
        options: ImageImportOptions,
        allowedPaletteIndices: IntArray? = null,
        paletteCapacities: IntArray? = null,
    ): PatternGrid = withContext(Dispatchers.Default) {
        require(options.width in 8..256 && options.height in 8..256)
        val conversionContext = currentCoroutineContext()
        conversionContext.ensureActive()
        val cropped = cropToRegionAndAspect(
            source = source,
            targetAspect = options.width.toFloat() / options.height,
            region = options.cropRegion,
        )
        try {
            val pixels = if (options.mode == ConversionMode.SPRITE) {
                sampleSprite(cropped, options.width, options.height, options)
            } else {
                samplePhoto(cropped, options.width, options.height, options)
            }
            conversionContext.ensureActive()
            val result = Quantizer.quantize(
                pixels = pixels,
                width = options.width,
                height = options.height,
                palette = palette,
                options = QuantizeOptions(
                    mode = options.mode,
                    maxColors = options.maxColors,
                    ditherStrength = options.ditherStrength,
                    cleanupIslandSize = options.cleanupIslandSize,
                    removeLightBackground = options.removeLightBackground,
                    paletteCapacities = paletteCapacities,
                    inventoryMode = options.inventoryMode ?: InventoryMode.BEST_EFFORT,
                ),
                allowedPaletteIndices = allowedPaletteIndices,
                cancellationCheck = { conversionContext.ensureActive() },
            )
            PatternGrid(options.width, options.height, result.cells)
        } finally {
            if (cropped !== source) cropped.recycle()
        }
    }

    suspend fun loadPreview(context: Context, uri: Uri, maxSide: Int = 1_200): Bitmap {
        try {
            return withBitmapHandoff(Dispatchers.IO) {
                require(maxSide > 0) { "预览尺寸必须大于 0" }
                val decoded = decodeSampled(
                    context = context,
                    uri = uri,
                    targetWidth = maxSide,
                    targetHeight = maxSide,
                    mode = DecodeMode.FIT_TARGET,
                    cropRegion = CropRegion.FULL,
                )
                scaleToFit(decoded, maxSide, maxSide)
            }
        } catch (tooLarge: ImageTooLargeException) {
            throw tooLarge
        } catch (outOfMemory: OutOfMemoryError) {
            throw ImageTooLargeException(outOfMemory)
        }
    }

    private suspend fun decodeCroppedSampled(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        targetAspect: Float,
        cropRegion: CropRegion,
    ): Bitmap {
        try {
            require(targetWidth > 0 && targetHeight > 0) { "目标尺寸必须大于 0" }
            val mimeType = context.contentResolver.getType(uri)?.substringBefore(';')?.trim()
            val failures = mutableListOf<ImageDecodeFailure>()
            try {
                return decodeWithBitmapRegionDecoder(
                    context = context,
                    uri = uri,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetAspect = targetAspect,
                    cropRegion = cropRegion,
                )
            } catch (failure: ImageDecodeFailure) {
                failures += failure
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    return decodeFullWithImageDecoderThenCrop(
                        context,
                        uri,
                        targetWidth,
                        targetHeight,
                        targetAspect,
                        cropRegion,
                    )
                } catch (failure: ImageDecodeFailure) {
                    failures += failure
                }
            }

            try {
                return decodeFullWithBitmapFactoryThenCrop(
                    context,
                    uri,
                    targetWidth,
                    targetHeight,
                    targetAspect,
                    cropRegion,
                )
            } catch (failure: ImageDecodeFailure) {
                failures += failure
            }

            val finalFailure = failures.last()
            throw UnsupportedImageException(
                ImageFormatSupport.failureMessage(mimeType, Build.VERSION.SDK_INT),
                finalFailure,
            ).also { unsupported ->
                failures.dropLast(1).forEach(unsupported::addSuppressed)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (permission: SecurityException) {
            throw ImagePermissionException(permission)
        } catch (missing: FileNotFoundException) {
            throw ImageUnavailableException(missing)
        } catch (readFailure: IOException) {
            throw ImageReadException(readFailure)
        } catch (outOfMemory: OutOfMemoryError) {
            throw ImageTooLargeException(outOfMemory)
        }
    }

    private suspend fun decodeWithBitmapRegionDecoder(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        targetAspect: Float,
        cropRegion: CropRegion,
    ): Bitmap {
        val coroutineContext = currentCoroutineContext()
        val resolver = context.contentResolver
        val orientation = readExifOrientation(context, uri)
        coroutineContext.ensureActive()
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开图片")
        var result: Bitmap? = null
        try {
            input.use { stream ->
                val decoder = try {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(stream, false)
                        ?: throw ImageDecodeFailure("BitmapRegionDecoder 无法打开图片")
                } catch (failure: ImageDecodeFailure) {
                    throw failure
                } catch (failure: IOException) {
                    throw ImageDecodeFailure("图片格式不支持区域解码", failure)
                }
                try {
                    val mapping = regionMapping(
                        rawWidth = decoder.width,
                        rawHeight = decoder.height,
                        orientation = orientation,
                        region = cropRegion,
                        targetAspect = targetAspect,
                    )
                    val sampleSize = calculateRegionSampleSize(
                        mapping = mapping,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                    )
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val decoded = decoder.decodeRegion(mapping.rawRect, options)
                        ?: throw ImageDecodeFailure("BitmapRegionDecoder 无法解码裁剪区域")
                    try {
                        coroutineContext.ensureActive()
                        result = applyExifOrientation(decoded, orientation)
                    } catch (error: Throwable) {
                        if (!decoded.isRecycled) decoded.recycle()
                        throw error
                    }
                } finally {
                    decoder.recycle()
                }
            }
        } catch (error: Throwable) {
            result?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            throw error
        }
        return checkNotNull(result) { "区域解码未返回图片" }
    }

    private suspend fun decodeFullWithBitmapFactoryThenCrop(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        targetAspect: Float,
        cropRegion: CropRegion,
    ): Bitmap {
        val source = decodeWithBitmapFactory(
            context,
            uri,
            targetWidth,
            targetHeight,
            DecodeMode.COVER_TARGET,
            cropRegion,
        )
        return cropOwned(source, targetAspect, cropRegion)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun decodeFullWithImageDecoderThenCrop(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        targetAspect: Float,
        cropRegion: CropRegion,
    ): Bitmap {
        val source = decodeWithImageDecoder(
            context,
            uri,
            targetWidth,
            targetHeight,
            DecodeMode.COVER_TARGET,
            cropRegion,
        )
        return cropOwned(source, targetAspect, cropRegion)
    }

    private suspend fun cropOwned(source: Bitmap, targetAspect: Float, cropRegion: CropRegion): Bitmap {
        val cropped = try {
            currentCoroutineContext().ensureActive()
            cropToRegionAndAspect(source, targetAspect, cropRegion)
        } catch (error: Throwable) {
            if (!source.isRecycled) source.recycle()
            throw error
        }
        if (cropped !== source) source.recycle()
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            if (!cropped.isRecycled) cropped.recycle()
            throw error
        }
        return cropped
    }

    private suspend fun decodeSampled(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
        cropRegion: CropRegion,
    ): Bitmap {
        try {
            require(targetWidth > 0 && targetHeight > 0) { "目标尺寸必须大于 0" }
            val mimeType = context.contentResolver.getType(uri)?.substringBefore(';')?.trim()
            val bitmapFactoryFailure = try {
                return decodeWithBitmapFactory(
                    context,
                    uri,
                    targetWidth,
                    targetHeight,
                    mode,
                    cropRegion,
                )
            } catch (failure: ImageDecodeFailure) {
                failure
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    return decodeWithImageDecoder(
                        context,
                        uri,
                        targetWidth,
                        targetHeight,
                        mode,
                        cropRegion,
                    )
                } catch (imageDecoderFailure: ImageDecodeFailure) {
                    throw UnsupportedImageException(
                        ImageFormatSupport.failureMessage(mimeType, Build.VERSION.SDK_INT),
                        imageDecoderFailure,
                    ).also { it.addSuppressed(bitmapFactoryFailure) }
                }
            }
            throw UnsupportedImageException(
                ImageFormatSupport.failureMessage(mimeType, Build.VERSION.SDK_INT),
                bitmapFactoryFailure,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (permission: SecurityException) {
            throw ImagePermissionException(permission)
        } catch (missing: FileNotFoundException) {
            throw ImageUnavailableException(missing)
        } catch (readFailure: IOException) {
            throw ImageReadException(readFailure)
        } catch (outOfMemory: OutOfMemoryError) {
            throw ImageTooLargeException(outOfMemory)
        }
    }

    private suspend fun decodeWithBitmapFactory(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
        cropRegion: CropRegion,
    ): Bitmap {
        val coroutineContext = currentCoroutineContext()
        val resolver = context.contentResolver
        val orientation = readExifOrientation(context, uri)
        coroutineContext.ensureActive()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = resolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开图片")
        boundsInput.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeFailure("BitmapFactory 无法读取图片尺寸")
        }
        coroutineContext.ensureActive()

        val sampleSize = calculateSampleSize(
            rawWidth = bounds.outWidth,
            rawHeight = bounds.outHeight,
            orientation = orientation,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            mode = mode,
            cropRegion = cropRegion,
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        var decoded: Bitmap? = null
        val decodeInput = resolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开图片")
        try {
            decodeInput.use {
                decoded = BitmapFactory.decodeStream(it, null, options)
            }
        } catch (error: Throwable) {
            decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            throw error
        }
        val ownedDecoded = decoded ?: throw ImageDecodeFailure("BitmapFactory 无法解码图片")
        try {
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            ownedDecoded.recycle()
            throw error
        }

        return applyExifOrientation(ownedDecoded, orientation)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun decodeWithImageDecoder(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
        cropRegion: CropRegion,
    ): Bitmap {
        val coroutineContext = currentCoroutineContext()
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetSampleSize(
                    calculateSampleSize(
                        rawWidth = info.size.width,
                        rawHeight = info.size.height,
                        orientation = ExifInterface.ORIENTATION_NORMAL,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        mode = mode,
                        cropRegion = cropRegion,
                    ),
                )
            }
        } catch (decodeFailure: ImageDecoder.DecodeException) {
            val sourceCause = decodeFailure.cause
            if (decodeFailure.error == ImageDecoder.DecodeException.SOURCE_EXCEPTION) {
                when (sourceCause) {
                    is SecurityException -> throw sourceCause
                    is FileNotFoundException -> throw sourceCause
                    is IOException -> throw sourceCause
                }
            }
            throw ImageDecodeFailure("ImageDecoder 无法解码图片", decodeFailure)
        }
        try {
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
        return decoded
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: throw FileNotFoundException("无法打开图片")
        } catch (missing: FileNotFoundException) {
            throw missing
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun regionMapping(
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
        region: CropRegion,
        targetAspect: Float,
    ): RegionMapping {
        if (rawWidth <= 0 || rawHeight <= 0) throw ImageDecodeFailure("无法读取图片尺寸")
        val swapsAxes = orientationSwapsAxes(orientation)
        val orientedWidth = if (swapsAxes) rawHeight else rawWidth
        val orientedHeight = if (swapsAxes) rawWidth else rawHeight
        val orientedRect = cropRectForAspect(
            width = orientedWidth,
            height = orientedHeight,
            targetAspect = targetAspect,
            region = region,
        )
        val corners = listOf(
            orientedRect.left to orientedRect.top,
            orientedRect.right to orientedRect.top,
            orientedRect.left to orientedRect.bottom,
            orientedRect.right to orientedRect.bottom,
        ).map { (x, y) ->
            mapOrientedEdgeToRaw(x, y, rawWidth, rawHeight, orientation)
        }
        val rawRect = Rect(
            corners.minOf { it.first }.coerceIn(0, rawWidth - 1),
            corners.minOf { it.second }.coerceIn(0, rawHeight - 1),
            corners.maxOf { it.first }.coerceIn(1, rawWidth),
            corners.maxOf { it.second }.coerceIn(1, rawHeight),
        )
        if (rawRect.width() <= 0 || rawRect.height() <= 0) {
            throw ImageDecodeFailure("裁剪区域超出图片范围")
        }
        return RegionMapping(orientedRect, rawRect)
    }

    private fun cropRectForAspect(
        width: Int,
        height: Int,
        targetAspect: Float,
        region: CropRegion,
    ): Rect {
        val safe = region.normalized()
        var left = (safe.left * width).roundToInt().coerceIn(0, width - 1)
        var top = (safe.top * height).roundToInt().coerceIn(0, height - 1)
        var cropWidth = (safe.width * width).roundToInt().coerceIn(1, width - left)
        var cropHeight = (safe.height * height).roundToInt().coerceIn(1, height - top)
        val selectedAspect = cropWidth.toFloat() / cropHeight
        if (abs(selectedAspect - targetAspect) >= 0.002f) {
            if (selectedAspect > targetAspect) {
                val adjustedWidth = (cropHeight * targetAspect).roundToInt().coerceIn(1, cropWidth)
                left += (cropWidth - adjustedWidth) / 2
                cropWidth = adjustedWidth
            } else {
                val adjustedHeight = (cropWidth / targetAspect).roundToInt().coerceIn(1, cropHeight)
                top += (cropHeight - adjustedHeight) / 2
                cropHeight = adjustedHeight
            }
        }
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun mapOrientedEdgeToRaw(
        x: Int,
        y: Int,
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
    ): Pair<Int, Int> = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rawWidth - x to y
        ExifInterface.ORIENTATION_ROTATE_180 -> rawWidth - x to rawHeight - y
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to rawHeight - y
        ExifInterface.ORIENTATION_TRANSPOSE -> y to x
        ExifInterface.ORIENTATION_ROTATE_90 -> y to rawHeight - x
        ExifInterface.ORIENTATION_TRANSVERSE -> rawWidth - y to rawHeight - x
        ExifInterface.ORIENTATION_ROTATE_270 -> rawWidth - y to x
        else -> x to y
    }

    private fun calculateRegionSampleSize(
        mapping: RegionMapping,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        val orientedWidth = mapping.orientedRect.width().toDouble()
        val orientedHeight = mapping.orientedRect.height().toDouble()
        var sampleSize = 1
        while (sampleSize <= Int.MAX_VALUE / 2) {
            val next = sampleSize * 2
            if (orientedWidth / next < targetWidth || orientedHeight / next < targetHeight) break
            sampleSize = next
        }
        while (!isDecodeWithinBudget(mapping.rawRect.width(), mapping.rawRect.height(), sampleSize) &&
            sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private suspend fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = exifMatrix(orientation) ?: return source
        val transformed = try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }
        if (transformed !== source) source.recycle()
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            if (!transformed.isRecycled) transformed.recycle()
            throw error
        }
        return transformed
    }

    private fun orientationSwapsAxes(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270

    private fun calculateSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
        cropRegion: CropRegion,
    ): Int {
        val swapsAxes = orientationSwapsAxes(orientation)
        val orientedWidth = (if (swapsAxes) rawHeight else rawWidth).toDouble()
        val orientedHeight = (if (swapsAxes) rawWidth else rawHeight).toDouble()

        val requiredWidth: Double
        val requiredHeight: Double
        if (mode == DecodeMode.FIT_TARGET) {
            val fitScale = min(
                1.0,
                min(targetWidth / orientedWidth, targetHeight / orientedHeight),
            )
            requiredWidth = max(1.0, orientedWidth * fitScale)
            requiredHeight = max(1.0, orientedHeight * fitScale)
        } else {
            val safeCrop = cropRegion.normalized()
            requiredWidth = targetWidth.toDouble() /
                safeCrop.width.coerceAtLeast((1.0 / orientedWidth).toFloat())
            requiredHeight = targetHeight.toDouble() /
                safeCrop.height.coerceAtLeast((1.0 / orientedHeight).toFloat())
        }

        var sampleSize = 1
        while (sampleSize <= Int.MAX_VALUE / 2) {
            val next = sampleSize * 2
            val keepsRequiredDetail = orientedWidth / next >= requiredWidth &&
                orientedHeight / next >= requiredHeight
            if (!keepsRequiredDetail) break
            sampleSize = next
        }

        while (!isDecodeWithinBudget(rawWidth, rawHeight, sampleSize) &&
            sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun isDecodeWithinBudget(rawWidth: Int, rawHeight: Int, sampleSize: Int): Boolean {
        val decodedWidth = ceilDiv(rawWidth.toLong(), sampleSize.toLong())
        val decodedHeight = ceilDiv(rawHeight.toLong(), sampleSize.toLong())
        return decodedWidth <= MAX_DECODE_SIDE &&
            decodedHeight <= MAX_DECODE_SIDE &&
            decodedWidth * decodedHeight <= MAX_DECODE_PIXELS
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor

    private fun exifMatrix(orientation: Int): Matrix? {
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(
                -1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(
                -1f, 0f, 0f,
                0f, -1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(
                1f, 0f, 0f,
                0f, -1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(
                0f, 1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(
                0f, -1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(
                0f, -1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(
                0f, 1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f,
            )
            else -> return null
        }
        return Matrix().apply { setValues(values) }
    }

    private fun scaleToFit(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width <= targetWidth && source.height <= targetHeight) return source
        val scale = min(targetWidth.toDouble() / source.width, targetHeight.toDouble() / source.height)
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val scaled = try {
            Bitmap.createScaledBitmap(source, width, height, true)
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun copyScaledToFit(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scale = min(
            1.0,
            min(targetWidth.toDouble() / source.width, targetHeight.toDouble() / source.height),
        )
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val copy = if (width == source.width && height == source.height) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(source, width, height, true).let { scaled ->
                if (scaled !== source) scaled else source.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        return checkNotNull(copy) { "无法创建参考图" }
    }

    private fun cropToRegionAndAspect(source: Bitmap, targetAspect: Float, region: CropRegion): Bitmap {
        val safe = region.normalized()
        var left = (safe.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        var top = (safe.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        var cropWidth = ((safe.right - safe.left) * source.width).roundToInt().coerceIn(1, source.width - left)
        var cropHeight = ((safe.bottom - safe.top) * source.height).roundToInt().coerceIn(1, source.height - top)
        val selectedAspect = cropWidth.toFloat() / cropHeight

        if (abs(selectedAspect - targetAspect) >= 0.002f) {
            if (selectedAspect > targetAspect) {
                val adjustedWidth = (cropHeight * targetAspect).roundToInt().coerceAtLeast(1)
                left += (cropWidth - adjustedWidth) / 2
                cropWidth = adjustedWidth
            } else {
                val adjustedHeight = (cropWidth / targetAspect).roundToInt().coerceAtLeast(1)
                top += (cropHeight - adjustedHeight) / 2
                cropHeight = adjustedHeight
            }
        }
        if (left == 0 && top == 0 && cropWidth == source.width && cropHeight == source.height) return source
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    private suspend fun sampleSprite(
        source: Bitmap,
        width: Int,
        height: Int,
        options: ImageImportOptions,
    ): IntArray {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()

        if (source.width <= width && source.height <= height) {
            val scaled = Bitmap.createScaledBitmap(source, width, height, false)
            return try {
                IntArray(width * height).also { pixels ->
                    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                    adjustPixels(pixels, options)
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }

        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        coroutineContext.ensureActive()

        val bucketStamps = IntArray(32 * 32 * 32)
        val bucketWeights = DoubleArray(bucketStamps.size)
        val redSums = DoubleArray(bucketStamps.size)
        val greenSums = DoubleArray(bucketStamps.size)
        val blueSums = DoubleArray(bucketStamps.size)
        val result = IntArray(width * height)
        val xScale = source.width.toDouble() / width
        val yScale = source.height.toDouble() / height
        var samplesSinceCancellationCheck = 0

        for (targetY in 0 until height) {
            coroutineContext.ensureActive()
            val startY = targetY * yScale
            val endY = (targetY + 1) * yScale
            val firstY = floor(startY).toInt().coerceIn(0, source.height - 1)
            val lastY = ceil(endY).toInt().coerceIn(firstY + 1, source.height)
            for (targetX in 0 until width) {
                val generation = targetY * width + targetX + 1
                val startX = targetX * xScale
                val endX = (targetX + 1) * xScale
                val firstX = floor(startX).toInt().coerceIn(0, source.width - 1)
                val lastX = ceil(endX).toInt().coerceIn(firstX + 1, source.width)
                val cellArea = (endX - startX) * (endY - startY)
                var opaqueArea = 0.0
                var bestKey = -1
                var bestWeight = -1.0

                for (sourceY in firstY until lastY) {
                    val overlapY = min(endY, sourceY + 1.0) - max(startY, sourceY.toDouble())
                    if (overlapY <= 0.0) continue
                    val rowOffset = sourceY * source.width
                    for (sourceX in firstX until lastX) {
                        val overlapX = min(endX, sourceX + 1.0) - max(startX, sourceX.toDouble())
                        if (overlapX <= 0.0) continue
                        val area = overlapX * overlapY
                        val color = sourcePixels[rowOffset + sourceX]
                        val alpha = color ushr 24 and 0xFF
                        val weight = area * alpha / 255.0
                        opaqueArea += weight
                        if (weight > 0.0) {
                            val red = color ushr 16 and 0xFF
                            val green = color ushr 8 and 0xFF
                            val blue = color and 0xFF
                            val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
                            if (bucketStamps[key] != generation) {
                                bucketStamps[key] = generation
                                bucketWeights[key] = 0.0
                                redSums[key] = 0.0
                                greenSums[key] = 0.0
                                blueSums[key] = 0.0
                            }
                            bucketWeights[key] += weight
                            redSums[key] += red * weight
                            greenSums[key] += green * weight
                            blueSums[key] += blue * weight
                            val accumulatedWeight = bucketWeights[key]
                            if (accumulatedWeight > bestWeight ||
                                accumulatedWeight == bestWeight && (bestKey < 0 || key < bestKey)
                            ) {
                                bestKey = key
                                bestWeight = accumulatedWeight
                            }
                        }

                        samplesSinceCancellationCheck++
                        if (samplesSinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
                            coroutineContext.ensureActive()
                            samplesSinceCancellationCheck = 0
                        }
                    }
                }

                result[generation - 1] = if (bestKey < 0 || opaqueArea < cellArea * 0.5) {
                    0
                } else {
                    val weight = bucketWeights[bestKey].coerceAtLeast(1e-9)
                    adjustColor(
                        alpha = 255,
                        red = (redSums[bestKey] / weight).roundToInt(),
                        green = (greenSums[bestKey] / weight).roundToInt(),
                        blue = (blueSums[bestKey] / weight).roundToInt(),
                        options = options,
                    )
                }
            }
        }
        return result
    }

    private suspend fun samplePhoto(
        source: Bitmap,
        width: Int,
        height: Int,
        options: ImageImportOptions,
    ): IntArray {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val factor = 4
        val scaledWidth = width * factor
        val scaledHeight = height * factor
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        return try {
            val sourcePixels = IntArray(scaledWidth * scaledHeight)
            scaled.getPixels(sourcePixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
            val result = IntArray(width * height)
            val keys = IntArray(factor * factor)
            val counts = IntArray(factor * factor)
            val alphaSums = IntArray(factor * factor)
            val redSums = IntArray(factor * factor)
            val greenSums = IntArray(factor * factor)
            val blueSums = IntArray(factor * factor)
            for (targetY in 0 until height) {
                coroutineContext.ensureActive()
                for (targetX in 0 until width) {
                    val targetIndex = targetY * width + targetX
                    if (options.photoSamplingMode == PhotoSamplingMode.DOMINANT) {
                        var used = 0
                        for (dy in 0 until factor) for (dx in 0 until factor) {
                            val raw = sourcePixels[(targetY * factor + dy) * scaledWidth + targetX * factor + dx]
                            val adjusted = adjustColor(
                                raw ushr 24 and 0xFF,
                                raw ushr 16 and 0xFF,
                                raw ushr 8 and 0xFF,
                                raw and 0xFF,
                                options,
                            )
                            val alpha = adjusted ushr 24 and 0xFF
                            if (alpha < 16) continue
                            val red = adjusted ushr 16 and 0xFF
                            val green = adjusted ushr 8 and 0xFF
                            val blue = adjusted and 0xFF
                            val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
                            var bucket = -1
                            for (entry in 0 until used) if (keys[entry] == key) { bucket = entry; break }
                            if (bucket == -1) {
                                bucket = used++
                                keys[bucket] = key
                                counts[bucket] = 0
                                alphaSums[bucket] = 0
                                redSums[bucket] = 0
                                greenSums[bucket] = 0
                                blueSums[bucket] = 0
                            }
                            counts[bucket]++
                            alphaSums[bucket] += alpha
                            redSums[bucket] += red
                            greenSums[bucket] += green
                            blueSums[bucket] += blue
                        }
                        if (used == 0) {
                            result[targetIndex] = 0
                        } else {
                            var best = 0
                            for (entry in 1 until used) if (counts[entry] > counts[best]) best = entry
                            val count = counts[best].coerceAtLeast(1)
                            result[targetIndex] = adjustColor(
                                alphaSums[best] / count,
                                redSums[best] / count,
                                greenSums[best] / count,
                                blueSums[best] / count,
                                options.copy(brightness = 0f, contrast = 1f, saturation = 1f),
                            )
                        }
                    } else {
                        var sumR = 0.0
                        var sumG = 0.0
                        var sumB = 0.0
                        var sumA = 0.0
                        for (dy in 0 until factor) for (dx in 0 until factor) {
                            val color = sourcePixels[(targetY * factor + dy) * scaledWidth + targetX * factor + dx]
                            val alpha = (color ushr 24 and 0xFF) / 255.0
                            sumA += alpha
                            sumR += srgbToLinear((color ushr 16 and 0xFF) / 255.0) * alpha
                            sumG += srgbToLinear((color ushr 8 and 0xFF) / 255.0) * alpha
                            sumB += srgbToLinear((color and 0xFF) / 255.0) * alpha
                        }
                        val samples = factor * factor.toDouble()
                        result[targetIndex] = if (sumA < 0.01) 0 else {
                            val alpha = (sumA / samples * 255).toInt()
                            val red = (linearToSrgb(sumR / sumA) * 255).toInt()
                            val green = (linearToSrgb(sumG / sumA) * 255).toInt()
                            val blue = (linearToSrgb(sumB / sumA) * 255).toInt()
                            adjustColor(alpha, red, green, blue, options)
                        }
                    }
                }
            }
            if (options.photoSamplingMode == PhotoSamplingMode.AVERAGE && options.edgeStrength > 0f) {
                enhanceEdges(result, width, height, options.edgeStrength)
            }
            result
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private suspend fun adjustPixels(pixels: IntArray, options: ImageImportOptions) {
        val coroutineContext = currentCoroutineContext()
        for (index in pixels.indices) {
            if (index % CANCELLATION_CHECK_INTERVAL == 0) coroutineContext.ensureActive()
            val color = pixels[index]
            pixels[index] = adjustColor(
                alpha = color ushr 24 and 0xFF,
                red = color ushr 16 and 0xFF,
                green = color ushr 8 and 0xFF,
                blue = color and 0xFF,
                options = options,
            )
        }
    }

    private suspend fun enhanceEdges(pixels: IntArray, width: Int, height: Int, strength: Float) {
        if (width < 3 || height < 3) return
        val coroutineContext = currentCoroutineContext()
        val source = pixels.copyOf()
        val amount = strength.coerceIn(0f, 1f) * 0.85f
        for (y in 1 until height - 1) {
            coroutineContext.ensureActive()
            for (x in 1 until width - 1) {
                val index = y * width + x
                val color = source[index]
                val alpha = color ushr 24 and 0xFF
                if (alpha < 32) continue
                fun channel(shift: Int): Int {
                    val current = color ushr shift and 0xFF
                    val average = ((source[index - 1] ushr shift and 0xFF) +
                        (source[index + 1] ushr shift and 0xFF) +
                        (source[index - width] ushr shift and 0xFF) +
                        (source[index + width] ushr shift and 0xFF)) / 4
                    return (current + (current - average) * amount).toInt().coerceIn(0, 255)
                }
                pixels[index] = (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
    }

    private fun adjustColor(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
        options: ImageImportOptions,
    ): Int {
        if (alpha == 0) return 0
        var r = red / 255f
        var g = green / 255f
        var b = blue / 255f
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = luma + (r - luma) * options.saturation
        g = luma + (g - luma) * options.saturation
        b = luma + (b - luma) * options.saturation
        r = (r - 0.5f) * options.contrast + 0.5f + options.brightness
        g = (g - 0.5f) * options.contrast + 0.5f + options.brightness
        b = (b - 0.5f) * options.contrast + 0.5f + options.brightness
        return (alpha shl 24) or
            ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or
            (b.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(value: Double): Double =
        if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055
}
