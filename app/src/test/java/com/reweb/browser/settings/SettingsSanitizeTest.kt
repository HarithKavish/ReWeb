package com.reweb.browser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The download subdirectory is user input that gets joined onto the public
 * Downloads path, so it must not be able to escape it.
 */
class SettingsSanitizeTest {

    @Test
    fun `an ordinary folder name is kept`() {
        assertEquals("ReWeb", Settings.sanitizeSubdirectory("ReWeb"))
        assertEquals("My Files", Settings.sanitizeSubdirectory("My Files"))
        assertEquals("web-downloads", Settings.sanitizeSubdirectory("web-downloads"))
    }

    @Test
    fun `parent references are removed`() {
        val sanitized = Settings.sanitizeSubdirectory("../../../sdcard")
        assertFalse(sanitized.contains(".."))
        assertEquals("sdcard", sanitized)
    }

    @Test
    fun `absolute paths become relative`() {
        val sanitized = Settings.sanitizeSubdirectory("/data/data/com.reweb.browser")
        assertFalse(sanitized.startsWith("/"))
    }

    @Test
    fun `backslashes are treated as separators, not as name characters`() {
        val sanitized = Settings.sanitizeSubdirectory("..\\..\\Windows")
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains(".."))
        assertEquals("Windows", sanitized)
    }

    @Test
    fun `nested folders are allowed`() {
        assertEquals("ReWeb/pdfs", Settings.sanitizeSubdirectory("ReWeb/pdfs"))
    }

    @Test
    fun `blank input yields the Downloads root`() {
        assertEquals("", Settings.sanitizeSubdirectory(""))
        assertEquals("", Settings.sanitizeSubdirectory("   "))
        assertEquals("", Settings.sanitizeSubdirectory("../.."))
        assertEquals("", Settings.sanitizeSubdirectory("/"))
    }
}
