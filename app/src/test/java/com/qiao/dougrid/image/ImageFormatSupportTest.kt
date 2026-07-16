package com.qiao.dougrid.image

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageFormatSupportTest {
    @Test
    fun avifExplainsItsPlatformRequirementBeforeAndroid12() {
        val message = ImageFormatSupport.failureMessage("image/avif", sdkInt = 30)

        assertTrue(message.contains("Android 12"))
        assertTrue(message.contains("JPG"))
    }

    @Test
    fun vectorAndRawFormatsGetActionableConversionAdvice() {
        assertTrue(ImageFormatSupport.failureMessage("image/svg+xml", 36).contains("PNG"))
        assertTrue(ImageFormatSupport.failureMessage("image/x-adobe-dng", 36).contains("RAW"))
    }

    @Test
    fun supportedListTracksAvifBySdkLevel() {
        assertTrue(ImageFormatSupport.supportedTypes(30).contains("AVIF 需要 Android 12+"))
        assertTrue(ImageFormatSupport.supportedTypes(31).endsWith("AVIF"))
    }

    @Test
    fun filePickerWhitelistExcludesUnsupportedAndGatesAvif() {
        val android11 = ImageFormatSupport.supportedMimeTypes(30).toSet()
        val android12 = ImageFormatSupport.supportedMimeTypes(31).toSet()

        assertTrue("image/jpeg" in android11)
        assertTrue("image/heic" in android11)
        assertFalse("image/svg+xml" in android11)
        assertFalse("image/avif" in android11)
        assertTrue("image/avif" in android12)
    }
}
