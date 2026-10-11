package com.example.wallettransactions.security;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

/**
 * Что сказать пользователю на ответы {@code BiometricManager}/{@code BiometricPrompt}
 * (org/docs/TODO_app_lock.md, пп. 3.4–3.5). Отдельно от экранов — чтобы проверять тестами.
 */
public final class BiometricErrors {

    public static final String KEY_INVALIDATED =
            "Отпечатки на телефоне изменились — вход по отпечатку выключен. Включите заново в настройках.";

    private BiometricErrors() {}

    /**
     * Почему отпечаток нельзя включить; null — можно.
     *
     * @param canAuthenticate {@code BiometricManager.canAuthenticate(BIOMETRIC_STRONG)}
     */
    public static String unavailableReason(boolean pinSet, int canAuthenticate) {
        if (!pinSet) return "Сначала включите код доступа";
        switch (canAuthenticate) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return null;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "Добавьте отпечаток в настройках телефона";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return "Датчику отпечатков нужно обновление безопасности телефона";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "Датчик отпечатков сейчас недоступен";
            default:
                return "На телефоне нет подходящего датчика отпечатков";
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
    public static String lockScreenMessage(int errorCode) {
        switch (errorCode) {
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_CANCELED:
                return null;
            case BiometricPrompt.ERROR_LOCKOUT:
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return "Отпечаток временно недоступен — введите код";
            default:
                return "Отпечаток не сработал — введите код";
        }
    }
}
