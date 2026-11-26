package com.example.wallettransactions.sync

import android.content.Context
import com.example.wallettransactions.R

/**
 * Тексты экрана входа по паролю для исходов [PasswordAuth.Result]. Отдельно от
 * Activity, чтобы проверяться тестом на обеих локалях без запуска экрана.
 */
object PasswordAuthMessages {

    /**
     * Сообщение об ошибке `POST /auth/login`. Недоступность сервера (сеть) сюда не
     * входит — её текст (`link_server_unreachable`) собирает вызывающий код с адресом.
     */
    @JvmStatic
    fun loginError(ctx: Context, r: PasswordAuth.Result): String =
        when (r.outcome) {
            PasswordAuth.Outcome.BAD_CREDENTIALS ->
                ctx.getString(
                    R.string.link_password_rejected,
                    if (r.error.isEmpty()) ctx.getString(R.string.link_password_bad_credentials) else r.error
                )
            PasswordAuth.Outcome.RATE_LIMITED ->
                ctx.getString(R.string.link_password_rate_limited, r.retryMinutes)
            PasswordAuth.Outcome.INSECURE_TRANSPORT ->
                ctx.getString(R.string.link_password_insecure_transport)
            PasswordAuth.Outcome.METHOD_DISABLED ->
                ctx.getString(R.string.link_password_method_disabled)
            PasswordAuth.Outcome.REGISTRATION_CLOSED ->
                ctx.getString(R.string.link_register_closed)
            // OK сюда не попадаем; на всякий случай — нейтральный текст как у прочих кодов
            PasswordAuth.Outcome.OK, PasswordAuth.Outcome.HTTP_ERROR ->
                ctx.getString(R.string.link_sign_in_rejected, r.code, r.body)
        }

    /** Сообщение об ошибке `POST /auth/register` (до входа): общий набор плюс «закрыта». */
    @JvmStatic
    fun registerError(ctx: Context, r: PasswordAuth.Result): String = loginError(ctx, r)

    /** Текст ошибки проверки формы регистрации. */
    @JvmStatic
    fun registrationCheck(ctx: Context, check: PasswordAuth.RegistrationCheck): String =
        when (check) {
            PasswordAuth.RegistrationCheck.EMAIL_EMPTY -> ctx.getString(R.string.link_register_email_empty)
            PasswordAuth.RegistrationCheck.PASSWORD_SHORT ->
                ctx.getString(R.string.link_register_password_short, PasswordAuth.MIN_PASSWORD)
            PasswordAuth.RegistrationCheck.PASSWORD_MISMATCH -> ctx.getString(R.string.link_register_password_mismatch)
            PasswordAuth.RegistrationCheck.OK -> ""
        }
}
