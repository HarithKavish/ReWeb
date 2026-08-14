package com.reweb.browser.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reweb.browser.engine.SslIssueKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frozen-trust-store explanation must be shown when it is the real cause and
 * withheld when it is not. Explaining away a genuine certificate problem would be
 * worse than saying nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CertificateAdviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `untrusted authority on an old device gets the trust-store explanation`() {
        // Android 5.1.1 - the device this was found on.
        assertNotNull(
            CertificateAdvice.outdatedTrustStoreHint(
                context, SslIssueKind.UNTRUSTED_AUTHORITY, sdkInt = 22
            )
        )
        // Android 7.0 is the last release missing ISRG Root X1.
        assertNotNull(
            CertificateAdvice.outdatedTrustStoreHint(
                context, SslIssueKind.UNTRUSTED_AUTHORITY, sdkInt = 24
            )
        )
    }

    @Test
    fun `newer devices do not get it, because their root store is fine`() {
        // ISRG Root X1 landed in Android 7.1.1 (API 25).
        assertNull(
            CertificateAdvice.outdatedTrustStoreHint(
                context, SslIssueKind.UNTRUSTED_AUTHORITY, sdkInt = 25
            )
        )
        assertNull(
            CertificateAdvice.outdatedTrustStoreHint(
                context, SslIssueKind.UNTRUSTED_AUTHORITY, sdkInt = 34
            )
        )
    }

    @Test
    fun `other certificate failures are never explained away`() {
        // A hostname mismatch or an expired leaf on an old device is a real
        // problem with the site. Blaming the trust store would be misleading.
        for (kind in listOf(
            SslIssueKind.HOSTNAME_MISMATCH,
            SslIssueKind.EXPIRED,
            SslIssueKind.NOT_YET_VALID,
            SslIssueKind.INVALID_DATE,
            SslIssueKind.UNKNOWN
        )) {
            assertNull(
                "$kind must not be attributed to the trust store",
                CertificateAdvice.outdatedTrustStoreHint(context, kind, sdkInt = 22)
            )
        }
    }

    @Test
    fun `device trust store classification matches the API boundary`() {
        assertTrue(CertificateAdvice.deviceHasOutdatedTrustStore(21))
        assertTrue(CertificateAdvice.deviceHasOutdatedTrustStore(22))
        assertTrue(CertificateAdvice.deviceHasOutdatedTrustStore(24))
        assertFalse(CertificateAdvice.deviceHasOutdatedTrustStore(25))
        assertFalse(CertificateAdvice.deviceHasOutdatedTrustStore(34))
    }
}
