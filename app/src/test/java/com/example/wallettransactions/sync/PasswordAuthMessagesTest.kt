package com.example.wallettransactions.sync

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Тексты ошибок входа по паролю (change add-password-login) на русском (телефон по умолчанию —
 * `robolectric.properties`) и английском: те же формулировки, что в спеке server-account.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordAuthMessagesTest {

    private val ru: Context = ApplicationProvider.getApplicationContext()

    private fun english(): Context {
        val cfg = Configuration(ru.resources.configuration)
        cfg.setLocale(Locale.ENGLISH)
        return ru.createConfigurationContext(cfg)
    }

    private fun result(code: Int, body: String, retryAfter: String?, success: Int): PasswordAuth.Result =
        PasswordAuth.classify(code, body, retryAfter, success)

    @Test
    fun ru_badCredentials() {
        assertEquals(
            "Сервер отклонил вход: неверный email или пароль",
            PasswordAuthMessages.loginError(ru, result(401, "{\"error\":\"неверный email или пароль\"}", null, 200))
        )
    }

    @Test
    fun ru_badCredentials_withoutServerText_usesDefault() {
        assertEquals(
            "Сервер отклонил вход: неверный email или пароль",
            PasswordAuthMessages.loginError(ru, result(401, "", null, 200))
        )
    }

    @Test
    fun ru_rateLimited() {
        assertEquals(
            "Слишком много попыток. Повторите через 10 мин.",
            PasswordAuthMessages.loginError(ru, result(429, "{}", "600", 200))
        )
    }

    @Test
    fun ru_insecureTransport() {
        assertEquals(
            "Сервер принимает пароль только по https. Укажите адрес с https:// в «Адрес сервера».",
            PasswordAuthMessages.loginError(ru, result(403, "{\"error\":\"insecure_transport\"}", null, 200))
        )
    }

    @Test
    fun ru_registrationClosedAndMethodDisabled() {
        assertEquals(
            "Регистрация закрыта администратором.",
            PasswordAuthMessages.registerError(ru, result(403, "{\"error\":\"registration_closed\"}", null, 202))
        )
        assertEquals(
            "Вход по паролю отключён на сервере.",
            PasswordAuthMessages.loginError(ru, result(403, "{\"error\":\"auth_method_disabled\"}", null, 200))
        )
    }

    @Test
    fun ru_otherHttpError() {
        assertEquals(
            "Сервер отклонил вход: HTTP 503 {\"error\":\"x\"}",
            PasswordAuthMessages.loginError(ru, result(503, "{\"error\":\"x\"}", null, 200))
        )
    }

    @Test
    fun ru_registrationChecks() {
        assertEquals(
            "Пароль должен быть не короче 10 символов",
            PasswordAuthMessages.registrationCheck(ru, PasswordAuth.RegistrationCheck.PASSWORD_SHORT)
        )
        assertEquals(
            "Пароли не совпадают",
            PasswordAuthMessages.registrationCheck(ru, PasswordAuth.RegistrationCheck.PASSWORD_MISMATCH)
        )
        assertEquals(
            "Введите email.",
            PasswordAuthMessages.registrationCheck(ru, PasswordAuth.RegistrationCheck.EMAIL_EMPTY)
        )
        assertEquals("", PasswordAuthMessages.registrationCheck(ru, PasswordAuth.RegistrationCheck.OK))
    }

    @Test
    fun en_texts() {
        val en = english()
        assertEquals(
            "The server rejected the sign-in: wrong email or password",
            PasswordAuthMessages.loginError(en, result(401, "", null, 200))
        )
        assertEquals(
            "Too many attempts. Try again in 10 min.",
            PasswordAuthMessages.loginError(en, result(429, "{}", "600", 200))
        )
        assertEquals(
            "The password must be at least 10 characters",
            PasswordAuthMessages.registrationCheck(en, PasswordAuth.RegistrationCheck.PASSWORD_SHORT)
        )
        assertEquals(
            "Passwords don't match",
            PasswordAuthMessages.registrationCheck(en, PasswordAuth.RegistrationCheck.PASSWORD_MISMATCH)
        )
        assertEquals(
            "Registration is closed by the administrator.",
            PasswordAuthMessages.registerError(en, result(403, "{\"error\":\"registration_closed\"}", null, 202))
        )
    }

    @Test
    fun ru_and_en_screenStringsExist() {
        val en = english()
        val ids = intArrayOf(
            com.example.wallettransactions.R.string.link_password_insecure_warning,
            com.example.wallettransactions.R.string.link_register_taken,
            com.example.wallettransactions.R.string.link_register_note,
        )
        for (id in ids) {
            assertEquals(false, ru.getString(id).isEmpty())
            assertEquals(false, en.getString(id).isEmpty())
        }
        assertEquals(
            "Адрес сервера без https: пароль будет передан открытым текстом.",
            ru.getString(com.example.wallettransactions.R.string.link_password_insecure_warning)
        )
        assertEquals(
            "The server address is not https: the password will be sent in plain text.",
            en.getString(com.example.wallettransactions.R.string.link_password_insecure_warning)
        )
    }
}
