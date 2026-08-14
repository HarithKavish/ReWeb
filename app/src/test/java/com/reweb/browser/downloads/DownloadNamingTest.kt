package com.reweb.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamingTest {

    @Test
    fun `content-disposition filename wins over the URL`() {
        val name = DownloadNaming.resolve(
            url = "https://example.com/download.php?id=42",
            contentDisposition = "attachment; filename=\"report.pdf\"",
            mimeType = "application/pdf"
        )
        assertEquals("report.pdf", name)
    }

    @Test
    fun `RFC 5987 encoded filenames are decoded`() {
        val name = DownloadNaming.resolve(
            url = "https://example.com/x",
            contentDisposition = "attachment; filename*=UTF-8''caf%C3%A9%20menu.pdf",
            mimeType = "application/pdf"
        )
        assertEquals("café menu.pdf", name)
    }

    @Test
    fun `unquoted filenames are accepted`() {
        assertEquals("data.csv", DownloadNaming.parseContentDisposition("attachment; filename=data.csv"))
    }

    @Test
    fun `missing content-disposition falls back to the URL path`() {
        val name = DownloadNaming.resolve(
            url = "https://example.com/files/annual-report.pdf?v=2",
            contentDisposition = null,
            mimeType = "application/pdf"
        )
        assertEquals("annual-report.pdf", name)
    }

    // --- The security-relevant part ---

    @Test
    fun `path traversal in a header collapses to a bare filename`() {
        // A server controls this header; without sanitising it, a download could
        // be written outside the downloads directory.
        assertEquals("passwd", DownloadNaming.sanitize("../../../../etc/passwd"))
        assertEquals("evil.sh", DownloadNaming.sanitize("/etc/init.d/evil.sh"))
        assertEquals("evil.exe", DownloadNaming.sanitize("..\\..\\windows\\system32\\evil.exe"))
    }

    @Test
    fun `traversal via content-disposition is neutralised end to end`() {
        val name = DownloadNaming.resolve(
            url = "https://example.com/x",
            contentDisposition = "attachment; filename=\"../../secret.txt\"",
            mimeType = "text/plain"
        )
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertEquals("secret.txt", name)
    }

    @Test
    fun `reserved characters are replaced, not dropped`() {
        assertEquals("a_b_c_d.txt", DownloadNaming.sanitize("a:b*c?d.txt"))
        assertEquals("q_1_2.json", DownloadNaming.sanitize("q\"1<2.json"))
    }

    @Test
    fun `leading dots cannot create a hidden file`() {
        assertEquals("bashrc", DownloadNaming.sanitize(".bashrc"))
        assertEquals("env", DownloadNaming.sanitize("...env"))
    }

    @Test
    fun `control characters are stripped`() {
        // A header can carry bytes that are not legal in a filename.
        val withBell = "rep" + 0x07.toChar() + "ort.pdf"
        assertEquals("report.pdf", DownloadNaming.sanitize(withBell))

        val withNul = "a" + 0x00.toChar() + "b.txt"
        assertEquals("ab.txt", DownloadNaming.sanitize(withNul))

        val withDelete = "x" + 0x7F.toChar() + "y.txt"
        assertEquals("xy.txt", DownloadNaming.sanitize(withDelete))
    }

    @Test
    fun `windows reserved device names are defused`() {
        assertEquals("download_con.txt", DownloadNaming.sanitize("con.txt"))
        assertEquals("download_NUL", DownloadNaming.sanitize("NUL"))
    }

    @Test
    fun `empty or all-stripped names fall back to a default`() {
        assertEquals("download", DownloadNaming.sanitize(""))
        assertEquals("download", DownloadNaming.sanitize("   "))
        assertEquals("download", DownloadNaming.sanitize("///"))
        assertEquals("download", DownloadNaming.sanitize("..."))
    }

    @Test
    fun `overlong names are truncated but keep their extension`() {
        val long = "a".repeat(400) + ".pdf"
        val sanitized = DownloadNaming.sanitize(long)
        assertTrue("Expected <= 127 chars, was ${sanitized.length}", sanitized.length <= 127)
        assertTrue(sanitized.endsWith(".pdf"))
    }

    // --- Extensions ---

    @Test
    fun `an extension is derived from the MIME type when the name has none`() {
        assertEquals(
            "invoice.pdf",
            DownloadNaming.ensureExtension("invoice", "application/pdf", null)
        )
        assertEquals(
            "archive.zip",
            DownloadNaming.ensureExtension("archive", "application/zip; charset=binary", null)
        )
    }

    @Test
    fun `an existing extension is never overwritten`() {
        assertEquals(
            "notes.txt",
            DownloadNaming.ensureExtension("notes.txt", "application/pdf", null)
        )
    }

    @Test
    fun `octet-stream adds no extension because it says nothing`() {
        assertEquals(
            "blob",
            DownloadNaming.ensureExtension("blob", "application/octet-stream", null)
        )
    }

    @Test
    fun `apk downloads keep their extension so the installer can be offered`() {
        val name = DownloadNaming.resolve(
            url = "https://example.com/app",
            contentDisposition = null,
            mimeType = "application/vnd.android.package-archive"
        )
        assertEquals("app.apk", name)
    }

    // --- Uniqueness ---

    @Test
    fun `uniquify appends a counter before the extension`() {
        val taken = setOf("file.pdf", "file (1).pdf")
        assertEquals("file (2).pdf", DownloadNaming.uniquify("file.pdf") { it in taken })
    }

    @Test
    fun `uniquify leaves a free name untouched`() {
        assertEquals("file.pdf", DownloadNaming.uniquify("file.pdf") { false })
    }

    @Test
    fun `uniquify handles extensionless names`() {
        val taken = setOf("README")
        assertEquals("README (1)", DownloadNaming.uniquify("README") { it in taken })
    }

    @Test
    fun `parseContentDisposition returns null when there is no filename`() {
        assertNull(DownloadNaming.parseContentDisposition("inline"))
        assertNull(DownloadNaming.parseContentDisposition(""))
    }
}
