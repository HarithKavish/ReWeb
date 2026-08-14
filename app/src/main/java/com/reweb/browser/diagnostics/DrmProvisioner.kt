package com.reweb.browser.diagnostics

import android.media.MediaDrm
import android.os.Build
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Completes Widevine device provisioning.
 *
 * A Widevine device needs a certificate from Google before a licence server will
 * issue it any licences. That certificate is fetched once, stored by the platform
 * (as `/data/mediadrm/.../ay64.dat`), and normally obtained during setup. On a
 * device whose Play Services no longer work it can be missing entirely — and the
 * symptom is confusing, because the DRM stack looks healthy: `MediaDrm` opens
 * sessions, EME reports Widevine support, the CDM instantiates. Only the licence
 * request fails, in whatever way the streaming service happens to report it.
 *
 * This is repair, not circumvention. It performs exactly the exchange the
 * platform defines — [MediaDrm.getProvisionRequest] produces a request, that
 * request is POSTed to Google's provisioning URL, and the response is handed back
 * to [MediaDrm.provideProvisionResponse]. No DRM protection is weakened or
 * bypassed; the device ends up with the certificate it was always supposed to
 * have. If Google declines to certify it, that is the answer and it stands.
 */
object DrmProvisioner {

    sealed class Result {
        object AlreadyProvisioned : Result()
        object Provisioned : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Runs the provisioning exchange. Performs network I/O — call off the main thread. */
    fun provision(): Result {
        if (!runCatching { MediaDrm.isCryptoSchemeSupported(DrmCapabilities.WIDEVINE_UUID) }
                .getOrDefault(false)
        ) {
            return Result.Failed("This device has no Widevine support to provision")
        }

        var drm: MediaDrm? = null
        try {
            drm = MediaDrm(DrmCapabilities.WIDEVINE_UUID)

            val request = runCatching { drm.provisionRequest }.getOrNull()
                ?: return Result.Failed("The platform did not produce a provisioning request")

            // An empty request means the platform sees nothing to do.
            if (request.data.isEmpty() || request.defaultUrl.isNullOrBlank()) {
                return Result.AlreadyProvisioned
            }

            // The platform supplies the URL; the request bytes go in the "signedRequest"
            // parameter. This shape is defined by the provisioning protocol, not chosen here.
            val url = "${request.defaultUrl}&signedRequest=${String(request.data, Charsets.UTF_8)}"
            val response = post(url)
                ?: return Result.Failed("Could not reach the provisioning server")

            runCatching { drm.provideProvisionResponse(response) }
                .onFailure { return Result.Failed("The provisioning response was rejected: ${it.javaClass.simpleName}") }

            return Result.Provisioned
        } catch (e: Exception) {
            return Result.Failed("Provisioning could not start: ${e.javaClass.simpleName}")
        } finally {
            releaseQuietly(drm)
        }
    }

    private fun post(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            DataOutputStream(connection.outputStream).use { it.write(ByteArray(0)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun releaseQuietly(drm: MediaDrm?) {
        if (drm == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else drm.release()
        }
    }

    private const val TIMEOUT_MS = 20_000
}
