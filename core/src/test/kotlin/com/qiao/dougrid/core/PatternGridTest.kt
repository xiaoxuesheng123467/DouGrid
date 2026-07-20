package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatternGridTest {
    @Test
    fun constructorNormalizesLegacyCompletionMarksOnEmptyCells() {
        val grid = PatternGrid(
            width = 3,
            height = 1,
            cells = intArrayOf(0, EMPTY_CELL, 1),
            completed = byteArrayOf(2, 1, 0),
        )

        assertContentEquals(byteArrayOf(1, 0, 0), grid.completed)
        assertEquals(1, grid.completedCount())
    }

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

    @Test
    fun deltaUndoAndRedoRestoreCompletionState() {
        val grid = PatternGrid(1, 1, intArrayOf(1), byteArrayOf(1))
        val history = EditorHistory()
        val delta = CellDelta(
            indices = intArrayOf(0),
            before = intArrayOf(1),
            after = intArrayOf(2),
            label = "替换",
            completedBefore = byteArrayOf(1),
            completedAfter = byteArrayOf(0),
        )

        delta.applyTo(grid)
        history.record(delta)
        assertContentEquals(byteArrayOf(0), grid.completed)
        history.undo(grid)
        assertContentEquals(intArrayOf(1), grid.cells)
        assertContentEquals(byteArrayOf(1), grid.completed)
        history.redo(grid)
        assertContentEquals(intArrayOf(2), grid.cells)
        assertContentEquals(byteArrayOf(0), grid.completed)
    }
}
