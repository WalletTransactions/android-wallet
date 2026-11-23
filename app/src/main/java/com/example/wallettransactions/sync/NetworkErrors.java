package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Texts;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Понятный текст для сетевой ошибки запроса к серверу. Java сводит DNS, отказ соединения и
 * сертификат в один {@link IOException}, и раньше все они выглядели как «Сервер недоступен:
 * Trust anchor for certification path not found». Здесь причина определяется по типу
 * исключения и цепочке причин, и пользователь получает подсказку, что исправить.
 */
public final class NetworkErrors {

    public enum Kind {
        /** Цепочка не доходит до доверенного корня: свой CA сервера не установлен на телефоне. */
        UNTRUSTED_CERT,
        /** Сертификат просрочен или ещё не действует (часто — неверная дата на телефоне). */
        CERT_EXPIRED,
        /** Сертификат выписан на другое имя, чем адрес в «Адрес сервера». */
        HOST_MISMATCH,
        /** Прочие ошибки TLS, например https:// на порт без TLS. */
        TLS,
        /** Имя сервера не резолвится. */
        UNKNOWN_HOST,
        /** Порт закрыт или хост недоступен. */
        CONNECT,
        /** Сервер не ответил вовремя. */
        TIMEOUT,
        OTHER,
    }

    private NetworkErrors() {}

    public static Kind classify(IOException e) {
        // Порядок важен: SSLPeerUnverifiedException и ошибки сертификата — подвиды SSLException.
        if (e instanceof SSLPeerUnverifiedException) return Kind.HOST_MISMATCH;
        if (hasCause(e, CertificateExpiredException.class) || hasCause(e, CertificateNotYetValidException.class)) {
            return Kind.CERT_EXPIRED;
        }
        if (hasCause(e, CertPathValidatorException.class)) return Kind.UNTRUSTED_CERT;
        if (e instanceof SSLException) return Kind.TLS;
        if (e instanceof UnknownHostException) return Kind.UNKNOWN_HOST;
        if (e instanceof SocketTimeoutException) return Kind.TIMEOUT;
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) return Kind.CONNECT;
        return Kind.OTHER;
    }

    /** Полный текст с подсказкой — для диалога синка, экрана входа, журнала. */
    public static String describe(Context ctx, IOException e) {
        switch (classify(e)) {
            case UNTRUSTED_CERT:
                return ctx.getString(R.string.net_error_untrusted_cert);
            case CERT_EXPIRED:
                return ctx.getString(R.string.net_error_cert_expired);
            case HOST_MISMATCH:
                return ctx.getString(R.string.net_error_host_mismatch);
            case TLS:
                return ctx.getString(R.string.net_error_tls, String.valueOf(e.getMessage()));
            case UNKNOWN_HOST:
                return ctx.getString(R.string.net_error_unknown_host);
            case CONNECT:
                return ctx.getString(R.string.net_error_connect);
            case TIMEOUT:
                return ctx.getString(R.string.net_error_timeout);
            case OTHER:
            default:
                return ctx.getString(R.string.sync_server_unavailable, String.valueOf(e.getMessage()));
        }
    }

    /** Короткий текст — для строки статуса (например, «Подключение к Telegram»). */
    public static String shortText(Context ctx, IOException e) {
        switch (classify(e)) {
            case UNTRUSTED_CERT:
            case CERT_EXPIRED:
            case HOST_MISMATCH:
            case TLS:
                return ctx.getString(R.string.net_error_short_cert);
            default:
                return ctx.getString(R.string.telegram_server_unavailable);
        }
    }

    /** {@link #describe(Context, IOException)} для кода без {@code Context} (синк). */
    static String describe(IOException e) {
        return describe(Texts.context(), e);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
