package com.example.wallettransactions.sync

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Чистая логика входа по паролю (change add-password-login): разбор `GET /auth/methods`,
 * классификация ответов, округление `Retry-After`, проверки регистрации, схема адреса.
 * Robolectric нужен только ради org.json (в JVM-тестах Android SDK — заглушка).
 */
@RunWith(RobolectricTestRunner::class)
class PasswordAuthTest {

    // ---- GET /auth/methods ----

    @Test
    fun methods_both() {
        val m = PasswordAuth.parseMethods("{\"methods\":[\"password\",\"google\"],\"registration\":true}")
        assertTrue(m.password)
        assertTrue(m.google)
        assertTrue(m.registration)
    }

    @Test
    fun methods_googleOnly() {
        val m = PasswordAuth.parseMethods("{\"methods\":[\"google\"],\"registration\":false}")
        assertFalse(m.password)
        assertTrue(m.google)
        assertFalse(m.registration)
    }

    @Test
    fun methods_passwordOnly_noRegistrationFieldMeansClosed() {
        val m = PasswordAuth.parseMethods("{\"methods\":[\"password\"]}")
        assertTrue(m.password)
        assertFalse(m.google)
        assertFalse(m.registration)
    }

    @Test
    fun methods_unknownMethodIgnored() {
        val m = PasswordAuth.parseMethods("{\"methods\":[\"github\",\"google\"],\"registration\":false}")
        assertFalse(m.password)
        assertTrue(m.google)
    }

    @Test(expected = JSONException::class)
    fun methods_garbageThrows() {
        PasswordAuth.parseMethods("<html>502</html>")
    }

    // ---- ответы POST /auth/login ----

    @Test
    fun login_ok() {
        val r = PasswordAuth.classify(200, "{\"access_token\":\"a\"}", null, 200)
        assertEquals(PasswordAuth.Outcome.OK, r.outcome)
    }

    @Test
    fun login_401_badCredentials_keepsServerText() {
        val r = PasswordAuth.classify(401, "{\"error\":\"неверный email или пароль\"}", null, 200)
        assertEquals(PasswordAuth.Outcome.BAD_CREDENTIALS, r.outcome)
        assertEquals("неверный email или пароль", r.error)
    }

    @Test
    fun login_429_roundsMinutesUp() {
        val r = PasswordAuth.classify(429, "{\"error\":\"x\"}", "600", 200)
        assertEquals(PasswordAuth.Outcome.RATE_LIMITED, r.outcome)
        assertEquals(10, r.retryMinutes)
        assertEquals(15, PasswordAuth.classify(429, "", "899", 200).retryMinutes) // 899 с = 14,98 мин -> 15
        assertEquals(1, PasswordAuth.classify(429, "", "1", 200).retryMinutes)
    }

    @Test
    fun login_403_variantsByErrorField() {
        assertEquals(
            PasswordAuth.Outcome.INSECURE_TRANSPORT,
            PasswordAuth.classify(403, "{\"error\":\"insecure_transport\"}", null, 200).outcome
        )
        assertEquals(
            PasswordAuth.Outcome.REGISTRATION_CLOSED,
            PasswordAuth.classify(403, "{\"error\":\"registration_closed\"}", null, 202).outcome
        )
        assertEquals(
            PasswordAuth.Outcome.METHOD_DISABLED,
            PasswordAuth.classify(403, "{\"error\":\"auth_method_disabled\"}", null, 200).outcome
        )
        // чужой 403 — обычная HTTP-ошибка
        assertEquals(PasswordAuth.Outcome.HTTP_ERROR, PasswordAuth.classify(403, "{\"error\":\"forbidden\"}", null, 200).outcome)
    }

    @Test
    fun login_otherCodesAreHttpError() {
        val r = PasswordAuth.classify(503, "{\"error\":\"сервис недоступен\"}", null, 200)
        assertEquals(PasswordAuth.Outcome.HTTP_ERROR, r.outcome)
        assertEquals(503, r.code)
        assertEquals(PasswordAuth.Outcome.HTTP_ERROR, PasswordAuth.classify(400, "не JSON", null, 200).outcome)
        assertEquals("", PasswordAuth.classify(500, "не JSON", null, 200).error)
    }

    @Test
    fun register_202_isOk_but_200_isNot() {
        assertEquals(PasswordAuth.Outcome.OK, PasswordAuth.classify(202, "{\"status\":\"accepted\"}", null, 202).outcome)
        assertEquals(PasswordAuth.Outcome.HTTP_ERROR, PasswordAuth.classify(200, "", null, 202).outcome)
    }

