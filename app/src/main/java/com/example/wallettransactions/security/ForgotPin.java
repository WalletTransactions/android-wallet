package com.example.wallettransactions.security;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;

import com.example.wallettransactions.TokenStore;

/**
 * «Забыли код?» (org/docs/TODO_app_lock.md, раздел 5, решено А + Г). Код знает только
 * пользователь — восстановить нельзя, можно снять:
 * <ul>
 *   <li>А — кодом блокировки самого телефона ({@link #resetAndUnlock});</li>
 *   <li>Г — стиранием данных приложения, всегда доступно; единственный путь,
 *       если блокировки экрана на телефоне нет.</li>
 * </ul>
 * Вход через Google сюда не годится: аккаунт на телефоне уже залогинен, выбор
 * аккаунта прошёл бы любой, кто держит разблокированный телефон.
 */
public final class ForgotPin {

    private static final String TAG = "ForgotPin";

    private ForgotPin() {}

    /** Вариант А доступен: на телефоне есть PIN/графический ключ/пароль. */
    public static boolean isDeviceSecure(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    /** Код телефона подтверждён: код приложения (и отпечаток с ним) снят, замок открыт. */
    public static void resetAndUnlock(Context ctx) {
        AppLockStore.clearPin(ctx);
        AppLock.unlock();
    }

    /** Предупреждение перед стиранием — зависит от того, есть ли копия операций на сервере. */
    public static String wipeWarning(boolean linkedToServer) {
        return linkedToServer
                ? "Все данные приложения на этом телефоне будут удалены. Операции, ещё не "
                        + "отправленные на сервер, пропадут; остальное вернётся после "
                        + "«Связать с сервером»."
                : "Все данные приложения будут удалены: операции, кошельки, категории, настройки. "
                        + "Телефон не связан с сервером — восстановить их будет неоткуда.";
    }

    /** Связан ли телефон с сервером. Хранилище токенов не открылось — считаем, что нет: так предупреждение строже. */
    public static boolean isLinkedToServer(Context ctx) {
        try {
            return TokenStore.getRefreshToken(ctx) != null;
        } catch (RuntimeException e) {
            Log.w(TAG, "TokenStore", e);
            return false;
        }
    }
}
