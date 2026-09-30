package com.example.wallettransactions.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Activities.MoreActivity;
import com.example.wallettransactions.R;
import com.example.wallettransactions.TokenStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
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
 * которое открывается {@link MoreActivity}, где пользователь заново
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

    static Outcome ensureFresh(Context ctx) {
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

        try (Response response = new OkHttpClient().newCall(request).execute()) {
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

    private static void notifyNeedsRelink(Context ctx) {
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (manager == null) return;

        // createNotificationChannel идемпотентна — дешевле звать каждый раз,
        // чем протаскивать отдельную инициализацию через WalletApplication.
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Статус синхронизации", NotificationManager.IMPORTANCE_DEFAULT));

        Intent intent = new Intent(ctx, MoreActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.main_icon)
                        .setContentTitle("Нужна повторная авторизация")
                        .setContentText("Сессия с сервером истекла — нажмите, чтобы войти заново")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true);

        // На Android 13+ POST_NOTIFICATIONS — рантайм-разрешение (запрашивается
        // при включении автоподключения, см. MoreActivity.onToggleAutoConnect);
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
