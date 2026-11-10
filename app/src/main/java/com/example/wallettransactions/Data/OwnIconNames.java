package com.example.wallettransactions.Data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Texts;

/**
 * Свободное имя для своей иконки, уступающей имя иконке с сервера (icon-sync, «Конфликт
 * имени при загрузке»): «<имя> (своя)», занято — «<имя> (своя 2)», «(своя 3)», … среди
 * живых иконок. Общий код pull ({@code SyncEngine.applyIcon}) и починки v90
 * ({@link WalletDbHelper#reviveServerIconsV90}).
 */
public final class OwnIconNames {

    private OwnIconNames() {}

    public static String freeOwnName(SQLiteDatabase db, String name) {
        String candidate = Texts.get(R.string.icon_own_suffix, name);
        for (int n = 2; nameTaken(db, candidate); n++) candidate = Texts.get(R.string.icon_own_suffix_n, name, n);
        return candidate;
    }

    private static boolean nameTaken(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery("SELECT 1 FROM icon WHERE name=? AND deleted_at IS NULL LIMIT 1",
                new String[]{name});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }
}
