package com.qiao.dougrid.ui.screens

import com.qiao.dougrid.data.EditorTool
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorToolGroupingTest {
    @Test
    fun commonToolsStayInThePrimaryToolbar() {
        assertEquals(
            listOf(EditorTool.PENCIL, EditorTool.ERASER, EditorTool.FILL, EditorTool.PAN),
            primaryEditorTools,
        )
    }

    @Test
    fun specialistToolsStayBehindMoreTools() {
        assertEquals(
            listOf(EditorTool.PICKER, EditorTool.REPLACE, EditorTool.SELECT),
            advancedEditorTools,
        )
    }
}
