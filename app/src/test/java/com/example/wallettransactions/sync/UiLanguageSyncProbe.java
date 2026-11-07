package com.example.wallettransactions.sync;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

/** Доступ к package-private загрузке синка для {@code UiLanguageTest} (журнал на языке интерфейса). */
public final class UiLanguageSyncProbe {

    private UiLanguageSyncProbe() {}

    /** Загружает одну новую валюту и возвращает текст журнала прохода. */
    public static String journalAfterCurrencyPull(SQLiteDatabase db) throws Exception {
        String icon = "0d000000-0000-4000-8000-000000000001";
        ContentValues cv = new ContentValues();
        cv.put("name", "probe icon");
        cv.put("image", new byte[]{1});
        cv.put("icon_type", 1);
        cv.put("uuid", icon);
        cv.put("origin", 0);
        cv.put("dirty", 0);
        db.insertOrThrow("icon", null, cv);
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrency(db, new JSONObject()
                .put("uuid", "1e000000-0000-4000-8000-000000000001")
                .put("ledger_id", "3c000000-0000-4000-8000-000000000004")
                .put("name", "Probe").put("iso_code", "PRB").put("icon_uuid", icon)
                .put("symbol", "P").put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL), log);
        return log.toDisplayText();
    }
}
