package com.phantom.checker.service

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCertificateTrustManagerTest {
    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(CERTIFICATE.byteInputStream()) as X509Certificate

    @Test
    fun acceptsMatchingFullFingerprint() {
        val manager = DesktopCertificateTrustManager("  ${FINGERPRINT.uppercase()}  ")
        manager.checkServerTrusted(arrayOf(certificate), "RSA")
        assertEquals(FINGERPRINT, manager.observedFingerprint.get())
        assertFalse(manager.fingerprintMismatch.get())
    }

    @Test
    fun rejectsMissingMalformedAndMismatchedFingerprints() {
        for (fingerprint in listOf("", FINGERPRINT.take(8), "g".repeat(64), "0".repeat(64))) {
            val manager = DesktopCertificateTrustManager(fingerprint)
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(arrayOf(certificate), "RSA")
            }
            assertTrue(manager.fingerprintMismatch.get())
            assertEquals("", manager.observedFingerprint.get())
        }
    }

    @Test
    fun rejectsEmptyCertificateChain() {
        val manager = DesktopCertificateTrustManager(FINGERPRINT)
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(null, "RSA") }
        assertEquals("", manager.observedFingerprint.get())
    }

    @Test
    fun validatesManualFingerprint() {
        assertTrue(isValidDesktopFingerprint(" ${FINGERPRINT.uppercase()} "))
        assertFalse(isValidDesktopFingerprint(""))
        assertFalse(isValidDesktopFingerprint(FINGERPRINT.take(8)))
        assertFalse(isValidDesktopFingerprint("g".repeat(64)))
        assertFalse(isValidDesktopFingerprint(FINGERPRINT + "0"))
    }

    companion object {
        private const val FINGERPRINT = "db0f562cc5c306377a0055b696385542a50f7926f11b9c69aeb2891f62a1b275"
        private const val CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIDDzCCAfegAwIBAgIUIIAEnOwBmJol2D7z13E91zpSY8IwDQYJKoZIhvcNAQEL
BQAwFzEVMBMGA1UEAwwMUGFpcmluZyBUZXN0MB4XDTI2MTEyNzEzMzg0MloXDTI2
MTEyODEzMzg0MlowFzEVMBMGA1UEAwwMUGFpcmluZyBUZXN0MIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxiK2ujZvla4AEZ158EYD4IR4//nRQkWo82+o
1nivvmAsQSWbzCqUjGfvwdtCjURoWobd4hcaez3c3Pp7F4CPFfPMAe6t7qSd9DYO
JDyDhRyTNu+Kwsc6VuBLejay67O6J5DjaNxBVTnDzGR62fONDBDpm6vf2ws2eq1k
TFutVQnZWBwPjuO7c4FvDZQNTbvRFFTdLQkD6fdq0pxd7RMETA0boI1gOOQ2z0YF
UqmYdrRIMtnjJkSP2JQ/4H3igMwUo9BWkNWZgFIOcazOY7kbQ+gtET1T4kLIoLKM
ZSRgWTCP1kM+jMa/BSEl6mul04SB+tDCkFcQH8pOghHDQk6eOwIDAQABo1MwUTAd
BgNVHQ4EFgQUFuOacv3Z/+Xn0PZ/mf5usNgiSHkwHwYDVR0jBBgwFoAUFuOacv3Z
/+Xn0PZ/mf5usNgiSHkwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOC
AQEAvQUO5SxwsQ334qpX6X45X7DtvJUbDBB0HT0hNF/WtMJorCWXtjt9+qAOe6Pr
IytvucVSGZ4CPyKMBquw4t4wuV+QToXvbGwGldjEbtl0RfTqtxy4cJ6o45N4wdPc
13lmUePRChpMM4SCzbtNPWLzstE2RXg5e/ZDOz69Kwkk6347r/zKuSHblBw+MD3S
8J+ujlLlf0ncNPq8AGUUXp1ntwqtRwy5ep19Cd4xoe2qo1AZlS9YTnuVN6M+VkK+
+BP6SnbasIpKYOUMrR6J0rNh3mRG1ycef/eRUs9eXqFFgi8XVUe1yj6NskZVcgcE
zf/y9VFubOBIZGpXHAEZQ6Cxmg==
-----END CERTIFICATE-----
"""
    }
}
