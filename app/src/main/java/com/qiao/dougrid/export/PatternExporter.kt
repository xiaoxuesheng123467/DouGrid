package com.qiao.dougrid.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.data.BeadProject
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_PNG_MAX_DIMENSION_PX = 8_192
const val DEFAULT_PNG_MAX_PIXEL_COUNT = 8_000_000L

enum class PngMode {
    PIXEL_ART,
    GRID_SHEET,
}

enum class PdfPageOrientation {
    PORTRAIT,
    LANDSCAPE,
}

data class PngExportOptions(
    val mode: PngMode = PngMode.PIXEL_ART,
    val requestedCellSizePx: Int = 24,
    val transparentEmptyCells: Boolean = true,
    val maxDimensionPx: Int = DEFAULT_PNG_MAX_DIMENSION_PX,
    val maxPixelCount: Long = DEFAULT_PNG_MAX_PIXEL_COUNT,
)

data class PdfExportOptions(
    val bagSize: Int = 1_000,
    val boardSize: Int? = null,
    val showSymbols: Boolean = true,
    val showColorCodes: Boolean = true,
    val showCalibrationMark: Boolean = false,
    val orientation: PdfPageOrientation = PdfPageOrientation.PORTRAIT,
    val physicalCellSizeMm: Float? = null,
)

data class PdfExportResult(
    val pageCount: Int,
    val materialPageCount: Int,
    val boardPageCount: Int,
)

internal data class PdfExportPlan(
    val boardSize: Int,
    val boardColumns: Int,
    val boardRows: Int,
    val boardPageCount: Int,
    val materialPageCount: Int,
    val pageCount: Int,
    val cellSizePoints: Float,
    val orientation: PdfPageOrientation,
    val physicalCellSizeMm: Float?,
    val boardPageWidthPoints: Int,
    val boardPageHeightPoints: Int,
    val gridLeftPoints: Float,
    val gridTopPoints: Float,
    val showSymbols: Boolean,
    val showColorCodes: Boolean,
    val showCalibrationMark: Boolean,
) {
    val boardGridSizePoints: Float get() = cellSizePoints * boardSize
}

data class PngExportResult(
    val widthPx: Int,
    val heightPx: Int,
    val cellSizePx: Int,
    val wasDownscaled: Boolean,
    val colorCodesRendered: Boolean,
    val colorSymbolsRendered: Boolean,
)

data class MaterialRequirement(
    val colorIndex: Int,
    val color: PaletteColor,
    val symbol: String,
    val beadCount: Int,
    val bagsNeeded: Int,
)

/**
 * Stateless export utilities. Output streams are flushed but remain owned by the caller.
 */
object PatternExporter {
    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842
    private const val DEFAULT_PDF_CELL_SIZE = 16.25f
    private const val DEFAULT_PDF_GRID_SIZE = DEFAULT_PDF_CELL_SIZE * BeadProject.DEFAULT_BOARD_SIZE
    private const val LANDSCAPE_PDF_GRID_SIZE = 430f
    private const val LANDSCAPE_GRID_AREA_LEFT = 42f
    private const val LANDSCAPE_GRID_AREA_TOP = 88f
    private const val CALIBRATION_MARK_MILLIMETERS = 25
    private const val POINTS_PER_MILLIMETER = 72f / 25.4f
    private const val COVER_MATERIAL_CAPACITY = 25
    private const val MATERIAL_PAGE_CAPACITY = 35
    private const val HARD_MAX_PNG_DIMENSION = DEFAULT_PNG_MAX_DIMENSION_PX
    private const val HARD_MAX_PNG_PIXELS = DEFAULT_PNG_MAX_PIXEL_COUNT

    private val symbolAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val regularTypeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val mediumTypeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

    /**
     * Exports an A4 portrait PDF. Page one contains project details and the first part of
     * the material legend; remaining material pages precede one page per configured board.
     */
    @Throws(IOException::class)
    fun exportPdf(
        project: BeadProject,
        palette: BeadPalette,
        output: OutputStream,
        options: PdfExportOptions = PdfExportOptions(),
    ): PdfExportResult {
        val materials = materials(project, palette, options.bagSize)
        val plan = createPdfExportPlan(project, options, materials.size)
        val document = PdfDocument()

        try {
            addCoverPage(
                document = document,
                project = project,
                palette = palette,
                materials = materials.take(COVER_MATERIAL_CAPACITY),
                bagSize = options.bagSize,
                boardColumns = plan.boardColumns,
                boardRows = plan.boardRows,
                boardPageCount = plan.boardPageCount,
                totalPages = plan.pageCount,
            )

            materials.drop(COVER_MATERIAL_CAPACITY)
                .chunked(MATERIAL_PAGE_CAPACITY)
                .forEachIndexed { index, pageMaterials ->
                    val pageNumber = index + 2
                    addMaterialPage(
                        document = document,
                        project = project,
                        palette = palette,
                        materials = pageMaterials,
                        pageNumber = pageNumber,
                        totalPages = plan.pageCount,
                    )
                }

            var boardOrdinal = 0
            for (boardRow in 0 until plan.boardRows) {
                for (boardColumn in 0 until plan.boardColumns) {
                    val pageNumber = plan.materialPageCount + boardOrdinal + 1
                    addBoardPage(
                        document = document,
                        project = project,
                        palette = palette,
                        plan = plan,
                        boardColumn = boardColumn,
                        boardRow = boardRow,
                        boardOrdinal = boardOrdinal,
                        pageNumber = pageNumber,
                        totalPages = plan.pageCount,
                    )
                    boardOrdinal++
                }
            }

            document.writeTo(output)
            output.flush()
        } finally {
            document.close()
        }

        return PdfExportResult(
            pageCount = plan.pageCount,
            materialPageCount = plan.materialPageCount,
            boardPageCount = plan.boardPageCount,
        )
    }

    internal fun pdfExportPlan(
        project: BeadProject,
        palette: BeadPalette,
        options: PdfExportOptions = PdfExportOptions(),
    ): PdfExportPlan {
        require(options.bagSize > 0) { "Bag size must be positive" }
        validate(project, palette)
        return createPdfExportPlan(project, options, project.grid.colorCounts().size)
    }

