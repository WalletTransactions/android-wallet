package com.example.wallettransactions.sync;

import android.database.sqlite.SQLiteDatabase;

/**
 * Поиск локальной строки по серверному uuid вне пакета {@code sync} (экран
 * «ИИ» сверяет черновик с телефоном). Логика — та же, что у pull:
 * {@link SyncEngine#localUuid} учитывает uuid, записанные без дефисов.
 */
public final class LocalUuid {

    private LocalUuid() {}

    /** uuid в том написании, в каком он лежит в {@code table}, или {@code null}. */
    public static String find(SQLiteDatabase db, String table, String uuid) {
        return SyncEngine.localUuid(db, table, uuid);
    }
}
