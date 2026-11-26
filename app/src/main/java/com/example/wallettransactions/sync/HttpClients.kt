package com.example.wallettransactions.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Один общий [OkHttpClient] на всё приложение (синк, refresh-токен,
 * привязка к серверу, Telegram) — с запасным DNS, см. [FallbackDns].
 *
 * Инцидент 2026-10-11: телефон на мобильном интернете ходит к серверу через
 * Tailscale по MagicDNS-имени (`*.ts.net`), и посреди синка имя вдруг
 * перестало резолвиться ("Unable to resolve host ... No address associated
 * with hostname") — 144 запроса подряд прошли, следующий упал. Заменить имя
 * на голый Tailscale-IP в настройках нельзя: Google OAuth не принимает IP как
 * redirect_uri на шаге привязки (см. SyncLinkActivity).
 */
object HttpClients {

    private const val PREFS = "dns_fallback"

    @Volatile
    private var shared: OkHttpClient? = null

    @JvmStatic
    fun get(ctx: Context): OkHttpClient {
        var c = shared
        if (c == null) {
            synchronized(HttpClients::class.java) {
                c = shared
                if (c == null) {
                    c = OkHttpClient.Builder()
                        .dns(FallbackDns(ctx.applicationContext))
                        .build()
                    shared = c
                }
            }
        }
        return c!!
    }

    /**
     * Системный DNS, а при его отказе — адреса, на которые это же имя
     * разрешилось в последний удачный раз (хранятся в SharedPreferences, так
     * что переживают перезапуск приложения). Подмена безопасна ровно
     * настолько, насколько безопасен сам адрес сервера: TLS здесь нет (LAN/
     * Tailscale-стенд), а Tailscale-IP узла стабилен, пока узел в tailnet.
     */
    internal class FallbackDns(appCtx: Context) : Dns {
        private val prefs: SharedPreferences = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val addrs = Dns.SYSTEM.lookup(hostname)
                remember(hostname, addrs)
                return addrs
            } catch (e: UnknownHostException) {
                val cached = recall(hostname)
                if (cached.isEmpty()) throw e
                Log.w(
                    "HttpClients", "DNS не ответил для " + hostname + " (" + e.message + // not UI: лог
                        ") — беру последний известный адрес " + cached // not UI: лог
                ) // not UI: лог
                return cached
            }
        }

        private fun remember(hostname: String, addrs: List<InetAddress>) {
            val ips = HashSet<String>()
            for (a in addrs) ips.add(a.hostAddress!!)
            if (ips != prefs.getStringSet(hostname, null)) {
                prefs.edit().putStringSet(hostname, ips).apply()
            }
        }

        private fun recall(hostname: String): List<InetAddress> {
            val out = ArrayList<InetAddress>()
            val ips = prefs.getStringSet(hostname, null) ?: return out
            for (ip in ips) {
                try {
                    // IP-литерал — getByAddress-разбор без сетевого запроса.
                    out.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address))
                } catch (ignored: UnknownHostException) {
                    // битая запись — пропускаем
                }
            }
            return out
        }
    }
}
