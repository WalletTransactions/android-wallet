package com.example.wallettransactions.security;

import android.content.Context;

import com.example.wallettransactions.R;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.VisibleForTesting;

import java.security.GeneralSecurityException;

/**
 * Prefs {@code app_lock}: хэш кода доступа и всё, что к нему относится
 * (org/docs/TODO_app_lock.md, п. 1.3). Самого кода здесь нет — только
 * {@link PinHasher#hash} с солью. Отдельно от {@code settings}: файл исключается
 * из бэкапа, а ключ Keystore, без которого хэш не проверить, на другой телефон
 * всё равно не переезжает.
 */
public final class AppLockStore {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 6;

    /** «Блокировать»: сразу / через 1 мин (по умолчанию) / через 5 мин. */
    public static final long[] TIMEOUTS_MS = {0, 60_000, 5 * 60_000};
    /** Подписи {@link #TIMEOUTS_MS} — ресурсы (openspec android-i18n). */
    public static final int[] TIMEOUT_TITLES = {R.string.lock_timeout_now, R.string.lock_timeout_1min, R.string.lock_timeout_5min};

    public static String[] timeoutTitles(Context ctx) {
        String[] titles = new String[TIMEOUT_TITLES.length];
        for (int i = 0; i < titles.length; i++) titles[i] = ctx.getString(TIMEOUT_TITLES[i]);
        return titles;
    }
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** Ошибок до первой паузы; дальше пауза удваивается с каждой ошибкой до потолка. */
    public static final int FREE_ATTEMPTS = 5;
    static final long FIRST_LOCKOUT_MS = 30_000;
    static final long MAX_LOCKOUT_MS = 15 * 60_000;

    private static final String PREFS_NAME = "app_lock";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_LENGTH = "pin_length";
    private static final String KEY_ITERATIONS = "pin_iterations";
    private static final String KEY_TIMEOUT = "lock_timeout_ms";
    private static final String KEY_FAILED = "failed_attempts";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final String KEY_BIOMETRIC = "biometric_on";
    private static final String KEY_HIDE_IN_RECENTS = "hide_in_recents";

    private static PinHasher.Pepper pepper = new PinHasher.KeystorePepper();

    private AppLockStore() {}

