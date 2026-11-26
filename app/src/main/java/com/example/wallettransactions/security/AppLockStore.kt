package com.example.wallettransactions.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.example.wallettransactions.R
import java.security.GeneralSecurityException

/**
 * Prefs `app_lock`: хэш кода доступа и всё, что к нему относится
 * (org/docs/TODO_app_lock.md, п. 1.3). Самого кода здесь нет — только
 * [PinHasher.hash] с солью. Отдельно от `settings`: файл исключается
 * из бэкапа, а ключ Keystore, без которого хэш не проверить, на другой телефон
 * всё равно не переезжает.
 */
object AppLockStore {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** «Блокировать»: сразу / через 1 мин (по умолчанию) / через 5 мин. */
    @JvmField
    val TIMEOUTS_MS: LongArray = longArrayOf(0, 60_000, 5 * 60_000)

    /** Подписи [TIMEOUTS_MS] — ресурсы (openspec android-i18n). */
    @JvmField
    val TIMEOUT_TITLES: IntArray =
        intArrayOf(R.string.lock_timeout_now, R.string.lock_timeout_1min, R.string.lock_timeout_5min)

    @JvmStatic
    fun timeoutTitles(ctx: Context): Array<String> =
        Array(TIMEOUT_TITLES.size) { i -> ctx.getString(TIMEOUT_TITLES[i]) }

    private const val DEFAULT_TIMEOUT_MS = 60_000L

    /** Ошибок до первой паузы; дальше пауза удваивается с каждой ошибкой до потолка. */
    const val FREE_ATTEMPTS = 5
    internal const val FIRST_LOCKOUT_MS = 30_000L
    internal const val MAX_LOCKOUT_MS = 15 * 60_000L

    private const val PREFS_NAME = "app_lock"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_LENGTH = "pin_length"
    private const val KEY_ITERATIONS = "pin_iterations"
    private const val KEY_TIMEOUT = "lock_timeout_ms"
    private const val KEY_FAILED = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val KEY_BIOMETRIC = "biometric_on"
    private const val KEY_HIDE_IN_RECENTS = "hide_in_recents"

    private var pepper: PinHasher.Pepper = PinHasher.KeystorePepper()

