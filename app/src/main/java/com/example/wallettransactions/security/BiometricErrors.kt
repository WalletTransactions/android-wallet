package com.example.wallettransactions.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.example.wallettransactions.R

/**
 * Что сказать пользователю на ответы `BiometricManager`/`BiometricPrompt`
 * (org/docs/TODO_app_lock.md, пп. 3.4–3.5). Отдельно от экранов — чтобы проверять тестами.
 */
object BiometricErrors {

    /** Отпечатки в телефоне изменились (ключ инвалидирован). */
    @JvmStatic
    fun keyInvalidated(c: Context): String = c.getString(R.string.biometric_key_invalidated)

    /**
     * Почему отпечаток нельзя включить; null — можно.
     *
     * @param canAuthenticate `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
     */
    @JvmStatic
    fun unavailableReason(c: Context, pinSet: Boolean, canAuthenticate: Int): String? {
        if (!pinSet) return c.getString(R.string.biometric_need_pin)
        return when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> c.getString(R.string.biometric_none_enrolled)
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> c.getString(R.string.biometric_security_update)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> c.getString(R.string.biometric_hw_unavailable)
            else -> c.getString(R.string.biometric_no_hardware)
        }
    }

    /** Нет отпечатков в телефоне — тогда в объяснении есть кнопка «Открыть настройки». */
    @JvmStatic
    fun canEnroll(pinSet: Boolean, canAuthenticate: Int): Boolean =
        pinSet && canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    /**
     * Строка на замке после `onAuthenticationError`; null — промолчать
     * (пользователь сам выбрал «Ввести код» или диалог закрылся вместе с экраном).
     */
    @JvmStatic
    fun lockScreenMessage(c: Context, errorCode: Int): String? =
        when (errorCode) {
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED -> null
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> c.getString(R.string.biometric_lockout)
            else -> c.getString(R.string.biometric_failed)
        }
}
