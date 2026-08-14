package com.reweb.browser.browser

import android.content.Context
import android.os.Build
import com.reweb.browser.R
import com.reweb.browser.engine.SslIssueKind
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Explains *why* a certificate failed on a legacy device, when the reason is
 * almost certainly the device rather than the site.
 *
 * The defining TLS problem on Android 7.0 and below is a frozen root store. These
 * ROMs shipped their CA list in 2015-2016 and it is part of the system image, so
 * it is never updated. They do not contain ISRG Root X1 — Let's Encrypt's root —
 * which was only added to Android in 7.1.1.
 *
 * For years this did not matter: Let's Encrypt cross-signed from DST Root CA X3,
 * which these devices do trust, and Android uniquely ignores the expiry date of a
 * trust anchor, so the chain kept validating even after DST Root CA X3 expired in
 * September 2021. That workaround has since ended, and Let's Encrypt now serves
 * the modern ISRG Root X1 chain by default.
 *
 * The consequence is stark and current: on an Android 7.0-or-older device, a
 * large share of the web — anything using Let's Encrypt, which includes Wikipedia
 * — now fails with "untrusted authority". Nothing is wrong with the site, the
 * connection, or the browser. Telling the user only "the certificate was not
 * trusted" would leave them with no way to understand or fix it.
 */
object CertificateAdvice {

    /**
     * ISRG Root X1 reached Android's trust store in 7.1.1 (API 25). Anything at or
     * below API 24 is missing it.
     */
    const val LAST_API_WITHOUT_MODERN_ROOTS = Build.VERSION_CODES.N

    /** Subject common name of the root whose absence causes this whole problem. */
    private const val MODERN_ROOT_CN = "ISRG Root X1"

    /**
     * Returns an extra explanation when the failure fits the frozen-trust-store
     * pattern, or null when the ordinary warning already says enough.
     *
     * Deliberately narrow on three axes: only an untrusted-authority failure, only
     * on an affected API level, and only while the root is genuinely missing. A
     * hostname mismatch or an expired leaf is a real problem with the site and must
     * not be explained away — and once the user has installed the root, telling
     * them to install it is worse than saying nothing.
     */
    fun outdatedTrustStoreHint(
        context: Context,
        kind: SslIssueKind,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): String? {
        if (kind != SslIssueKind.UNTRUSTED_AUTHORITY) return null
        if (!hasOutdatedTrustStore(sdkInt)) return null
        if (isModernRootTrusted()) return null
        return context.getString(R.string.ssl_outdated_trust_store)
    }

    /**
     * True when this device's *shipped* root store predates the current web's
     * roots. Says nothing about whether the user has since repaired it — use
     * [trustStoreStatus] for that.
     */
    fun hasOutdatedTrustStore(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt <= LAST_API_WITHOUT_MODERN_ROOTS

    enum class TrustStoreStatus {
        /** Ships with modern roots; nothing to do. */
        CURRENT,

        /** Predates ISRG Root X1 and the root is not present. Much of the web fails. */
        OUTDATED,

        /** Predated it, but the user has installed the missing root themselves. */
        REPAIRED
    }

    fun trustStoreStatus(sdkInt: Int = Build.VERSION.SDK_INT): TrustStoreStatus = when {
        !hasOutdatedTrustStore(sdkInt) -> TrustStoreStatus.CURRENT
        isModernRootTrusted() -> TrustStoreStatus.REPAIRED
        else -> TrustStoreStatus.OUTDATED
    }

    /**
     * Whether ISRG Root X1 is in the trust store this device would actually use.
     *
     * `AndroidCAStore` is the merged view of the system roots and any the user has
     * installed, which is exactly the set the WebView validates against, so this
     * detects a user-installed repair without needing to know how it was done.
     */
    fun isModernRootTrusted(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val certificate = keyStore.getCertificate(aliases.nextElement()) as? X509Certificate
                ?: continue
            if (certificate.subjectX500Principal.name.contains(MODERN_ROOT_CN, ignoreCase = true)) {
                return@runCatching true
            }
        }
        false
    }.getOrDefault(false)
}
