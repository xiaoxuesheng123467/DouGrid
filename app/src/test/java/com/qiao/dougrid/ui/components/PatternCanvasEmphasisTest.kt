package com.qiao.dougrid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternCanvasEmphasisTest {
    @Test
    fun selectedColorKeepsFullEmphasis() {
        assertEquals(1f, patternCellAlpha(completed = false, highlighted = true))
    }

    @Test
    fun otherColorsFadeBehindTheSelectedColor() {
        assertEquals(0.06f, patternCellAlpha(completed = false, highlighted = false))
    }

    @Test
    fun completedCellsRemainVisibleButSubdued() {
        assertEquals(0.18f, patternCellAlpha(completed = true, highlighted = true))
    }

    @Test
    fun completedOtherColorsDoNotCompeteWithTheSelection() {
        assertEquals(0.04f, patternCellAlpha(completed = true, highlighted = false))
    }
}
