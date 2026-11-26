package com.example.wallettransactions.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Вход и регистрация по email и паролю (change add-password-login): чистая логика без
 * Android и сети — разбор ответов google-auth, проверки полей и сборка тел запросов.
 * Сами запросы шлёт [com.example.wallettransactions.Activities.SyncLinkActivity],
 * тексты ошибок собирает [PasswordAuthMessages].
 *
 * Контракт ручек — `google-auth/openapi.yaml`: `GET /auth/methods`,
 * `POST /auth/login`, `POST /auth/register`.
 */
object PasswordAuth {

    /** Минимальная длина пароля в символах (как на сервере и в wallet-web). */
    const val MIN_PASSWORD = 10

    /** Ответ `GET /auth/methods`: какие способы входа включены на сервере. */
    class Methods(
        @JvmField val password: Boolean,
        @JvmField val google: Boolean,
        /** Открыта ли самостоятельная регистрация (сервер уже учёл включённый `password`). */
        @JvmField val registration: Boolean
    )

    /** Разбирает `{"methods":["password","google"],"registration":true}`. */
    @JvmStatic
    @Throws(JSONException::class)
    fun parseMethods(json: String): Methods {
        val o = JSONObject(json)
        var password = false
        var google = false
        val arr: JSONArray = o.getJSONArray("methods")
        for (i in 0 until arr.length()) {
            val m = arr.getString(i)
            if ("password" == m) password = true
            else if ("google" == m) google = true
        }
        return Methods(password, google, o.optBoolean("registration", false))
    }

    /** Адрес сервера по обычному HTTP: пароль уйдёт открытым текстом (предупреждение на экране). */
    @JvmStatic
    fun isInsecureUrl(baseUrl: String?): Boolean =
        baseUrl != null && baseUrl.trim().lowercase(Locale.ROOT).startsWith("http://")

    /** Email как на сервере: без пробелов по краям, в нижнем регистре. */
    @JvmStatic
    fun normalizeEmail(raw: String?): String =
        if (raw == null) "" else raw.trim().lowercase(Locale.ROOT)

    /** Исход ответа ручек пароля. */
    enum class Outcome {
        /** 200 (вход) или 202 (регистрация). */
        OK,
        /** 401 — неверный email или пароль. */
        BAD_CREDENTIALS,
        /** 403 `insecure_transport` — сервер принимает пароль только по https. */
        INSECURE_TRANSPORT,
        /** 403 `registration_closed`. */
        REGISTRATION_CLOSED,
        /** 403 `auth_method_disabled` — вход по паролю выключен на сервере. */
        METHOD_DISABLED,
        /** 429 — слишком много попыток, [Result.retryMinutes]. */
        RATE_LIMITED,
        /** Любой другой код. */
        HTTP_ERROR
    }

    class Result internal constructor(
        @JvmField val outcome: Outcome,
        @JvmField val code: Int,
        @JvmField val body: String,
        /** Текст `error` из ответа сервера (пусто, если тела нет или оно не JSON). */
        @JvmField val error: String,
        /** Для [Outcome.RATE_LIMITED]: через сколько минут повторить (округлено вверх, не меньше 1). */
        @JvmField val retryMinutes: Int
    )

    /**
     * Классифицирует ответ `POST /auth/login` или `POST /auth/register`.
     *
     * @param success   код, который считается успехом (`200` для входа, `202` для регистрации)
     * @param retryAfter значение заголовка `Retry-After` (секунды) или `null`
     */
    @JvmStatic
    fun classify(code: Int, body: String?, retryAfter: String?, success: Int): Result {
        val safeBody = body ?: ""
        val error = errorField(safeBody)
        if (code == success) return Result(Outcome.OK, code, safeBody, error, 0)
        if (code == 401) return Result(Outcome.BAD_CREDENTIALS, code, safeBody, error, 0)
        if (code == 429) return Result(Outcome.RATE_LIMITED, code, safeBody, error, retryMinutes(retryAfter))
        if (code == 403) {
            if ("insecure_transport" == error) return Result(Outcome.INSECURE_TRANSPORT, code, safeBody, error, 0)
            if ("registration_closed" == error) return Result(Outcome.REGISTRATION_CLOSED, code, safeBody, error, 0)
            if ("auth_method_disabled" == error) return Result(Outcome.METHOD_DISABLED, code, safeBody, error, 0)
        }
        return Result(Outcome.HTTP_ERROR, code, safeBody, error, 0)
    }

    /** `Retry-After` в секундах -> минуты, округление вверх; нет или не число — 1. */
    @JvmStatic
    fun retryMinutes(retryAfter: String?): Int {
        if (retryAfter == null) return 1
        return try {
            val sec = retryAfter.trim().toLong()
            if (sec <= 0) 1 else minOf(Int.MAX_VALUE.toLong(), (sec + 59) / 60).toInt()
        } catch (e: NumberFormatException) {
            1
        }
    }

    private fun errorField(body: String): String =
        try {
            JSONObject(body).optString("error", "")
        } catch (e: JSONException) {
            ""
        }

    /** Пара токенов из успешного ответа входа. */
    class Tokens internal constructor(
        @JvmField val accessToken: String,
        @JvmField val refreshToken: String
    )

    /** Разбирает `{"access_token","refresh_token","expires_in"}` успешного входа. */
    @JvmStatic
    @Throws(JSONException::class)
    fun parseTokens(body: String): Tokens {
        val o = JSONObject(body)
        return Tokens(o.getString("access_token"), o.getString("refresh_token"))
    }

    /** Тело `POST /auth/login`: стабильный id установки, имя устройства и платформа android. */
    @JvmStatic
    fun loginBody(email: String?, password: String?, deviceId: String?, deviceName: String?): JSONObject {
        val o = JSONObject()
        try {
            o.put("email", email)
            o.put("password", password)
            o.put("device_id", deviceId)
            o.put("device_name", deviceName)
            o.put("platform", "android")
        } catch (e: JSONException) {
            throw AssertionError(e) // ключи и значения — обычные строки
        }
        return o
    }

    /** Тело `POST /auth/register`; пустое имя не отправляется (сервер возьмёт часть email). */
    @JvmStatic
    fun registerBody(email: String?, password: String?, name: String?): JSONObject {
        val o = JSONObject()
        try {
            o.put("email", email)
            o.put("password", password)
            if (name != null && name.trim().isNotEmpty()) o.put("name", name.trim())
        } catch (e: JSONException) {
            throw AssertionError(e)
        }
        return o
    }

    /** Результат проверки формы регистрации на телефоне (до запроса). */
    enum class RegistrationCheck { OK, EMAIL_EMPTY, PASSWORD_SHORT, PASSWORD_MISMATCH }

    /** Длина считается в символах (кодовых точках), как на сервере. */
    @JvmStatic
    fun checkRegistration(email: String?, password: String?, password2: String?): RegistrationCheck {
        if (normalizeEmail(email).isEmpty()) return RegistrationCheck.EMAIL_EMPTY
        if (password == null || password.codePointCount(0, password.length) < MIN_PASSWORD) {
            return RegistrationCheck.PASSWORD_SHORT
        }
        if (password != password2) return RegistrationCheck.PASSWORD_MISMATCH
        return RegistrationCheck.OK
    }
}
