package com.qiao.dougrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.data.EditorTool
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class GridWindow(
    val startColumn: Int,
    val startRow: Int,
    val width: Int,
    val height: Int,
)

enum class PatternCanvasMode { EDIT, CRAFT, PREVIEW }

@Composable
fun PatternCanvas(
    grid: PatternGrid,
    palette: BeadPalette,
    revision: Long,
    modifier: Modifier = Modifier,
    mode: PatternCanvasMode = PatternCanvasMode.EDIT,
    tool: EditorTool = EditorTool.PAN,
    selectedColorIndex: Int = 0,
    showColorCodes: Boolean = true,
    highContrastGrid: Boolean = false,
    highlightColorIndex: Int? = null,
    hideCompleted: Boolean = false,
    window: GridWindow? = null,
    referenceImage: ImageBitmap? = null,
    referenceAlpha: Float = 0f,
    onStrokeStart: () -> Unit = {},
    onStroke: (List<Int>) -> Unit = {},
    onStrokeEnd: () -> Unit = {},
    onCellAction: (Int) -> Unit = {},
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(grid.width, grid.height, window) { mutableFloatStateOf(1f) }
    var pan by remember(grid.width, grid.height, window) { mutableStateOf(Offset.Zero) }
    val gridWindow = window ?: GridWindow(0, 0, grid.width, grid.height)
    val canTransform = mode != PatternCanvasMode.PREVIEW && tool == EditorTool.PAN
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val updatedScale = (scale * zoomChange).coerceIn(0.7f, 18f)
        val ratio = updatedScale / scale
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val focalPoint = centroid - center
        pan = pan * ratio + focalPoint * (1f - ratio) + panChange
        scale = updatedScale
    }

    val gestureModifier = when {
        mode == PatternCanvasMode.PREVIEW -> Modifier
        mode == PatternCanvasMode.CRAFT -> Modifier.pointerInput(grid, gridWindow, scale, pan, canvasSize) {
            detectTapGestures { position ->
                mapToCell(position, canvasSize, grid, gridWindow, scale, pan)?.let(onCellAction)
            }
        }
        tool == EditorTool.PENCIL || tool == EditorTool.ERASER -> Modifier
            .pointerInput(grid, gridWindow, tool, scale, pan, canvasSize) {
                var stroke = linkedSetOf<Int>()
                var previous: Int? = null
                fun emitNewCells(candidates: Iterable<Int>) {
                    val added = ArrayList<Int>()
                    candidates.forEach { index ->
                        if (stroke.add(index)) added += index
                    }
                    if (added.isNotEmpty()) onStroke(added)
                }
                detectDragGestures(
                    orientationLock = null,
                    onDragStart = { down, slopTriggerChange, _ ->
                        onStrokeStart()
                        stroke = linkedSetOf()
                        val downCell = mapToCell(down.position, canvasSize, grid, gridWindow, scale, pan)
                        val slopCell = mapToCell(
                            slopTriggerChange.position,
                            canvasSize,
                            grid,
                            gridWindow,
                            scale,
                            pan,
                        )
                        when {
                            downCell != null && slopCell != null -> emitNewCells(lineIndices(downCell, slopCell, grid.width))
                            downCell != null -> emitNewCells(listOf(downCell))
                            slopCell != null -> emitNewCells(listOf(slopCell))
                        }
                        previous = slopCell ?: downCell
                    },
                    onDrag = { change, _ ->
                        val current = mapToCell(change.position, canvasSize, grid, gridWindow, scale, pan)
                        if (current != null) {
                            val last = previous
                            val candidates = if (last == null) listOf(current) else lineIndices(last, current, grid.width)
                            emitNewCells(candidates)
                            previous = current
                        }
                        change.consume()
                    },
                    onDragEnd = { onStrokeEnd() },
                    onDragCancel = {
                        stroke.clear()
                        onStrokeEnd()
                    },
                )
            }
            .pointerInput(grid, gridWindow, tool, scale, pan, canvasSize) {
                detectTapGestures { position ->
                    mapToCell(position, canvasSize, grid, gridWindow, scale, pan)?.let {
                        onStrokeStart()
                        onStroke(listOf(it))
                        onStrokeEnd()
                    }
                }
            }
        tool != EditorTool.PAN -> Modifier.pointerInput(grid, gridWindow, tool, scale, pan, canvasSize) {
            detectTapGestures { position ->
                mapToCell(position, canvasSize, grid, gridWindow, scale, pan)?.let(onCellAction)
            }
        }
        else -> Modifier
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFFF0F3F1))
            .onSizeChanged { canvasSize = it }
            .then(gestureModifier)
            .transformable(transformState, enabled = canTransform),
    ) {
        drawPattern(
            grid = grid,
            palette = palette,
            window = gridWindow,
            scale = scale,
            pan = pan,
            mode = mode,
            selectedColorIndex = selectedColorIndex,
            showColorCodes = showColorCodes,
            highContrastGrid = highContrastGrid,
            highlightColorIndex = highlightColorIndex,
            hideCompleted = hideCompleted,
            referenceImage = referenceImage,
            referenceAlpha = referenceAlpha,
            revision = revision,
        )
    }
}

