package com.example.wallettransactions.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Activities.IntegrationsActivity;
import com.example.wallettransactions.Activities.MoreActivity;
import com.example.wallettransactions.R;
import com.example.wallettransactions.TokenStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * {@code POST /auth/refresh} с сохранённым refresh-токеном — общая часть для
 * {@link SyncWorker} (раньше жила прямо в {@code ConnectWorker}, до того как
 * появились {@code /sync/push}/{@code /sync/pull} и понадобилось звать
 * refresh не самим по себе, а перед реальной синхронизацией, см.
 * org/docs/TODO_mobile_sync.md, Фаза 3, п. 4).
 * <p>
 * Если сервер ответил 401 — refresh-токен истёк, отозван или уже был
 * использован (см. README google-auth, "Обновление сессии") — тихо повторить
 * вход через Google отсюда нельзя: Credential Manager обязан показать UI
 * поверх Activity, а это фоновая задача без единой видимой Activity. Вместо
 * этого — {@link AppSettings#setNeedsRelink} и уведомление, по тапу на
 * которое открывается {@link IntegrationsActivity}, где пользователь заново
 * пройдёт {@code SyncLinkActivity} сам.
 */
final class AuthSession {

    private static final String TAG = "AuthSession";
    private static final String CHANNEL_ID = "sync_status";
    private static final int NOTIFICATION_ID = 1;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private AuthSession() {}

    enum Outcome {
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
     * {@code synchronized}: refresh-токен одноразовый (ротация), и два
     * параллельных refresh — проход синка и запрос экрана «ИИ» — отдали бы
     * один и тот же токен, второй получил бы 401 и пользователь — ложное
     * «Нужна повторная авторизация». Второй вызывающий ждёт первого и читает
     * уже новый токен из {@link TokenStore}.
     */
    static synchronized Outcome ensureFresh(Context ctx) {
        String refreshToken = TokenStore.getRefreshToken(ctx);
        if (refreshToken == null) return Outcome.NO_SESSION;

        JSONObject payload = new JSONObject();
        try {
            payload.put("refresh_token", refreshToken);
        } catch (JSONException e) {
            throw new AssertionError(e); // ключ и значение — обычные строки, не может упасть
        }

        Request request = new Request.Builder()
                .url(AppSettings.getServerBaseUrl(ctx) + "auth/refresh")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response response = HttpClients.get(ctx).newCall(request).execute()) {
            if (response.code() == 401) {
                Log.i(TAG, "refresh-токен недействителен — нужна повторная авторизация");
                TokenStore.clear(ctx);
                AppSettings.setNeedsRelink(ctx, true);
                notifyNeedsRelink(ctx);
                return Outcome.NEEDS_RELINK;
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "POST /auth/refresh -> HTTP " + response.code());
                return Outcome.NETWORK_ERROR;
            }

            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            TokenStore.saveTokens(ctx, json.getString("access_token"), json.getString("refresh_token"));
            AppSettings.setNeedsRelink(ctx, false);
            return Outcome.OK;
        } catch (IOException e) {
            // Сервер выключен/недоступен по сети — обычное дело для LAN-only
            // стенда (план, п. 1.4), не повод помечать связку разорванной.
            Log.i(TAG, "сервер недоступен: " + e.getMessage());
            return Outcome.NETWORK_ERROR;
        } catch (JSONException e) {
            Log.w(TAG, "некорректный ответ /auth/refresh", e);
            return Outcome.NETWORK_ERROR;
        }
    }

    /**
     * Для запросов вне синка (экран «ИИ», статус ИИ на «Ещё»): refresh только
     * если access-токена нет или он истекает меньше чем через минуту — иначе
     * каждое открытие экрана крутило бы ротацию refresh-токена.
     */
    static Outcome ensureFreshForRequest(Context ctx) {
        if (TokenStore.getRefreshToken(ctx) == null) return Outcome.NO_SESSION;
        long exp = accessTokenExpiresAt(TokenStore.getAccessToken(ctx));
        if (exp - 60 > System.currentTimeMillis() / 1000) return Outcome.OK;
        return ensureFresh(ctx);
    }

    /** {@code exp} (секунды epoch) из payload JWT без проверки подписи; 0 — не разобрать. */
    static long accessTokenExpiresAt(String jwt) {
        if (jwt == null) return 0;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return 0;
        try {
            byte[] json = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            return new JSONObject(new String(json, java.nio.charset.StandardCharsets.UTF_8)).optLong("exp", 0);
        } catch (IllegalArgumentException | JSONException e) {
            return 0;
        }
    }

    private static void notifyNeedsRelink(Context ctx) {
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (manager == null) return;

        // createNotificationChannel идемпотентна — дешевле звать каждый раз,
        // чем протаскивать отдельную инициализацию через WalletApplication.
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Статус синхронизации", NotificationManager.IMPORTANCE_DEFAULT));

        // «Ещё» под «Интеграциями» — чтобы «назад» с них вёл в приложение, а не наружу.
        PendingIntent pendingIntent = TaskStackBuilder.create(ctx)
                .addNextIntent(new Intent(ctx, MoreActivity.class))
                .addNextIntent(new Intent(ctx, IntegrationsActivity.class))
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.main_icon)
                        .setContentTitle("Нужна повторная авторизация")
                        .setContentText("Сессия с сервером истекла — нажмите, чтобы войти заново")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true);

        // На Android 13+ POST_NOTIFICATIONS — рантайм-разрешение (запрашивается
        // при включении автосинхронизации, см. IntegrationsActivity.onToggleAutoConnect);
        // без него notify() просто ничего не покажет, но раз проверка дешёвая —
        // не полагаемся молча на то, что NotificationManagerCompat не упадёт.
        if (ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
    }
}
