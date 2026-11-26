package com.example.wallettransactions.security

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import com.example.wallettransactions.R
import com.example.wallettransactions.TokenStore

/**
 * «Забыли код?» (org/docs/TODO_app_lock.md, раздел 5, решено А + Г). Код знает только
 * пользователь — восстановить нельзя, можно снять:
 *  - А — кодом блокировки самого телефона ([resetAndUnlock]);
 *  - Г — стиранием данных приложения, всегда доступно; единственный путь,
 *    если блокировки экрана на телефоне нет.
 *
 * Вход через Google сюда не годится: аккаунт на телефоне уже залогинен, выбор
 * аккаунта прошёл бы любой, кто держит разблокированный телефон.
 */
object ForgotPin {

    private const val TAG = "ForgotPin"

    /** Вариант А доступен: на телефоне есть PIN/графический ключ/пароль. */
    @JvmStatic
    fun isDeviceSecure(ctx: Context): Boolean {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        return km != null && km.isDeviceSecure
    }

    /** Код телефона подтверждён: код приложения (и отпечаток с ним) снят, замок открыт. */
    @JvmStatic
    fun resetAndUnlock(ctx: Context) {
        AppLockStore.clearPin(ctx)
        AppLock.unlock()
    }

    /** Предупреждение перед стиранием — зависит от того, есть ли копия операций на сервере. */
    @JvmStatic
    fun wipeWarning(c: Context, linkedToServer: Boolean): String =
        c.getString(if (linkedToServer) R.string.wipe_warning_linked else R.string.wipe_warning_offline)

    /** Связан ли телефон с сервером. Хранилище токенов не открылось — считаем, что нет: так предупреждение строже. */
    @JvmStatic
    fun isLinkedToServer(ctx: Context): Boolean =
        try {
            TokenStore.getRefreshToken(ctx) != null
        } catch (e: RuntimeException) {
            Log.w(TAG, "TokenStore", e)
            false
        }
}