    /**
     * Exports either clean pixel art or a printable full-pattern grid sheet as PNG.
     * The requested cell size is reduced before allocation when it would exceed the
     * fixed dimension or pixel budget.
     */
    @Throws(IOException::class)
    fun exportPng(
        project: BeadProject,
        palette: BeadPalette,
        output: OutputStream,
        options: PngExportOptions = PngExportOptions(),
    ): PngExportResult {
        validate(project, palette)
        val layout = choosePngLayout(project, options)
        val bitmap = try {
            Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            throw IOException(
                "Unable to allocate ${layout.width} x ${layout.height} PNG bitmap",
                error,
            )
        }

        val symbols = colorSymbols(palette)
        try {
            val canvas = Canvas(bitmap)
            when (options.mode) {
                PngMode.PIXEL_ART -> drawPixelArtPng(
                    canvas = canvas,
                    project = project,
                    palette = palette,
                    cellSize = layout.cellSize,
                    transparentEmptyCells = options.transparentEmptyCells,
                )

                PngMode.GRID_SHEET -> drawGridSheetPng(
                    canvas = canvas,
                    project = project,
                    palette = palette,
                    symbols = symbols,
                    layout = layout,
                )
            }

            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Android PNG encoder returned false")
            }
            output.flush()
        } finally {
            bitmap.recycle()
        }

