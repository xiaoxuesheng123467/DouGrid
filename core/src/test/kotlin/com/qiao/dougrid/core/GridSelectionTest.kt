package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GridSelectionTest {
    @Test
    fun selectionIsAnImmutableStructuralValue() {
        val source = intArrayOf(1, EMPTY_CELL, 2, 3)
        val selection = GridSelection(2, 2, source)

        source[0] = 9
        val exported = selection.toIntArray()
        exported[1] = 9

        assertContentEquals(intArrayOf(1, EMPTY_CELL, 2, 3), selection.toIntArray())
        assertEquals(GridSelection(2, 2, intArrayOf(1, EMPTY_CELL, 2, 3)), selection)
        assertEquals(
            GridSelection(2, 2, intArrayOf(1, EMPTY_CELL, 2, 3)).hashCode(),
            selection.hashCode(),
        )
    }

    @Test
    fun selectionTransformsPreserveEmptyCellsAndRectangularShape() {
        val selection = GridSelection(
            width = 2,
            height = 3,
            cells = intArrayOf(
                1, 2,
                3, EMPTY_CELL,
                4, 5,
            ),
        )

        val clockwise = selection.rotateClockwise()
        assertEquals(3, clockwise.width)
        assertEquals(2, clockwise.height)
        assertContentEquals(
            intArrayOf(
                4, 3, 1,
                5, EMPTY_CELL, 2,
            ),
            clockwise.toIntArray(),
        )

        assertContentEquals(
            intArrayOf(
                2, EMPTY_CELL, 5,
                1, 3, 4,
            ),
            selection.rotateCounterClockwise().toIntArray(),
        )
        assertContentEquals(
            intArrayOf(
                2, 1,
                EMPTY_CELL, 3,
                5, 4,
            ),
            selection.mirrorHorizontal().toIntArray(),
        )
        assertContentEquals(
            intArrayOf(
                4, 5,
                3, EMPTY_CELL,
                1, 2,
            ),
            selection.mirrorVertical().toIntArray(),
        )
    }

    @Test
    fun regionNormalizesDragCornersAndClampsToGrid() {
        val region = GridRegion.fromCellCorners(
            firstColumn = 3,
            firstRow = 4,
            secondColumn = -1,
            secondRow = 1,
        )

        assertEquals(GridRegion(left = -1, top = 1, width = 5, height = 4), region)
        assertEquals(
            GridRegion(left = 0, top = 1, width = 3, height = 2),
            region.clampedTo(gridWidth = 3, gridHeight = 3),
        )
        assertEquals(null, GridRegion(left = 5, top = 0, width = 2, height = 2).clampedTo(3, 3))
    }

    @Test
    fun regionAndSelectionRejectInvalidDimensions() {
        assertFailsWith<IllegalArgumentException> { GridRegion(0, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { GridSelection(2, 2, intArrayOf(1, 2)) }
    }
}
