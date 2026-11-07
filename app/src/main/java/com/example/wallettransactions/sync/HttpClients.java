package com.example.wallettransactions.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * Один общий {@link OkHttpClient} на всё приложение (синк, refresh-токен,
 * привязка к серверу, Telegram) — с запасным DNS, см. {@link FallbackDns}.
 * <p>
 * Инцидент 2026-10-11: телефон на мобильном интернете ходит к серверу через
 * Tailscale по MagicDNS-имени ({@code *.ts.net}), и посреди синка имя вдруг
 * перестало резолвиться ("Unable to resolve host ... No address associated
 * with hostname") — 144 запроса подряд прошли, следующий упал. Заменить имя
 * на голый Tailscale-IP в настройках нельзя: Google OAuth не принимает IP как
 * redirect_uri на шаге привязки (см. SyncLinkActivity).
 */
public final class HttpClients {

    private static final String PREFS = "dns_fallback";

    private static volatile OkHttpClient shared;

    private HttpClients() {}

    public static OkHttpClient get(Context ctx) {
        OkHttpClient c = shared;
        if (c == null) {
            synchronized (HttpClients.class) {
                c = shared;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .dns(new FallbackDns(ctx.getApplicationContext()))
                            .build();
                    shared = c;
                }
            }
        }
        return c;
    }

    /**
     * Системный DNS, а при его отказе — адреса, на которые это же имя
     * разрешилось в последний удачный раз (хранятся в SharedPreferences, так
     * что переживают перезапуск приложения). Подмена безопасна ровно
     * настолько, насколько безопасен сам адрес сервера: TLS здесь нет (LAN/
     * Tailscale-стенд), а Tailscale-IP узла стабилен, пока узел в tailnet.
     */
    static final class FallbackDns implements Dns {
        private final SharedPreferences prefs;

        FallbackDns(Context appCtx) {
            this.prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            try {
                List<InetAddress> addrs = Dns.SYSTEM.lookup(hostname);
                remember(hostname, addrs);
                return addrs;
            } catch (UnknownHostException e) {
                List<InetAddress> cached = recall(hostname);
                if (cached.isEmpty()) throw e;
                Log.w("HttpClients", "DNS не ответил для " + hostname + " (" + e.getMessage()
                        + ") — беру последний известный адрес " + cached); // not UI: лог
                return cached;
            }
        }

        private void remember(String hostname, List<InetAddress> addrs) {
            Set<String> ips = new HashSet<>();
            for (InetAddress a : addrs) ips.add(a.getHostAddress());
            if (!ips.equals(prefs.getStringSet(hostname, null))) {
                prefs.edit().putStringSet(hostname, ips).apply();
            }
        }

        private List<InetAddress> recall(String hostname) {
            List<InetAddress> out = new ArrayList<>();
            Set<String> ips = prefs.getStringSet(hostname, null);
            if (ips == null) return out;
            for (String ip : ips) {
                try {
                    // IP-литерал — getByAddress-разбор без сетевого запроса.
                    out.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip).getAddress()));
                } catch (UnknownHostException ignored) {
                    // битая запись — пропускаем
                }
            }
            return out;
        }
    }
}
