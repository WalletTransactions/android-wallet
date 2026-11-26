package com.example.wallettransactions.sync

import android.content.Context
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import com.example.wallettransactions.TokenStore
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * «Ещё» → «Интеграции» → «Подключение к Telegram»: та же связка Telegram ↔
 * пользователь, что в веб-версии («Настройки» → Telegram), через те же
 * эндпоинты google-auth (org/docs/TODO_telegram_tokens.md, шаг 7):
 *  - `GET /auth/telegram/links` — подключённые Telegram-аккаунты;
 *  - `POST /auth/telegram/link-code` — одноразовая ссылка
 *    `t.me/<бот>?start=<код>` (живёт ~10 минут): её открывает
 *    Telegram, бот обменивает код на связку;
 *  - `DELETE /auth/telegram/links/:tg_user_id` — отключить.
 *
 * Сначала с текущим access-токеном, на 401 — [AuthSession.ensureFresh]
 * и ещё одна попытка. Не обновлять токен на каждый вызов: обновление
 * одноразовое (ротация), и параллельный фоновый синк мог бы поймать уже
 * погашенный refresh-токен и пометить сессию истёкшей.
 *
 * Сеть — вызывающий отвечает за то, чтобы звать не с главного потока.
 */
object TelegramLinkClient {

    /** Связка, как её отдаёт сервер. */
    class Link internal constructor(
        @JvmField val tgUserId: String,
        /** «@username» или имя устройства, если username у аккаунта нет. */
        @JvmField val name: String,
        @JvmField val createdAt: String
    )

    /** Понятная пользователю причина отказа — текст идёт прямо в диалог/строку. */
    class TelegramException internal constructor(
        message: String,
        /** Приложение не связано с сервером или сессия истекла — нужен вход через «Связь с Google аккаунтом». */
        @JvmField val needsServerLink: Boolean
    ) : Exception(message)

    @JvmStatic
    @Throws(TelegramException::class)
    fun listLinks(ctx: Context): List<Link> {
        val body = call(ctx, "GET", "auth/telegram/links")
        val out = ArrayList<Link>()
        val links = body.optJSONArray("links") ?: return out
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            val username = l.optString("tg_username", "")
            val name = if (username.isNotEmpty()) "@$username" else l.optString("name", l.optString("tg_user_id"))
            out.add(Link(l.optString("tg_user_id"), name, l.optString("created_at", "")))
        }
        return out
    }

    /** @return deep link `https://t.me/<бот>?start=<код>` */
    @JvmStatic
    @Throws(TelegramException::class)
    fun createLinkCode(ctx: Context): String {
        val link = call(ctx, "POST", "auth/telegram/link-code").optString("deep_link", "")
        if (link.isEmpty()) throw TelegramException(ctx.getString(R.string.telegram_no_link), false)
        return link
    }

    @JvmStatic
    @Throws(TelegramException::class)
    fun unlink(ctx: Context, tgUserId: String) {
        call(ctx, "DELETE", "auth/telegram/links/$tgUserId")
    }

    @Throws(TelegramException::class)
    private fun call(ctx: Context, method: String, path: String): JSONObject {
        if (TokenStore.getRefreshToken(ctx) == null || AppSettings.isNeedsRelink(ctx)) {
            throw TelegramException(ctx.getString(R.string.telegram_sign_in_first), true)
        }
        try {
            val access = TokenStore.getAccessToken(ctx)
            if (access != null) {
                val body = execute(ctx, method, path, access)
                if (body != null) return body
            }
            // Нет access-токена или 401 — обновить и повторить один раз.
            when (AuthSession.ensureFresh(ctx)) {
                AuthSession.Outcome.OK -> {}
                AuthSession.Outcome.NETWORK_ERROR ->
                    throw TelegramException(
                        AuthSession.lastNetworkErrorShort(
                            ctx,
                            ctx.getString(R.string.telegram_server_unavailable)
                        ), false
                    )
                else -> throw TelegramException(ctx.getString(R.string.telegram_session_expired), true)
            }
            val body = execute(ctx, method, path, TokenStore.getAccessToken(ctx))
                ?: throw TelegramException(ctx.getString(R.string.telegram_session_expired), true)
            return body
        } catch (e: IOException) {
            throw TelegramException(NetworkErrors.shortText(ctx, e), false)
        } catch (e: JSONException) {
            throw TelegramException(ctx.getString(R.string.telegram_bad_response), false)
        }
    }

    /** @return тело ответа; `null` — 401 (токен не подошёл). */
    @Throws(IOException::class, JSONException::class, TelegramException::class)
    private fun execute(ctx: Context, method: String, path: String, accessToken: String?): JSONObject? {
        val empty: RequestBody? = if ("GET" == method) null else ByteArray(0).toRequestBody()
        val request = Request.Builder()
            .url(AppSettings.getServerBaseUrl(ctx) + path)
            .header("Authorization", "Bearer $accessToken")
            .method(method, empty)
            .build()
        HttpClients.get(ctx).newCall(request).execute().use { response ->
            if (response.code == 401) return null
            val text = response.body?.string() ?: ""
            val body = JSONObject(if (text.isEmpty()) "{}" else text)
            if (response.code == 503) {
                throw TelegramException(ctx.getString(R.string.telegram_not_configured), false)
            }
            if (!response.isSuccessful) {
                val error = body.optString("error", "")
                throw TelegramException(
                    if (error.isEmpty()) ctx.getString(R.string.telegram_http_error, response.code) else error,
                    false
                )
            }
            return body
        }
    }
}
