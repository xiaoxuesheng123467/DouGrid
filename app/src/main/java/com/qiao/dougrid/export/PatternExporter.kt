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

data class PngExportOptions(
    val mode: PngMode = PngMode.PIXEL_ART,
    val requestedCellSizePx: Int = 24,
    val transparentEmptyCells: Boolean = true,
    val maxDimensionPx: Int = DEFAULT_PNG_MAX_DIMENSION_PX,
    val maxPixelCount: Long = DEFAULT_PNG_MAX_PIXEL_COUNT,
)

data class PdfExportOptions(
    val bagSize: Int = 1_000,
)

data class PdfExportResult(
    val pageCount: Int,
    val materialPageCount: Int,
    val boardPageCount: Int,
)

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
    private const val BOARD_SIZE = BeadProject.BOARD_SIZE
    private const val COVER_MATERIAL_CAPACITY = 25
    private const val MATERIAL_PAGE_CAPACITY = 35
    private const val HARD_MAX_PNG_DIMENSION = DEFAULT_PNG_MAX_DIMENSION_PX
    private const val HARD_MAX_PNG_PIXELS = DEFAULT_PNG_MAX_PIXEL_COUNT

    private val symbolAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val regularTypeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val mediumTypeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

    /**
     * Exports an A4 portrait PDF. Page one contains project details and the first part of
     * the material legend; remaining material pages precede one fixed 29 x 29 page per board.
     */
    @Throws(IOException::class)
    fun exportPdf(
        project: BeadProject,
        palette: BeadPalette,
        output: OutputStream,
        options: PdfExportOptions = PdfExportOptions(),
    ): PdfExportResult {
        require(options.bagSize > 0) { "Bag size must be positive" }
        validate(project, palette)

        val materials = materials(project, palette, options.bagSize)
        val continuationMaterialPages = pageCountAfterFirst(
            itemCount = materials.size,
            firstPageCapacity = COVER_MATERIAL_CAPACITY,
            continuationCapacity = MATERIAL_PAGE_CAPACITY,
        )
        val materialPageCount = 1 + continuationMaterialPages
        val totalPages = materialPageCount + project.boardCount
        val document = PdfDocument()

        try {
            addCoverPage(
                document = document,
                project = project,
                palette = palette,
                materials = materials.take(COVER_MATERIAL_CAPACITY),
                bagSize = options.bagSize,
                totalPages = totalPages,
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
                        totalPages = totalPages,
                    )
                }

            var boardOrdinal = 0
            for (boardRow in 0 until project.boardRows) {
                for (boardColumn in 0 until project.boardColumns) {
                    val pageNumber = materialPageCount + boardOrdinal + 1
                    addBoardPage(
                        document = document,
                        project = project,
                        palette = palette,
                        boardColumn = boardColumn,
                        boardRow = boardRow,
                        boardOrdinal = boardOrdinal,
                        pageNumber = pageNumber,
                        totalPages = totalPages,
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
            pageCount = totalPages,
            materialPageCount = materialPageCount,
            boardPageCount = project.boardCount,
        )
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
                "${project.boardColumns} x ${project.boardRows} (${project.boardCount})",
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
        boardColumn: Int,
        boardRow: Int,
        boardOrdinal: Int,
        pageNumber: Int,
        totalPages: Int,
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val symbols = colorSymbols(palette)
            val startColumn = boardColumn * BOARD_SIZE
            val startRow = boardRow * BOARD_SIZE
            val endColumn = min(startColumn + BOARD_SIZE, project.grid.width)
            val endRow = min(startRow + BOARD_SIZE, project.grid.height)

            drawText(
                canvas,
                "Board ${boardOrdinal + 1}/${project.boardCount}",
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
                553f,
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
                553f,
                71f,
                10f,
                Color.rgb(88, 94, 100),
                paint,
                maxWidth = 220f,
                textAlign = Paint.Align.RIGHT,
            )

            val cellSize = 16.25f
            val gridSize = cellSize * BOARD_SIZE
            val gridLeft = (PDF_WIDTH - gridSize) / 2f
            val gridTop = 118f
            val gridRight = gridLeft + gridSize
            val gridBottom = gridTop + gridSize

            drawPdfBoardCells(
                canvas = canvas,
                project = project,
                palette = palette,
                symbols = symbols,
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
                startColumn = startColumn,
                startRow = startRow,
                gridLeft = gridLeft,
                gridTop = gridTop,
                cellSize = cellSize,
                paint = paint,
            )

            drawText(
                canvas,
                "Bold guides follow absolute 5-cell boundaries. Each filled cell shows symbol and color code.",
                42f,
                632f,
                9f,
                Color.rgb(88, 94, 100),
                paint,
            )
            drawBoardMaterialStrip(
                canvas = canvas,
                project = project,
                palette = palette,
                symbols = symbols,
                startColumn = startColumn,
                startRow = startRow,
                paint = paint,
            )
            drawPageFooter(canvas, pageNumber, totalPages, paint)
        } finally {
            document.finishPage(page)
        }
    }

    private fun drawPdfBoardCells(
        canvas: Canvas,
        project: BeadProject,
        palette: BeadPalette,
        symbols: Map<String, String>,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        for (localRow in 0 until BOARD_SIZE) {
            val row = startRow + localRow
            for (localColumn in 0 until BOARD_SIZE) {
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
                drawCenteredText(
                    canvas = canvas,
                    text = symbols.getValue(color.code),
                    centerX = rect.centerX(),
                    centerY = rect.centerY() - 3.2f,
                    desiredSize = 6.3f,
                    minSize = 4f,
                    maxWidth = cellSize - 1.5f,
                    color = textColor,
                    paint = paint,
                    typeface = mediumTypeface,
                )
                drawCenteredText(
                    canvas = canvas,
                    text = color.code,
                    centerX = rect.centerX(),
                    centerY = rect.centerY() + 4f,
                    desiredSize = 5.1f,
                    minSize = 3.2f,
                    maxWidth = cellSize - 1.2f,
                    color = textColor,
                    paint = paint,
                )
            }
        }
    }

    private fun drawPdfCoordinates(
        canvas: Canvas,
        project: BeadProject,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        gridRight: Float,
        gridBottom: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        for (localColumn in 0 until BOARD_SIZE) {
            val column = startColumn + localColumn
            if (column >= project.grid.width) continue
            val centerX = gridLeft + (localColumn + 0.5f) * cellSize
            val label = (column + 1).toString()
            drawCenteredText(
                canvas,
                label,
                centerX,
                gridTop - 9f,
                6.5f,
                4.5f,
                cellSize - 1f,
                Color.rgb(60, 66, 72),
                paint,
            )
            drawCenteredText(
                canvas,
                label,
                centerX,
                gridBottom + 10f,
                6.5f,
                4.5f,
                cellSize - 1f,
                Color.rgb(60, 66, 72),
                paint,
            )
        }
        for (localRow in 0 until BOARD_SIZE) {
            val row = startRow + localRow
            if (row >= project.grid.height) continue
            val centerY = gridTop + (localRow + 0.5f) * cellSize
            val label = (row + 1).toString()
            drawCenteredText(
                canvas,
                label,
                gridLeft - 13f,
                centerY,
                6.5f,
                4.5f,
                22f,
                Color.rgb(60, 66, 72),
                paint,
            )
            drawCenteredText(
                canvas,
                label,
                gridRight + 13f,
                centerY,
                6.5f,
                4.5f,
                22f,
                Color.rgb(60, 66, 72),
                paint,
            )
        }
    }

    private fun drawBoardGridLines(
        canvas: Canvas,
        startColumn: Int,
        startRow: Int,
        gridLeft: Float,
        gridTop: Float,
        cellSize: Float,
        paint: Paint,
    ) {
        val gridSize = cellSize * BOARD_SIZE
        paint.style = Paint.Style.STROKE
        for (line in 0..BOARD_SIZE) {
            val x = gridLeft + line * cellSize
            val absoluteBoundary = startColumn + line
            paint.color = if (line == 0 || line == BOARD_SIZE) {
                Color.rgb(30, 34, 38)
            } else {
                Color.rgb(72, 78, 84)
            }
            paint.strokeWidth = when {
                line == 0 || line == BOARD_SIZE -> 1.45f
                absoluteBoundary % 5 == 0 -> 1.15f
                else -> 0.32f
            }
            canvas.drawLine(x, gridTop, x, gridTop + gridSize, paint)
        }
        for (line in 0..BOARD_SIZE) {
            val y = gridTop + line * cellSize
            val absoluteBoundary = startRow + line
            paint.color = if (line == 0 || line == BOARD_SIZE) {
                Color.rgb(30, 34, 38)
            } else {
                Color.rgb(72, 78, 84)
            }
            paint.strokeWidth = when {
                line == 0 || line == BOARD_SIZE -> 1.45f
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
        startColumn: Int,
        startRow: Int,
        paint: Paint,
    ) {
        val counts = linkedMapOf<Int, Int>()
        val endColumn = min(startColumn + BOARD_SIZE, project.grid.width)
        val endRow = min(startRow + BOARD_SIZE, project.grid.height)
        for (row in startRow until endRow) {
            for (column in startColumn until endColumn) {
                val colorIndex = project.grid[column, row]
                if (colorIndex != EMPTY_CELL) {
                    counts[colorIndex] = (counts[colorIndex] ?: 0) + 1
                }
            }
        }
        val items = counts.toList().sortedBy { palette.colors[it.first].code }
        drawText(
            canvas,
            "Board materials (${items.sumOf { it.second }} beads)",
            42f,
            663f,
            11f,
            Color.rgb(35, 40, 45),
            paint,
            mediumTypeface,
        )
        if (items.isEmpty()) {
            drawText(canvas, "No filled cells on this board.", 42f, 686f, 9f, Color.GRAY, paint)
            return
        }

        val maxItems = 24
        items.take(maxItems).forEachIndexed { index, (colorIndex, count) ->
            val column = index % 4
            val row = index / 4
            val x = 42f + column * 128f
            val top = 677f + row * 19f
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
                maxWidth = 106f,
            )
        }
        if (items.size > maxItems) {
            drawText(
                canvas,
                "+${items.size - maxItems} more colors; see the material legend.",
                42f,
                798f,
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

    private fun drawPageFooter(canvas: Canvas, pageNumber: Int, totalPages: Int, paint: Paint) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        paint.color = Color.rgb(205, 209, 212)
        canvas.drawLine(42f, 814f, 553f, 814f, paint)
        drawText(
            canvas,
            "豆格图纸导出",
            42f,
            831f,
            7.5f,
            Color.rgb(115, 120, 125),
            paint,
        )
        drawText(
            canvas,
            "第 $pageNumber / $totalPages 页",
            553f,
            831f,
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

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create()

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
