package com.reweb.browser.diagnostics

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.os.Build
import java.util.UUID

/**
 * What this device's DRM stack can do, asked of the platform directly.
 *
 * This exists because three different things get confused with one another, and
 * confusing them produces confidently wrong answers:
 *
 *  1. **The device has a Widevine library.** A file on disk. Proves nothing.
 *  2. **The platform can use it.** `MediaDrm` can open a session — which requires
 *     the device to have been *provisioned* against Google's servers.
 *  3. **The WebView can use it.** Separate bridge, separate answer, and the only
 *     one that decides whether a web page can play protected media.
 *
 * [CompatibilityTest] answers (3) from inside the page. This class answers (1) and
 * (2) from outside it, so the three can be told apart. A device that passes (2)
 * but fails (3) has a WebView limitation; one that fails (2) with
 * [needsProvisioning] set has something an app can actually repair.
 */
data class DrmCapabilities(
    /** The platform recognises the Widevine crypto scheme at all. */
    val schemeSupported: Boolean,

    /** "L1" (hardware-backed) or "L3" (software), or null if it cannot be read. */
    val securityLevel: String?,

    val vendor: String?,
    val version: String?,
    val description: String?,

    /** A DRM session was opened successfully — the strongest positive signal. */
    val sessionOpened: Boolean,

    /**
     * The device has Widevine but has never completed provisioning.
     *
     * Reported, but deliberately not acted on. ReWeb briefly shipped a repair
     * that performed the provisioning handshake; on the one device it ran
     * against, the licence failure afterwards was worse than before, and
     * MediaDrm offers no way to undo it. Writing to a device's DRM state on a
     * hunch is not worth the risk of leaving it in a state the owner cannot
     * recover, so this is now information only.
     */
    val needsProvisioning: Boolean,

    /** Why the check failed, when it did. Never shown as a bare exception. */
    val failure: String?
) {
    val isUsableByPlatform: Boolean get() = schemeSupported && sessionOpened

    companion object {
        /** The Widevine key system UUID, as registered with the DASH-IF. */
        val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        fun read(): DrmCapabilities {
            val supported = runCatching { MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID) }
                .getOrDefault(false)
            if (!supported) {
                return DrmCapabilities(
                    schemeSupported = false,
                    securityLevel = null,
                    vendor = null,
                    version = null,
                    description = null,
                    sessionOpened = false,
                    needsProvisioning = false,
                    failure = "The platform does not recognise the Widevine crypto scheme"
                )
            }

            var drm: MediaDrm? = null
            try {
                drm = MediaDrm(WIDEVINE_UUID)
                val level = property(drm, "securityLevel")
                val vendor = property(drm, "vendor")
                val version = property(drm, "version")
                val description = property(drm, "description")

                // Opening a session is the real test. Everything above can succeed
                // on a device that still cannot decrypt anything.
                var opened = false
                var unprovisioned = false
                var failure: String? = null
                try {
                    val session = drm.openSession()
                    opened = true
                    runCatching { drm.closeSession(session) }
                } catch (_: NotProvisionedException) {
                    unprovisioned = true
                    failure = "Widevine is present but this device has never been provisioned"
                } catch (e: Exception) {
                    failure = "Could not open a DRM session: ${e.javaClass.simpleName}"
                }

                return DrmCapabilities(
                    schemeSupported = true,
                    securityLevel = level,
                    vendor = vendor,
                    version = version,
                    description = description,
                    sessionOpened = opened,
                    needsProvisioning = unprovisioned,
                    failure = failure
                )
            } catch (e: Exception) {
                return DrmCapabilities(
                    schemeSupported = true,
                    securityLevel = null,
                    vendor = null,
                    version = null,
                    description = null,
                    sessionOpened = false,
                    needsProvisioning = false,
                    failure = "MediaDrm could not be created: ${e.javaClass.simpleName}"
                )
            } finally {
                releaseQuietly(drm)
            }
        }

        private fun property(drm: MediaDrm, name: String): String? =
            runCatching { drm.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }

        @Suppress("DEPRECATION")
        private fun releaseQuietly(drm: MediaDrm?) {
            if (drm == null) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else drm.release()
            }
        }
    }
}