    @Test
    fun retryMinutes_edgeCases() {
        assertEquals(1, PasswordAuth.retryMinutes(null))
        assertEquals(1, PasswordAuth.retryMinutes("abc"))
        assertEquals(1, PasswordAuth.retryMinutes("0"))
        assertEquals(1, PasswordAuth.retryMinutes("-5"))
        assertEquals(1, PasswordAuth.retryMinutes("60"))
        assertEquals(2, PasswordAuth.retryMinutes("61"))
        assertEquals(10, PasswordAuth.retryMinutes(" 600 "))
    }

    @Test
    fun tokens_parsed() {
        val t = PasswordAuth.parseTokens("{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"expires_in\":900}")
        assertEquals("AT", t.accessToken)
        assertEquals("RT", t.refreshToken)
    }

    @Test(expected = JSONException::class)
    fun tokens_missingFieldThrows() {
        PasswordAuth.parseTokens("{\"access_token\":\"AT\"}")
    }

    // ---- тела запросов ----

    @Test
    fun loginBody_hasDeviceAndPlatform() {
        val o = PasswordAuth.loginBody("user@example.com", "secret pass 123", "dev-1", "Huawei P20 Pro")
        assertEquals("user@example.com", o.getString("email"))
        assertEquals("secret pass 123", o.getString("password"))
        assertEquals("dev-1", o.getString("device_id"))
        assertEquals("Huawei P20 Pro", o.getString("device_name"))
        assertEquals("android", o.getString("platform"))
    }

    @Test
    fun registerBody_omitsBlankName() {
        assertFalse(PasswordAuth.registerBody("a@b.com", "0123456789", "   ").has("name"))
        assertFalse(PasswordAuth.registerBody("a@b.com", "0123456789", null).has("name"))
        assertEquals("Иван", PasswordAuth.registerBody("a@b.com", "0123456789", " Иван ").getString("name"))
    }

    // ---- проверки формы регистрации ----

    @Test
    fun registration_shortPassword() {
        assertEquals(
            PasswordAuth.RegistrationCheck.PASSWORD_SHORT,
            PasswordAuth.checkRegistration("a@b.com", "123456789", "123456789") // 9
        )
        assertEquals(
            PasswordAuth.RegistrationCheck.OK,
            PasswordAuth.checkRegistration("a@b.com", "1234567890", "1234567890") // 10
        )
    }

    @Test
    fun registration_lengthCountsCharactersNotBytes() {
        // 10 кириллических символов — 20 байт, но допустимо; 9 — нет
        assertEquals(
            PasswordAuth.RegistrationCheck.OK,
            PasswordAuth.checkRegistration("a@b.com", "жжжжжжжжжж", "жжжжжжжжжж")
        )
        assertEquals(
            PasswordAuth.RegistrationCheck.PASSWORD_SHORT,
            PasswordAuth.checkRegistration("a@b.com", "жжжжжжжжж", "жжжжжжжжж")
        )
    }

    @Test
    fun registration_mismatchAndEmptyEmail() {
        assertEquals(
            PasswordAuth.RegistrationCheck.PASSWORD_MISMATCH,
            PasswordAuth.checkRegistration("a@b.com", "0123456789", "0123456780")
        )
        assertEquals(
            PasswordAuth.RegistrationCheck.EMAIL_EMPTY,
            PasswordAuth.checkRegistration("   ", "0123456789", "0123456789")
        )
    }

    // ---- email и схема адреса ----

    @Test
    fun email_normalized() {
        assertEquals("user@mail.com", PasswordAuth.normalizeEmail("  User@Mail.COM \n"))
        assertEquals("", PasswordAuth.normalizeEmail(null))
    }

    @Test
    fun insecureUrl_byScheme() {
        assertTrue(PasswordAuth.isInsecureUrl("http://192.168.1.42:8090/"))
        assertTrue(PasswordAuth.isInsecureUrl("HTTP://localhost:8090/"))
        assertTrue(PasswordAuth.isInsecureUrl("  http://x/"))
        assertFalse(PasswordAuth.isInsecureUrl("https://wallet.example.com/"))
        assertFalse(PasswordAuth.isInsecureUrl(""))
        assertFalse(PasswordAuth.isInsecureUrl(null))
    }
}