    @VisibleForTesting
    @JvmStatic
    fun setPepperForTest(p: PinHasher.Pepper) {
        pepper = p
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Код ───────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isPinSet(ctx: Context): Boolean = prefs(ctx).contains(KEY_HASH)

    @JvmStatic
    fun getPinLength(ctx: Context): Int = prefs(ctx).getInt(KEY_LENGTH, MIN_LENGTH)

    /** Долгая операция (PBKDF2) — не с главного потока. */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun setPin(ctx: Context, pin: String?) {
        if (!isValidPin(pin)) throw IllegalArgumentException("pin must be 4-6 digits")
        pin!!
        pepper.ensureKey()
        val salt = PinHasher.newSalt()
        val iterations = PinHasher.DEFAULT_ITERATIONS
        val hash = PinHasher.hash(pin, salt, iterations, pepper)
        prefs(ctx).edit()
            .putString(KEY_HASH, b64(hash))
            .putString(KEY_SALT, b64(salt))
            .putInt(KEY_LENGTH, pin.length)
            .putInt(KEY_ITERATIONS, iterations)
            .remove(KEY_FAILED)
            .remove(KEY_LOCKOUT_UNTIL)
            .commit()
    }

    /**
     * Выключение кода: остаются только настройки, не относящиеся к нему (таймаут).
     * Отпечаток без кода не живёт — выключается вместе с ключом.
     */
    @JvmStatic
    fun clearPin(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_HASH).remove(KEY_SALT).remove(KEY_LENGTH).remove(KEY_ITERATIONS)
            .remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).remove(KEY_BIOMETRIC)
            .commit()
        BiometricKey.delete()
    }

    @JvmStatic
    fun isValidPin(pin: String?): Boolean =
        pin != null && pin.length >= MIN_LENGTH && pin.length <= MAX_LENGTH &&
            pin.chars().allMatch { c -> c >= '0'.code && c <= '9'.code }

    /**
     * Проверка с учётом пауз: неверный код увеличивает счётчик, верный — сбрасывает.
     * Долгая операция (PBKDF2) — не с главного потока.
     *
     * @throws PinHasher.KeyLostException ключа Keystore нет — код не проверить
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun check(ctx: Context, pin: String, nowMillis: Long): Boolean {
        if (getLockoutRemainingMs(ctx, nowMillis) > 0) return false
        val p = prefs(ctx)
        val hash = p.getString(KEY_HASH, null)
        val salt = p.getString(KEY_SALT, null)
        if (hash == null || salt == null) return false

        val ok = PinHasher.matches(
            pin, unb64(salt),
            p.getInt(KEY_ITERATIONS, PinHasher.DEFAULT_ITERATIONS), unb64(hash), pepper
        )
        if (ok) {
            p.edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).commit()
        } else {
            val failed = p.getInt(KEY_FAILED, 0) + 1
            val lockout = lockoutMs(failed)
            val e = p.edit().putInt(KEY_FAILED, failed)
            if (lockout > 0) e.putLong(KEY_LOCKOUT_UNTIL, nowMillis + lockout)
            e.commit()
        }
        return ok
    }

    // ── Паузы после ошибок ────────────────────────────────────────────────────

    /** Пауза после `failed`-й ошибки подряд: 0 для первых пяти, затем 30 с, 1 мин, 2 мин… до 15 мин. */
    @JvmStatic
    internal fun lockoutMs(failed: Int): Long {
        if (failed < FREE_ATTEMPTS) return 0
        val doublings = minOf(failed - FREE_ATTEMPTS, 10)
        return minOf(FIRST_LOCKOUT_MS shl doublings, MAX_LOCKOUT_MS)
    }

    /**
     * Сколько ещё ждать. Часы считаем по `currentTimeMillis`, чтобы пауза пережила
     * перезагрузку; переведённые назад часы не растянут её больше потолка.
     */
    @JvmStatic
    fun getLockoutRemainingMs(ctx: Context, nowMillis: Long): Long {
        val until = prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0)
        val remaining = until - nowMillis
        if (remaining <= 0) return 0
        return minOf(remaining, MAX_LOCKOUT_MS)
    }

    @JvmStatic
    fun getFailedAttempts(ctx: Context): Int = prefs(ctx).getInt(KEY_FAILED, 0)

    /** Вход по отпечатку: владелец подтверждён — счётчик ошибок кода и пауза снимаются. */
    @JvmStatic
    fun resetFailures(ctx: Context) {
        prefs(ctx).edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).commit()
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────────

    /** Флаг, а не сам отпечаток: его сверяет система, приложение получает только ответ. */
    @JvmStatic
    fun isBiometricOn(ctx: Context): Boolean =
        isPinSet(ctx) && prefs(ctx).getBoolean(KEY_BIOMETRIC, false)

    /** Выключение удаляет и ключ: включат снова — [BiometricKey.create] заведёт новый. */
    @JvmStatic
    fun setBiometricOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BIOMETRIC, on).apply()
        if (!on) BiometricKey.delete()
    }

    // ── Недавние ──────────────────────────────────────────────────────────────

    /** «Скрывать в недавних» (п. 4.2): по умолчанию включено, действует только при заданном коде. */
    @JvmStatic
    fun isHideInRecents(ctx: Context): Boolean =
        isPinSet(ctx) && prefs(ctx).getBoolean(KEY_HIDE_IN_RECENTS, true)

    @JvmStatic
    fun getHideInRecentsSetting(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HIDE_IN_RECENTS, true)

    @JvmStatic
    fun setHideInRecents(ctx: Context, hide: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_IN_RECENTS, hide).apply()
    }

    // ── Таймаут ───────────────────────────────────────────────────────────────

    @JvmStatic
    fun getTimeoutMs(ctx: Context): Long = prefs(ctx).getLong(KEY_TIMEOUT, DEFAULT_TIMEOUT_MS)

    @JvmStatic
    fun setTimeoutMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_TIMEOUT, ms).apply()
    }

    @JvmStatic
    fun getTimeoutTitle(ctx: Context): String {
        val ms = getTimeoutMs(ctx)
        for (i in TIMEOUTS_MS.indices) if (TIMEOUTS_MS[i] == ms) return ctx.getString(TIMEOUT_TITLES[i])
        return ctx.getString(TIMEOUT_TITLES[1])
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