        val isGridSheet = options.mode == PngMode.GRID_SHEET
        return PngExportResult(
            widthPx = layout.width,
            heightPx = layout.height,
            cellSizePx = layout.cellSize,
            wasDownscaled = layout.cellSize < options.requestedCellSizePx,
            colorCodesRendered = isGridSheet && layout.cellSize >= 7,
            colorSymbolsRendered = isGridSheet && layout.cellSize >= 12,
        )
    }

    fun materials(
        project: BeadProject,
        palette: BeadPalette,
        bagSize: Int = 1_000,
    ): List<MaterialRequirement> {
        require(bagSize > 0) { "Bag size must be positive" }
        validate(project, palette)
        val symbols = colorSymbols(palette)
        return project.grid.colorCounts()
            .toList()
            .sortedWith(compareBy({ palette.colors[it.first].code }, { it.first }))
            .map { (colorIndex, count) ->
                val color = palette.colors[colorIndex]
                MaterialRequirement(
                    colorIndex = colorIndex,
                    color = color,
                    symbol = symbols.getValue(color.code),
                    beadCount = count,
                    bagsNeeded = ceil(count.toDouble() / bagSize).toInt(),
                )
            }
    }

    /** Returns a unique, stable symbol for every palette code, independent of palette ordering. */
    fun colorSymbols(palette: BeadPalette): Map<String, String> = buildMap {
        palette.colors.map { it.code }.sorted().forEachIndexed { index, code ->
            put(code, encodeSymbol(index))
        }
    }

    fun shoppingListText(
        project: BeadProject,
        palette: BeadPalette,
        bagSize: Int = 1_000,
    ): String {
        val materials = materials(project, palette, bagSize)
        return buildString {
            appendLine("采购单：${singleLine(project.title)}")
            appendLine("色卡：${singleLine(palette.title)} (${singleLine(palette.version)})")
            appendLine("网格：${project.grid.width} x ${project.grid.height}")
            appendLine("拼板：${project.boardColumns} x ${project.boardRows} = ${project.boardCount}")
            appendLine("总豆数：${project.grid.beadCount()}")
            appendLine("每袋：$bagSize 颗")
            appendLine()
            appendLine("符号\t色号\t名称\t色系\t豆数\t袋数")
            materials.forEach { item ->
                append(item.symbol)
                append('\t')
                append(singleLine(item.color.code))
                append('\t')
                append(singleLine(item.color.name).takeUnless { it.equals(item.color.code, ignoreCase = true) } ?: "-")
                append('\t')
                append(singleLine(item.color.group))
                append('\t')
                append(item.beadCount)
                append('\t')
                appendLine(item.bagsNeeded)
            }
        }
    }

    @Throws(IOException::class)
    fun writeShoppingList(
        project: BeadProject,
        palette: BeadPalette,
        output: OutputStream,
        bagSize: Int = 1_000,
    ) {
        output.write(shoppingListText(project, palette, bagSize).toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun addCoverPage(
        document: PdfDocument,
        project: BeadProject,
        palette: BeadPalette,
        materials: List<MaterialRequirement>,
        bagSize: Int,
        boardColumns: Int,
        boardRows: Int,
        boardPageCount: Int,
        totalPages: Int,
    ) {
        val page = document.startPage(pageInfo(1))
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            drawText(
                canvas = canvas,
                text = project.title,
                x = 42f,
                baseline = 66f,
                size = 28f,
                color = Color.rgb(24, 28, 32),
                paint = paint,
                typeface = mediumTypeface,
                maxWidth = 511f,
            )
            drawText(
                canvas = canvas,
                text = "拼豆图纸手册",
                x = 42f,
                baseline = 91f,
                size = 12f,
                color = Color.rgb(88, 96, 104),
                paint = paint,
            )

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(245, 247, 248)
            canvas.drawRoundRect(RectF(42f, 113f, 553f, 210f), 6f, 6f, paint)
            drawSummaryValue(canvas, "色卡", palette.title, 58f, 139f, 235f, paint)
            drawSummaryValue(
                canvas,
                "网格",
                "${project.grid.width} x ${project.grid.height}",
                316f,
                139f,
                220f,
                paint,
            )
            drawSummaryValue(
                canvas,
                "拼板",
                "$boardColumns x $boardRows ($boardPageCount)",
                58f,
                183f,
                235f,
                paint,
            )
            drawSummaryValue(
                canvas,
                "豆数",
                project.grid.beadCount().toString(),
                316f,
                183f,
                220f,
                paint,
            )

            drawText(
                canvas,
                "材料用量",
                42f,
                250f,
                18f,
                Color.rgb(24, 28, 32),
                paint,
                mediumTypeface,
            )
            drawText(
                canvas,
                "每袋按 $bagSize 颗估算",
                553f,
                250f,
                9f,
                Color.rgb(100, 106, 112),
                paint,
                textAlign = Paint.Align.RIGHT,
            )
            drawMaterialTable(
                canvas = canvas,
                materials = materials,
                headerBaseline = 278f,
                firstRowTop = 287f,
                rowHeight = 19f,
                paint = paint,
            )
            if (materials.isEmpty()) {
                drawText(
                    canvas,
                    "图纸中没有拼豆。",
                    42f,
                    320f,
                    11f,
                    Color.DKGRAY,
                    paint,
                )
            }
            drawPageFooter(canvas, 1, totalPages, paint)
        } finally {
            document.finishPage(page)
        }
    }

    private fun addMaterialPage(
        document: PdfDocument,
        project: BeadProject,
        palette: BeadPalette,
        materials: List<MaterialRequirement>,
        pageNumber: Int,
        totalPages: Int,
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            drawText(
                canvas,
                "材料用量（续）",
                42f,
                61f,
                24f,
                Color.rgb(24, 28, 32),
                paint,
                mediumTypeface,
            )
            drawText(
                canvas,
                "${project.title} - ${palette.title}",
                42f,
                84f,
                10f,
                Color.rgb(90, 96, 102),
                paint,
                maxWidth = 511f,
            )
            drawMaterialTable(
                canvas = canvas,
                materials = materials,
                headerBaseline = 116f,
                firstRowTop = 126f,
                rowHeight = 19f,
                paint = paint,
            )
            drawPageFooter(canvas, pageNumber, totalPages, paint)
        } finally {
            document.finishPage(page)
        }
    }

    private fun addBoardPage(
        document: PdfDocument,
        project: BeadProject,
        palette: BeadPalette,
        plan: PdfExportPlan,
        boardColumn: Int,
        boardRow: Int,
        boardOrdinal: Int,
        pageNumber: Int,
        totalPages: Int,
    ) {
        val page = document.startPage(
            pageInfo(
                pageNumber = pageNumber,
                width = plan.boardPageWidthPoints,
                height = plan.boardPageHeightPoints,
            ),
        )
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val symbols = colorSymbols(palette)
            val pageRight = plan.boardPageWidthPoints - 42f
            val startColumn = boardColumn * plan.boardSize
            val startRow = boardRow * plan.boardSize
            val endColumn = min(startColumn + plan.boardSize, project.grid.width)
            val endRow = min(startRow + plan.boardSize, project.grid.height)

            drawText(
                canvas,
                "Board ${boardOrdinal + 1}/${plan.boardPageCount}",
                42f,
                47f,
                21f,
                Color.rgb(24, 28, 32),
                paint,
                mediumTypeface,
            )
            drawText(
                canvas,
                "Row ${boardRow + 1}, column ${boardColumn + 1}",
                pageRight,
                47f,
                11f,
                Color.rgb(75, 82, 88),
                paint,
                textAlign = Paint.Align.RIGHT,
            )
            drawText(
                canvas,
                "Absolute columns ${startColumn + 1}-$endColumn  |  rows ${startRow + 1}-$endRow",
                42f,
                71f,
                10f,
                Color.rgb(88, 94, 100),
                paint,
            )
            drawText(
                canvas,
                project.title,
                pageRight,
                71f,
                10f,
                Color.rgb(88, 94, 100),
                paint,
                maxWidth = 220f,
                textAlign = Paint.Align.RIGHT,
            )

            val cellSize = plan.cellSizePoints
            val gridSize = plan.boardGridSizePoints
            val gridLeft = plan.gridLeftPoints
            val gridTop = plan.gridTopPoints
            val gridRight = gridLeft + gridSize
            val gridBottom = gridTop + gridSize

            drawPdfBoardCells(
                canvas = canvas,
                project = project,
                palette = palette,
                symbols = symbols,
                boardSize = plan.boardSize,
                showSymbols = plan.showSymbols,
                showColorCodes = plan.showColorCodes,
                startColumn = startColumn,
                startRow = startRow,
                gridLeft = gridLeft,
                gridTop = gridTop,
                cellSize = cellSize,
                paint = paint,
            )
            drawPdfCoordinates(
                canvas = canvas,
                project = project,
                boardSize = plan.boardSize,
                startColumn = startColumn,
                startRow = startRow,
                gridLeft = gridLeft,
                gridTop = gridTop,
                gridRight = gridRight,
                gridBottom = gridBottom,
                cellSize = cellSize,
                paint = paint,
            )
            drawBoardGridLines(
                canvas = canvas,
                boardSize = plan.boardSize,
                startColumn = startColumn,
                startRow = startRow,
                gridLeft = gridLeft,
                gridTop = gridTop,
                cellSize = cellSize,
                paint = paint,
            )

            if (plan.showCalibrationMark) {
                val calibrationRight = plan.boardPageWidthPoints - 42f
                val calibrationY = if (plan.orientation == PdfPageOrientation.PORTRAIT) 609f else 500f
                drawCalibrationMark(canvas, paint, calibrationRight, calibrationY)
            }
            val annotationBaseline =
                if (plan.orientation == PdfPageOrientation.PORTRAIT) 632f else 548f
            drawText(
                canvas,
                boardAnnotationDescription(plan.showSymbols, plan.showColorCodes),
                42f,
                annotationBaseline,
                9f,
                Color.rgb(88, 94, 100),
                paint,
            )
            drawBoardMaterialStrip(
                canvas = canvas,
                project = project,
                palette = palette,
                symbols = symbols,
                boardSize = plan.boardSize,
                orientation = plan.orientation,
                startColumn = startColumn,
                startRow = startRow,
                paint = paint,
            )
            drawPageFooter(
                canvas = canvas,
                pageNumber = pageNumber,
                totalPages = totalPages,
                paint = paint,
                pageWidth = plan.boardPageWidthPoints,
                pageHeight = plan.boardPageHeightPoints,
            )
        } finally {
            document.finishPage(page)
        }
    }

    private fun drawPdfBoardCells(
        canvas: Canvas,
        project: BeadProject,
        palette: BeadPalette,
        symbols: Map<String, String>,
        boardSize: Int,
        showSymbols: Boolean,
        showColorCodes: Boolean,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        for (localRow in 0 until boardSize) {
            val row = startRow + localRow
            for (localColumn in 0 until boardSize) {
                val column = startColumn + localColumn
                val left = gridLeft + localColumn * cellSize
                val top = gridTop + localRow * cellSize
                val rect = RectF(left, top, left + cellSize, top + cellSize)
                if (!project.grid.isInside(column, row)) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(238, 240, 242)
                    canvas.drawRect(rect, paint)
                    continue
                }

                val colorIndex = project.grid[column, row]
                if (colorIndex == EMPTY_CELL) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    canvas.drawRect(rect, paint)
                    continue
                }

                val color = palette.colors[colorIndex]
                paint.style = Paint.Style.FILL
                paint.color = color.opaqueArgb
                canvas.drawRect(rect, paint)

                val textColor = contrastingTextColor(color.opaqueArgb)
                val scale = min(1f, cellSize / DEFAULT_PDF_CELL_SIZE)
                when {
                    showSymbols && showColorCodes -> {
                        drawCenteredText(
                            canvas = canvas,
                            text = symbols.getValue(color.code),
                            centerX = rect.centerX(),
                            centerY = rect.centerY() - 3.2f * scale,
                            desiredSize = 6.3f * scale,
                            minSize = 4f * scale,
                            maxWidth = cellSize - 1.5f * scale,
                            color = textColor,
                            paint = paint,
                            typeface = mediumTypeface,
                        )
                        drawCenteredText(
                            canvas = canvas,
                            text = color.code,
                            centerX = rect.centerX(),
                            centerY = rect.centerY() + 4f * scale,
                            desiredSize = 5.1f * scale,
                            minSize = 3.2f * scale,
                            maxWidth = cellSize - 1.2f * scale,
                            color = textColor,
                            paint = paint,
                        )
                    }

                    showSymbols -> drawCenteredText(
                        canvas = canvas,
                        text = symbols.getValue(color.code),
                        centerX = rect.centerX(),
                        centerY = rect.centerY(),
                        desiredSize = min(8f, cellSize * 0.48f),
                        minSize = min(4f, cellSize * 0.28f),
                        maxWidth = cellSize - 1.2f * scale,
                        color = textColor,
                        paint = paint,
                        typeface = mediumTypeface,
                    )

                    showColorCodes -> drawCenteredText(
                        canvas = canvas,
                        text = color.code,
                        centerX = rect.centerX(),
                        centerY = rect.centerY(),
                        desiredSize = min(6.5f, cellSize * 0.4f),
                        minSize = min(3.2f, cellSize * 0.22f),
                        maxWidth = cellSize - 1.2f * scale,
                        color = textColor,
                        paint = paint,
                    )
                }
            }
        }
    }

    private fun drawPdfCoordinates(
        canvas: Canvas,
        project: BeadProject,
        boardSize: Int,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        gridRight: Float,
        gridBottom: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        val scale = min(1f, cellSize / DEFAULT_PDF_CELL_SIZE)
        val labelSize = 6.5f * scale
        val minimumLabelSize = 4.5f * scale
        for (localColumn in 0 until boardSize) {
            val column = startColumn + localColumn
            if (column >= project.grid.width) continue
            val centerX = gridLeft + (localColumn + 0.5f) * cellSize
            val label = (column + 1).toString()
            drawCenteredText(
                canvas,
                label,
                centerX,
                gridTop - 9f,
                labelSize,
                minimumLabelSize,
                cellSize - 1f,
                Color.rgb(60, 66, 72),
                paint,
            )
            drawCenteredText(
                canvas,
                label,
                centerX,
                gridBottom + 10f,
                labelSize,
                minimumLabelSize,
                cellSize - 1f,
                Color.rgb(60, 66, 72),
                paint,
            )
        }
        for (localRow in 0 until boardSize) {
            val row = startRow + localRow
            if (row >= project.grid.height) continue
            val centerY = gridTop + (localRow + 0.5f) * cellSize
            val label = (row + 1).toString()
            drawCenteredText(
                canvas,
                label,
                gridLeft - 13f,
                centerY,
                labelSize,
                minimumLabelSize,
                22f,
                Color.rgb(60, 66, 72),
                paint,
            )
            drawCenteredText(
                canvas,
                label,
                gridRight + 13f,
                centerY,
                labelSize,
                minimumLabelSize,
                22f,
                Color.rgb(60, 66, 72),
                paint,
            )
        }
    }

    private fun drawBoardGridLines(
        canvas: Canvas,
        boardSize: Int,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        val gridSize = cellSize * boardSize
        paint.style = Paint.Style.STROKE
        for (line in 0..boardSize) {
            val x = gridLeft + line * cellSize
            val absoluteBoundary = startColumn + line
            paint.color = if (line == 0 || line == boardSize) {
                Color.rgb(30, 34, 38)
            } else {
                Color.rgb(72, 78, 84)
            }
            paint.strokeWidth = when {
                line == 0 || line == boardSize -> 1.45f
                absoluteBoundary % 5 == 0 -> 1.15f
                else -> 0.32f
            }
            canvas.drawLine(x, gridTop, x, gridTop + gridSize, paint)
        }
        for (line in 0..boardSize) {
            val y = gridTop + line * cellSize
            val absoluteBoundary = startRow + line
            paint.color = if (line == 0 || line == boardSize) {
                Color.rgb(30, 34, 38)
            } else {
                Color.rgb(72, 78, 84)
            }
            paint.strokeWidth = when {
                line == 0 || line == boardSize -> 1.45f
                absoluteBoundary % 5 == 0 -> 1.15f
                else -> 0.32f
            }
            canvas.drawLine(gridLeft, y, gridLeft + gridSize, y, paint)
        }
    }

    private fun drawBoardMaterialStrip(
        canvas: Canvas,
        project: BeadProject,
        palette: BeadPalette,
        symbols: Map<String, String>,
        boardSize: Int,
        orientation: PdfPageOrientation,
        startColumn: Int,
        startRow: Int,
        paint: Paint,
    ) {
        val counts = linkedMapOf<Int, Int>()
        val endColumn = min(startColumn + boardSize, project.grid.width)
        val endRow = min(startRow + boardSize, project.grid.height)
        for (row in startRow until endRow) {
            for (column in startColumn until endColumn) {
                val colorIndex = project.grid[column, row]
                if (colorIndex != EMPTY_CELL) {
                    counts[colorIndex] = (counts[colorIndex] ?: 0) + 1
                }
            }
        }
        val items = counts.toList().sortedBy { palette.colors[it.first].code }
        val isLandscape = orientation == PdfPageOrientation.LANDSCAPE
        val legendLeft = if (isLandscape) 514f else 42f
        val titleBaseline = if (isLandscape) 105f else 663f
        val firstRowTop = if (isLandscape) 116f else 677f
        val columnCount = if (isLandscape) 2 else 4
        val columnWidth = if (isLandscape) 143f else 128f
        val rowHeight = if (isLandscape) 18f else 19f
        val maximumRows = if (isLandscape) 12 else 6
        val maxItems = columnCount * maximumRows
        drawText(
            canvas,
            "Board materials (${items.sumOf { it.second }} beads)",
            legendLeft,
            titleBaseline,
            11f,
            Color.rgb(35, 40, 45),
            paint,
            mediumTypeface,
        )
        if (items.isEmpty()) {
            drawText(
                canvas,
                "No filled cells on this board.",
                legendLeft,
                titleBaseline + 23f,
                9f,
                Color.GRAY,
                paint,
            )
            return
        }

        items.take(maxItems).forEachIndexed { index, (colorIndex, count) ->
            val column = index % columnCount
            val row = index / columnCount
            val x = legendLeft + column * columnWidth
            val top = firstRowTop + row * rowHeight
            val color = palette.colors[colorIndex]
            paint.style = Paint.Style.FILL
            paint.color = color.opaqueArgb
            canvas.drawRect(RectF(x, top, x + 13f, top + 13f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = Color.rgb(95, 100, 105)
            canvas.drawRect(RectF(x, top, x + 13f, top + 13f), paint)
            drawText(
                canvas,
                "${symbols.getValue(color.code)} ${color.code}  x$count",
                x + 18f,
                top + 10.5f,
                7.5f,
                Color.rgb(48, 53, 58),
                paint,
                maxWidth = columnWidth - 22f,
            )
        }
        if (items.size > maxItems) {
            drawText(
                canvas,
                "+${items.size - maxItems} more colors; see the material legend.",
                legendLeft,
                firstRowTop + maximumRows * rowHeight + 7f,
                8f,
                Color.GRAY,
                paint,
            )
        }
    }

    private fun drawPixelArtPng(
        canvas: Canvas,
        project: BeadProject,
        palette: BeadPalette,
        cellSize: Int,
        transparentEmptyCells: Boolean,
    ) {
        canvas.drawColor(if (transparentEmptyCells) Color.TRANSPARENT else Color.WHITE)
        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        for (row in 0 until project.grid.height) {
            for (column in 0 until project.grid.width) {
                val colorIndex = project.grid[column, row]
                if (colorIndex == EMPTY_CELL) continue
                paint.color = palette.colors[colorIndex].opaqueArgb
                val left = column * cellSize
                val top = row * cellSize
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + cellSize).toFloat(),
                    (top + cellSize).toFloat(),
                    paint,
                )
            }
        }
    }

    private fun drawGridSheetPng(
        canvas: Canvas,
        project: BeadProject,
        palette: BeadPalette,
        symbols: Map<String, String>,
        layout: PngLayout,
    ) {
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gridLeft = layout.leftMargin.toFloat()
        val gridTop = layout.topMargin.toFloat()
        val cellSize = layout.cellSize.toFloat()
        val gridRight = gridLeft + project.grid.width * cellSize
        val gridBottom = gridTop + project.grid.height * cellSize

        drawText(
            canvas,
            project.title,
            gridLeft,
            max(20f, layout.topMargin * 0.38f),
            min(22f, max(12f, layout.cellSize * 1.25f)),
            Color.rgb(25, 29, 33),
            paint,
            mediumTypeface,
            maxWidth = layout.width - layout.leftMargin - layout.rightMargin.toFloat(),
        )
        drawText(
            canvas,
            "${project.grid.width} x ${project.grid.height}  |  ${project.grid.beadCount()} beads  |  ${palette.title}",
            gridLeft,
            max(38f, layout.topMargin * 0.68f),
            min(13f, max(8f, layout.cellSize * 0.7f)),
            Color.rgb(88, 94, 100),
            paint,
            maxWidth = layout.width - layout.leftMargin - layout.rightMargin.toFloat(),
        )

        paint.style = Paint.Style.FILL
        for (row in 0 until project.grid.height) {
            for (column in 0 until project.grid.width) {
                val colorIndex = project.grid[column, row]
                val left = gridLeft + column * cellSize
                val top = gridTop + row * cellSize
                paint.color = if (colorIndex == EMPTY_CELL) {
                    Color.WHITE
                } else {
                    palette.colors[colorIndex].opaqueArgb
                }
                canvas.drawRect(left, top, left + cellSize, top + cellSize, paint)

                if (colorIndex == EMPTY_CELL || layout.cellSize < 7) continue
                val color = palette.colors[colorIndex]
                val textColor = contrastingTextColor(color.opaqueArgb)
                if (layout.cellSize >= 12) {
                    drawCenteredText(
                        canvas,
                        symbols.getValue(color.code),
                        left + cellSize / 2f,
                        top + cellSize * 0.34f,
                        cellSize * 0.34f,
                        4f,
                        cellSize - 2f,
                        textColor,
                        paint,
                        mediumTypeface,
                    )
                    drawCenteredText(
                        canvas,
                        color.code,
                        left + cellSize / 2f,
                        top + cellSize * 0.72f,
                        cellSize * 0.29f,
                        3.5f,
                        cellSize - 1.5f,
                        textColor,
                        paint,
                    )
                } else {
                    drawCenteredText(
                        canvas,
                        color.code,
                        left + cellSize / 2f,
                        top + cellSize / 2f,
                        cellSize * 0.55f,
                        2.5f,
                        cellSize - 1f,
                        textColor,
                        paint,
                    )
                }
            }
        }

        drawPngGridLines(canvas, project, layout, paint)
        drawPngCoordinates(
            canvas,
            project,
            gridLeft,
            gridTop,
            gridRight,
            gridBottom,
            cellSize,
            layout,
            paint,
        )
        drawText(
            canvas,
            "Bold lines mark every 5 cells. Coordinates are absolute and 1-based.",
            gridLeft,
            layout.height - max(10f, layout.bottomMargin * 0.32f),
            min(11f, max(7f, layout.cellSize * 0.55f)),
            Color.rgb(88, 94, 100),
            paint,
            maxWidth = gridRight - gridLeft,
        )
    }

    private fun drawPngGridLines(
        canvas: Canvas,
        project: BeadProject,
        layout: PngLayout,
        paint: Paint,
    ) {
        val left = layout.leftMargin.toFloat()
        val top = layout.topMargin.toFloat()
        val width = project.grid.width * layout.cellSize.toFloat()
        val height = project.grid.height * layout.cellSize.toFloat()
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(58, 64, 70)

        if (layout.cellSize >= 4) {
            paint.strokeWidth = 1f
            for (column in 0..project.grid.width) {
                val x = left + column * layout.cellSize
                canvas.drawLine(x, top, x, top + height, paint)
            }
            for (row in 0..project.grid.height) {
                val y = top + row * layout.cellSize
                canvas.drawLine(left, y, left + width, y, paint)
            }
        }

        paint.color = Color.rgb(24, 28, 32)
        paint.strokeWidth = if (layout.cellSize >= 8) 2.5f else 1.5f
        for (column in 0..project.grid.width step 5) {
            val x = left + column * layout.cellSize
            canvas.drawLine(x, top, x, top + height, paint)
        }
        if (project.grid.width % 5 != 0) {
            canvas.drawLine(left + width, top, left + width, top + height, paint)
        }
        for (row in 0..project.grid.height step 5) {
            val y = top + row * layout.cellSize
            canvas.drawLine(left, y, left + width, y, paint)
        }
        if (project.grid.height % 5 != 0) {
            canvas.drawLine(left, top + height, left + width, top + height, paint)
        }
    }

    private fun drawPngCoordinates(
        canvas: Canvas,
        project: BeadProject,
        gridLeft: Float,
        gridTop: Float,
        gridRight: Float,
        gridBottom: Float,
        cellSize: Float,
        layout: PngLayout,
        paint: Paint,
    ) {
        val interval = when {
            layout.cellSize >= 12 -> 5
            layout.cellSize >= 5 -> 10
            else -> 25
        }
        val labelSize = min(12f, max(6f, layout.cellSize * 0.55f))
        val columnLabels = coordinateLabelIndices(project.grid.width, interval)
        val rowLabels = coordinateLabelIndices(project.grid.height, interval)
        columnLabels.forEach { column ->
            val x = gridLeft + (column + 0.5f) * cellSize
            drawCenteredText(
                canvas,
                (column + 1).toString(),
                x,
                gridTop - max(8f, labelSize),
                labelSize,
                4f,
                max(cellSize * interval - 2f, cellSize),
                Color.rgb(54, 60, 66),
                paint,
            )
            drawCenteredText(
                canvas,
                (column + 1).toString(),
                x,
                gridBottom + max(8f, labelSize),
                labelSize,
                4f,
                max(cellSize * interval - 2f, cellSize),
                Color.rgb(54, 60, 66),
                paint,
            )
        }
        rowLabels.forEach { row ->
            val y = gridTop + (row + 0.5f) * cellSize
            drawCenteredText(
                canvas,
                (row + 1).toString(),
                gridLeft - max(12f, labelSize * 1.6f),
                y,
                labelSize,
                4f,
                layout.leftMargin * 0.7f,
                Color.rgb(54, 60, 66),
                paint,
            )
            drawCenteredText(
                canvas,
                (row + 1).toString(),
                gridRight + max(12f, labelSize * 1.6f),
                y,
                labelSize,
                4f,
                layout.rightMargin * 1.4f,
                Color.rgb(54, 60, 66),
                paint,
            )
        }
    }

    private fun drawMaterialTable(
        canvas: Canvas,
        materials: List<MaterialRequirement>,
        headerBaseline: Float,
        firstRowTop: Float,
        rowHeight: Float,
        paint: Paint,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(236, 239, 241)
        canvas.drawRect(42f, headerBaseline - 16f, 553f, headerBaseline + 6f, paint)
        drawText(canvas, "符号", 72f, headerBaseline, 8.5f, Color.DKGRAY, paint, mediumTypeface)
        drawText(canvas, "色号", 124f, headerBaseline, 8.5f, Color.DKGRAY, paint, mediumTypeface)
        drawText(canvas, "名称", 196f, headerBaseline, 8.5f, Color.DKGRAY, paint, mediumTypeface)
        drawText(
            canvas,
            "豆数",
            480f,
            headerBaseline,
            8.5f,
            Color.DKGRAY,
            paint,
            mediumTypeface,
            textAlign = Paint.Align.RIGHT,
        )
        drawText(
            canvas,
            "袋数",
            545f,
            headerBaseline,
            8.5f,
            Color.DKGRAY,
            paint,
            mediumTypeface,
            textAlign = Paint.Align.RIGHT,
        )

        materials.forEachIndexed { index, item ->
            val top = firstRowTop + index * rowHeight
            if (index % 2 == 1) {
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(249, 250, 251)
                canvas.drawRect(42f, top, 553f, top + rowHeight, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = item.color.opaqueArgb
            canvas.drawRect(RectF(44f, top + 3f, 57f, top + 16f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = Color.rgb(90, 96, 102)
            canvas.drawRect(RectF(44f, top + 3f, 57f, top + 16f), paint)
            drawText(canvas, item.symbol, 72f, top + 13.5f, 8.5f, Color.DKGRAY, paint, mediumTypeface)
            drawText(canvas, item.color.code, 124f, top + 13.5f, 8.5f, Color.DKGRAY, paint)
            val displayName = materialDisplayName(item.color)
            drawText(
                canvas,
                displayName,
                196f,
                top + 13.5f,
                8.5f,
                Color.DKGRAY,
                paint,
                maxWidth = 220f,
            )
            drawText(
                canvas,
                item.beadCount.toString(),
                480f,
                top + 13.5f,
                8.5f,
                Color.DKGRAY,
                paint,
                textAlign = Paint.Align.RIGHT,
            )
            drawText(
                canvas,
                item.bagsNeeded.toString(),
                545f,
                top + 13.5f,
                8.5f,
                Color.DKGRAY,
                paint,
                textAlign = Paint.Align.RIGHT,
            )
        }
    }

    private fun drawSummaryValue(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        baseline: Float,
        width: Float,
        paint: Paint,
    ) {
        drawText(canvas, label.uppercase(), x, baseline - 14f, 7.5f, Color.rgb(105, 111, 117), paint, mediumTypeface)
        drawText(canvas, value, x, baseline + 5f, 12f, Color.rgb(33, 38, 43), paint, mediumTypeface, width)
    }

    private fun drawCalibrationMark(
        canvas: Canvas,
        paint: Paint,
        right: Float,
        y: Float,
    ) {
        val markLength = CALIBRATION_MARK_MILLIMETERS * POINTS_PER_MILLIMETER
        val left = right - markLength
        val center = (left + right) / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = Color.rgb(62, 68, 74)
        canvas.drawLine(left, y, right, y, paint)
        canvas.drawLine(left, y - 4f, left, y + 4f, paint)
        canvas.drawLine(center, y - 2.5f, center, y + 2.5f, paint)
        canvas.drawLine(right, y - 4f, right, y + 4f, paint)
        drawText(
            canvas = canvas,
            text = "$CALIBRATION_MARK_MILLIMETERS mm calibration",
            x = center,
            baseline = y + 12f,
            size = 7f,
            color = Color.rgb(62, 68, 74),
            paint = paint,
            textAlign = Paint.Align.CENTER,
        )
    }

    private fun boardAnnotationDescription(showSymbols: Boolean, showColorCodes: Boolean): String =
        when {
            showSymbols && showColorCodes ->
                "Bold guides follow absolute 5-cell boundaries. Each filled cell shows symbol and color code."

            showSymbols ->
                "Bold guides follow absolute 5-cell boundaries. Each filled cell shows its symbol."

            showColorCodes ->
                "Bold guides follow absolute 5-cell boundaries. Each filled cell shows its color code."

            else ->
                "Bold guides follow absolute 5-cell boundaries. Filled cells are color-only."
        }

    private fun drawPageFooter(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        paint: Paint,
        pageWidth: Int = PDF_WIDTH,
        pageHeight: Int = PDF_HEIGHT,
    ) {
        val right = pageWidth - 42f
        val lineY = pageHeight - 28f
        val textBaseline = pageHeight - 11f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        paint.color = Color.rgb(205, 209, 212)
        canvas.drawLine(42f, lineY, right, lineY, paint)
        drawText(
            canvas,
            "豆格图纸导出",
            42f,
            textBaseline,
            7.5f,
            Color.rgb(115, 120, 125),
            paint,
        )
        drawText(
            canvas,
            "第 $pageNumber / $totalPages 页",
            right,
            textBaseline,
            7.5f,
            Color.rgb(115, 120, 125),
            paint,
            textAlign = Paint.Align.RIGHT,
        )
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        paint: Paint,
        typeface: Typeface = regularTypeface,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        textAlign: Paint.Align = Paint.Align.LEFT,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = typeface
        paint.textAlign = textAlign
        val fitted = ellipsize(text, maxWidth, paint)
        canvas.drawText(fitted, x, baseline, paint)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        desiredSize: Float,
        minSize: Float,
        maxWidth: Float,
        color: Int,
        paint: Paint,
        typeface: Typeface = regularTypeface,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.typeface = typeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = desiredSize
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > maxWidth && measuredWidth > 0f) {
            paint.textSize = max(minSize, desiredSize * maxWidth / measuredWidth)
        }
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (!maxWidth.isFinite() || paint.measureText(text) <= maxWidth) return text
        val suffix = "..."
        if (paint.measureText(suffix) > maxWidth) return ""
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (paint.measureText(text.substring(0, middle) + suffix) <= maxWidth) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return text.substring(0, low) + suffix
    }

    private fun choosePngLayout(project: BeadProject, options: PngExportOptions): PngLayout {
        require(options.requestedCellSizePx > 0) { "Requested PNG cell size must be positive" }
        require(options.maxDimensionPx > 0) { "PNG max dimension must be positive" }
        require(options.maxPixelCount > 0) { "PNG max pixel count must be positive" }
        val maxDimension = min(options.maxDimensionPx, HARD_MAX_PNG_DIMENSION)
        val maxPixels = min(options.maxPixelCount, HARD_MAX_PNG_PIXELS)
        val requested = min(options.requestedCellSizePx, HARD_MAX_PNG_DIMENSION)

        fun candidate(cellSize: Int): PngLayout = when (options.mode) {
            PngMode.PIXEL_ART -> PngLayout(
                width = project.grid.width * cellSize,
                height = project.grid.height * cellSize,
                cellSize = cellSize,
                leftMargin = 0,
                topMargin = 0,
                rightMargin = 0,
                bottomMargin = 0,
            )

            PngMode.GRID_SHEET -> {
                val left = max(42, cellSize * 2)
                val top = max(58, cellSize * 3)
                val right = max(24, cellSize * 2)
                val bottom = max(42, cellSize * 2)
                PngLayout(
                    width = project.grid.width * cellSize + left + right,
                    height = project.grid.height * cellSize + top + bottom,
                    cellSize = cellSize,
                    leftMargin = left,
                    topMargin = top,
                    rightMargin = right,
                    bottomMargin = bottom,
                )
            }
        }

        fun fits(layout: PngLayout): Boolean {
            val pixels = layout.width.toLong() * layout.height.toLong()
            return layout.width <= maxDimension &&
                layout.height <= maxDimension &&
                pixels <= maxPixels
        }

        val minimum = candidate(1)
        require(fits(minimum)) {
            "PNG bounds are too small for a ${project.grid.width} x ${project.grid.height} pattern; " +
                "minimum output is ${minimum.width} x ${minimum.height}"
        }

        var low = 1
        var high = requested
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (fits(candidate(middle))) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return candidate(low)
    }

    private fun coordinateLabelIndices(size: Int, interval: Int): List<Int> = buildList {
        add(0)
        var index = interval - 1
        while (index < size) {
            if (index != 0) add(index)
            index += interval
        }
        if (size > 1 && last() != size - 1) add(size - 1)
    }

    private fun validate(project: BeadProject, palette: BeadPalette) {
        project.grid.cells.forEachIndexed { index, colorIndex ->
            require(colorIndex == EMPTY_CELL || colorIndex in palette.colors.indices) {
                "Cell $index references palette index $colorIndex, but palette ${palette.id} has ${palette.colors.size} colors"
            }
        }
    }

    private fun encodeSymbol(index: Int): String {
        require(index >= 0)
        val base = symbolAlphabet.length
        var value = index
        val result = StringBuilder()
        do {
            result.append(symbolAlphabet[value % base])
            value = value / base - 1
        } while (value >= 0)
        return result.reverse().toString()
    }

    private fun contrastingTextColor(argb: Int): Int {
        val red = Color.red(argb)
        val green = Color.green(argb)
        val blue = Color.blue(argb)
        val luminance = (red * 299 + green * 587 + blue * 114) / 1_000
        return if (luminance < 142) Color.WHITE else Color.rgb(20, 24, 28)
    }

    private fun materialDisplayName(color: PaletteColor): String {
        val name = singleLine(color.name)
        val group = singleLine(color.group)
        return when {
            name.equals(color.code, ignoreCase = true) && group.isNotBlank() -> group
            name.equals(color.code, ignoreCase = true) -> "-"
            group.isBlank() || group.equals(name, ignoreCase = true) -> name
            else -> "$name ($group)"
        }
    }

    private fun singleLine(value: String): String = value
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()

    private fun pageCountAfterFirst(
        itemCount: Int,
        firstPageCapacity: Int,
        continuationCapacity: Int,
    ): Int {
        val remaining = max(0, itemCount - firstPageCapacity)
        return if (remaining == 0) 0 else (remaining + continuationCapacity - 1) / continuationCapacity
    }

    private fun createPdfExportPlan(
        project: BeadProject,
        options: PdfExportOptions,
        materialCount: Int,
    ): PdfExportPlan {
        require(options.bagSize > 0) { "Bag size must be positive" }
        require(materialCount >= 0) { "Material count must not be negative" }
        val boardSize = options.boardSize ?: project.boardSize
        require(boardSize in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE) {
            "Board size must be in ${BeadProject.MIN_BOARD_SIZE}..${BeadProject.MAX_BOARD_SIZE}"
        }
        val boardColumns = (project.grid.width + boardSize - 1) / boardSize
        val boardRows = (project.grid.height + boardSize - 1) / boardSize
        val boardPageCount = boardColumns * boardRows
        val continuationMaterialPages = pageCountAfterFirst(
            itemCount = materialCount,
            firstPageCapacity = COVER_MATERIAL_CAPACITY,
            continuationCapacity = MATERIAL_PAGE_CAPACITY,
        )
        val materialPageCount = 1 + continuationMaterialPages
        val physicalCellSizeMm = options.physicalCellSizeMm
        require(
            physicalCellSizeMm == null ||
                (physicalCellSizeMm.isFinite() && physicalCellSizeMm > 0f),
        ) {
            "Physical cell size must be a positive finite millimeter value"
        }
        val maximumGridSize = when (options.orientation) {
            PdfPageOrientation.PORTRAIT -> DEFAULT_PDF_GRID_SIZE
            PdfPageOrientation.LANDSCAPE -> LANDSCAPE_PDF_GRID_SIZE
        }
        val cellSizePoints = physicalCellSizeMm
            ?.let { it * POINTS_PER_MILLIMETER }
            ?: (maximumGridSize / boardSize)
        val gridSizePoints = cellSizePoints * boardSize
        require(gridSizePoints <= maximumGridSize + 0.001f) {
            "A $boardSize x $boardSize board at $physicalCellSizeMm mm per cell does not fit " +
                "an A4 ${options.orientation.name.lowercase()} board page"
        }
        val boardPageWidth = when (options.orientation) {
            PdfPageOrientation.PORTRAIT -> PDF_WIDTH
            PdfPageOrientation.LANDSCAPE -> PDF_HEIGHT
        }
        val boardPageHeight = when (options.orientation) {
            PdfPageOrientation.PORTRAIT -> PDF_HEIGHT
            PdfPageOrientation.LANDSCAPE -> PDF_WIDTH
        }
        val gridLeft = when (options.orientation) {
            PdfPageOrientation.PORTRAIT -> (boardPageWidth - gridSizePoints) / 2f
            PdfPageOrientation.LANDSCAPE ->
                LANDSCAPE_GRID_AREA_LEFT + (LANDSCAPE_PDF_GRID_SIZE - gridSizePoints) / 2f
        }
        val gridTop = when (options.orientation) {
            PdfPageOrientation.PORTRAIT -> 118f
            PdfPageOrientation.LANDSCAPE ->
                LANDSCAPE_GRID_AREA_TOP + (LANDSCAPE_PDF_GRID_SIZE - gridSizePoints) / 2f
        }
        return PdfExportPlan(
            boardSize = boardSize,
            boardColumns = boardColumns,
            boardRows = boardRows,
            boardPageCount = boardPageCount,
            materialPageCount = materialPageCount,
            pageCount = materialPageCount + boardPageCount,
            cellSizePoints = cellSizePoints,
            orientation = options.orientation,
            physicalCellSizeMm = physicalCellSizeMm,
            boardPageWidthPoints = boardPageWidth,
            boardPageHeightPoints = boardPageHeight,
            gridLeftPoints = gridLeft,
            gridTopPoints = gridTop,
            showSymbols = options.showSymbols,
            showColorCodes = options.showColorCodes,
            showCalibrationMark = options.showCalibrationMark,
        )
    }

    private fun pageInfo(
        pageNumber: Int,
        width: Int = PDF_WIDTH,
        height: Int = PDF_HEIGHT,
    ): PdfDocument.PageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()

    private data class PngLayout(
        val width: Int,
        val height: Int,
        val cellSize: Int,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
    )
}
