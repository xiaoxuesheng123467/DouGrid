package com.qiao.dougrid.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ColorMath
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.CropRegion
import com.qiao.dougrid.core.PaletteColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class BitmapPatternConverterTest {
    private val red = ColorMath.argb(255, 0, 0)
    private val blue = ColorMath.argb(0, 0, 255)
    private val palette = BeadPalette(
        id = "sampling-test",
        title = "Sampling test",
        version = "1",
        source = "fixture",
        colors = listOf(
            PaletteColor("R", red),
            PaletteColor("B", blue),
        ),
    )

    @Test
    fun illustrationSamplingUsesTheDominantColorInsideEachCell() = runBlocking {
        val bitmap = dominantBlocks(blockSize = 4, blocks = 8)
        try {
            val grid = BitmapPatternConverter.convertBitmap(
                source = bitmap,
                palette = palette,
                options = ImageImportOptions(
                    width = 8,
                    height = 8,
                    mode = ConversionMode.PHOTO,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                    photoSamplingMode = PhotoSamplingMode.DOMINANT,
                    edgeStrength = 0f,
                ),
            )

            assertTrue(grid.cells.all { it == 0 })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun spriteDownsamplingUsesAreaCoverageInsteadOfOnePixel() = runBlocking {
        val bitmap = dominantBlocks(blockSize = 2, blocks = 8)
        try {
            val grid = BitmapPatternConverter.convertBitmap(
                source = bitmap,
                palette = palette,
                options = ImageImportOptions(
                    width = 8,
                    height = 8,
                    mode = ConversionMode.SPRITE,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                ),
            )

            assertTrue(grid.cells.all { it == 0 })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun allExifOrientationsPreserveTheExpectedCorners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = cornerBitmap()
        val cases = listOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(0, 1, 2, 3),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(1, 0, 3, 2),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(3, 2, 1, 0),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(2, 3, 0, 1),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(0, 2, 1, 3),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(2, 0, 3, 1),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(3, 1, 2, 0),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(1, 3, 0, 2),
        )
        try {
            cases.forEach { (orientation, expectedCorners) ->
                val file = File(context.cacheDir, "dougrid-exif-$orientation.jpg")
                file.outputStream().use { output ->
                    check(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
                }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }

                val decoded = BitmapPatternConverter.loadPreview(context, Uri.fromFile(file), maxSide = 200)
                try {
                    val swapsAxes = orientation in setOf(
                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface.ORIENTATION_TRANSVERSE,
                        ExifInterface.ORIENTATION_ROTATE_270,
                    )
                    assertEquals(if (swapsAxes) 60 else 80, decoded.width)
                    assertEquals(if (swapsAxes) 80 else 60, decoded.height)
                    assertEquals(expectedCorners, cornerLabels(decoded))
                } finally {
                    decoded.recycle()
                    file.delete()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun commonRasterFormatsDecodeThroughTheImportPipeline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = cornerBitmap()
        val fixtures = mutableListOf<File>()
        try {
            listOf(
                "jpg" to Bitmap.CompressFormat.JPEG,
                "png" to Bitmap.CompressFormat.PNG,
                "webp" to Bitmap.CompressFormat.WEBP_LOSSLESS,
            ).forEach { (extension, format) ->
                val file = File(context.cacheDir, "dougrid-format.$extension")
                file.outputStream().use { output -> check(source.compress(format, 100, output)) }
                fixtures += file
            }
            File(context.cacheDir, "dougrid-format.gif").also {
                it.writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT))
                fixtures += it
            }
            File(context.cacheDir, "dougrid-format.bmp").also {
                it.writeBytes(twoByTwoBmp())
                fixtures += it
            }

            fixtures.forEach { file ->
                val decoded = BitmapPatternConverter.loadPreview(context, Uri.fromFile(file), maxSide = 200)
                try {
                    assertTrue("${file.extension} should decode", decoded.width > 0 && decoded.height > 0)
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            source.recycle()
            fixtures.forEach(File::delete)
        }
    }

    @Test
    fun cropRegionExcludesUnwantedBackground() = runBlocking {
        val pixels = IntArray(200 * 100) { index -> if (index % 200 < 100) red else blue }
        val bitmap = Bitmap.createBitmap(pixels, 200, 100, Bitmap.Config.ARGB_8888)
        try {
            val grid = BitmapPatternConverter.convertBitmap(
                source = bitmap,
                palette = palette,
                options = ImageImportOptions(
                    width = 8,
                    height = 8,
                    mode = ConversionMode.SPRITE,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                    cropRegion = com.qiao.dougrid.core.CropRegion(0.5f, 0f, 1f, 1f),
                ),
            )

            assertTrue(grid.cells.all { it == 1 })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fivePercentCropFromImageOverFourMegapixelsKeepsOriginalDetailAndAlignment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val width = 3_000
        val height = 1_600
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(red)
        }
        val canvas = Canvas(source)
        val paint = Paint().apply { style = Paint.Style.FILL }
        for (stripe in 0 until 15) {
            paint.color = if (stripe % 2 == 0) red else blue
            canvas.drawRect(
                (2_850 + stripe * 10).toFloat(),
                725f,
                (2_860 + stripe * 10).toFloat(),
                875f,
                paint,
            )
        }
        val file = File(context.cacheDir, "dougrid-tight-crop.png")
        try {
            file.outputStream().use { output -> check(source.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            BitmapPatternConverter.prepareImport(
                context = context,
                uri = Uri.fromFile(file),
                palette = palette,
                options = ImageImportOptions(
                    width = 128,
                    height = 128,
                    mode = ConversionMode.SPRITE,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                    cropRegion = CropRegion(0.95f, 0f, 1f, 1f),
                ),
                referenceMaxSide = 256,
            ).use { prepared ->
                assertEquals(150, prepared.referenceBitmap.width)
                assertEquals(150, prepared.referenceBitmap.height)
                assertTrue("tight crop should retain both stripe colors", prepared.grid.cells.toSet().size == 2)

                for (stripe in 0 until 15) {
                    val referenceX = stripe * 10 + 5
                    val expected = if (stripe % 2 == 0) 0 else 1
                    val gridX = (referenceX * prepared.grid.width / prepared.referenceBitmap.width)
                        .coerceIn(0, prepared.grid.width - 1)
                    val gridColor = prepared.grid.cells[prepared.grid.height / 2 * prepared.grid.width + gridX]
                    assertEquals("stripe $stripe should align", expected, gridColor)
                    assertEquals(
                        if (expected == 0) red else blue,
                        prepared.referenceBitmap.getPixel(referenceX, prepared.referenceBitmap.height / 2),
                    )
                }
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun preparedReferenceIsNormalizedToTheSameCropAsTheGrid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pixels = IntArray(200 * 100) { index -> if (index % 200 < 100) red else blue }
        val source = Bitmap.createBitmap(pixels, 200, 100, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "dougrid-prepared-reference.png")
        try {
            file.outputStream().use { output -> check(source.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            BitmapPatternConverter.prepareImport(
                context = context,
                uri = Uri.fromFile(file),
                palette = palette,
                options = ImageImportOptions(
                    width = 8,
                    height = 8,
                    mode = ConversionMode.SPRITE,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                    cropRegion = CropRegion(0.5f, 0f, 1f, 1f),
                ),
                referenceMaxSide = 256,
            ).use { prepared ->
                assertTrue(prepared.grid.cells.all { it == 1 })
                assertEquals(prepared.referenceBitmap.width, prepared.referenceBitmap.height)
                assertTrue(cornerLabels(prepared.referenceBitmap).all { it == 2 })
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun missingSourceGetsAnActionableErrorInsteadOfUnsupportedFormat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "dougrid-does-not-exist.png").apply { delete() }

        val failure = runCatching {
            BitmapPatternConverter.loadPreview(context, Uri.fromFile(file), maxSide = 200)
        }.exceptionOrNull()

        assertTrue(failure is ImageUnavailableException)
        assertTrue(failure?.message.orEmpty().contains("找不到"))
    }

    @Test
    fun preparedReferenceAppliesExifBeforeSaving() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = cornerBitmap()
        val file = File(context.cacheDir, "dougrid-prepared-exif.jpg")
        try {
            file.outputStream().use { output -> check(source.compress(Bitmap.CompressFormat.JPEG, 100, output)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            BitmapPatternConverter.prepareImport(
                context = context,
                uri = Uri.fromFile(file),
                palette = palette,
                options = ImageImportOptions(
                    width = 9,
                    height = 12,
                    mode = ConversionMode.SPRITE,
                    maxColors = 2,
                    cleanupIslandSize = 0,
                ),
                referenceMaxSide = 200,
            ).use { prepared ->
                assertEquals(60, prepared.referenceBitmap.width)
                assertEquals(80, prepared.referenceBitmap.height)
                assertEquals(listOf(2, 0, 3, 1), cornerLabels(prepared.referenceBitmap))
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun partialRegionCropMapsThroughEveryExifOrientation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = cornerBitmap()
        val cases = listOf(
            ExifInterface.ORIENTATION_NORMAL to 0,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to 1,
            ExifInterface.ORIENTATION_ROTATE_180 to 3,
            ExifInterface.ORIENTATION_FLIP_VERTICAL to 2,
            ExifInterface.ORIENTATION_TRANSPOSE to 0,
            ExifInterface.ORIENTATION_ROTATE_90 to 2,
            ExifInterface.ORIENTATION_TRANSVERSE to 3,
            ExifInterface.ORIENTATION_ROTATE_270 to 1,
        )
        try {
            cases.forEach { (orientation, expectedTopLeft) ->
                val file = File(context.cacheDir, "dougrid-region-exif-$orientation.jpg")
                try {
                    file.outputStream().use { output -> check(source.compress(Bitmap.CompressFormat.JPEG, 100, output)) }
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        saveAttributes()
                    }
                    val swapsAxes = orientation in setOf(
                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface.ORIENTATION_TRANSVERSE,
                        ExifInterface.ORIENTATION_ROTATE_270,
                    )
                    BitmapPatternConverter.prepareImport(
                        context = context,
                        uri = Uri.fromFile(file),
                        palette = palette,
                        options = ImageImportOptions(
                            width = if (swapsAxes) 9 else 12,
                            height = if (swapsAxes) 12 else 9,
                            mode = ConversionMode.SPRITE,
                            maxColors = 2,
                            cleanupIslandSize = 0,
                            cropRegion = CropRegion(0f, 0f, 0.5f, 0.5f),
                        ),
                        referenceMaxSide = 200,
                    ).use { prepared ->
                        assertTrue(
                            "orientation $orientation should map the displayed top-left region",
                            cornerLabels(prepared.referenceBitmap).all { it == expectedTopLeft },
                        )
                    }
                } finally {
                    file.delete()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun malformedImagePreservesBothDecoderFailures() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "dougrid-malformed.png").apply {
            writeText("not an image")
        }
        try {
            val failure = runCatching {
                BitmapPatternConverter.loadPreview(context, Uri.fromFile(file), maxSide = 200)
            }.exceptionOrNull()

            assertTrue(failure is UnsupportedImageException)
            assertTrue(failure?.cause != null)
            assertTrue(failure?.suppressed?.isNotEmpty() == true)
        } finally {
            file.delete()
        }
    }

    private fun dominantBlocks(blockSize: Int, blocks: Int): Bitmap {
        val side = blockSize * blocks
        val pixels = IntArray(side * side)
        repeat(blocks) { blockY ->
            repeat(blocks) { blockX ->
                repeat(blockSize) { y ->
                    repeat(blockSize) { x ->
                        val localIndex = y * blockSize + x
                        val color = if (localIndex < blockSize * blockSize * 3 / 4) red else blue
                        val targetX = blockX * blockSize + x
                        val targetY = blockY * blockSize + y
                        pixels[targetY * side + targetX] = color
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun cornerBitmap(): Bitmap {
        val colors = intArrayOf(
            ColorMath.argb(240, 20, 20),
            ColorMath.argb(20, 220, 40),
            ColorMath.argb(20, 50, 235),
            ColorMath.argb(240, 220, 20),
        )
        val width = 80
        val height = 60
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (if (y < height / 2) 0 else 2) + if (x < width / 2) 0 else 1
        }.map { colors[it] }.toIntArray()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun cornerLabels(bitmap: Bitmap): List<Int> {
        val references = intArrayOf(
            ColorMath.argb(240, 20, 20),
            ColorMath.argb(20, 220, 40),
            ColorMath.argb(20, 50, 235),
            ColorMath.argb(240, 220, 20),
        )
        return listOf(
            bitmap.width / 4 to bitmap.height / 4,
            bitmap.width * 3 / 4 to bitmap.height / 4,
            bitmap.width / 4 to bitmap.height * 3 / 4,
            bitmap.width * 3 / 4 to bitmap.height * 3 / 4,
        ).map { (x, y) ->
            val actual = bitmap.getPixel(x, y)
            references.indices.minBy { index -> rgbDistance(actual, references[index]) }
        }
    }

    private fun rgbDistance(left: Int, right: Int): Int {
        val red = (left ushr 16 and 0xFF) - (right ushr 16 and 0xFF)
        val green = (left ushr 8 and 0xFF) - (right ushr 8 and 0xFF)
        val blue = (left and 0xFF) - (right and 0xFF)
        return red * red + green * green + blue * blue
    }

    private fun twoByTwoBmp(): ByteArray {
        val rowSize = 8
        val pixelBytes = rowSize * 2
        return ByteBuffer.allocate(54 + pixelBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte())
            put('M'.code.toByte())
            putInt(54 + pixelBytes)
            putInt(0)
            putInt(54)
            putInt(40)
            putInt(2)
            putInt(2)
            putShort(1)
            putShort(24)
            putInt(0)
            putInt(pixelBytes)
            putInt(2_835)
            putInt(2_835)
            putInt(0)
            putInt(0)
            repeat(2) {
                put(byteArrayOf(0, 0, -1, -1, 0, 0, 0, 0))
            }
        }.array()
    }
}
