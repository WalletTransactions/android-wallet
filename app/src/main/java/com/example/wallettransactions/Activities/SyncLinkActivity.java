package com.example.wallettransactions.Activities;

import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.R;
import com.example.wallettransactions.TokenStore;
import com.example.wallettransactions.sync.HttpClients;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Связка приложения с сервером через Google (org/docs/TODO_mobile_sync.md,
 * Фаза 1) — {@code POST /auth/nonce} → Credential Manager →
 * {@code POST /auth/google/mobile} → токены в {@link TokenStore}. Тот же
 * путь обслуживает и первую связку, и повторную (например, после того как
 * {@link com.example.wallettransactions.sync.SyncWorker} обнаружил
 * истёкший refresh-токен) — экран не различает эти два случая, вход через
 * Google в обоих один и тот же.
 *
 * Открывается с экрана «Ещё» → «Интеграции» → «Связь с Google
 * аккаунтом» ({@link IntegrationsActivity#onLinkServer}).
 *
 * Адрес сервера — {@link AppSettings#getServerBaseUrl}, настраивается там
 * же (по умолчанию {@code http://localhost:8090/} — LAN-only на этом
 * этапе, план п. 1.4). Web client ID — тот же, что уже использует
 * {@code google-auth} (см. {@code cred/webclient.json}).
 */
public class SyncLinkActivity extends AppCompatActivity {

    private static final String TAG = "SyncLinkActivity";

    // google-auth/cred/webclient.json → "client_id". Аудитория
    // токенов, которые уже проверяет google-auth (Web, не Android) — см.
    // README google-auth, "1. Получить Client ID".
    private static final String WEB_CLIENT_ID =
            "432841979878-srpc0c3msmuli6gdcsojc2g812nmlot7.apps.googleusercontent.com";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private TextView textResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_link);

        textResult = findViewById(R.id.textResult);
        Button buttonSignIn = findViewById(R.id.buttonSignIn);
        buttonSignIn.setOnClickListener(v -> fetchNonceThenSignIn());
    }

    /** Шаг 1: POST /auth/nonce — сервер выдаёт одноразовый nonce (см. README google-auth, "Мобильный вход"). */
    private void fetchNonceThenSignIn() {
        showResult("Запрашиваю nonce у " + AppSettings.getServerBaseUrl(this) + "…");

        Request request = new Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/nonce")
                .post(RequestBody.create(new byte[0])) // POST без тела — nonce сервер генерирует сам
                .build();

        HttpClients.get(this).newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "POST /auth/nonce failed", e);
                runOnUiThread(() -> showResult("Не удалось связаться с сервером (" +
                        AppSettings.getServerBaseUrl(SyncLinkActivity.this) +
                        "): " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        runOnUiThread(() -> showResult("Сервер отклонил запрос nonce: HTTP " + r.code() + " " + body));
                        return;
                    }
                    String nonce = new JSONObject(body).getString("nonce");
                    runOnUiThread(() -> startCredentialManager(nonce));
                } catch (JSONException e) {
                    runOnUiThread(() -> showResult("Некорректный ответ /auth/nonce: " + e.getMessage()));
                }
            }
        });
    }

    /** Шаг 2: Credential Manager с nonce от сервера — Google подпишет его внутрь id_token. */
    private void startCredentialManager(String nonce) {
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setNonce(nonce)
                // Явный флоу входа должен предлагать любой аккаунт на
                // устройстве, а не только уже авторизовывавшие это
                // приложение (тот фильтр — для молчаливого/one-tap входа).
                .setFilterByAuthorizedAccounts(false)
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        // Выбор аккаунта — чужой экран: возврат из него не должен запирать (security.AppLock).
        com.example.wallettransactions.security.AppLock.allowNextLeave();
        CredentialManager.create(this).getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredential(result.getCredential());
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        Log.e(TAG, "getCredentialAsync failed", e);
                        showResult("Ошибка входа: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                    }
                });
    }

    private void handleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(((CustomCredential) credential).getType())) {
            showResult("Неожиданный тип credential: " + credential.getType());
            return;
        }
        // GoogleIdTokenCredential.createFrom не объявляет checked-исключение
        // в 1.1.1 (см. историю Фазы 0 в плане) — try/catch тут не нужен.
        GoogleIdTokenCredential googleIdTokenCredential =
                GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
        exchangeIdToken(googleIdTokenCredential.getIdToken(), googleIdTokenCredential.getId());
    }

    /** Шаг 3: POST /auth/google/mobile — обмен id_token на собственную пару токенов сервера. */
    private void exchangeIdToken(String idToken, String email) {
        showResult("id_token получен, обмениваю на токены сервера…");

        // device_id — стабильный по этой установке (AppSettings, один раз
        // сгенерированный UUID), а не android_id/IMEI и т.п.: тем и хорош,
        // что не требует разрешений и не идентифицирует физическое
        // устройство за пределами этого приложения (план, п. 1.2 — сервер
        // всё равно использует его только как непрозрачный ключ сессии, а
        // не для слежки). device_name — только для показа человеку (см.
        // README google-auth, "Мобильный вход").
        JSONObject payload = new JSONObject();
        try {
            payload.put("id_token", idToken);
            payload.put("device_id", AppSettings.getOrCreateDeviceId(this));
            payload.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            payload.put("platform", "android");
        } catch (JSONException e) {
            throw new AssertionError(e); // ключи и значения — обычные строки, не может упасть
        }

        Request request = new Request.Builder()
                .url(AppSettings.getServerBaseUrl(this) + "auth/google/mobile")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        HttpClients.get(this).newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "POST /auth/google/mobile failed", e);
                runOnUiThread(() -> showResult("Не удалось обменять id_token: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        runOnUiThread(() -> showResult("Сервер отклонил вход: HTTP " + r.code() + " " + body));
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    String accessToken = json.getString("access_token");
                    String refreshToken = json.getString("refresh_token");
                    runOnUiThread(() -> onLinked(accessToken, refreshToken, email));
                } catch (JSONException e) {
                    runOnUiThread(() -> showResult("Некорректный ответ /auth/google/mobile: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Связка удалась — сохраняем токены и email (для строки статуса на
     * экране «Настройки»), снимаем флаг «нужна повторная авторизация»,
     * выставленный {@code SyncWorker}, если он был, и возвращаемся
     * назад с небольшой паузой, чтобы результат успел мелькнуть на экране.
     * <p>
     * Если связанный email изменился — другой Google-аккаунт почти
     * наверняка ведёт на другой леджер сервера (org/docs/TODO_mobile_sync.md,
     * Фаза 3, п. 1.3) — сбрасываем курсоры синка: старая позиция в потоках
     * чужого леджера не подходит (см. {@code AppSettings.resetSyncCursors}).
     * Тот же email (обычное продление после истёкшего refresh-токена) курсоры
     * не трогает.
     */
    private void onLinked(String accessToken, String refreshToken, String email) {
        String previousEmail = AppSettings.getLinkedEmail(this);
        TokenStore.saveTokens(this, accessToken, refreshToken);
        AppSettings.setLinkedEmail(this, email);
        AppSettings.setNeedsRelink(this, false);
        if (!email.equals(previousEmail)) {
            AppSettings.resetSyncCursors(this);
        }
        showResult("Готово — связано с " + email + ".");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
    }

    private void showResult(String text) {
        Log.i(TAG, text);
        textResult.setText(text);
    }
}
