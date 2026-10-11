package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.TokenStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * «Ещё» → «Интеграции» → «Подключение к Telegram»: та же связка Telegram ↔
 * пользователь, что в веб-версии («Настройки» → Telegram), через те же
 * эндпоинты google-auth (org/docs/TODO_telegram_tokens.md, шаг 7):
 * <ul>
 *   <li>{@code GET /auth/telegram/links} — подключённые Telegram-аккаунты;</li>
 *   <li>{@code POST /auth/telegram/link-code} — одноразовая ссылка
 *       {@code t.me/<бот>?start=<код>} (живёт ~10 минут): её открывает
 *       Telegram, бот обменивает код на связку;</li>
 *   <li>{@code DELETE /auth/telegram/links/:tg_user_id} — отключить.</li>
 * </ul>
 * Сначала с текущим access-токеном, на 401 — {@link AuthSession#ensureFresh}
 * и ещё одна попытка. Не обновлять токен на каждый вызов: обновление
 * одноразовое (ротация), и параллельный фоновый синк мог бы поймать уже
 * погашенный refresh-токен и пометить сессию истёкшей.
 * <p>
 * Сеть — вызывающий отвечает за то, чтобы звать не с главного потока.
 */
public final class TelegramLinkClient {

    /** Связка, как её отдаёт сервер. */
    public static final class Link {
        public final String tgUserId;
        /** «@username» или имя устройства, если username у аккаунта нет. */
        public final String name;
        public final String createdAt;

        Link(String tgUserId, String name, String createdAt) {
            this.tgUserId = tgUserId;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    /** Понятная пользователю причина отказа — текст идёт прямо в диалог/строку. */
    public static final class TelegramException extends Exception {
        /** Приложение не связано с сервером или сессия истекла — нужен «Связать с сервером». */
        public final boolean needsServerLink;

        TelegramException(String message, boolean needsServerLink) {
            super(message);
            this.needsServerLink = needsServerLink;
        }
    }

    private static final OkHttpClient HTTP = new OkHttpClient();

    private TelegramLinkClient() {}

    public static List<Link> listLinks(Context ctx) throws TelegramException {
        JSONObject body = call(ctx, "GET", "auth/telegram/links");
        List<Link> out = new ArrayList<>();
        JSONArray links = body.optJSONArray("links");
        if (links == null) return out;
        for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i);
            if (l == null) continue;
            String username = l.optString("tg_username", "");
            String name = !username.isEmpty() ? "@" + username : l.optString("name", l.optString("tg_user_id"));
            out.add(new Link(l.optString("tg_user_id"), name, l.optString("created_at", "")));
        }
        return out;
    }

    /** @return deep link {@code https://t.me/<бот>?start=<код>} */
    public static String createLinkCode(Context ctx) throws TelegramException {
        String link = call(ctx, "POST", "auth/telegram/link-code").optString("deep_link", "");
        if (link.isEmpty()) throw new TelegramException("Сервер не выдал ссылку для Telegram", false);
        return link;
    }

    public static void unlink(Context ctx, String tgUserId) throws TelegramException {
        call(ctx, "DELETE", "auth/telegram/links/" + tgUserId);
    }

    private static JSONObject call(Context ctx, String method, String path) throws TelegramException {
        if (TokenStore.getRefreshToken(ctx) == null || AppSettings.isNeedsRelink(ctx)) {
            throw new TelegramException("Сначала свяжите приложение с сервером", true);
        }
        try {
            String access = TokenStore.getAccessToken(ctx);
            if (access != null) {
                JSONObject body = execute(ctx, method, path, access);
                if (body != null) return body;
            }
            // Нет access-токена или 401 — обновить и повторить один раз.
            switch (AuthSession.ensureFresh(ctx)) {
                case OK:
                    break;
                case NETWORK_ERROR:
                    throw new TelegramException("Сервер недоступен", false);
                default:
                    throw new TelegramException("Сессия истекла — нажмите «Связать с сервером»", true);
            }
            JSONObject body = execute(ctx, method, path, TokenStore.getAccessToken(ctx));
            if (body == null) throw new TelegramException("Сессия истекла — нажмите «Связать с сервером»", true);
            return body;
        } catch (IOException e) {
            throw new TelegramException("Сервер недоступен", false);
        } catch (JSONException e) {
            throw new TelegramException("Некорректный ответ сервера", false);
        }
    }

    /** @return тело ответа; {@code null} — 401 (токен не подошёл). */
    private static JSONObject execute(Context ctx, String method, String path, String accessToken)
            throws IOException, JSONException, TelegramException {
        RequestBody empty = "GET".equals(method) ? null : RequestBody.create(new byte[0]);
        Request request = new Request.Builder()
                .url(AppSettings.getServerBaseUrl(ctx) + path)
                .header("Authorization", "Bearer " + accessToken)
                .method(method, empty)
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.code() == 401) return null;
            String text = response.body() != null ? response.body().string() : "";
            JSONObject body = new JSONObject(text.isEmpty() ? "{}" : text);
            if (response.code() == 503) {
                throw new TelegramException("Telegram-бот на сервере не настроен", false);
            }
            if (!response.isSuccessful()) {
                String error = body.optString("error", "");
                throw new TelegramException(error.isEmpty() ? "Ошибка сервера: HTTP " + response.code() : error, false);
            }
            return body;
        }
    }
}
