package com.example.wallettransactions.security;

import android.content.Context;

import com.example.wallettransactions.R;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

/**
 * Что сказать пользователю на ответы {@code BiometricManager}/{@code BiometricPrompt}
 * (org/docs/TODO_app_lock.md, пп. 3.4–3.5). Отдельно от экранов — чтобы проверять тестами.
 */
public final class BiometricErrors {

    /** Отпечатки в телефоне изменились (ключ инвалидирован). */
    public static String keyInvalidated(Context c) {
        return c.getString(R.string.biometric_key_invalidated);
    }

    private BiometricErrors() {}

    /**
     * Почему отпечаток нельзя включить; null — можно.
     *
     * @param canAuthenticate {@code BiometricManager.canAuthenticate(BIOMETRIC_STRONG)}
     */
    public static String unavailableReason(Context c, boolean pinSet, int canAuthenticate) {
        if (!pinSet) return c.getString(R.string.biometric_need_pin);
        switch (canAuthenticate) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return null;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return c.getString(R.string.biometric_none_enrolled);
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return c.getString(R.string.biometric_security_update);
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return c.getString(R.string.biometric_hw_unavailable);
            default:
                return c.getString(R.string.biometric_no_hardware);
        }
    }

    /** Нет отпечатков в телефоне — тогда в объяснении есть кнопка «Открыть настройки». */
    public static boolean canEnroll(boolean pinSet, int canAuthenticate) {
        return pinSet && canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
    }

    /**
     * Строка на замке после {@code onAuthenticationError}; null — промолчать
     * (пользователь сам выбрал «Ввести код» или диалог закрылся вместе с экраном).
     */
    public static String lockScreenMessage(Context c, int errorCode) {
        switch (errorCode) {
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_CANCELED:
                return null;
            case BiometricPrompt.ERROR_LOCKOUT:
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return c.getString(R.string.biometric_lockout);
            default:
                return c.getString(R.string.biometric_failed);
        }
    }
}
