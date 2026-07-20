package com.qiao.dougrid.export

import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.data.BeadProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternExporterPdfTest {
    private val palette = palette(1)

    @Test
    fun defaultPlanUsesProjectBoardSizeAndKeepsExistingAnnotations() {
        val project = project(width = 33, height = 17, boardSize = 16)

        val plan = PatternExporter.pdfExportPlan(project, palette)

        assertEquals(16, plan.boardSize)
        assertEquals(3, plan.boardColumns)
        assertEquals(2, plan.boardRows)
        assertEquals(6, plan.boardPageCount)
        assertEquals(1, plan.materialPageCount)
        assertEquals(7, plan.pageCount)
        assertTrue(plan.showSymbols)
        assertTrue(plan.showColorCodes)
        assertFalse(plan.showCalibrationMark)
        assertEquals(PdfPageOrientation.PORTRAIT, plan.orientation)
        assertEquals(null, plan.physicalCellSizeMm)
        assertEquals(595, plan.boardPageWidthPoints)
        assertEquals(842, plan.boardPageHeightPoints)
        assertEquals(471.25f, plan.boardGridSizePoints, 0.001f)
    }

    @Test
    fun optionsOverrideBoardSplittingAndAnnotationChoices() {
        val project = project(width = 64, height = 64, boardSize = 29)

        val plan = PatternExporter.pdfExportPlan(
            project = project,
            palette = palette,
            options = PdfExportOptions(
                boardSize = 8,
                showSymbols = false,
                showColorCodes = false,
                showCalibrationMark = true,
            ),
        )

        assertEquals(8, plan.boardSize)
        assertEquals(8, plan.boardColumns)
        assertEquals(8, plan.boardRows)
        assertEquals(64, plan.boardPageCount)
        assertEquals(65, plan.pageCount)
        assertFalse(plan.showSymbols)
        assertFalse(plan.showColorCodes)
        assertTrue(plan.showCalibrationMark)
    }

    @Test
    fun pageCountIncludesAllMaterialContinuationPages() {
        val manyColors = palette(61)
        val project = BeadProject(
            title = "many materials",
            paletteId = manyColors.id,
            grid = PatternGrid(61, 1, IntArray(61) { it }),
            boardSize = 8,
        )

        val plan = PatternExporter.pdfExportPlan(project, manyColors)

        assertEquals(8, plan.boardPageCount)
        assertEquals(3, plan.materialPageCount)
        assertEquals(11, plan.pageCount)
    }

    @Test
    fun everySupportedBoardSizeFitsEachOrientationPrintableGridBounds() {
        val project = project(width = 1, height = 1, boardSize = 29)

        for (orientation in PdfPageOrientation.entries) {
            val expectedGridSize = when (orientation) {
                PdfPageOrientation.PORTRAIT -> 471.25f
                PdfPageOrientation.LANDSCAPE -> 430f
            }
            for (boardSize in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE) {
                val plan = PatternExporter.pdfExportPlan(
                    project,
                    palette,
                    PdfExportOptions(boardSize = boardSize, orientation = orientation),
                )

                assertTrue(plan.cellSizePoints > 0f)
                assertEquals(expectedGridSize, plan.boardGridSizePoints, 0.001f)
            }
        }
    }

    @Test
    fun landscapeUsesTrueA4DimensionsAndSideLegendGridBounds() {
        val plan = PatternExporter.pdfExportPlan(
            project = project(width = 29, height = 29, boardSize = 29),
            palette = palette,
            options = PdfExportOptions(orientation = PdfPageOrientation.LANDSCAPE),
        )

        assertEquals(PdfPageOrientation.LANDSCAPE, plan.orientation)
        assertEquals(842, plan.boardPageWidthPoints)
        assertEquals(595, plan.boardPageHeightPoints)
        assertEquals(430f, plan.boardGridSizePoints, 0.001f)
        assertEquals(42f, plan.gridLeftPoints, 0.001f)
        assertEquals(88f, plan.gridTopPoints, 0.001f)
    }

    @Test
    fun fiveMillimeterCellsUsePhysicalPointScaleInBothOrientations() {
        val project = project(width = 29, height = 29, boardSize = 29)
        val expectedCellPoints = 5f * 72f / 25.4f

        for (orientation in PdfPageOrientation.entries) {
            val plan = PatternExporter.pdfExportPlan(
                project = project,
                palette = palette,
                options = PdfExportOptions(
                    orientation = orientation,
                    physicalCellSizeMm = 5f,
                ),
            )

            assertEquals(5f, plan.physicalCellSizeMm ?: 0f, 0f)
            assertEquals(expectedCellPoints, plan.cellSizePoints, 0.0001f)
            assertEquals(expectedCellPoints * 29, plan.boardGridSizePoints, 0.001f)
        }
    }

    @Test
    fun physicalScaleThatCannotFitSelectedOrientationIsRejected() {
        val project = project(width = 29, height = 29, boardSize = 29)

        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(
                project,
                palette,
                PdfExportOptions(
                    orientation = PdfPageOrientation.PORTRAIT,
                    physicalCellSizeMm = 6f,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(
                project,
                palette,
                PdfExportOptions(
                    orientation = PdfPageOrientation.LANDSCAPE,
                    physicalCellSizeMm = 5.5f,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(
                project,
                palette,
                PdfExportOptions(physicalCellSizeMm = 0f),
            )
        }
    }

    @Test
    fun boardSizeOutsideSupportedRangeIsRejected() {
        val project = project(width = 29, height = 29, boardSize = 29)

        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(
                project,
                palette,
                PdfExportOptions(boardSize = BeadProject.MIN_BOARD_SIZE - 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(
                project,
                palette,
                PdfExportOptions(boardSize = BeadProject.MAX_BOARD_SIZE + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatternExporter.pdfExportPlan(project.copy(boardSize = 7), palette)
        }
    }

    private fun project(width: Int, height: Int, boardSize: Int): BeadProject = BeadProject(
        title = "test",
        paletteId = palette.id,
        grid = PatternGrid(width, height, IntArray(width * height) { EMPTY_CELL }),
        boardSize = boardSize,
    )

    private fun palette(size: Int): BeadPalette = BeadPalette(
        id = "test-$size",
        title = "test",
        version = "1",
        source = "test",
        colors = List(size) { index ->
            PaletteColor(
                code = "C${index.toString().padStart(3, '0')}",
                argb = index,
            )
        },
    )
}
