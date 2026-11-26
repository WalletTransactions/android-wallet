package com.example.wallettransactions.Data

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/**
 * Правило совпадения имён счетов: без учёта регистра, «ё» = «е». «Кафе», «кафе» и
 * «КАФЁ» — одно имя. То же правило держат сервер (uq_account_name в
 * wallet-api/schema/table/account.sql: `lower(translate(name, 'Ёё', 'Ее'))`) и бот
 * (wallet-telegram, text::normalize_name).
 *
 * Ключ считается только в SQL — тем же выражением, по которому построен индекс
 * uq_account_name, чтобы проверка в коде и индекс не могли разойтись. `lower()`
 * SQLite в Android собран с ICU и понижает не только ASCII (в отличие от
 * `COLLATE NOCASE`). Своя SQL-функция недоступна (API 30, minSdk 27), а цепочка
 * `replace()` по каждой букве не помещается в стек парсера SQLite до 3.46.
 */
object AccountNames {
    /**
     * SQL-выражение ключа от `expr` (имя колонки или `?`). Текст должен
     * совпадать с выражением индекса, иначе SQLite индекс не использует, — поэтому
     * строится только здесь.
     */
    @JvmStatic
    fun keySql(expr: String): String {
        return "replace(lower($expr), 'ё', 'е')" // not UI: SQL
    }

    /** Условие WHERE «имя в колонке совпадает с параметром ?» по правилу выше. */
    @JvmStatic
    fun sameSql(column: String): String {
        return keySql(column) + " = " + keySql("?")
    }

    /**
     * Ключ для поиска по подстроке (openspec wallets-filter-and-debt-autoarchive, D6): те же
     * правила, что у ключа выше — без регистра, «ё» = «е», пробелы по краям обрезаны. Считается
     * в Java: для фильтра списка в памяти БД не нужна.
     */
    @JvmStatic
    fun searchKey(text: String?): String {
        if (text == null) return ""
        return text.trim { it <= ' ' }.lowercase(Locale.ROOT).replace('ё', 'е')
    }

    /**
     * Имя подходит под строку поиска: подстрока как текст (без шаблона, «(» не падает), пустая
     * строка подходит всему.
     */
    @JvmStatic
    fun matchesSearch(name: String?, query: String?): Boolean {
        val q = searchKey(query)
        return q.isEmpty() || searchKey(name).contains(q)
    }

    /** Ключ имени, посчитанный тем же SQLite, что держит индекс. */
    @JvmStatic
    fun key(db: SQLiteDatabase, name: String?): String {
        db.rawQuery("SELECT " + keySql("?"), arrayOf(name)).use { c ->
            c.moveToFirst()
            return c.getString(0)
        }
    }
}
