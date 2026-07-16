package com.qiao.dougrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.core.CropRegion
import kotlin.math.hypot

private enum class CropDragMode { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NEW }

@Composable
fun CropSelectionOverlay(
    region: CropRegion,
    sourceWidth: Int,
    sourceHeight: Int,
    targetAspect: Float,
    onRegionChange: (CropRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentRegion by rememberUpdatedState(region.normalized())
    val currentOnRegionChange by rememberUpdatedState(onRegionChange)
    val handleRadius = with(LocalDensity.current) { 22.dp.toPx() }
    val handleDrawRadius = with(LocalDensity.current) { 5.dp.toPx() }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = "裁剪范围" }
            .pointerInput(sourceWidth, sourceHeight, targetAspect) {
                var dragMode = CropDragMode.NEW
                var startPoint = Offset.Zero
                var startRegion = CropRegion.FULL
                var anchor = Offset.Zero

                fun normalized(point: Offset): Offset = Offset(
                    x = (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    y = (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                )

                fun cornerDistance(point: Offset, x: Float, y: Float): Float = hypot(
                    point.x - x * size.width,
                    point.y - y * size.height,
                )

                detectDragGestures(
                    onDragStart = { rawPoint ->
                        startPoint = normalized(rawPoint)
                        startRegion = currentRegion
                        dragMode = when {
                            cornerDistance(rawPoint, startRegion.left, startRegion.top) <= handleRadius -> CropDragMode.TOP_LEFT
                            cornerDistance(rawPoint, startRegion.right, startRegion.top) <= handleRadius -> CropDragMode.TOP_RIGHT
                            cornerDistance(rawPoint, startRegion.left, startRegion.bottom) <= handleRadius -> CropDragMode.BOTTOM_LEFT
                            cornerDistance(rawPoint, startRegion.right, startRegion.bottom) <= handleRadius -> CropDragMode.BOTTOM_RIGHT
                            startRegion.contains(startPoint.x, startPoint.y) -> CropDragMode.MOVE
                            else -> CropDragMode.NEW
                        }
                        anchor = when (dragMode) {
                            CropDragMode.TOP_LEFT -> Offset(startRegion.right, startRegion.bottom)
                            CropDragMode.TOP_RIGHT -> Offset(startRegion.left, startRegion.bottom)
                            CropDragMode.BOTTOM_LEFT -> Offset(startRegion.right, startRegion.top)
                            CropDragMode.BOTTOM_RIGHT -> Offset(startRegion.left, startRegion.top)
                            CropDragMode.NEW -> startPoint
                            CropDragMode.MOVE -> Offset.Zero
                        }
                    },
                    onDrag = { change, _ ->
                        val point = normalized(change.position)
                        val updated = if (dragMode == CropDragMode.MOVE) {
                            startRegion.moveBy(point.x - startPoint.x, point.y - startPoint.y)
                        } else {
                            CropRegion.fromAnchor(
                                anchorX = anchor.x,
                                anchorY = anchor.y,
                                currentX = point.x,
                                currentY = point.y,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                targetAspect = targetAspect,
                            )
                        }
                        currentOnRegionChange(updated)
                        change.consume()
                    },
                )
            },
    ) {
        val safe = region.normalized()
        val left = safe.left * size.width
        val top = safe.top * size.height
        val right = safe.right * size.width
        val bottom = safe.bottom * size.height
        val cropSize = Size(right - left, bottom - top)
        val shade = Color.Black.copy(alpha = 0.56f)

        drawRect(shade, size = Size(size.width, top))
        drawRect(shade, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(shade, topLeft = Offset(0f, top), size = Size(left, cropSize.height))
        drawRect(shade, topLeft = Offset(right, top), size = Size(size.width - right, cropSize.height))
        drawRect(Color.White, topLeft = Offset(left, top), size = cropSize, style = Stroke(width = 3f))

        val guide = Color.White.copy(alpha = 0.58f)
        repeat(2) { index ->
            val fraction = (index + 1) / 3f
            drawLine(guide, Offset(left + cropSize.width * fraction, top), Offset(left + cropSize.width * fraction, bottom), 1f)
            drawLine(guide, Offset(left, top + cropSize.height * fraction), Offset(right, top + cropSize.height * fraction), 1f)
        }
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(left, bottom),
            Offset(right, bottom),
        ).forEach { point ->
            drawCircle(Color.White, handleDrawRadius, point)
            drawCircle(Color(0xFF007A72), handleDrawRadius * 0.58f, point)
        }
    }
}
