package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * «Полная очистка базы» (ResetDataActivity.runHardWipe) молча откатывалась на устройстве с
 * капитальными активами: {@code account_asset} ссылается на {@code account} по FK, но её не
 * было в списке очищаемых таблиц, и COMMIT падал (P20, 2026-11-10).
 */
@RunWith(RobolectricTestRunner.class)
public class HardWipeAllTablesTest {

    /** Служебные таблицы, которые не очищаются: не данные пользователя. */
    private static final Set<String> NOT_WIPED = new HashSet<>(Arrays.asList(
            "android_metadata", "sqlite_sequence", "sync_reload_seen"));

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        // Новая база на каждый тест: Singleton держит её между тестами одного процесса.
        for (String name : new String[]{"dbHelper", "readableDatabase", "writeableDatabase"}) {
            Field field = Singleton.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
    }

    /** Новая таблица БД обязана попасть в ALL_TABLES — иначе очистка забудет её или упадёт на FK. */
    @Test
    public void everyDataTableIsInWipeList() {
        Set<String> wiped = new HashSet<>(Arrays.asList(ResetDataActivity.ALL_TABLES));
        List<String> missing = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'", null)) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (!NOT_WIPED.contains(name) && !wiped.contains(name)) missing.add(name);
            }
        }
        assertTrue("таблицы не в ALL_TABLES: " + missing, missing.isEmpty());
    }

    /** Живой сценарий: счёт с активом, очистка в одной транзакции — COMMIT проходит. */
    @Test
    public void wipeWithAssetRow_commits() {
        db.execSQL("DELETE FROM account_asset");
        db.execSQL("DELETE FROM account");
        // Чистая база уже засеяна набором — берём любую его иконку для FK счёта.
        long iconId = android.database.DatabaseUtils.longForQuery(db, "SELECT min(_id) FROM icon", null);
        db.execSQL("INSERT INTO account (_id, name, comment, icon_id) VALUES (9001, 'Квартира', '', " + iconId + ")");
        db.execSQL("INSERT INTO account_asset (_id, asset_type) VALUES (9001, 1)");

        db.beginTransaction();
        try {
            ResetDataActivity.deleteAllRows(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        for (String table : ResetDataActivity.ALL_TABLES) {
            try (Cursor c = db.rawQuery("SELECT count(*) FROM " + table, null)) {
                c.moveToFirst();
                assertEquals(table, 0, c.getInt(0));
            }
        }
    }
}
