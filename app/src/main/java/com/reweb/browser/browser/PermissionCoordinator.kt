package com.reweb.browser.browser

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.reweb.browser.engine.WebPermissionKind
import com.reweb.browser.engine.WebPermissionRequest

/**
 * Bridges a page's permission request and Android's runtime permissions.
 *
 * Both gates have to pass, in this order:
 *
 *  1. The user approves the *site's* request in ReWeb's own prompt, which names
 *     the origin. Approving here is not remembered — a site must ask each time,
 *     because ReWeb has no per-origin permission store and silently granting on
 *     a later visit would be worse than asking again.
 *  2. Android grants the app the matching runtime permission. If the user
 *     refuses at this step, the site is denied rather than left waiting.
 *
 * Nothing is granted automatically. There is no path through this class that
 * calls [WebPermissionRequest.grant] without an explicit user tap.
 */
class PermissionCoordinator(
    private val activity: AppCompatActivity,
    private val permissionLauncher: ActivityResultLauncher<Array<String>>
) {

    private var pending: Pending? = null

    private data class Pending(
        val request: WebPermissionRequest,
        val approvedKinds: Set<WebPermissionKind>
    )

    /**
     * Called after the user approved the site prompt for [approvedKinds].
     * Requests any missing Android permissions, then answers the page.
     */
    fun fulfill(request: WebPermissionRequest, approvedKinds: Set<WebPermissionKind>) {
        if (approvedKinds.isEmpty()) {
            request.deny()
            return
        }

        val missing = approvedKinds
            .mapNotNull { androidPermissionFor(it) }
            .filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            .distinct()

        if (missing.isEmpty()) {
            request.grant(approvedKinds)
            return
        }

        // Only one page request can be outstanding; a second would have no way to
        // tell the results apart. Deny the older one rather than dropping it.
        pending?.request?.deny()
        pending = Pending(request, approvedKinds)
        permissionLauncher.launch(missing.toTypedArray())
    }

    /** Wired to the activity's permission-result callback. */
    fun onAndroidPermissionResult(results: Map<String, Boolean>) {
        val current = pending ?: return
        pending = null

        val granted = current.approvedKinds.filter { kind ->
            val permission = androidPermissionFor(kind)
            // Kinds with no Android counterpart (protected media, MIDI) are gated
            // by the site prompt alone.
            permission == null || results[permission] == true ||
                ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }.toSet()

        if (granted.isEmpty()) current.request.deny() else current.request.grant(granted)
    }

    fun cancelPending() {
        pending?.request?.deny()
        pending = null
    }

    companion object {
        fun androidPermissionFor(kind: WebPermissionKind): String? = when (kind) {
            WebPermissionKind.CAMERA -> Manifest.permission.CAMERA
            WebPermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            WebPermissionKind.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
            // Widevine and MIDI have no Android runtime permission; whether they
            // work at all is a platform capability, not a permission.
            WebPermissionKind.PROTECTED_MEDIA,
            WebPermissionKind.MIDI,
            WebPermissionKind.UNKNOWN -> null
        }
    }
}
