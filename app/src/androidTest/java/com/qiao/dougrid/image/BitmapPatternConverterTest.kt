package com.qiao.dougrid.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ColorMath
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.PaletteColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
}
