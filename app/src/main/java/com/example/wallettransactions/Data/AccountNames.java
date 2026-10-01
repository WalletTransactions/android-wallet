package com.example.wallettransactions.Data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Правило совпадения имён счетов: без учёта регистра, «ё» = «е». «Кафе», «кафе» и
 * «КАФЁ» — одно имя. То же правило держат сервер (uq_account_name в
 * wallet-api/schema/table/account.sql: {@code lower(translate(name, 'Ёё', 'Ее'))}) и бот
 * (wallet-telegram, text::normalize_name).
 *
 * <p>Ключ считается только в SQL — тем же выражением, по которому построен индекс
 * uq_account_name, чтобы проверка в коде и индекс не могли разойтись. {@code lower()}
 * SQLite в Android собран с ICU и понижает не только ASCII (в отличие от
 * {@code COLLATE NOCASE}). Своя SQL-функция недоступна (API 30, minSdk 27), а цепочка
 * {@code replace()} по каждой букве не помещается в стек парсера SQLite до 3.46.
 */
public final class AccountNames {
    private AccountNames() {}

    /**
     * SQL-выражение ключа от {@code expr} (имя колонки или {@code ?}). Текст должен
     * совпадать с выражением индекса, иначе SQLite индекс не использует, — поэтому
     * строится только здесь.
     */
    public static String keySql(String expr) {
        return "replace(lower(" + expr + "), 'ё', 'е')";
    }

    /** Условие WHERE «имя в колонке совпадает с параметром ?» по правилу выше. */
    public static String sameSql(String column) {
        return keySql(column) + " = " + keySql("?");
    }

    /** Ключ имени, посчитанный тем же SQLite, что держит индекс. */
    public static String key(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery("SELECT " + keySql("?"), new String[]{name});
        try {
            c.moveToFirst();
            return c.getString(0);
        } finally {
            c.close();
        }
    }
}
