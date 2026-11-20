package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.R;

/**
 * Тексты экрана входа по паролю для исходов {@link PasswordAuth.Result}. Отдельно от
 * Activity, чтобы проверяться тестом на обеих локалях без запуска экрана.
 */
public final class PasswordAuthMessages {

    private PasswordAuthMessages() {}

    /**
     * Сообщение об ошибке {@code POST /auth/login}. Недоступность сервера (сеть) сюда не
     * входит — её текст ({@code link_server_unreachable}) собирает вызывающий код с адресом.
     */
    public static String loginError(Context ctx, PasswordAuth.Result r) {
        switch (r.outcome) {
            case BAD_CREDENTIALS:
                return ctx.getString(R.string.link_password_rejected,
                        r.error.isEmpty() ? ctx.getString(R.string.link_password_bad_credentials) : r.error);
            case RATE_LIMITED:
                return ctx.getString(R.string.link_password_rate_limited, r.retryMinutes);
            case INSECURE_TRANSPORT:
                return ctx.getString(R.string.link_password_insecure_transport);
            case METHOD_DISABLED:
                return ctx.getString(R.string.link_password_method_disabled);
            case REGISTRATION_CLOSED:
                return ctx.getString(R.string.link_register_closed);
            case OK: // сюда не попадаем; на всякий случай — нейтральный текст как у прочих кодов
            case HTTP_ERROR:
            default:
                return ctx.getString(R.string.link_sign_in_rejected, r.code, r.body);
        }
    }

    /** Сообщение об ошибке {@code POST /auth/register} (до входа): общий набор плюс «закрыта». */
    public static String registerError(Context ctx, PasswordAuth.Result r) {
        return loginError(ctx, r);
    }

    /** Текст ошибки проверки формы регистрации. */
    public static String registrationCheck(Context ctx, PasswordAuth.RegistrationCheck check) {
        switch (check) {
            case EMAIL_EMPTY:
                return ctx.getString(R.string.link_register_email_empty);
            case PASSWORD_SHORT:
                return ctx.getString(R.string.link_register_password_short, PasswordAuth.MIN_PASSWORD);
            case PASSWORD_MISMATCH:
                return ctx.getString(R.string.link_register_password_mismatch);
            case OK:
            default:
                return "";
        }
    }
}
