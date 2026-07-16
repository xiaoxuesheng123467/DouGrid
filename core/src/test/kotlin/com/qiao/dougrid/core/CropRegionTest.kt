package com.qiao.dougrid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropRegionTest {
    @Test
    fun maximumRegionMatchesTargetAspect() {
        val region = CropRegion.forAspect(sourceWidth = 400, sourceHeight = 300, targetAspect = 1f)

        assertEquals(0f, region.top)
        assertEquals(1f, region.bottom)
        assertEquals(0.125f, region.left)
        assertEquals(0.875f, region.right)
    }

    @Test
    fun dragSelectionPreservesAspectAndBounds() {
        val region = CropRegion.fromAnchor(
            anchorX = 0.9f,
            anchorY = 0.9f,
            currentX = 0.1f,
            currentY = 0.2f,
            sourceWidth = 400,
            sourceHeight = 300,
            targetAspect = 2f,
        )

        val pixelAspect = region.width * 400f / (region.height * 300f)
        assertTrue(region.left >= 0f && region.top >= 0f)
        assertTrue(region.right <= 1f && region.bottom <= 1f)
        assertEquals(2f, pixelAspect, absoluteTolerance = 0.001f)
    }

    @Test
    fun movingRegionStopsAtImageEdges() {
        val moved = CropRegion(0.2f, 0.2f, 0.6f, 0.6f).moveBy(0.8f, -0.5f)

        assertEquals(0.6f, moved.left, absoluteTolerance = 0.001f)
        assertEquals(0f, moved.top, absoluteTolerance = 0.001f)
        assertEquals(1f, moved.right, absoluteTolerance = 0.001f)
        assertEquals(0.4f, moved.bottom, absoluteTolerance = 0.001f)
    }

    @Test
    fun changingAspectPreservesSelectionCenterAndArea() {
        val source = CropRegion(0.2f, 0.25f, 0.6f, 0.75f)

        val adjusted = source.withAspectAroundCenter(
            sourceWidth = 1_000,
            sourceHeight = 500,
            targetAspect = 1f,
        )

        assertEquals(0.4f, (adjusted.left + adjusted.right) / 2f, absoluteTolerance = 0.0001f)
        assertEquals(0.5f, (adjusted.top + adjusted.bottom) / 2f, absoluteTolerance = 0.0001f)
        val pixelAspect = adjusted.width * 1_000f / (adjusted.height * 500f)
        assertEquals(1f, pixelAspect, absoluteTolerance = 0.0001f)
        val originalArea = source.width * 1_000f * source.height * 500f
        val adjustedArea = adjusted.width * 1_000f * adjusted.height * 500f
        assertEquals(originalArea, adjustedArea, absoluteTolerance = 0.1f)
    }

    @Test
    fun changingAspectNearEdgeKeepsRegionInsideImage() {
        val adjusted = CropRegion(0f, 0.2f, 0.2f, 0.8f)
            .withAspectAroundCenter(1_000, 1_000, targetAspect = 2f)

        assertTrue(adjusted.left >= 0f && adjusted.top >= 0f)
        assertTrue(adjusted.right <= 1f && adjusted.bottom <= 1f)
        assertEquals(0.1f, (adjusted.left + adjusted.right) / 2f, absoluteTolerance = 0.0001f)
        assertEquals(0.5f, (adjusted.top + adjusted.bottom) / 2f, absoluteTolerance = 0.0001f)
    }
}
