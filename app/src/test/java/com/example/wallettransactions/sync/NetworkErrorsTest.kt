package com.example.wallettransactions.sync

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Причина сетевой ошибки и её текст ([NetworkErrors]) — как их бросают OkHttp и Conscrypt. */
@RunWith(RobolectricTestRunner::class)
class NetworkErrorsTest {

    private val ru: Context = ApplicationProvider.getApplicationContext()

    private fun english(): Context {
        val cfg = Configuration(ru.resources.configuration)
        cfg.setLocale(Locale.ENGLISH)
        return ru.createConfigurationContext(cfg)
    }

    companion object {
        /** Как на Android: SSLHandshakeException ← CertificateException ← CertPathValidatorException. */
        private fun untrusted(): IOException {
            val e = SSLHandshakeException(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."
            )
            e.initCause(
                CertificateException(
                    CertPathValidatorException("Trust anchor for certification path not found.")
                )
            )
            return e
        }

        private fun expired(): IOException {
            val e = SSLHandshakeException("certificate expired")
            e.initCause(
                CertificateException(
                    CertPathValidatorException(
                        "timestamp check failed", CertificateExpiredException("expired")
                    )
                )
            )
            return e
        }
    }

    @Test
    fun classify_byExceptionAndCauses() {
        assertEquals(NetworkErrors.Kind.UNTRUSTED_CERT, NetworkErrors.classify(untrusted()))
        assertEquals(NetworkErrors.Kind.CERT_EXPIRED, NetworkErrors.classify(expired()))
        assertEquals(
            NetworkErrors.Kind.HOST_MISMATCH, NetworkErrors.classify(
                SSLPeerUnverifiedException("Hostname 192.168.1.105 not verified")
            )
        )
        assertEquals(
            NetworkErrors.Kind.TLS, NetworkErrors.classify(
                SSLHandshakeException("Connection closed by peer")
            )
        )
        assertEquals(
            NetworkErrors.Kind.UNKNOWN_HOST, NetworkErrors.classify(
                UnknownHostException("Unable to resolve host")
            )
        )
        assertEquals(
            NetworkErrors.Kind.CONNECT, NetworkErrors.classify(
                ConnectException("Failed to connect to /192.168.1.105:8444")
            )
        )
        assertEquals(NetworkErrors.Kind.TIMEOUT, NetworkErrors.classify(SocketTimeoutException("timeout")))
        assertEquals(NetworkErrors.Kind.OTHER, NetworkErrors.classify(IOException("unexpected end of stream")))
    }

    @Test
    fun ru_untrustedCert_explainsCaInstall() {
        val text = NetworkErrors.describe(ru, untrusted())
        assertTrue(text, text.startsWith("Телефон не доверяет сертификату сервера."))
        assertTrue(text, text.contains("Сертификат ЦС"))
        assertEquals("Ошибка сертификата", NetworkErrors.shortText(ru, untrusted()))
    }

    @Test
    fun en_untrustedCert_explainsCaInstall() {
        val text = NetworkErrors.describe(english(), untrusted())
        assertTrue(text, text.startsWith("The phone doesn't trust the server certificate."))
        assertEquals("Certificate error", NetworkErrors.shortText(english(), untrusted()))
    }

    @Test
    fun other_keepsOriginalMessage() {
        assertEquals(
            "Сервер недоступен: unexpected end of stream",
            NetworkErrors.describe(ru, IOException("unexpected end of stream"))
        )
        assertEquals(
            "Сервер недоступен",
            NetworkErrors.shortText(ru, ConnectException("refused"))
        )
    }
}
