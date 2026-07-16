package com.qiao.dougrid.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LeasedResourceTest {
    @Test
    fun retirementWaitsUntilTheLastLeaseCloses() {
        val resource = Any()
        var disposeCount = 0
        val owner = LeasedResource(resource) { disposeCount += 1 }
        val first = requireNotNull(owner.acquire())
        val second = requireNotNull(owner.acquire())

        owner.retire()

        assertEquals(0, disposeCount)
        assertNull(owner.acquire())
        assertSame(resource, first.value)
        first.close()
        assertEquals(0, disposeCount)
        second.close()
        assertEquals(1, disposeCount)

        second.close()
        owner.retire()
        assertEquals(1, disposeCount)
    }

    @Test
    fun resourceWithoutLeasesDisposesImmediatelyWhenRetired() {
        var disposed: String? = null
        val owner = LeasedResource("preview") { disposed = it }

        owner.retire()

        assertEquals("preview", disposed)
        assertNull(owner.acquire())
    }
}
