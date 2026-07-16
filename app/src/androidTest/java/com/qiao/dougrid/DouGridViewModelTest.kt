package com.qiao.dougrid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.data.EditorTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DouGridViewModelTest {
    @Test
    fun editorStrokeUpdatesImmediatelyAndUndoRestoresTheWholeStroke() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = withContext(Dispatchers.Main) { DouGridViewModel(application) }
        val loaded = withTimeout(10_000) { viewModel.uiState.filter { !it.isLoading }.first() }
        val testTitle = "编辑笔画测试-${System.nanoTime()}"

        withContext(Dispatchers.Main) {
            viewModel.createBlank(testTitle, width = 8, height = 8, paletteId = loaded.settings.defaultPaletteId)
        }
        val project = withTimeout(5_000) {
            viewModel.uiState.filter { state -> state.projects.firstOrNull()?.title == testTitle }
                .first()
                .projects
                .first()
        }
        val indices = listOf(0, 1)
        assertTrue(project.grid.cells.all { it == EMPTY_CELL })

        withContext(Dispatchers.Main) {
            viewModel.setEditorTool(EditorTool.PENCIL)
            val initialRevision = viewModel.uiState.value.editorRevision
            val initialStatsRevision = viewModel.uiState.value.editorStatsRevision
            viewModel.beginEditorStroke(project.id)
            viewModel.extendEditorStroke(project.id, listOf(indices[0]))

            assertEquals(0, project.grid.cells[indices[0]])
            assertTrue(viewModel.uiState.value.editorRevision > initialRevision)
            assertEquals(initialStatsRevision, viewModel.uiState.value.editorStatsRevision)

            viewModel.extendEditorStroke(project.id, listOf(indices[1]))
            viewModel.endEditorStroke(project.id)
            assertTrue(viewModel.uiState.value.editorStatsRevision > initialStatsRevision)
            viewModel.undo(project.id)
        }

        assertEquals(EMPTY_CELL, project.grid.cells[indices[0]])
        assertEquals(EMPTY_CELL, project.grid.cells[indices[1]])
    }
}
