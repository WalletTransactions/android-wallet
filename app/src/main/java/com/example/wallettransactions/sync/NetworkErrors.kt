package com.example.wallettransactions.sync

import android.content.Context
import com.example.wallettransactions.R
import com.example.wallettransactions.Texts
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Понятный текст для сетевой ошибки запроса к серверу. Java сводит DNS, отказ соединения и
 * сертификат в один [IOException], и раньше все они выглядели как «Сервер недоступен:
 * Trust anchor for certification path not found». Здесь причина определяется по типу
 * исключения и цепочке причин, и пользователь получает подсказку, что исправить.
 */
object NetworkErrors {

    enum class Kind {
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

    @JvmStatic
    fun classify(e: IOException): Kind {
        // Порядок важен: SSLPeerUnverifiedException и ошибки сертификата — подвиды SSLException.
        if (e is SSLPeerUnverifiedException) return Kind.HOST_MISMATCH
        if (hasCause(e, CertificateExpiredException::class.java) ||
            hasCause(e, CertificateNotYetValidException::class.java)
        ) {
            return Kind.CERT_EXPIRED
        }
        if (hasCause(e, CertPathValidatorException::class.java)) return Kind.UNTRUSTED_CERT
        if (e is SSLException) return Kind.TLS
        if (e is UnknownHostException) return Kind.UNKNOWN_HOST
        if (e is SocketTimeoutException) return Kind.TIMEOUT
        if (e is ConnectException || e is NoRouteToHostException) return Kind.CONNECT
        return Kind.OTHER
    }

    /** Полный текст с подсказкой — для диалога синка, экрана входа, журнала. */
    @JvmStatic
    fun describe(ctx: Context, e: IOException): String =
        when (classify(e)) {
            Kind.UNTRUSTED_CERT -> ctx.getString(R.string.net_error_untrusted_cert)
            Kind.CERT_EXPIRED -> ctx.getString(R.string.net_error_cert_expired)
            Kind.HOST_MISMATCH -> ctx.getString(R.string.net_error_host_mismatch)
            Kind.TLS -> ctx.getString(R.string.net_error_tls, e.message.toString())
            Kind.UNKNOWN_HOST -> ctx.getString(R.string.net_error_unknown_host)
            Kind.CONNECT -> ctx.getString(R.string.net_error_connect)
            Kind.TIMEOUT -> ctx.getString(R.string.net_error_timeout)
            Kind.OTHER -> ctx.getString(R.string.sync_server_unavailable, e.message.toString())
        }

    /** Короткий текст — для строки статуса (например, «Подключение к Telegram»). */
    @JvmStatic
    fun shortText(ctx: Context, e: IOException): String =
        when (classify(e)) {
            Kind.UNTRUSTED_CERT, Kind.CERT_EXPIRED, Kind.HOST_MISMATCH, Kind.TLS ->
                ctx.getString(R.string.net_error_short_cert)
            else -> ctx.getString(R.string.telegram_server_unavailable)
        }

    /** [describe] для кода без `Context` (синк). */
    @JvmStatic
    internal fun describe(e: IOException): String = describe(Texts.context(), e)

    private fun hasCause(e: Throwable, type: Class<out Throwable>): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (type.isInstance(t)) return true
            if (t.cause === t) break
            t = t.cause
        }
        return false
    }
}
