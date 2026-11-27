package com.phantom.checker.service

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509TrustManager

internal fun isValidDesktopFingerprint(value: String): Boolean =
    value.trim().matches(Regex("(?i)[0-9a-f]{64}"))

internal class DesktopCertificateTrustManager(expectedFingerprint: String) : X509TrustManager {
    private val expected = expectedFingerprint.trim().lowercase()
    val observedFingerprint = AtomicReference("")
    val fingerprintMismatch = AtomicBoolean(false)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull()
            ?: throw CertificateException("Server presented empty certificate chain")
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (!isValidDesktopFingerprint(expected) || actual != expected) {
            fingerprintMismatch.set(true)
            throw CertificateException("Desktop certificate fingerprint mismatch")
        }
        observedFingerprint.set(actual)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
