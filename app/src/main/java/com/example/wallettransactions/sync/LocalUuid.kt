package com.example.wallettransactions.sync

import android.database.sqlite.SQLiteDatabase

/**
 * Поиск локальной строки по серверному uuid вне пакета `sync` (экран
 * «ИИ» сверяет черновик с телефоном). Логика — та же, что у pull:
 * [SyncEngine.localUuid] учитывает uuid, записанные без дефисов.
 */
object LocalUuid {

    /** uuid в том написании, в каком он лежит в `table`, или `null`. */
    @JvmStatic
    fun find(db: SQLiteDatabase, table: String, uuid: String?): String? =
        SyncEngine.localUuid(db, table, uuid)
}