@Composable
fun PatternThumbnail(
    grid: PatternGrid,
    palette: BeadPalette,
    revision: Long,
    modifier: Modifier = Modifier,
) {
    PatternCanvas(
        grid = grid,
        palette = palette,
        revision = revision,
        modifier = modifier,
        mode = PatternCanvasMode.PREVIEW,
        showColorCodes = false,
    )
}

@Suppress("UNUSED_PARAMETER")
private fun DrawScope.drawPattern(
    grid: PatternGrid,
    palette: BeadPalette,
    window: GridWindow,
    scale: Float,
    pan: Offset,
    mode: PatternCanvasMode,
    selectedColorIndex: Int,
    showColorCodes: Boolean,
    highContrastGrid: Boolean,
    highlightColorIndex: Int?,
    hideCompleted: Boolean,
    referenceImage: ImageBitmap?,
    referenceAlpha: Float,
    revision: Long,
) {
    val baseCell = min(size.width / window.width, size.height / window.height)
    val cell = baseCell * scale
    val boardWidth = window.width * cell
    val boardHeight = window.height * cell
    val origin = Offset((size.width - boardWidth) / 2f, (size.height - boardHeight) / 2f) + pan
    drawRect(Color(0xFFFFFFFF), topLeft = origin, size = Size(boardWidth, boardHeight))

    if (referenceImage != null && referenceAlpha > 0f) {
        drawImage(
            image = referenceImage,
            dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
            dstSize = IntSize(boardWidth.toInt().coerceAtLeast(1), boardHeight.toInt().coerceAtLeast(1)),
            alpha = referenceAlpha.coerceIn(0f, 0.85f),
        )
    }

    val localStartColumn = max(0, floor((-origin.x) / cell).toInt())
    val localEndColumn = min(window.width - 1, floor((size.width - origin.x) / cell).toInt())
    val localStartRow = max(0, floor((-origin.y) / cell).toInt())
    val localEndRow = min(window.height - 1, floor((size.height - origin.y) / cell).toInt())
    if (localStartColumn > localEndColumn || localStartRow > localEndRow) return

    val beadStyle = mode == PatternCanvasMode.CRAFT && cell >= 7f
    for (localRow in localStartRow..localEndRow) {
        val row = window.startRow + localRow
        for (localColumn in localStartColumn..localEndColumn) {
            val column = window.startColumn + localColumn
            if (!grid.isInside(column, row)) continue
            val index = grid.indexOf(column, row)
            val colorIndex = grid.cells[index]
            if (colorIndex == EMPTY_CELL || (hideCompleted && grid.completed[index].toInt() != 0)) continue
            val paletteColor = palette.colors.getOrNull(colorIndex) ?: continue
            val completed = grid.completed[index].toInt() != 0
            val highlighted = highlightColorIndex == null || highlightColorIndex == colorIndex
            val alpha = when {
                completed -> 0.24f
                !highlighted -> 0.12f
                else -> 1f
            }
            val topLeft = Offset(origin.x + localColumn * cell, origin.y + localRow * cell)
            val color = Color(paletteColor.opaqueArgb).copy(alpha = alpha)
            if (beadStyle) {
                val center = topLeft + Offset(cell / 2f, cell / 2f)
                drawCircle(color, radius = cell * 0.43f, center = center)
                if (cell >= 13f && highlighted && !completed) {
                    drawCircle(Color.Black.copy(alpha = 0.2f), radius = cell * 0.11f, center = center)
                }
            } else {
                val inset = if (cell >= 4f) 0.45f else 0f
                drawRect(
                    color,
                    topLeft = topLeft + Offset(inset, inset),
                    size = Size((cell - inset * 2).coerceAtLeast(0.5f), (cell - inset * 2).coerceAtLeast(0.5f)),
                )
            }
            if (completed && cell >= 12f) drawCompletionMark(topLeft, cell)
            if (showColorCodes && highlighted && !completed && cell >= 25f) {
                drawCode(paletteColor.code, topLeft, cell, paletteColor.opaqueArgb)
            }
        }
    }

    if (cell >= 3.2f) {
        val normal = Color.Black.copy(alpha = if (highContrastGrid) 0.34f else 0.14f)
        val guide = Color.Black.copy(alpha = if (highContrastGrid) 0.58f else 0.3f)
        for (localColumn in 0..window.width) {
            val absolute = window.startColumn + localColumn
            val color = if (absolute % 5 == 0 || absolute % 29 == 0) guide else normal
            val stroke = if (absolute % 29 == 0) 2.2f else if (absolute % 5 == 0) 1.2f else 0.65f
            val x = origin.x + localColumn * cell
            drawLine(color, Offset(x, origin.y), Offset(x, origin.y + boardHeight), strokeWidth = stroke)
        }
        for (localRow in 0..window.height) {
            val absolute = window.startRow + localRow
            val color = if (absolute % 5 == 0 || absolute % 29 == 0) guide else normal
            val stroke = if (absolute % 29 == 0) 2.2f else if (absolute % 5 == 0) 1.2f else 0.65f
            val y = origin.y + localRow * cell
            drawLine(color, Offset(origin.x, y), Offset(origin.x + boardWidth, y), strokeWidth = stroke)
        }
    }
    drawRect(
        color = Color(0xFF263331),
        topLeft = origin,
        size = Size(boardWidth, boardHeight),
        style = Stroke(width = 2f),
    )
}

