package com.example.wallettransactions;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Access/refresh-токены синка (org/docs/TODO_mobile_sync.md, Фаза 1/3) —
 * секреты, поэтому отдельно от {@link AppSettings} (обычные, не
 * зашифрованные SharedPreferences) и в своём файле prefs под ключом
 * из Android Keystore (EncryptedSharedPreferences), а не как есть.
 *
 * Refresh-токен живёт до 30 дней (server/google-auth README, "Обновление
 * сессии") — то есть переживает выключение экрана и перезапуск процесса, и
 * терять его между сессиями означает разлогинивать пользователя каждый
 * запуск, поэтому это не in-memory состояние.
 */
public final class TokenStore {

    private static final String PREFS_NAME = "secure_auth";
    private static final String PREF_ACCESS_TOKEN = "accessToken";
    private static final String PREF_REFRESH_TOKEN = "refreshToken";

    private TokenStore() {}

    private static SharedPreferences prefs(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Ключ в Android Keystore недоступен (крайне редкий случай —
            // повреждённый keystore и т.п.) — без него безопасно хранить
            // токены всё равно нельзя, поэтому не пытаемся притвориться,
            // что ничего не произошло.
            throw new RuntimeException("Не удалось открыть зашифрованное хранилище токенов", e);
        }
    }

    public static void saveTokens(Context ctx, String accessToken, String refreshToken) {
        prefs(ctx).edit()
                .putString(PREF_ACCESS_TOKEN, accessToken)
                .putString(PREF_REFRESH_TOKEN, refreshToken)
                .apply();
    }

    /** null, если пользователь ещё не входил (или вышел — см. {@link #clear}). */
    public static String getAccessToken(Context ctx) {
        return prefs(ctx).getString(PREF_ACCESS_TOKEN, null);
    }

    public static String getRefreshToken(Context ctx) {
        return prefs(ctx).getString(PREF_REFRESH_TOKEN, null);
    }

    /** Логаут: оба токена стираются локально. Отзыв на сервере (POST /auth/logout) — отдельный вызов. */
    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
