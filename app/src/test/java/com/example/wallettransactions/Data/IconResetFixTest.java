package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Texts;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.UUID;

/**
 * android-wallet#50 (openspec fix-icon-reset-substitutes), задачи 2.3 и 4.3: «Удалить данные»
 * с «Иконки» не трогает иконки с сервера, пересев не заводит дублей, миграция v90 чинит
 * уже пострадавшие базы. Картинки в тестовых строках — по байту: настоящие PNG сида не
 * читаются курсором Robolectric (см. HardWipeCacheInvalidationTest), здесь их и не читаем.
 */
@RunWith(RobolectricTestRunner.class)
public class IconResetFixTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 2: ResetIcons ───────────────────────────────────────────────────────────

    /** Сценарий спеки «Пересев при иконках с сервера». */
    @Test
    public void resetWithReseed_keepsServerIcons_noOwnDuplicates_relinks() {
        // Сид чистой базы: «Groceries» и флаг USD будто пришли с сервера, «Bar» — своя.
        long groceries = liveIdByName("Groceries");
        long flagUsd = liveIdByName("Flag USD");
        long bar = liveIdByName("Bar");
        makeServer(groceries);
        makeServer(flagUsd);
        long accGroceries = account("T groceries", groceries);
        long accBar = account("T bar", bar);
        long usd = currencyIdByIso("USD");
        assertEquals(flagUsd, longOf("currency", usd, "icon_id"));

        WalletFillData.ResetIcons(db, context, true);

        assertNull("серверная не удалена", longOrNull("icon", groceries, "deleted_at"));
        assertNull(longOrNull("icon", flagUsd, "deleted_at"));
        assertEquals("своя «Groceries» не заведена", 1, liveCount("Groceries"));
        assertEquals(1, liveCount("Flag USD"));
        assertEquals(groceries, longOf("account", accGroceries, "icon_id"));
        assertEquals(flagUsd, longOf("currency", usd, "icon_id"));

        long newBar = liveIdByName("Bar");
        assertNotEquals("своя пересеяна заново", bar, newBar);
        assertEquals(newBar, longOf("account", accBar, "icon_id"));
        assertEquals("ни одна иконка с сервера не удалена", 0,
                count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NOT NULL AND origin = "
                        + WalletContract.IconEntry.ORIGIN_SERVER));
    }

    /** Сценарий спеки «Очистка без пересева». */
    @Test
    public void resetWithoutReseed_deletesOnlyUnusedOwnIcons() {
        long server = icon("T server unused", new byte[]{1}, WalletContract.IconEntry.ORIGIN_SERVER, 0, null);
        long own = icon("T own unused", new byte[]{2}, WalletContract.IconEntry.ORIGIN_LOCAL, 0, null);

        WalletFillData.ResetIcons(db, context, false);

        assertNull(longOrNull("icon", server, "deleted_at"));
        assertTrue(longOrNull("icon", own, "deleted_at") != null);
        assertEquals("ни одна иконка с сервера не удалена", 0,
                count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NOT NULL AND origin = "
                        + WalletContract.IconEntry.ORIGIN_SERVER));
    }

    // ── 4: миграция v90 ─────────────────────────────────────────────────────────

    /** Сценарии спеки «Та же картинка» и «Другая картинка» плюс tombstone и осиротевшие ссылки. */
    @Test
    public void upgradeTo90_revivesLocallyDeletedServerIcons() {
        int server = WalletContract.IconEntry.ORIGIN_SERVER;
        int own = WalletContract.IconEntry.ORIGIN_LOCAL;
        // Та же картинка: своя уходит, ссылки — на серверную.
        long srvHouse = icon("T Houseware", new byte[]{5}, server, 1, 1_000L);
        long ownHouse = icon("T Houseware", new byte[]{5}, own, 0, null);
        long accHouse = account("T house", ownHouse);
        long curHouse = currency("XTA", ownHouse);
        // Другая картинка: своя переименовывается, ссылки не трогаются.
        long srvCoin = icon("T Coin", new byte[]{1}, server, 1, 1_000L);
        long ownCoin = icon("T Coin", new byte[]{2}, own, 0, null);
        long accCoin = account("T coin", ownCoin);
        // Удалена сервером (tombstone из pull) — остаётся удалённой.
        long srvGone = icon("T Gone", new byte[]{3}, server, 0, 1_000L);
        // Без своей пары — просто возвращается.
        long srvLone = icon("T Lone", new byte[]{4}, server, 1, 1_000L);
        // Счёт на удалённой своей при живой одноимённой (22 счёта на «Groceries»).
        long deadGroceries = icon("T Groceries", new byte[]{6}, own, 1, 1_000L);
        long liveGroceries = icon("T Groceries", new byte[]{6}, own, 0, null);
        long accGroceries = account("T groceries", deadGroceries);

        helper.onUpgrade(db, 89, 90);

        assertNull(longOrNull("icon", srvHouse, "deleted_at"));
        assertEquals(0, longOf("icon", srvHouse, "dirty"));
        assertTrue(longOrNull("icon", ownHouse, "deleted_at") != null);
        assertEquals(srvHouse, longOf("account", accHouse, "icon_id"));
        assertEquals(srvHouse, longOf("currency", curHouse, "icon_id"));
        assertEquals(1, liveCount("T Houseware"));

        assertNull(longOrNull("icon", srvCoin, "deleted_at"));
        assertEquals(Texts.get(R.string.icon_own_suffix, "T Coin"), stringOf("icon", ownCoin, "name"));
        assertNull(longOrNull("icon", ownCoin, "deleted_at"));
        assertEquals(ownCoin, longOf("account", accCoin, "icon_id"));

        assertTrue("tombstone сервера не возвращается", longOrNull("icon", srvGone, "deleted_at") != null);
        assertNull(longOrNull("icon", srvLone, "deleted_at"));
        assertEquals(liveGroceries, longOf("account", accGroceries, "icon_id"));
    }

    @Test
    public void upgradeTo90_healthyDb_changesNoIcons() {
        long before = count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NULL");
        long dirtyBefore = count("SELECT COUNT(*) FROM icon WHERE dirty <> 0");

        helper.onUpgrade(db, 89, 90);

        assertEquals(before, count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NULL"));
        assertEquals(dirtyBefore, count("SELECT COUNT(*) FROM icon WHERE dirty <> 0"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void makeServer(long iconId) {
        // Иконки с сервера чистые; второй UPDATE снимает dirty, если его поднял trg_icon_dirty.
        db.execSQL("UPDATE icon SET origin = ?, uuid = ?, dirty = 0 WHERE _id = ?",
                new Object[]{WalletContract.IconEntry.ORIGIN_SERVER, UUID.randomUUID().toString(), iconId});
        db.execSQL("UPDATE icon SET dirty = 0 WHERE _id = ? AND dirty <> 0", new Object[]{iconId});
    }

    private long icon(String name, byte[] image, int origin, int dirty, Long deletedAt) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("image", image);
        cv.put("icon_type", 0);
        cv.put("origin", origin);
        cv.put("uuid", UUID.randomUUID().toString());
        cv.put("dirty", dirty);
        if (deletedAt != null) cv.put("deleted_at", deletedAt);
        return db.insertOrThrow("icon", null, cv);
    }

    private long account(String name, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("uuid", UUID.randomUUID().toString());
        return db.insertOrThrow("account", null, cv);
    }

    private long currency(String iso, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put("name", "Test " + iso);
        cv.put("iso_code", iso);
        cv.put("icon_id", iconId);
        cv.put("symbol", iso);
        cv.put("uuid", UUID.randomUUID().toString());
        return db.insertOrThrow("currency", null, cv);
    }

    private long liveIdByName(String name) {
        return count("SELECT _id FROM icon WHERE name = '" + name.replace("'", "''") + "' AND deleted_at IS NULL");
    }

    private long liveCount(String name) {
        return count("SELECT COUNT(*) FROM icon WHERE name = '" + name.replace("'", "''") + "' AND deleted_at IS NULL");
    }

    private long currencyIdByIso(String iso) {
        return count("SELECT _id FROM currency WHERE iso_code = '" + iso + "' AND deleted_at IS NULL");
    }

    private long count(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private long longOf(String table, long id, String column) {
        Long v = longOrNull(table, id, column);
        assertTrue(table + "." + column + " #" + id, v != null);
        return v;
    }

    private Long longOrNull(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id = ?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.isNull(0) ? null : c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String stringOf(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id = ?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }
}
