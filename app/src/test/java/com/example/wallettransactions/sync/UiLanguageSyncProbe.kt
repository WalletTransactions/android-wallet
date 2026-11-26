package com.example.wallettransactions.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Доступ к internal загрузке синка для `UiLanguageTest` (журнал на языке интерфейса). */
object UiLanguageSyncProbe {

    /** Загружает одну новую валюту и возвращает текст журнала прохода. */
    @JvmStatic
    @Throws(Exception::class)
    fun journalAfterCurrencyPull(db: SQLiteDatabase): String {
        val icon = "0d000000-0000-4000-8000-000000000001"
        val cv = ContentValues()
        cv.put("name", "probe icon")
        cv.put("image", byteArrayOf(1))
        cv.put("icon_type", 1)
        cv.put("uuid", icon)
        cv.put("origin", 0)
        cv.put("dirty", 0)
        db.insertOrThrow("icon", null, cv)
        val log = SyncLog()
        SyncEngine.applyCurrency(
            db, JSONObject()
                .put("uuid", "1e000000-0000-4000-8000-000000000001")
                .put("ledger_id", "3c000000-0000-4000-8000-000000000004")
                .put("name", "Probe").put("iso_code", "PRB").put("icon_uuid", icon)
                .put("symbol", "P").put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL), log
        )
        return log.toDisplayText()
    }
}