    @VisibleForTesting
    public static void setPepperForTest(PinHasher.Pepper p) {
        pepper = p;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Код ───────────────────────────────────────────────────────────────────

    public static boolean isPinSet(Context ctx) {
        return prefs(ctx).contains(KEY_HASH);
    }

    public static int getPinLength(Context ctx) {
        return prefs(ctx).getInt(KEY_LENGTH, MIN_LENGTH);
    }

    /** Долгая операция (PBKDF2) — не с главного потока. */
    public static void setPin(Context ctx, String pin) throws GeneralSecurityException {
        if (!isValidPin(pin)) throw new IllegalArgumentException("pin must be 4-6 digits");
        pepper.ensureKey();
        byte[] salt = PinHasher.newSalt();
        int iterations = PinHasher.DEFAULT_ITERATIONS;
        byte[] hash = PinHasher.hash(pin, salt, iterations, pepper);
        prefs(ctx).edit()
                .putString(KEY_HASH, b64(hash))
                .putString(KEY_SALT, b64(salt))
                .putInt(KEY_LENGTH, pin.length())
                .putInt(KEY_ITERATIONS, iterations)
                .remove(KEY_FAILED)
                .remove(KEY_LOCKOUT_UNTIL)
                .commit();
    }

    /**
     * Выключение кода: остаются только настройки, не относящиеся к нему (таймаут).
     * Отпечаток без кода не живёт — выключается вместе с ключом.
     */
    public static void clearPin(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_HASH).remove(KEY_SALT).remove(KEY_LENGTH).remove(KEY_ITERATIONS)
                .remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).remove(KEY_BIOMETRIC)
                .commit();
        BiometricKey.delete();
    }

    public static boolean isValidPin(String pin) {
        return pin != null && pin.length() >= MIN_LENGTH && pin.length() <= MAX_LENGTH
                && pin.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * Проверка с учётом пауз: неверный код увеличивает счётчик, верный — сбрасывает.
     * Долгая операция (PBKDF2) — не с главного потока.
     *
     * @throws PinHasher.KeyLostException ключа Keystore нет — код не проверить
     */
    public static boolean check(Context ctx, String pin, long nowMillis) throws GeneralSecurityException {
        if (getLockoutRemainingMs(ctx, nowMillis) > 0) return false;
        SharedPreferences p = prefs(ctx);
        String hash = p.getString(KEY_HASH, null);
        String salt = p.getString(KEY_SALT, null);
        if (hash == null || salt == null) return false;

        boolean ok = PinHasher.matches(pin, unb64(salt),
                p.getInt(KEY_ITERATIONS, PinHasher.DEFAULT_ITERATIONS), unb64(hash), pepper);
        if (ok) {
            p.edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).commit();
        } else {
            int failed = p.getInt(KEY_FAILED, 0) + 1;
            long lockout = lockoutMs(failed);
            SharedPreferences.Editor e = p.edit().putInt(KEY_FAILED, failed);
            if (lockout > 0) e.putLong(KEY_LOCKOUT_UNTIL, nowMillis + lockout);
            e.commit();
        }
        return ok;
    }

    // ── Паузы после ошибок ────────────────────────────────────────────────────

    /** Пауза после {@code failed}-й ошибки подряд: 0 для первых пяти, затем 30 с, 1 мин, 2 мин… до 15 мин. */
    static long lockoutMs(int failed) {
        if (failed < FREE_ATTEMPTS) return 0;
        int doublings = Math.min(failed - FREE_ATTEMPTS, 10);
        return Math.min(FIRST_LOCKOUT_MS << doublings, MAX_LOCKOUT_MS);
    }

    /**
     * Сколько ещё ждать. Часы считаем по {@code currentTimeMillis}, чтобы пауза пережила
     * перезагрузку; переведённые назад часы не растянут её больше потолка.
     */
    public static long getLockoutRemainingMs(Context ctx, long nowMillis) {
        long until = prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0);
        long remaining = until - nowMillis;
        if (remaining <= 0) return 0;
        return Math.min(remaining, MAX_LOCKOUT_MS);
    }

    public static int getFailedAttempts(Context ctx) {
        return prefs(ctx).getInt(KEY_FAILED, 0);
    }

    /** Вход по отпечатку: владелец подтверждён — счётчик ошибок кода и пауза снимаются. */
    public static void resetFailures(Context ctx) {
        prefs(ctx).edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).commit();
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    /** Флаг, а не сам отпечаток: его сверяет система, приложение получает только ответ. */
    public static boolean isBiometricOn(Context ctx) {
        return isPinSet(ctx) && prefs(ctx).getBoolean(KEY_BIOMETRIC, false);
    }

    /** Выключение удаляет и ключ: включат снова — {@link BiometricKey#create} заведёт новый. */
    public static void setBiometricOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_BIOMETRIC, on).apply();
        if (!on) BiometricKey.delete();
    }

    // ── Недавние ──────────────────────────────────────────────────────────────

    /** «Скрывать в недавних» (п. 4.2): по умолчанию включено, действует только при заданном коде. */
    public static boolean isHideInRecents(Context ctx) {
        return isPinSet(ctx) && prefs(ctx).getBoolean(KEY_HIDE_IN_RECENTS, true);
    }

    public static boolean getHideInRecentsSetting(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HIDE_IN_RECENTS, true);
    }

    public static void setHideInRecents(Context ctx, boolean hide) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_IN_RECENTS, hide).apply();
    }

    // ── Таймаут ───────────────────────────────────────────────────────────────

    public static long getTimeoutMs(Context ctx) {
        return prefs(ctx).getLong(KEY_TIMEOUT, DEFAULT_TIMEOUT_MS);
    }

    public static void setTimeoutMs(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_TIMEOUT, ms).apply();
    }

    public static String getTimeoutTitle(Context ctx) {
        long ms = getTimeoutMs(ctx);
        for (int i = 0; i < TIMEOUTS_MS.length; i++) if (TIMEOUTS_MS[i] == ms) return ctx.getString(TIMEOUT_TITLES[i]);
        return ctx.getString(TIMEOUT_TITLES[1]);
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }
}
