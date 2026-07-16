package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatternGridTest {
    @Test
    fun floodFillOnlyChangesConnectedRegion() {
        val grid = PatternGrid(3, 3, intArrayOf(
            1, 1, 2,
            1, 2, 2,
            3, 3, 2,
        ))

        val delta = assertNotNull(grid.floodFill(0, 0, 4))

        assertContentEquals(intArrayOf(4, 4, 2, 4, 2, 2, 3, 3, 2), grid.cells)
        assertEquals(3, delta.indices.size)
        delta.revertOn(grid)
        assertContentEquals(intArrayOf(1, 1, 2, 1, 2, 2, 3, 3, 2), grid.cells)
    }

    @Test
    fun historySupportsUndoAndRedo() {
        val grid = PatternGrid(2, 2)
        val history = EditorHistory()
        val delta = CellDelta(intArrayOf(0), intArrayOf(EMPTY_CELL), intArrayOf(2), "画笔")
        delta.applyTo(grid)
        history.record(delta)

        assertTrue(history.canUndo)
        history.undo(grid)
        assertEquals(EMPTY_CELL, grid.cells[0])
        history.redo(grid)
        assertEquals(2, grid.cells[0])
    }
}
