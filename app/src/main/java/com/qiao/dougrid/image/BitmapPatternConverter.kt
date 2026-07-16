package com.qiao.dougrid.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.core.QuantizeOptions
import com.qiao.dougrid.core.Quantizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    val cropX: Float = 0.5f,
    val cropY: Float = 0.5f,
    val useInventoryOnly: Boolean = false,
    val photoSamplingMode: PhotoSamplingMode = PhotoSamplingMode.AVERAGE,
    val edgeStrength: Float = 0.2f,
)

object BitmapPatternConverter {
    private const val MAX_DECODE_PIXELS = 4_000_000L
    private const val MAX_DECODE_SIDE = 8_192L
    private const val CANCELLATION_CHECK_INTERVAL = 4_096

    private enum class DecodeMode { COVER_TARGET, FIT_TARGET }

    suspend fun convert(
        context: Context,
        uri: Uri,
        palette: BeadPalette,
        options: ImageImportOptions,
        allowedPaletteIndices: IntArray? = null,
    ): PatternGrid {
        require(options.width in 8..256 && options.height in 8..256)
        val multiplier = if (options.mode == ConversionMode.PHOTO) 4 else 1
        val source = withContext(Dispatchers.IO) {
            decodeSampled(
                context = context,
                uri = uri,
                targetWidth = options.width * multiplier,
                targetHeight = options.height * multiplier,
                mode = DecodeMode.COVER_TARGET,
            )
        }
        try {
            return convertBitmap(source, palette, options, allowedPaletteIndices)
        } finally {
            source.recycle()
        }
    }

    suspend fun convertBitmap(
        source: Bitmap,
        palette: BeadPalette,
        options: ImageImportOptions,
        allowedPaletteIndices: IntArray? = null,
    ): PatternGrid = withContext(Dispatchers.Default) {
        require(options.width in 8..256 && options.height in 8..256)
        val conversionContext = currentCoroutineContext()
        conversionContext.ensureActive()
        val cropped = cropToAspect(source, options.width.toFloat() / options.height, options.cropX, options.cropY)
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
                ),
                allowedPaletteIndices = allowedPaletteIndices,
                cancellationCheck = { conversionContext.ensureActive() },
            )
            PatternGrid(options.width, options.height, result.cells)
        } finally {
            if (cropped !== source) cropped.recycle()
        }
    }

    suspend fun loadPreview(context: Context, uri: Uri, maxSide: Int = 1_200): Bitmap =
        withContext(Dispatchers.IO) {
            require(maxSide > 0) { "预览尺寸必须大于 0" }
            val decoded = decodeSampled(
                context = context,
                uri = uri,
                targetWidth = maxSide,
                targetHeight = maxSide,
                mode = DecodeMode.FIT_TARGET,
            )
            scaleToFit(decoded, maxSide, maxSide)
        }

    private suspend fun decodeSampled(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
    ): Bitmap {
        require(targetWidth > 0 && targetHeight > 0) { "目标尺寸必须大于 0" }
        val coroutineContext = currentCoroutineContext()
        val resolver = context.contentResolver
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        coroutineContext.ensureActive()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = resolver.openInputStream(uri) ?: error("无法打开图片")
        boundsInput.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸" }
        coroutineContext.ensureActive()

        val sampleSize = calculateSampleSize(
            rawWidth = bounds.outWidth,
            rawHeight = bounds.outHeight,
            orientation = orientation,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            mode = mode,
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法解码图片")
        try {
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }

        val matrix = exifMatrix(orientation) ?: return decoded
        val transformed = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, false)
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
        if (transformed !== decoded) decoded.recycle()
        try {
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            transformed.recycle()
            throw error
        }
        return transformed
    }

    private fun calculateSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
        targetWidth: Int,
        targetHeight: Int,
        mode: DecodeMode,
    ): Int {
        val swapsAxes = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
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
            requiredWidth = targetWidth.toDouble()
            requiredHeight = targetHeight.toDouble()
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

    private fun cropToAspect(source: Bitmap, targetAspect: Float, cropX: Float, cropY: Float): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height
        if (abs(sourceAspect - targetAspect) < 0.002f) return source
        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect > targetAspect) {
            cropHeight = source.height
            cropWidth = (cropHeight * targetAspect).toInt().coerceAtLeast(1)
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth / targetAspect).toInt().coerceAtLeast(1)
        }
        val left = ((source.width - cropWidth) * cropX.coerceIn(0f, 1f)).toInt()
            .coerceIn(0, source.width - cropWidth)
        val top = ((source.height - cropHeight) * cropY.coerceIn(0f, 1f)).toInt()
            .coerceIn(0, source.height - cropHeight)
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
