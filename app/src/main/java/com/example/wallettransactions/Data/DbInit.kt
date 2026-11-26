package com.example.wallettransactions.Data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.example.wallettransactions.Activities.DbInitActivity

/**
 * Первое открытие базы — не на главном потоке (openspec fix-first-start-anr).
 *
 * [WalletDbHelper.onCreate] заводит схему и заполняет справочник (иконки
 * перекодируются в PNG), `onUpgrade` гоняет миграции. Раньше это шло там, где
 * базу попросили первой, — в `MainActivity.onCreate`, — и на медленном телефоне
 * (Huawei P20 Pro) первый запуск ловил ANR. Теперь точки входа сперва спрашивают
 * [redirectIfNeeded]: база не готова — экран уступает место
 * [DbInitActivity], которая открывает её в фоне и потом пускает дальше.
 */
object DbInit {

    /** Исходный intent экрана, который надо открыть, когда база готова. */
    const val EXTRA_NEXT = "com.example.wallettransactions.DB_INIT_NEXT"

    @Volatile
    private var ready = false

    /**
     * База создана и не ждёт миграций: файл есть и его версия не ниже
     * [WalletDbHelper.DATABASE_VERSION]. Проверка — чтение заголовка файла,
     * без onCreate/onUpgrade; после первого «да» — просто флаг.
     */
    @JvmStatic
    fun isReady(ctx: Context): Boolean {
        if (ready) return true
        val file = ctx.getDatabasePath(WalletDbHelper.DATABASE_NAME)
        if (!file.exists()) return false
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                if (db.version >= WalletDbHelper.DATABASE_VERSION) ready = true
            }
        } catch (e: SQLiteException) {
            // Недописанный файл (процесс убили посреди onCreate) — пусть разбирается helper в фоне.
            return false
        }
        return ready
    }

    /** Вызывает [DbInitActivity], когда база открыта. */
    @JvmStatic
    fun markReady() {
        ready = true
    }

    /** Для тестов: забыть, что база уже проверена. */
    @JvmStatic
    fun resetForTest() {
        ready = false
    }

    /**
     * Звать в `onCreate` точки входа сразу после `super.onCreate`.
     * База готова — false, экран работает как обычно. Нет — открывает
     * [DbInitActivity] с исходным intent'ом, закрывает экран и возвращает true:
     * вызывающий должен сразу выйти из `onCreate`.
     */
    @JvmStatic
    fun redirectIfNeeded(activity: Activity): Boolean {
        if (isReady(activity)) return false
        activity.startActivity(initIntent(activity, activity.intent, activity.javaClass))
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }

    /**
     * Intent экрана подготовки. Исходный уходит в [EXTRA_NEXT] без флагов запуска
     * (задача уже есть), но с правами на файл из «Поделиться»: ClipData и флаги
     * GRANT_* копируются и на сам экран подготовки — иначе право на чужой URI
     * не доехало бы до экрана выписки.
     */
    @JvmStatic
    fun initIntent(ctx: Context, original: Intent, target: Class<*>): Intent {
        val grants = original.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val next = Intent(original).setClass(ctx, target).setFlags(grants)
        val init = Intent(ctx, DbInitActivity::class.java).putExtra(EXTRA_NEXT, next)
        if (original.clipData != null) {
            init.clipData = original.clipData
            init.addFlags(grants)
        }
        return init
    }
}
