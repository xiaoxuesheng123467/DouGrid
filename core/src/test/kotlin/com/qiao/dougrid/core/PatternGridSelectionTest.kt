package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatternGridSelectionTest {
    @Test
    fun copyRegionIncludesEmptyCellsAndIsDetachedFromGrid() {
        val grid = PatternGrid(
            width = 4,
            height = 3,
            cells = intArrayOf(
                0, 1, 2, 3,
                4, EMPTY_CELL, 6, 7,
                8, 9, 10, 11,
            ),
        )

        val selection = grid.copyRegion(GridRegion(left = 1, top = 0, width = 2, height = 2))
        grid[1, 0] = 99

        assertEquals(2, selection.width)
        assertEquals(2, selection.height)
        assertContentEquals(intArrayOf(1, 2, EMPTY_CELL, 6), selection.toIntArray())
        assertFailsWith<IllegalArgumentException> {
            grid.copyRegion(GridRegion(left = 3, top = 2, width = 2, height = 1))
        }
    }

    @Test
    fun clearRegionOnlyClearsSelectedNonEmptyCellsAndRoundTrips() {
        val grid = PatternGrid(
            width = 4,
            height = 3,
            cells = intArrayOf(
                0, 1, 2, 3,
                4, EMPTY_CELL, 6, 7,
                8, 9, 10, 11,
            ),
        )
        val before = grid.cells.copyOf()

        val delta = grid.clearRegion(GridRegion(left = 1, top = 1, width = 2, height = 2))
        val after = intArrayOf(
            0, 1, 2, 3,
            4, EMPTY_CELL, EMPTY_CELL, 7,
            8, EMPTY_CELL, EMPTY_CELL, 11,
        )

        assertEquals(3, delta.indices.size)
        assertDeltaRoundTrips(grid, delta, before, after)
    }

    @Test
    fun pasteRegionClipsAtGridEdgesAndPastesEmptyCells() {
        val grid = PatternGrid(3, 3, IntArray(9) { 9 })
        val before = grid.cells.copyOf()
        val selection = GridSelection(
            width = 3,
            height = 2,
            cells = intArrayOf(
                1, EMPTY_CELL, 2,
                3, 4, 5,
            ),
        )

        val delta = grid.pasteRegion(
            selection = selection,
            destinationColumn = -1,
            destinationRow = 1,
        )
        val after = intArrayOf(
            9, 9, 9,
            EMPTY_CELL, 2, 9,
            4, 5, 9,
        )

        assertDeltaRoundTrips(grid, delta, before, after)
    }

    @Test
    fun moveRegionUsesSnapshotWhenSourceAndDestinationOverlap() {
        val grid = PatternGrid(5, 1, intArrayOf(1, 2, 3, 4, 5))
        val before = grid.cells.copyOf()

        val delta = grid.moveRegion(
            region = GridRegion(left = 1, top = 0, width = 3, height = 1),
            destinationColumn = 2,
            destinationRow = 0,
        )
        val after = intArrayOf(1, EMPTY_CELL, 2, 3, 4)

        assertDeltaRoundTrips(grid, delta, before, after)

        val history = EditorHistory()
        history.record(delta)
        history.undo(grid)
        assertContentEquals(before, grid.cells)
        assertTrue(history.canRedo)
        history.redo(grid)
        assertContentEquals(after, grid.cells)
    }

    @Test
    fun clockwiseRotationSwapsRectangularDimensionsAtSelectionOrigin() {
        val grid = rotationGrid()
        val before = grid.cells.copyOf()

        val delta = grid.rotateRegionClockwise(GridRegion(left = 1, top = 1, width = 2, height = 3))
        val after = intArrayOf(
            9, 9, 9, 9, 9,
            9, 4, 3, 1, 9,
            9, 5, EMPTY_CELL, 2, 9,
            9, EMPTY_CELL, EMPTY_CELL, 6, 9,
        )

        assertDeltaRoundTrips(grid, delta, before, after)
    }

    @Test
    fun counterClockwiseRotationSwapsRectangularDimensionsAtSelectionOrigin() {
        val grid = rotationGrid()
        val before = grid.cells.copyOf()

        val delta = grid.rotateRegionCounterClockwise(
            GridRegion(left = 1, top = 1, width = 2, height = 3),
        )
        val after = intArrayOf(
            9, 9, 9, 9, 9,
            9, 2, EMPTY_CELL, 5, 9,
            9, 1, 3, 4, 9,
            9, EMPTY_CELL, EMPTY_CELL, 6, 9,
        )

        assertDeltaRoundTrips(grid, delta, before, after)
    }

    @Test
    fun rotationNearRightEdgeShiftsInwardWithoutDroppingCells() {
        val grid = PatternGrid(5, 4, IntArray(20) { it })
        val before = grid.cells.copyOf()

        val delta = grid.rotateRegionClockwise(GridRegion(left = 3, top = 1, width = 2, height = 3))

        assertDeltaRoundTrips(
            grid,
            delta,
            before,
            intArrayOf(
                0, 1, 2, 3, 4,
                5, 6, 18, 13, 8,
                10, 11, 19, 14, 9,
                15, 16, 17, EMPTY_CELL, EMPTY_CELL,
            ),
        )
    }

    @Test
    fun rotationThatCannotFitTheCanvasIsANoOp() {
        val grid = PatternGrid(2, 4, IntArray(8) { it })
        val before = grid.cells.copyOf()

        val delta = grid.rotateRegionClockwise(GridRegion(left = 0, top = 0, width = 2, height = 4))

        assertTrue(delta.indices.isEmpty())
        assertContentEquals(before, grid.cells)
    }

    @Test
    fun selectedRegionMirrorsDoNotChangeOutsideCells() {
        val before = rotationGrid().cells.copyOf()
        val region = GridRegion(left = 1, top = 1, width = 2, height = 3)

        val horizontalGrid = rotationGrid()
        val horizontalDelta = horizontalGrid.mirrorRegionHorizontal(region)
        assertDeltaRoundTrips(
            horizontalGrid,
            horizontalDelta,
            before,
            intArrayOf(
                9, 9, 9, 9, 9,
                9, 2, 1, 8, 9,
                9, EMPTY_CELL, 3, 7, 9,
                9, 5, 4, 6, 9,
            ),
        )

        val verticalGrid = rotationGrid()
        val verticalDelta = verticalGrid.mirrorRegionVertical(region)
        assertDeltaRoundTrips(
            verticalGrid,
            verticalDelta,
            before,
            intArrayOf(
                9, 9, 9, 9, 9,
                9, 4, 5, 8, 9,
                9, 3, EMPTY_CELL, 7, 9,
                9, 1, 2, 6, 9,
            ),
        )
    }

    private fun rotationGrid(): PatternGrid = PatternGrid(
        width = 5,
        height = 4,
        cells = intArrayOf(
            9, 9, 9, 9, 9,
            9, 1, 2, 8, 9,
            9, 3, EMPTY_CELL, 7, 9,
            9, 4, 5, 6, 9,
        ),
    )

    private fun assertDeltaRoundTrips(
        grid: PatternGrid,
        delta: CellDelta,
        before: IntArray,
        after: IntArray,
    ) {
        assertContentEquals(after, grid.cells)
        assertContentEquals(delta.indices.sortedArray(), delta.indices)
        delta.revertOn(grid)
        assertContentEquals(before, grid.cells)
        delta.applyTo(grid)
        assertContentEquals(after, grid.cells)
    }
}