private fun DrawScope.drawCompletionMark(topLeft: Offset, cell: Float) {
    val color = Color(0xFF006B64)
    drawLine(
        color,
        topLeft + Offset(cell * 0.25f, cell * 0.53f),
        topLeft + Offset(cell * 0.43f, cell * 0.7f),
        strokeWidth = max(1.5f, cell * 0.08f),
    )
    drawLine(
        color,
        topLeft + Offset(cell * 0.43f, cell * 0.7f),
        topLeft + Offset(cell * 0.76f, cell * 0.3f),
        strokeWidth = max(1.5f, cell * 0.08f),
    )
}

private fun DrawScope.drawCode(code: String, topLeft: Offset, cell: Float, backgroundArgb: Int) {
    val red = backgroundArgb ushr 16 and 0xFF
    val green = backgroundArgb ushr 8 and 0xFF
    val blue = backgroundArgb and 0xFF
    val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    val textColor = if (luma > 145f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (cell * 0.28f).coerceIn(7f, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.nativeCanvas.drawText(
            code.take(5),
            topLeft.x + cell / 2f,
            topLeft.y + cell * 0.61f,
            paint,
        )
    }
}

private fun mapToCell(
    position: Offset,
    canvasSize: IntSize,
    grid: PatternGrid,
    window: GridWindow,
    scale: Float,
    pan: Offset,
): Int? {
    if (canvasSize == IntSize.Zero) return null
    val baseCell = min(canvasSize.width.toFloat() / window.width, canvasSize.height.toFloat() / window.height)
    val cell = baseCell * scale
    val boardWidth = window.width * cell
    val boardHeight = window.height * cell
    val origin = Offset((canvasSize.width - boardWidth) / 2f, (canvasSize.height - boardHeight) / 2f) + pan
    val localColumn = floor((position.x - origin.x) / cell).toInt()
    val localRow = floor((position.y - origin.y) / cell).toInt()
    if (localColumn !in 0 until window.width || localRow !in 0 until window.height) return null
    val column = window.startColumn + localColumn
    val row = window.startRow + localRow
    return if (grid.isInside(column, row)) grid.indexOf(column, row) else null
}

private fun lineIndices(start: Int, end: Int, width: Int): List<Int> {
    var x0 = start % width
    var y0 = start / width
    val x1 = end % width
    val y1 = end / width
    val dx = kotlin.math.abs(x1 - x0)
    val sx = if (x0 < x1) 1 else -1
    val dy = -kotlin.math.abs(y1 - y0)
    val sy = if (y0 < y1) 1 else -1
    var error = dx + dy
    val result = ArrayList<Int>(max(dx, -dy) + 1)
    while (true) {
        result += y0 * width + x0
        if (x0 == x1 && y0 == y1) break
        val twice = 2 * error
        if (twice >= dy) {
            error += dy
            x0 += sx
        }
        if (twice <= dx) {
            error += dx
            y0 += sy
        }
    }
    return result
}
