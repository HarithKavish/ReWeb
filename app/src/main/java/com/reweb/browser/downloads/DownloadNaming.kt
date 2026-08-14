package com.reweb.browser.downloads

import java.net.URLDecoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Works out what to call a downloaded file.
 *
 * This is deliberately separate from anything Android so it can be tested
 * directly. The security-relevant part is [sanitize]: a server controls both the
 * URL and the Content-Disposition header, so a filename arriving from either is
 * untrusted input that must never be able to contain a path.
 */
object DownloadNaming {

    private const val DEFAULT_NAME = "download"
    private const val MAX_NAME_LENGTH = 127

    /** RFC 5987 `filename*=UTF-8''percent%20encoded.ext` */
    private val EXTENDED_FILENAME: Pattern = Pattern.compile(
        "filename\\*\\s*=\\s*([^']*)'([^']*)'([^;]+)",
        Pattern.CASE_INSENSITIVE
    )

    /** Plain `filename="quoted.ext"` or `filename=bare.ext` */
    private val PLAIN_FILENAME: Pattern = Pattern.compile(
        "filename\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))",
        Pattern.CASE_INSENSITIVE
    )

    private val WINDOWS_RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    /**
     * Chooses a filename, preferring Content-Disposition, then the URL path, then
     * a generic name. [extensionForMimeType] supplies the platform's MIME lookup
     * so this object stays free of Android imports; pass null to skip it.
     */
    fun resolve(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        extensionForMimeType: ((String) -> String?)? = null
    ): String {
        val fromHeader = contentDisposition?.let { parseContentDisposition(it) }
        val candidate = fromHeader?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(url)
        val sanitized = sanitize(candidate)
        return ensureExtension(sanitized, mimeType, extensionForMimeType)
    }

    /** Extracts a filename from a Content-Disposition header, or null. */
    fun parseContentDisposition(header: String): String? {
        val extended = EXTENDED_FILENAME.matcher(header)
        if (extended.find()) {
            val charset = extended.group(1)?.ifBlank { "UTF-8" } ?: "UTF-8"
            val value = extended.group(3)?.trim()
            if (!value.isNullOrBlank()) {
                val decoded = runCatching {
                    URLDecoder.decode(value, if (charset.equals("UTF-8", true)) "UTF-8" else charset)
                }.getOrDefault(value)
                if (decoded.isNotBlank()) return decoded
            }
        }
        val plain = PLAIN_FILENAME.matcher(header)
        if (plain.find()) {
            val value = (plain.group(1) ?: plain.group(2))?.trim()
            if (!value.isNullOrBlank()) {
                // Some servers percent-encode without declaring filename*.
                val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                return decoded
            }
        }
        return null
    }

    fun fileNameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val lastSegment = withoutQuery.trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
        return decoded.ifBlank { DEFAULT_NAME }
    }

    /**
     * Reduces an arbitrary string to a safe single path component.
     *
     * Strips directory separators (so `../../etc/passwd` collapses to `passwd`),
     * control and reserved characters, leading dots (no hidden files), and caps
     * the length while preserving the extension.
     */
    fun sanitize(rawName: String): String {
        // Take only the final component: this is what defeats traversal.
        val lastComponent = rawName
            .replace('\\', '/')
            .substringAfterLast('/')

        val cleaned = buildString {
            for (ch in lastComponent) {
                when {
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    ch in RESERVED_CHARACTERS -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trimStart('.').trim()

        if (cleaned.isBlank()) return DEFAULT_NAME

        val base = cleaned.substringBeforeLast('.', cleaned)
        val extension = cleaned.substringAfterLast('.', "")

        if (base.lowercase(Locale.US) in WINDOWS_RESERVED) {
            return if (extension.isBlank()) "${DEFAULT_NAME}_$base" else "${DEFAULT_NAME}_$base.$extension"
        }

        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

        val keepExtension = if (extension.length in 1..10) ".$extension" else ""
        val allowance = MAX_NAME_LENGTH - keepExtension.length
        return base.take(allowance) + keepExtension
    }

    /** Appends an extension derived from the MIME type when the name has none. */
    fun ensureExtension(
        name: String,
        mimeType: String?,
        extensionForMimeType: ((String) -> String?)?
    ): String {
        val existing = name.substringAfterLast('.', "")
        if (existing.isNotBlank() && existing.length <= 10) return name
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        if (mime.isNullOrBlank() || mime == "application/octet-stream") return name
        val extension = extensionForMimeType?.invoke(mime) ?: BUILT_IN_EXTENSIONS[mime]
        return if (extension.isNullOrBlank()) name else "$name.$extension"
    }

    /**
     * Ensures a name is unique within a directory by appending " (n)" before the
     * extension. [exists] reports whether a candidate is already taken.
     */
    fun uniquify(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        for (index in 1..MAX_UNIQUE_ATTEMPTS) {
            val candidate = "$base ($index)$suffix"
            if (!exists(candidate)) return candidate
        }
        return "$base-${System.currentTimeMillis()}$suffix"
    }

    private const val MAX_UNIQUE_ATTEMPTS = 999

    private val RESERVED_CHARACTERS = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000'
    )

    /** Fallback map used when no platform MimeTypeMap is available (unit tests). */
    private val BUILT_IN_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "application/json" to "json",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/csv" to "csv",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "ogg",
        "video/mp4" to "mp4",
        "video/webm" to "webm"
    )
}
