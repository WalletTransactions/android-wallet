package com.example.wallettransactions.sync;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Вход и регистрация по email и паролю (change add-password-login): чистая логика без
 * Android и сети — разбор ответов google-auth, проверки полей и сборка тел запросов.
 * Сами запросы шлёт {@link com.example.wallettransactions.Activities.SyncLinkActivity},
 * тексты ошибок собирает {@link PasswordAuthMessages}.
 * <p>
 * Контракт ручек — {@code google-auth/openapi.yaml}: {@code GET /auth/methods},
 * {@code POST /auth/login}, {@code POST /auth/register}.
 */
public final class PasswordAuth {

    /** Минимальная длина пароля в символах (как на сервере и в wallet-web). */
    public static final int MIN_PASSWORD = 10;

    private PasswordAuth() {}

    /** Ответ {@code GET /auth/methods}: какие способы входа включены на сервере. */
    public static final class Methods {
        public final boolean password;
        public final boolean google;
        /** Открыта ли самостоятельная регистрация (сервер уже учёл включённый {@code password}). */
        public final boolean registration;

        public Methods(boolean password, boolean google, boolean registration) {
            this.password = password;
            this.google = google;
            this.registration = registration;
        }
    }

    /** Разбирает {@code {"methods":["password","google"],"registration":true}}. */
    public static Methods parseMethods(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        boolean password = false;
        boolean google = false;
        org.json.JSONArray arr = o.getJSONArray("methods");
        for (int i = 0; i < arr.length(); i++) {
            String m = arr.getString(i);
            if ("password".equals(m)) password = true;
            else if ("google".equals(m)) google = true;
        }
        return new Methods(password, google, o.optBoolean("registration", false));
    }

    /** Адрес сервера по обычному HTTP: пароль уйдёт открытым текстом (предупреждение на экране). */
    public static boolean isInsecureUrl(String baseUrl) {
        return baseUrl != null && baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /** Email как на сервере: без пробелов по краям, в нижнем регистре. */
    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Исход ответа ручек пароля. */
    public enum Outcome {
        /** 200 (вход) или 202 (регистрация). */
        OK,
        /** 401 — неверный email или пароль. */
        BAD_CREDENTIALS,
        /** 403 {@code insecure_transport} — сервер принимает пароль только по https. */
        INSECURE_TRANSPORT,
        /** 403 {@code registration_closed}. */
        REGISTRATION_CLOSED,
        /** 403 {@code auth_method_disabled} — вход по паролю выключен на сервере. */
        METHOD_DISABLED,
        /** 429 — слишком много попыток, {@link Result#retryMinutes}. */
        RATE_LIMITED,
        /** Любой другой код. */
        HTTP_ERROR
    }

    public static final class Result {
        public final Outcome outcome;
        public final int code;
        public final String body;
        /** Текст {@code error} из ответа сервера (пусто, если тела нет или оно не JSON). */
        public final String error;
        /** Для {@link Outcome#RATE_LIMITED}: через сколько минут повторить (округлено вверх, не меньше 1). */
        public final int retryMinutes;

        Result(Outcome outcome, int code, String body, String error, int retryMinutes) {
            this.outcome = outcome;
            this.code = code;
            this.body = body;
            this.error = error;
            this.retryMinutes = retryMinutes;
        }
    }

    /**
     * Классифицирует ответ {@code POST /auth/login} или {@code POST /auth/register}.
     *
     * @param success   код, который считается успехом ({@code 200} для входа, {@code 202} для регистрации)
     * @param retryAfter значение заголовка {@code Retry-After} (секунды) или {@code null}
     */
    public static Result classify(int code, String body, String retryAfter, int success) {
        String safeBody = body == null ? "" : body;
        String error = errorField(safeBody);
        if (code == success) return new Result(Outcome.OK, code, safeBody, error, 0);
        if (code == 401) return new Result(Outcome.BAD_CREDENTIALS, code, safeBody, error, 0);
        if (code == 429) return new Result(Outcome.RATE_LIMITED, code, safeBody, error, retryMinutes(retryAfter));
        if (code == 403) {
            if ("insecure_transport".equals(error)) return new Result(Outcome.INSECURE_TRANSPORT, code, safeBody, error, 0);
            if ("registration_closed".equals(error)) return new Result(Outcome.REGISTRATION_CLOSED, code, safeBody, error, 0);
            if ("auth_method_disabled".equals(error)) return new Result(Outcome.METHOD_DISABLED, code, safeBody, error, 0);
        }
        return new Result(Outcome.HTTP_ERROR, code, safeBody, error, 0);
    }

    /** {@code Retry-After} в секундах -> минуты, округление вверх; нет или не число — 1. */
    public static int retryMinutes(String retryAfter) {
        if (retryAfter == null) return 1;
        try {
            long sec = Long.parseLong(retryAfter.trim());
            return sec <= 0 ? 1 : (int) Math.min(Integer.MAX_VALUE, (sec + 59) / 60);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String errorField(String body) {
        try {
            return new JSONObject(body).optString("error", "");
        } catch (JSONException e) {
            return "";
        }
    }

    /** Пара токенов из успешного ответа входа. */
    public static final class Tokens {
        public final String accessToken;
        public final String refreshToken;

        Tokens(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    /** Разбирает {@code {"access_token","refresh_token","expires_in"}} успешного входа. */
    public static Tokens parseTokens(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        return new Tokens(o.getString("access_token"), o.getString("refresh_token"));
    }

    /** Тело {@code POST /auth/login}: стабильный id установки, имя устройства и платформа android. */
    public static JSONObject loginBody(String email, String password, String deviceId, String deviceName) {
        JSONObject o = new JSONObject();
        try {
            o.put("email", email);
            o.put("password", password);
            o.put("device_id", deviceId);
            o.put("device_name", deviceName);
            o.put("platform", "android");
        } catch (JSONException e) {
            throw new AssertionError(e); // ключи и значения — обычные строки
        }
        return o;
    }

    /** Тело {@code POST /auth/register}; пустое имя не отправляется (сервер возьмёт часть email). */
    public static JSONObject registerBody(String email, String password, String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("email", email);
            o.put("password", password);
            if (name != null && !name.trim().isEmpty()) o.put("name", name.trim());
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        return o;
    }

    /** Результат проверки формы регистрации на телефоне (до запроса). */
    public enum RegistrationCheck { OK, EMAIL_EMPTY, PASSWORD_SHORT, PASSWORD_MISMATCH }

    /** Длина считается в символах (кодовых точках), как на сервере. */
    public static RegistrationCheck checkRegistration(String email, String password, String password2) {
        if (normalizeEmail(email).isEmpty()) return RegistrationCheck.EMAIL_EMPTY;
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD) {
            return RegistrationCheck.PASSWORD_SHORT;
        }
        if (!password.equals(password2)) return RegistrationCheck.PASSWORD_MISMATCH;
        return RegistrationCheck.OK;
    }
}
