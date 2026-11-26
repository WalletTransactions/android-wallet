package com.example.wallettransactions.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.example.wallettransactions.Activities.IntegrationsActivity
import com.example.wallettransactions.Activities.MoreActivity
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import com.example.wallettransactions.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * `POST /auth/refresh` с сохранённым refresh-токеном — общая часть для
 * [SyncWorker] (раньше жила прямо в `ConnectWorker`, до того как
 * появились `/sync/push`/`/sync/pull` и понадобилось звать
 * refresh не самим по себе, а перед реальной синхронизацией, см.
 * org/docs/TODO_mobile_sync.md, Фаза 3, п. 4).
 *
 * Если сервер ответил 401 — refresh-токен истёк, отозван или уже был
 * использован (см. README google-auth, "Обновление сессии") — тихо повторить
 * вход через Google отсюда нельзя: Credential Manager обязан показать UI
 * поверх Activity, а это фоновая задача без единой видимой Activity. Вместо
 * этого — [AppSettings.setNeedsRelink] и уведомление, по тапу на
 * которое открывается [IntegrationsActivity], где пользователь заново
 * пройдёт `SyncLinkActivity` сам.
 */
internal object AuthSession {

    private const val TAG = "AuthSession"
    private const val CHANNEL_ID = "sync_status"
    private const val NOTIFICATION_ID = 1
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Последняя сетевая ошибка [ensureFresh] (исход NETWORK_ERROR) — чтобы вызывающий
     * показал причину ([NetworkErrors]), а не общее «сервер недоступен». `null` —
     * ошибки не было или это был HTTP-код, а не сбой соединения.
     */
    @Volatile
    private var lastNetworkError: IOException? = null

    /** Текст причины последнего NETWORK_ERROR или `fallback`, если причина неизвестна. */
    fun lastNetworkErrorText(ctx: Context, fallback: String): String {
        val e = lastNetworkError
        return if (e != null) NetworkErrors.describe(ctx, e) else fallback
    }

    /** Короткий вариант [lastNetworkErrorText] — для строки статуса. */
    fun lastNetworkErrorShort(ctx: Context, fallback: String): String {
        val e = lastNetworkError
        return if (e != null) NetworkErrors.shortText(ctx, e) else fallback
    }

    enum class Outcome {
        /** Связки ещё не было (или её сняли) — синхронизировать нечего, это не ошибка. */
        NO_SESSION,
        /** Токен свежий (или только что обновлён) — можно звать /sync/push и /sync/pull. */
        OK,
        /** Refresh-токен недействителен — нужен новый вход через Google, уведомление уже показано. */
        NEEDS_RELINK,
        /** Сервер недоступен по сети — обычное дело для LAN-only стенда, не повод для needsRelink. */
        NETWORK_ERROR,
    }

    /**
     * `synchronized`: refresh-токен одноразовый (ротация), и два
     * параллельных refresh — проход синка и запрос экрана «ИИ» — отдали бы
     * один и тот же токен, второй получил бы 401 и пользователь — ложное
     * «Нужна повторная авторизация». Второй вызывающий ждёт первого и читает
     * уже новый токен из [TokenStore].
     */
    fun ensureFresh(ctx: Context): Outcome = synchronized(AuthSession::class.java) {
        val refreshToken = TokenStore.getRefreshToken(ctx) ?: return@synchronized Outcome.NO_SESSION
        lastNetworkError = null

        val payload = JSONObject()
        try {
            payload.put("refresh_token", refreshToken)
        } catch (e: JSONException) {
            throw AssertionError(e) // ключ и значение — обычные строки, не может упасть
        }

        val request = Request.Builder()
            .url(AppSettings.getServerBaseUrl(ctx) + "auth/refresh")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        try {
            HttpClients.get(ctx).newCall(request).execute().use { response ->
                if (response.code == 401) {
                    Log.i(TAG, "refresh-токен недействителен — нужна повторная авторизация")
                    TokenStore.clear(ctx)
                    AppSettings.setNeedsRelink(ctx, true)
                    notifyNeedsRelink(ctx)
                    return@synchronized Outcome.NEEDS_RELINK
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST /auth/refresh -> HTTP " + response.code)
                    return@synchronized Outcome.NETWORK_ERROR
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                TokenStore.saveTokens(ctx, json.getString("access_token"), json.getString("refresh_token"))
                AppSettings.setNeedsRelink(ctx, false)
                Outcome.OK
            }
        } catch (e: IOException) {
            // Сервер выключен/недоступен по сети — обычное дело для LAN-only
            // стенда (план, п. 1.4), не повод помечать связку разорванной.
            Log.i(TAG, "сервер недоступен: $e")
            lastNetworkError = e
            Outcome.NETWORK_ERROR
        } catch (e: JSONException) {
            Log.w(TAG, "некорректный ответ /auth/refresh", e)
            Outcome.NETWORK_ERROR
        }
    }

    /**
     * Для запросов вне синка (экран «ИИ», статус ИИ на «Ещё»): refresh только
     * если access-токена нет или он истекает меньше чем через минуту — иначе
     * каждое открытие экрана крутило бы ротацию refresh-токена.
     */
    fun ensureFreshForRequest(ctx: Context): Outcome {
        if (TokenStore.getRefreshToken(ctx) == null) return Outcome.NO_SESSION
        val exp = accessTokenExpiresAt(TokenStore.getAccessToken(ctx))
        if (exp - 60 > System.currentTimeMillis() / 1000) return Outcome.OK
        return ensureFresh(ctx)
    }

    /** `exp` (секунды epoch) из payload JWT без проверки подписи; 0 — не разобрать. */
    fun accessTokenExpiresAt(jwt: String?): Long {
        if (jwt == null) return 0
        val parts = jwt.split("\\.".toRegex()).toTypedArray()
        if (parts.size < 2) return 0
        return try {
            val json = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            JSONObject(String(json, java.nio.charset.StandardCharsets.UTF_8)).optLong("exp", 0)
        } catch (e: IllegalArgumentException) {
            0
        } catch (e: JSONException) {
            0
        }
    }

    private fun notifyNeedsRelink(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return

        // createNotificationChannel идемпотентна — дешевле звать каждый раз,
        // чем протаскивать отдельную инициализацию через WalletApplication.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.auth_channel), NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        // «Ещё» под «Интеграциями» — чтобы «назад» с них вёл в приложение, а не наружу.
        val pendingIntent = TaskStackBuilder.create(ctx)
            .addNextIntent(Intent(ctx, MoreActivity::class.java))
            .addNextIntent(Intent(ctx, IntegrationsActivity::class.java))
            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.main_icon)
            .setContentTitle(ctx.getString(R.string.auth_relink_title))
            .setContentText(ctx.getString(R.string.auth_relink_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // На Android 13+ POST_NOTIFICATIONS — рантайм-разрешение (запрашивается
        // при включении автосинхронизации, см. IntegrationsActivity.onToggleAutoConnect);
        // без него notify() просто ничего не покажет, но раз проверка дешёвая —
        // не полагаемся молча на то, что NotificationManagerCompat не упадёт.
        if (ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
    }
}
