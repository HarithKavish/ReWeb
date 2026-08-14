package com.reweb.browser.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import com.reweb.browser.engine.FileChooserRequest
import com.reweb.browser.engine.FileChooserResponse
import java.io.File

/**
 * Serves `<input type="file">` using the system picker.
 *
 * ReWeb asks for no storage permission to do this: `ACTION_GET_CONTENT` and
 * `ACTION_OPEN_DOCUMENT` return a `content://` URI that the WebView can read
 * through the grant attached to the result, which is the whole point of the
 * Storage Access Framework. Requesting READ_EXTERNAL_STORAGE for uploads would
 * be asking for far more access than the feature needs.
 *
 * Camera capture is offered alongside the picker when the page asks for it and a
 * camera app exists. The photo is written to the app's own cache directory and
 * shared back through ReWeb's FileProvider.
 */
class FileChooserCoordinator(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>
) {

    private var pendingResponse: FileChooserResponse? = null
    private var pendingCaptureUri: Uri? = null

    /** Returns false if no picker could be launched, so the page is told immediately. */
    fun start(request: FileChooserRequest, response: FileChooserResponse): Boolean {
        // A second request supersedes the first; the page behind it is gone.
        pendingResponse?.cancel()
        pendingResponse = response
        pendingCaptureUri = null

        val contentIntent = buildContentIntent(request)
        val captureIntent = if (request.preferCapture || acceptsImages(request)) buildCaptureIntent() else null

        val chooser = Intent.createChooser(contentIntent, null).apply {
            if (captureIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
            }
        }

        return try {
            launcher.launch(chooser)
            true
        } catch (_: Exception) {
            // No picker at all — a real state on stripped-down legacy ROMs.
            pendingResponse = null
            pendingCaptureUri = null
            response.cancel()
            false
        }
    }

    fun onResult(result: ActivityResult) {
        val response = pendingResponse ?: return
        pendingResponse = null
        val captureUri = pendingCaptureUri
        pendingCaptureUri = null

        if (result.resultCode != Activity.RESULT_OK) {
            deleteStagedCapture(captureUri)
            response.cancel()
            return
        }

        val uris = extractUris(result.data, captureUri)
        if (uris.isEmpty()) {
            deleteStagedCapture(captureUri)
            response.cancel()
        } else {
            response.submit(uris)
        }
    }

    /** Answers any outstanding request, so a page is never left waiting forever. */
    fun cancelPending() {
        pendingResponse?.cancel()
        pendingResponse = null
        deleteStagedCapture(pendingCaptureUri)
        pendingCaptureUri = null
    }

    private fun extractUris(data: Intent?, captureUri: Uri?): List<Uri> {
        // A camera capture returns a null data Intent; the file is where we put it.
        if (data == null || (data.data == null && data.clipData == null)) {
            return listOfNotNull(captureUri?.takeIf { stagedFileHasContent(it) })
        }
        data.clipData?.let { clip ->
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(data.data)
    }

    private fun buildContentIntent(request: FileChooserRequest): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypeFor(request)
            val explicitTypes = request.acceptTypes.mapNotNull { normalizeAcceptType(it) }
            if (explicitTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, explicitTypes.toTypedArray())
            }
            if (request.allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    private fun buildCaptureIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(context.packageManager) == null) return null
        val uri = createCaptureUri() ?: return null
        pendingCaptureUri = uri
        return intent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun createCaptureUri(): Uri? = runCatching {
        val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun stagedFileHasContent(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    }.getOrDefault(false)

    private fun deleteStagedCapture(uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        private const val UPLOAD_DIR = "uploads"

        /** Removes stale camera captures. Called when the browser starts. */
        fun clearStagedUploads(context: Context) {
            runCatching { File(context.cacheDir, UPLOAD_DIR).deleteRecursively() }
        }

        /**
         * Collapses the page's accept list into the single MIME type the picker
         * Intent takes. Mixed types widen to their common prefix (for example
         * "image" plus "image" becomes "image" slash wildcard), and types with
         * nothing in common widen to the match-anything wildcard.
         */
        fun mimeTypeFor(request: FileChooserRequest): String {
            val types = request.acceptTypes.mapNotNull { normalizeAcceptType(it) }.distinct()
            if (types.isEmpty()) return "*/*"
            if (types.size == 1) return types.first()
            val prefixes = types.map { it.substringBefore('/') }.distinct()
            return if (prefixes.size == 1) "${prefixes.first()}/*" else "*/*"
        }

        /**
         * `accept` may carry MIME types or file extensions (".pdf"). Extensions are
         * mapped through the platform's MIME table where possible.
         */
        fun normalizeAcceptType(raw: String): String? {
            val value = raw.trim().lowercase()
            if (value.isEmpty()) return null
            if (value.startsWith(".")) {
                return android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(value.removePrefix("."))
            }
            return if (value.contains('/')) value else null
        }

        fun acceptsImages(request: FileChooserRequest): Boolean =
            request.acceptTypes.any { it.trim().lowercase().startsWith("image/") }

        /** Whether a camera app exists at all, for deciding what to offer. */
        fun hasCamera(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}
