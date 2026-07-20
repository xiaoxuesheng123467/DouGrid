package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuantizerTest {
    private val palette = BeadPalette(
        id = "test",
        title = "Test",
        version = "1",
        source = "fixture",
        colors = listOf(
            PaletteColor("R", ColorMath.argb(255, 0, 0)),
            PaletteColor("G", ColorMath.argb(0, 255, 0)),
            PaletteColor("B", ColorMath.argb(0, 0, 255)),
            PaletteColor("W", ColorMath.argb(255, 255, 255)),
        ),
    )

    @Test
    fun exactColorsMapToTheirPaletteIndices() {
        val pixels = intArrayOf(
            ColorMath.argb(255, 0, 0),
            ColorMath.argb(0, 255, 0),
            ColorMath.argb(0, 0, 255),
            ColorMath.argb(255, 255, 255),
        )
        val result = Quantizer.quantize(
            pixels,
            width = 2,
            height = 2,
            palette = palette,
            options = QuantizeOptions(maxColors = 4, cleanupIslandSize = 0),
        )

        assertEquals(0, result.cells[0])
        assertEquals(1, result.cells[1])
        assertEquals(2, result.cells[2])
        assertEquals(3, result.cells[3])
    }

    @Test
    fun lightBackgroundCanBecomeTransparent() {
        val result = Quantizer.quantize(
            pixels = intArrayOf(ColorMath.argb(255, 255, 255), ColorMath.argb(255, 0, 0)),
            width = 2,
            height = 1,
            palette = palette,
            options = QuantizeOptions(
                maxColors = 2,
                cleanupIslandSize = 0,
                removeLightBackground = true,
            ),
        )

        assertEquals(EMPTY_CELL, result.cells[0])
        assertTrue(result.cells[1] >= 0)
    }

    @Test
    fun lightBackgroundRemovalKeepsWhiteDetailsEnclosedByTheSubject() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        val pixels = IntArray(49) { ColorMath.argb(255, 255, 255) }
        for (coordinate in 2..4) {
            pixels[2 * 7 + coordinate] = ColorMath.argb(0, 0, 0)
            pixels[4 * 7 + coordinate] = ColorMath.argb(0, 0, 0)
            pixels[coordinate * 7 + 2] = ColorMath.argb(0, 0, 0)
            pixels[coordinate * 7 + 4] = ColorMath.argb(0, 0, 0)
        }

        val result = Quantizer.quantize(
            pixels = pixels,
            width = 7,
            height = 7,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                cleanupIslandSize = 0,
                removeLightBackground = true,
            ),
        )

        repeat(7) { coordinate ->
            assertEquals(EMPTY_CELL, result.cells[coordinate])
            assertEquals(EMPTY_CELL, result.cells[6 * 7 + coordinate])
            assertEquals(EMPTY_CELL, result.cells[coordinate * 7])
            assertEquals(EMPTY_CELL, result.cells[coordinate * 7 + 6])
        }
        assertEquals(EMPTY_CELL, result.cells[1 * 7 + 1])
        assertEquals(0, result.cells[2 * 7 + 3])
        assertEquals(1, result.cells[3 * 7 + 3])
    }

    @Test
    fun explicitlyEmptyAllowedPaletteDoesNotExpandToAllColors() {
        val result = Quantizer.quantize(
            pixels = intArrayOf(ColorMath.argb(255, 0, 0)),
            width = 1,
            height = 1,
            palette = palette,
            options = QuantizeOptions(cleanupIslandSize = 0),
            allowedPaletteIndices = intArrayOf(-1, 99),
        )

        assertContentEquals(intArrayOf(EMPTY_CELL), result.cells)
        assertTrue(result.selectedPaletteIndices.isEmpty())
    }

    @Test
    fun cancellationCheckRunsThroughoutQuantization() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        var checks = 0
        Quantizer.quantize(
            pixels = IntArray(64) { ColorMath.argb(128, 128, 128) },
            width = 8,
            height = 8,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.PHOTO,
                maxColors = 2,
                ditherStrength = 1f,
                cleanupIslandSize = 1,
            ),
            cancellationCheck = { checks++ },
        )

        assertTrue(checks >= 32, "Expected periodic checks across all conversion stages")
    }

    @Test
    fun quantizationIsDeterministicForPaletteReductionAndDithering() {
        val richPalette = paletteOf(
            ColorMath.argb(12, 18, 24),
            ColorMath.argb(55, 70, 84),
            ColorMath.argb(108, 122, 137),
            ColorMath.argb(168, 178, 188),
            ColorMath.argb(235, 239, 242),
            ColorMath.argb(210, 48, 58),
            ColorMath.argb(28, 144, 92),
            ColorMath.argb(35, 98, 205),
        )
        val width = 13
        val height = 11
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            ColorMath.argb(
                red = (x * 31 + y * 17) and 0xFF,
                green = (x * 11 + y * 43) and 0xFF,
                blue = (x * 53 + y * 7) and 0xFF,
            )
        }
        val options = QuantizeOptions(
            mode = ConversionMode.PHOTO,
            maxColors = 5,
            ditherStrength = 0.72f,
            cleanupIslandSize = 0,
        )
        val allowed = intArrayOf(7, 2, 5, 2, 0, 6, 4, 3, 1)
        val expected = Quantizer.quantize(
            pixels,
            width,
            height,
            richPalette,
            options,
            allowed,
        )

        repeat(8) {
            val actual = Quantizer.quantize(
                pixels,
                width,
                height,
                richPalette,
                options,
                allowed,
            )
            assertContentEquals(expected.selectedPaletteIndices, actual.selectedPaletteIndices)
            assertContentEquals(expected.cells, actual.cells)
        }
    }

    @Test
    fun maxColorsOneProducesAtMostOneBeadColor() {
        val grayscale = grayscalePalette()
        val pixels = IntArray(64) { index ->
            val value = index * 255 / 63
            ColorMath.argb(value, value, value)
        }
        val result = Quantizer.quantize(
            pixels = pixels,
            width = 8,
            height = 8,
            palette = grayscale,
            options = QuantizeOptions(maxColors = 1, cleanupIslandSize = 0),
        )

        assertEquals(1, result.selectedPaletteIndices.size)
        assertEquals(1, result.cells.filter { it != EMPTY_CELL }.distinct().size)
        assertTrue(result.cells.all { it == result.selectedPaletteIndices.single() })
    }

    @Test
    fun alphaAndLightBackgroundRulesDoNotEraseSaturatedForeground() {
        val foregroundPalette = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 0, 0),
            ColorMath.argb(255, 255, 0),
            ColorMath.argb(255, 255, 255),
        )
        val result = Quantizer.quantize(
            pixels = intArrayOf(
                ColorMath.argb(255, 0, 0, alpha = 0),
                ColorMath.argb(255, 0, 0, alpha = 31),
                ColorMath.argb(255, 255, 255),
                ColorMath.argb(255, 255, 0),
                ColorMath.argb(255, 0, 0, alpha = 128),
            ),
            width = 5,
            height = 1,
            palette = foregroundPalette,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 4,
                cleanupIslandSize = 0,
                removeLightBackground = true,
            ),
        )

        assertContentEquals(
            intArrayOf(EMPTY_CELL, EMPTY_CELL, EMPTY_CELL),
            result.cells.copyOfRange(0, 3),
        )
        assertEquals(2, result.cells[3])
        assertEquals(1, result.cells[4])

        val transparentOnly = Quantizer.quantize(
            pixels = IntArray(6),
            width = 3,
            height = 2,
            palette = foregroundPalette,
            options = QuantizeOptions(cleanupIslandSize = 0),
        )
        assertTrue(transparentOnly.cells.all { it == EMPTY_CELL })
        assertTrue(transparentOnly.selectedPaletteIndices.isEmpty())
    }

    @Test
    fun cleanupOnlyReplacesAnEnclosedUnambiguousIsland() {
        val enclosed = IntArray(25) { 2 }.also { it[12] = 1 }
        Quantizer.cleanupSmallIslands(enclosed, width = 5, height = 5, maxIslandSize = 1)
        assertTrue(enclosed.all { it == 2 })

        val edgeDetail = IntArray(25) { 2 }.also { it[2] = 1 }
        Quantizer.cleanupSmallIslands(edgeDetail, width = 5, height = 5, maxIslandSize = 1)
        assertEquals(1, edgeDetail[2])

        val mixedBoundary = IntArray(25) { 2 }.also {
            it[12] = 1
            it[13] = 3
        }
        Quantizer.cleanupSmallIslands(mixedBoundary, width = 5, height = 5, maxIslandSize = 1)
        assertEquals(1, mixedBoundary[12])

        val diagonalStroke = IntArray(25) { 2 }.also {
            it[6] = 1
            it[12] = 1
        }
        Quantizer.cleanupSmallIslands(diagonalStroke, width = 5, height = 5, maxIslandSize = 1)
        assertEquals(1, diagonalStroke[6])
        assertEquals(1, diagonalStroke[12])
    }

    @Test
    fun cleanupPreservesARealHighContrastSpriteDetail() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(128, 128, 128),
            ColorMath.argb(255, 255, 255),
        )
        val pixels = IntArray(49) { ColorMath.argb(255, 255, 255) }
        pixels[24] = ColorMath.argb(0, 0, 0)

        val result = Quantizer.quantize(
            pixels = pixels,
            width = 7,
            height = 7,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                cleanupIslandSize = 1,
            ),
        )

        assertEquals(0, result.cells[24])
        assertTrue(result.cells.filterIndexed { index, _ -> index != 24 }.all { it == 2 })
        assertContentEquals(intArrayOf(0, 2), result.selectedPaletteIndices)
    }

    @Test
    fun photoModeUsesDitherBlendWhileSpriteModeKeepsFlatPixels() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        val pixels = IntArray(64) { ColorMath.argb(128, 128, 128) }
        val photo = Quantizer.quantize(
            pixels = pixels,
            width = 8,
            height = 8,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.PHOTO,
                maxColors = 2,
                ditherStrength = 1f,
                cleanupIslandSize = 0,
            ),
        )
        val sprite = Quantizer.quantize(
            pixels = pixels,
            width = 8,
            height = 8,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                ditherStrength = 1f,
                cleanupIslandSize = 0,
            ),
        )

        assertContentEquals(intArrayOf(0, 1), photo.selectedPaletteIndices)
        assertEquals(setOf(0, 1), photo.cells.toSet())
        assertEquals(1, sprite.selectedPaletteIndices.size)
        assertEquals(1, sprite.cells.toSet().size)
        assertFalse(photo.cells.contentEquals(sprite.cells))
    }

    @Test
    fun ditheringDoesNotCrossTransparentBoundaries() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        val options = QuantizeOptions(
            mode = ConversionMode.PHOTO,
            maxColors = 2,
            ditherStrength = 1f,
            cleanupIslandSize = 0,
        )
        fun convert(leftColor: Int): QuantizeResult {
            val pixels = IntArray(28) { ColorMath.argb(128, 128, 128) }
            repeat(4) { row ->
                repeat(3) { column -> pixels[row * 7 + column] = leftColor }
                pixels[row * 7 + 3] = 0
            }
            return Quantizer.quantize(pixels, 7, 4, blackWhite, options)
        }
        val darkLeft = convert(ColorMath.argb(0, 0, 0))
        val lightLeft = convert(ColorMath.argb(255, 255, 255))

        repeat(4) { row ->
            assertEquals(EMPTY_CELL, darkLeft.cells[row * 7 + 3])
            assertEquals(EMPTY_CELL, lightLeft.cells[row * 7 + 3])
            assertContentEquals(
                darkLeft.cells.copyOfRange(row * 7 + 4, row * 7 + 7),
                lightLeft.cells.copyOfRange(row * 7 + 4, row * 7 + 7),
            )
        }
    }

    @Test
    fun strictInventoryUsesExactCapacitiesAndKeepsTheLowestErrorAssignments() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        val result = Quantizer.quantize(
            pixels = intArrayOf(
                ColorMath.argb(0, 0, 0),
                ColorMath.argb(72, 72, 72),
                ColorMath.argb(255, 255, 255),
            ),
            width = 3,
            height = 1,
            palette = blackWhite,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                cleanupIslandSize = 0,
                paletteCapacities = intArrayOf(1, 2),
                inventoryMode = InventoryMode.STRICT,
            ),
        )

        assertContentEquals(intArrayOf(0, 1, 1), result.cells)
        assertEquals(1, result.cells.count { it == 0 })
        assertEquals(2, result.cells.count { it == 1 })
    }

    @Test
    fun insufficientInventoryIsEmptyInStrictModeAndMinimalOverflowInBestEffortMode() {
        val pixels = intArrayOf(
            ColorMath.argb(255, 0, 0),
            ColorMath.argb(255, 0, 0),
            ColorMath.argb(255, 0, 0),
            ColorMath.argb(0, 255, 0),
            ColorMath.argb(0, 255, 0),
            ColorMath.argb(0, 255, 0),
        )
        val capacities = intArrayOf(1, 1, 0, 0)
        val strict = Quantizer.quantize(
            pixels = pixels,
            width = 6,
            height = 1,
            palette = palette,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                cleanupIslandSize = 0,
                paletteCapacities = capacities,
                inventoryMode = InventoryMode.STRICT,
            ),
        )
        val bestEffort = Quantizer.quantize(
            pixels = pixels,
            width = 6,
            height = 1,
            palette = palette,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 2,
                cleanupIslandSize = 0,
                paletteCapacities = capacities,
                inventoryMode = InventoryMode.BEST_EFFORT,
            ),
        )

        assertEquals(2, strict.cells.count { it != EMPTY_CELL })
        assertTrue(strict.cells.count { it == 0 } <= capacities[0])
        assertTrue(strict.cells.count { it == 1 } <= capacities[1])
        assertTrue(bestEffort.cells.all { it != EMPTY_CELL })
        val overflow = bestEffort.cells
            .filter { it != EMPTY_CELL }
            .groupingBy { it }
            .eachCount()
            .entries
            .sumOf { (paletteIndex, count) -> (count - capacities[paletteIndex]).coerceAtLeast(0) }
        assertEquals(4, overflow)
    }

    @Test
    fun transparentCellsDoNotConsumeStrictInventory() {
        val result = Quantizer.quantize(
            pixels = intArrayOf(
                ColorMath.argb(255, 0, 0, alpha = 0),
                ColorMath.argb(255, 0, 0),
                ColorMath.argb(255, 0, 0),
            ),
            width = 3,
            height = 1,
            palette = palette,
            options = QuantizeOptions(
                mode = ConversionMode.SPRITE,
                maxColors = 1,
                cleanupIslandSize = 0,
                paletteCapacities = intArrayOf(2, 0, 0, 0),
                inventoryMode = InventoryMode.STRICT,
            ),
        )

        assertContentEquals(intArrayOf(EMPTY_CELL, 0, 0), result.cells)
    }

    @Test
    fun inventoryConstrainedDitheringIsDeterministic() {
        val blackWhite = paletteOf(
            ColorMath.argb(0, 0, 0),
            ColorMath.argb(255, 255, 255),
        )
        val pixels = IntArray(63) { index ->
            val value = (index * 37) and 0xFF
            ColorMath.argb(value, value, value)
        }
        val options = QuantizeOptions(
            mode = ConversionMode.PHOTO,
            maxColors = 2,
            ditherStrength = 0.8f,
            cleanupIslandSize = 1,
            paletteCapacities = intArrayOf(12, 17),
            inventoryMode = InventoryMode.BEST_EFFORT,
        )
        val expected = Quantizer.quantize(pixels, 9, 7, blackWhite, options)

        repeat(8) {
            val actual = Quantizer.quantize(pixels, 9, 7, blackWhite, options)
            assertContentEquals(expected.selectedPaletteIndices, actual.selectedPaletteIndices)
            assertContentEquals(expected.cells, actual.cells)
        }
    }

    @Test
    fun inventoryModeDoesNotChangeLegacyQuantizationWithoutCapacities() {
        val pixels = IntArray(48) { index ->
            ColorMath.argb(
                red = (index * 23) and 0xFF,
                green = (index * 47) and 0xFF,
                blue = (index * 71) and 0xFF,
            )
        }
        val options = QuantizeOptions(
            mode = ConversionMode.PHOTO,
            maxColors = 3,
            ditherStrength = 0.65f,
            cleanupIslandSize = 1,
        )
        val legacy = Quantizer.quantize(pixels, 8, 6, palette, options)
        val explicitStrictWithoutCapacities = Quantizer.quantize(
            pixels,
            8,
            6,
            palette,
            options.copy(inventoryMode = InventoryMode.STRICT),
        )

        assertContentEquals(
            legacy.selectedPaletteIndices,
            explicitStrictWithoutCapacities.selectedPaletteIndices,
        )
        assertContentEquals(legacy.cells, explicitStrictWithoutCapacities.cells)
    }

    private fun grayscalePalette(): BeadPalette = paletteOf(
        ColorMath.argb(0, 0, 0),
        ColorMath.argb(51, 51, 51),
        ColorMath.argb(102, 102, 102),
        ColorMath.argb(153, 153, 153),
        ColorMath.argb(204, 204, 204),
        ColorMath.argb(255, 255, 255),
    )

    private fun paletteOf(vararg colors: Int): BeadPalette = BeadPalette(
        id = "generated-test",
        title = "Generated test palette",
        version = "1",
        source = "fixture",
        colors = colors.mapIndexed { index, color -> PaletteColor("C$index", color) },
    )
}
