package com.example.wallettransactions

import android.content.Context

/**
 * Тексты интерфейса для классов без своего экрана — импорт, синк, ИИ (openspec
 * android-i18n, design D2): журнал синка, отчёт импорта и т.п. строятся глубоко в логике,
 * куда `Context` не передаётся. Берёт строки из ресурсов приложения на текущем
 * языке; контекст ставит `WalletApplication.onCreate`.
 */
object Texts {

    @Volatile
    private var app: Context? = null

    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun ctx(): Context {
        val c = app ?: throw IllegalStateException("Texts.init was not called (WalletApplication.onCreate)")
        return AppLanguage.localized(c)
    }

    /** Контекст приложения на текущем языке — для помощников, принимающих `Context`. */
    @JvmStatic
    fun context(): Context = ctx()

    @JvmStatic
    fun get(resId: Int): String = ctx().getString(resId)

    @JvmStatic
    fun get(resId: Int, vararg args: Any?): String = ctx().getString(resId, *args)

    /** Строка с числом по правилам языка (`<plurals>`); `n` — и выбор формы, и первый аргумент. */
    @JvmStatic
    fun plural(resId: Int, n: Int, vararg args: Any?): String {
        val all = arrayOfNulls<Any>(args.size + 1)
        all[0] = n
        System.arraycopy(args, 0, all, 1, args.size)
        return ctx().resources.getQuantityString(resId, n, *all)
    }
}
