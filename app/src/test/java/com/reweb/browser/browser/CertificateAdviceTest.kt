package com.reweb.browser.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reweb.browser.engine.SslIssueKind
import org.junit.Assert.assertEquals
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
    fun `shipped trust store classification matches the API boundary`() {
        // ISRG Root X1 reached Android in 7.1.1 (API 25).
        assertTrue(CertificateAdvice.hasOutdatedTrustStore(21))
        assertTrue(CertificateAdvice.hasOutdatedTrustStore(22))
        assertTrue(CertificateAdvice.hasOutdatedTrustStore(24))
        assertFalse(CertificateAdvice.hasOutdatedTrustStore(25))
        assertFalse(CertificateAdvice.hasOutdatedTrustStore(34))
    }

    @Test
    fun `a modern device is reported as current regardless of the store contents`() {
        assertEquals(
            CertificateAdvice.TrustStoreStatus.CURRENT,
            CertificateAdvice.trustStoreStatus(sdkInt = 34)
        )
    }

    @Test
    fun `an affected device reports outdated or repaired, never current`() {
        // Which of the two depends on whether the host running the test happens to
        // trust ISRG Root X1; both are correct answers, CURRENT never is.
        val status = CertificateAdvice.trustStoreStatus(sdkInt = 22)
        assertTrue(
            "expected OUTDATED or REPAIRED, got $status",
            status == CertificateAdvice.TrustStoreStatus.OUTDATED ||
                status == CertificateAdvice.TrustStoreStatus.REPAIRED
        )
    }
}
