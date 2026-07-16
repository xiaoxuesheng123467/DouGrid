package com.qiao.dougrid.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class CropRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun normalized(): CropRegion {
        val safeLeft = min(left, right).coerceIn(0f, 1f)
        val safeRight = max(left, right).coerceIn(0f, 1f)
        val safeTop = min(top, bottom).coerceIn(0f, 1f)
        val safeBottom = max(top, bottom).coerceIn(0f, 1f)
        return if (safeRight > safeLeft && safeBottom > safeTop) {
            CropRegion(safeLeft, safeTop, safeRight, safeBottom)
        } else {
            FULL
        }
    }

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun moveBy(deltaX: Float, deltaY: Float): CropRegion {
        val safe = normalized()
        val nextLeft = (safe.left + deltaX).coerceIn(0f, 1f - safe.width)
        val nextTop = (safe.top + deltaY).coerceIn(0f, 1f - safe.height)
        return CropRegion(nextLeft, nextTop, nextLeft + safe.width, nextTop + safe.height)
    }

    fun withAspectAroundCenter(sourceWidth: Int, sourceHeight: Int, targetAspect: Float): CropRegion {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetAspect <= 0f) return normalized()
        val safe = normalized()
        val centerX = (safe.left + safe.right) * sourceWidth / 2f
        val centerY = (safe.top + safe.bottom) * sourceHeight / 2f
        val selectedArea = safe.width * sourceWidth * safe.height * sourceHeight
        var widthPixels = sqrt(selectedArea * targetAspect).coerceAtLeast(1f)
        var heightPixels = (widthPixels / targetAspect).coerceAtLeast(1f)
        val availableWidth = 2f * min(centerX, sourceWidth - centerX).coerceAtLeast(0.5f)
        val availableHeight = 2f * min(centerY, sourceHeight - centerY).coerceAtLeast(0.5f)
        val fitScale = min(1f, min(availableWidth / widthPixels, availableHeight / heightPixels))
        widthPixels *= fitScale
        heightPixels *= fitScale
        return CropRegion(
            left = (centerX - widthPixels / 2f) / sourceWidth,
            top = (centerY - heightPixels / 2f) / sourceHeight,
            right = (centerX + widthPixels / 2f) / sourceWidth,
            bottom = (centerY + heightPixels / 2f) / sourceHeight,
        ).normalized()
    }

    companion object {
        val FULL = CropRegion(0f, 0f, 1f, 1f)

        fun forAspect(sourceWidth: Int, sourceHeight: Int, targetAspect: Float): CropRegion {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetAspect <= 0f) return FULL
            val sourceAspect = sourceWidth.toFloat() / sourceHeight
            return if (sourceAspect > targetAspect) {
                val width = targetAspect / sourceAspect
                val left = (1f - width) / 2f
                CropRegion(left, 0f, left + width, 1f)
            } else {
                val height = sourceAspect / targetAspect
                val top = (1f - height) / 2f
                CropRegion(0f, top, 1f, top + height)
            }
        }

        fun fromAnchor(
            anchorX: Float,
            anchorY: Float,
            currentX: Float,
            currentY: Float,
            sourceWidth: Int,
            sourceHeight: Int,
            targetAspect: Float,
            minimumPixels: Float = 32f,
        ): CropRegion {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetAspect <= 0f) return FULL
            val ax = anchorX.coerceIn(0f, 1f)
            val ay = anchorY.coerceIn(0f, 1f)
            val cx = currentX.coerceIn(0f, 1f)
            val cy = currentY.coerceIn(0f, 1f)
            val directionX = if (cx >= ax) 1f else -1f
            val directionY = if (cy >= ay) 1f else -1f
            val availableWidth = (if (directionX > 0f) 1f - ax else ax) * sourceWidth
            val availableHeight = (if (directionY > 0f) 1f - ay else ay) * sourceHeight
            val maximumWidth = min(availableWidth, availableHeight * targetAspect).coerceAtLeast(1f)
            val requestedWidth = max(
                abs(cx - ax) * sourceWidth,
                abs(cy - ay) * sourceHeight * targetAspect,
            )
            val minimumWidth = min(minimumPixels, maximumWidth)
            val widthPixels = requestedWidth.coerceIn(minimumWidth, maximumWidth)
            val heightPixels = widthPixels / targetAspect
            val endX = ax + directionX * widthPixels / sourceWidth
            val endY = ay + directionY * heightPixels / sourceHeight
            return CropRegion(
                left = min(ax, endX),
                top = min(ay, endY),
                right = max(ax, endX),
                bottom = max(ay, endY),
            ).normalized()
        }
    }
}
