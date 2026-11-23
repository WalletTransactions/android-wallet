package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Причина сетевой ошибки и её текст ({@link NetworkErrors}) — как их бросают OkHttp и Conscrypt. */
@RunWith(RobolectricTestRunner.class)
public class NetworkErrorsTest {

    private final Context ru = ApplicationProvider.getApplicationContext();

    private Context english() {
        Configuration cfg = new Configuration(ru.getResources().getConfiguration());
        cfg.setLocale(Locale.ENGLISH);
        return ru.createConfigurationContext(cfg);
    }

    /** Как на Android: SSLHandshakeException ← CertificateException ← CertPathValidatorException. */
    private static IOException untrusted() {
        SSLHandshakeException e = new SSLHandshakeException(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.");
        e.initCause(new CertificateException(
                new CertPathValidatorException("Trust anchor for certification path not found.")));
        return e;
    }

    private static IOException expired() {
        SSLHandshakeException e = new SSLHandshakeException("certificate expired");
        e.initCause(new CertificateException(new CertPathValidatorException(
                "timestamp check failed", new CertificateExpiredException("expired"))));
        return e;
    }

    @Test
    public void classify_byExceptionAndCauses() {
        assertEquals(NetworkErrors.Kind.UNTRUSTED_CERT, NetworkErrors.classify(untrusted()));
        assertEquals(NetworkErrors.Kind.CERT_EXPIRED, NetworkErrors.classify(expired()));
        assertEquals(NetworkErrors.Kind.HOST_MISMATCH, NetworkErrors.classify(
                new SSLPeerUnverifiedException("Hostname 192.168.1.105 not verified")));
        assertEquals(NetworkErrors.Kind.TLS, NetworkErrors.classify(
                new SSLHandshakeException("Connection closed by peer")));
        assertEquals(NetworkErrors.Kind.UNKNOWN_HOST, NetworkErrors.classify(
                new UnknownHostException("Unable to resolve host")));
        assertEquals(NetworkErrors.Kind.CONNECT, NetworkErrors.classify(
                new ConnectException("Failed to connect to /192.168.1.105:8444")));
        assertEquals(NetworkErrors.Kind.TIMEOUT, NetworkErrors.classify(new SocketTimeoutException("timeout")));
        assertEquals(NetworkErrors.Kind.OTHER, NetworkErrors.classify(new IOException("unexpected end of stream")));
    }

    @Test
    public void ru_untrustedCert_explainsCaInstall() {
        String text = NetworkErrors.describe(ru, untrusted());
        assertTrue(text, text.startsWith("Телефон не доверяет сертификату сервера."));
        assertTrue(text, text.contains("Сертификат ЦС"));
        assertEquals("Ошибка сертификата", NetworkErrors.shortText(ru, untrusted()));
    }

    @Test
    public void en_untrustedCert_explainsCaInstall() {
        String text = NetworkErrors.describe(english(), untrusted());
        assertTrue(text, text.startsWith("The phone doesn't trust the server certificate."));
        assertEquals("Certificate error", NetworkErrors.shortText(english(), untrusted()));
    }

    @Test
    public void other_keepsOriginalMessage() {
        assertEquals("Сервер недоступен: unexpected end of stream",
                NetworkErrors.describe(ru, new IOException("unexpected end of stream")));
        assertEquals("Сервер недоступен",
                NetworkErrors.shortText(ru, new ConnectException("refused")));
    }
}
