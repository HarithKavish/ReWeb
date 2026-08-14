package com.reweb.browser.downloads

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.reweb.browser.engine.DownloadRequest
import com.reweb.browser.settings.Settings
import java.io.File

/**
 * Runs downloads through the platform's [DownloadManager].
 *
 * Using the system service rather than a private HTTP stack means downloads
 * survive the app being killed, get retried across network changes, and appear
 * in the system's own notification and Downloads UI — all behaviour that would
 * otherwise need a foreground service, which is exactly what a low-memory device
 * cannot afford.
 */
class DownloadController(
    private val context: Context,
    private val settings: Settings,
    private val store: DownloadStore
) {

    sealed class Result {
        data class Started(val fileName: String) : Result()
        data class SavedLocally(val fileName: String) : Result()
        /** [reason] is a user-facing explanation, not an exception message. */
        data class Failed(val reason: FailureReason, val detail: String? = null) : Result()
    }

    enum class FailureReason {
        NEEDS_STORAGE_PERMISSION,
        UNSUPPORTED_SCHEME,
        BLOB_UNSUPPORTED,
        DOWNLOAD_MANAGER_DISABLED,
        STORAGE_UNAVAILABLE,
        UNKNOWN
    }

    /** Filename that would be used, for the pre-download confirmation dialog. */
    fun proposedFileName(request: DownloadRequest): String = DownloadNaming.resolve(
        url = request.url,
        contentDisposition = request.contentDisposition,
        mimeType = request.mimeType,
        extensionForMimeType = { mime -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) }
    )

    fun enqueue(request: DownloadRequest): Result {
        val fileName = proposedFileName(request)
        return when {
            request.url.startsWith("data:", ignoreCase = true) -> saveDataUri(request, fileName)
            request.url.startsWith("blob:", ignoreCase = true) ->
                // A blob: URL names an object that only exists inside the page's own
                // renderer. DownloadManager cannot fetch it, and reading it back out
                // would require exposing another JavaScript bridge to every site.
                // ReWeb refuses rather than pretending; see COMPATIBILITY.md.
                Result.Failed(FailureReason.BLOB_UNSUPPORTED)
            request.url.startsWith("http://", true) || request.url.startsWith("https://", true) ->
                enqueueHttp(request, fileName)
            else -> Result.Failed(FailureReason.UNSUPPORTED_SCHEME)
        }
    }

    private fun enqueueHttp(request: DownloadRequest, fileName: String): Result {
        if (needsLegacyStoragePermission()) return Result.Failed(FailureReason.NEEDS_STORAGE_PERMISSION)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Result.Failed(FailureReason.DOWNLOAD_MANAGER_DISABLED)

        val subdirectory = settings.downloadSubdirectory
        val relativePath = if (subdirectory.isBlank()) fileName else "$subdirectory/$fileName"

        return try {
            val downloadRequest = DownloadManager.Request(Uri.parse(request.url)).apply {
                setTitle(fileName)
                setDescription(context.packageName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relativePath)
                setAllowedOverRoaming(false)
                request.mimeType?.let { setMimeType(it) }

                // Session cookies must be forwarded or authenticated downloads come
                // back as a login page. Cookies are attached here and never logged.
                val cookie = runCatching { CookieManager.getInstance().getCookie(request.url) }.getOrNull()
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                request.userAgent?.let { addRequestHeader("User-Agent", it) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setRequiresCharging(false)
                }
            }

            val systemId = manager.enqueue(downloadRequest)
            store.insert(
                systemId = systemId,
                fileName = fileName,
                url = request.url,
                mimeType = request.mimeType,
                totalBytes = request.contentLengthBytes
            )
            Result.Started(fileName)
        } catch (e: IllegalStateException) {
            // Thrown when the external storage directory cannot be created.
            Result.Failed(FailureReason.STORAGE_UNAVAILABLE, e.message)
        } catch (e: SecurityException) {
            Result.Failed(FailureReason.NEEDS_STORAGE_PERMISSION, e.message)
        } catch (e: IllegalArgumentException) {
            Result.Failed(FailureReason.UNSUPPORTED_SCHEME, e.message)
        }
    }

    /**
     * `data:` URIs are decoded in-process: DownloadManager rejects them, and they
     * are usually small artefacts a page generated (an exported file, a QR code).
     */
    private fun saveDataUri(request: DownloadRequest, fileName: String): Result {
        val payload = request.url.substringAfter(',', "")
        if (payload.isEmpty()) return Result.Failed(FailureReason.UNKNOWN)
        val header = request.url.substringBefore(',')
        val isBase64 = header.contains(";base64", ignoreCase = true)

        return try {
            val bytes = if (isBase64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray()
            }
            if (bytes.size > MAX_INLINE_DOWNLOAD_BYTES) {
                return Result.Failed(FailureReason.UNKNOWN)
            }
            val targetDir = downloadDirectory() ?: return Result.Failed(FailureReason.STORAGE_UNAVAILABLE)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return Result.Failed(FailureReason.STORAGE_UNAVAILABLE)
            }
            val unique = DownloadNaming.uniquify(fileName) { File(targetDir, it).exists() }
            val file = File(targetDir, unique)
            file.outputStream().use { it.write(bytes) }

            store.insert(
                systemId = -1,
                // The full data: URI is not stored; it can be many megabytes and
                // retrying it is meaningless once the page is gone.
                fileName = unique,
                url = "data:",
                mimeType = request.mimeType,
                totalBytes = bytes.size.toLong(),
                status = DownloadStatus.COMPLETE,
                localUri = file.absolutePath
            )
            Result.SavedLocally(unique)
        } catch (e: IllegalArgumentException) {
            Result.Failed(FailureReason.UNKNOWN, e.message)
        } catch (e: java.io.IOException) {
            Result.Failed(FailureReason.STORAGE_UNAVAILABLE, e.message)
        }
    }

    /**
     * Reconciles local records with the system service. Called when the downloads
     * screen opens rather than on a timer, so nothing runs in the background.
     */
    fun refreshStatuses() {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val records = store.all().filter {
            it.systemId >= 0 && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING)
        }
        if (records.isEmpty()) return

        val query = DownloadManager.Query().setFilterById(*records.map { it.systemId }.toLongArray())
        runCatching {
            manager.query(query)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                if (idIndex < 0 || statusIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val systemId = cursor.getLong(idIndex)
                    val status = when (cursor.getInt(statusIndex)) {
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                        else -> DownloadStatus.RUNNING
                    }
                    store.updateStatus(
                        systemId = systemId,
                        status = status,
                        localUri = if (uriIndex >= 0) cursor.getString(uriIndex) else null,
                        totalBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                    )
                }
            }
        }
    }

    /**
     * Opens a completed download in whichever app handles its type.
     *
     * APKs are deliberately not special-cased into an install flow: ReWeb hands
     * the file to the system installer like any other app would, and the user
     * confirms there. It never triggers an install by itself.
     */
    fun openDownload(activity: Activity, record: DownloadRecord): Boolean {
        val uri = resolveContentUri(record) ?: return false
        val mime = record.mimeType
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(record.fileName.substringAfterLast('.', ""))
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun resolveContentUri(record: DownloadRecord): Uri? {
        val local = record.localUri ?: return null
        return runCatching {
            if (local.startsWith("content://")) return Uri.parse(local)
            val file = if (local.startsWith("file://")) File(Uri.parse(local).path!!) else File(local)
            if (!file.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun downloadDirectory(): File? {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val subdirectory = settings.downloadSubdirectory
        return if (subdirectory.isBlank()) base else File(base, subdirectory)
    }

    /**
     * Writing into the public Downloads directory needs WRITE_EXTERNAL_STORAGE up
     * to API 28. From API 29 scoped storage grants DownloadManager access without
     * any permission at all.
     */
    fun needsLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** data: URIs are decoded into memory, so they are capped. */
        const val MAX_INLINE_DOWNLOAD_BYTES = 16 * 1024 * 1024
    }
}
