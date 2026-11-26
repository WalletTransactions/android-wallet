package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.IconList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec currency-ui-tweaks: миграция v85 ({@code show_in_equivalent}, {@code is_shared}),
 * загрузка флагов, запись флага формой валюты, запрет смены кода валюты из пары,
 * удаление своей пары вместе с курсами.
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyFlagsDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        iconId = insert(
            WalletContract.IconEntry.TABLE_NAME,
            WalletContract.IconEntry.COLUMN_NAME, "test_icon",
            WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0),
            WalletContract.IconEntry.COLUMN_ICON_TYPE, 1
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 1.1 / 1.2: миграция v84 → v85 ───────────────────────────────────────────

    @Test
    fun upgradeTo85_addsColumnsWithoutTouchingDirty_andResetsReferenceCursor() {
        // База v84: таблицы без новых колонок, с тем же триггером dirty, что createDirtyTrigger.
        val old = SQLiteDatabase.create(null)
        try {
            old.execSQL(
                "CREATE TABLE currency (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "iso_code TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)"
            )
            old.execSQL(
                "CREATE TABLE currency_pair (_id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, "
                    + "dirty INTEGER NOT NULL DEFAULT 1)"
            )
            for (t in arrayOf("currency", "currency_pair")) {
                old.execSQL(
                    "CREATE TRIGGER trg_" + t + "_dirty AFTER UPDATE ON " + t
                        + " WHEN NEW.dirty = OLD.dirty BEGIN UPDATE " + t
                        + " SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1; END;"
                )
            }
            old.execSQL("INSERT INTO currency (name, iso_code, dirty) VALUES ('A', 'AAA', 0), ('B', 'BBB', 1)")
            old.execSQL("INSERT INTO currency_pair (symbol, dirty) VALUES ('AAABBB', 0)")

            AppSettings.setReferenceSyncSeq(context, 42)
            AppSettings.setLedgerSyncSeq(context, 7)

            WalletDbHelper.addCurrencyFlagsV85(old, context)

            val c = old.rawQuery("SELECT iso_code, dirty, show_in_equivalent, is_shared FROM currency ORDER BY _id", null)
            try {
                assertTrue(c.moveToNext())
                assertEquals(0, c.getInt(1))
                assertEquals(1, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertTrue(c.moveToNext())
                assertEquals(1, c.getInt(1))
                assertEquals(1, c.getInt(2))
                assertEquals(0, c.getInt(3))
            } finally {
                c.close()
            }
            val p = old.rawQuery("SELECT dirty, is_shared FROM currency_pair", null)
            try {
                assertTrue(p.moveToFirst())
                assertEquals(0, p.getInt(0))
                assertEquals(0, p.getInt(1))
            } finally {
                p.close()
            }
            assertEquals(0L, AppSettings.getReferenceSyncSeq(context))
            assertEquals(7L, AppSettings.getLedgerSyncSeq(context))

            // Повторный вызов (база, уже пересобранная текущим телом) не падает на duplicate column.
            WalletDbHelper.addCurrencyFlagsV85(old, context)
        } finally {
            old.close()
        }
    }

    @Test
    fun onUpgradeFrom84_runsV85Step() {
        AppSettings.setReferenceSyncSeq(context, 42)
        helper.onUpgrade(db, 84, 85)
        assertEquals(0L, AppSettings.getReferenceSyncSeq(context))
    }

    // ── 1.3: загрузка флагов ────────────────────────────────────────────────────

    @Test
    fun loadCurrencyList_readsBothFlags() {
        val own = currency("Test own", "TSA")
        val shared = currency("Test shared", "TSB")
        db.execSQL("UPDATE currency SET show_in_equivalent = 0 WHERE _id = " + own)
        db.execSQL("UPDATE currency SET is_shared = 1 WHERE _id = " + shared)

        val list = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, list, IconList())

        val a = list.getCurrencyById(own.toInt())
        val b = list.getCurrencyById(shared.toInt())
        assertNotNull(a)
        assertNotNull(b)
        assertFalse(a!!.isShowInEquivalent)
        assertFalse(a.isShared)
        assertTrue(b!!.isShowInEquivalent)
        assertTrue(b.isShared)
    }

    // ── 4.3: флаг в Insert/UpdateCurrency ───────────────────────────────────────

    @Test
    fun insertAndUpdateCurrency_storeFlagAndMarkDirty() {
        val id = WalletDbCRUD.InsertCurrency(db, "Test coin", "TSC", "T", 2, iconId.toInt(), false)
        assertTrue(id > 0)
        assertEquals(0, intCol("currency", "show_in_equivalent", id))
        assertEquals(1, intCol("currency", "dirty", id))

        db.execSQL("UPDATE currency SET dirty = 0 WHERE _id = " + id)
        assertEquals(1, WalletDbCRUD.UpdateCurrency(db, id.toInt(), "Test coin", "TSC", "T", 2, iconId.toInt(), true))
        assertEquals(1, intCol("currency", "show_in_equivalent", id))
        assertEquals(1, intCol("currency", "dirty", id))
    }

    @Test
    fun updateCurrency_sharedRowUntouched() {
        val id = currency("Test shared", "TSD")
        db.execSQL("UPDATE currency SET is_shared = 1, dirty = 0 WHERE _id = " + id)

        assertEquals(0, WalletDbCRUD.UpdateCurrency(db, id.toInt(), "Renamed", "TSD", "T", 2, iconId.toInt(), false))
        assertEquals(1, intCol("currency", "show_in_equivalent", id))
        assertEquals(0, intCol("currency", "dirty", id))
    }

    // ── 4.5: код валюты из пары ─────────────────────────────────────────────────

    @Test
    fun isoChange_blockedOnlyForCurrencyInLivePair() {
        val xau = currency("Test gold", "TSX")
        currency("Test dollar", "TSU")
        val pair = WalletDbCRUD.InsertCurrencyPair(db, "TSX", "TSU", 2)
        assertTrue(pair > 0)

        assertTrue(WalletDbCRUD.IsIsoChangeBlocked(db, xau.toInt(), "GLD"))
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, xau.toInt(), "TSX"))
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, xau.toInt(), "tsx"))
        assertFalse("новая валюта", WalletDbCRUD.IsIsoChangeBlocked(db, -1, "GLD"))

        val free = currency("Test free", "TSF")
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, free.toInt(), "TSG"))

        WalletDbCRUD.DeleteCurrencyPair(db, pair.toInt())
        assertFalse("пара удалена — код можно менять", WalletDbCRUD.IsIsoChangeBlocked(db, xau.toInt(), "GLD"))
    }

    // ── 3.1: удаление пары ──────────────────────────────────────────────────────

    @Test
    fun deleteCurrencyPair_softDeletesPairAndLiveRates() {
        currency("Test euro", "TSE")
        currency("Test dollar", "TSU")
        val pair = WalletDbCRUD.InsertCurrencyPair(db, "TSE", "TSU", 4)
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-01", 1.1)
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-02", 1.2)
        db.execSQL("UPDATE currency_pair SET dirty = 0 WHERE _id = " + pair)
        db.execSQL("UPDATE currency_pair_rate SET dirty = 0 WHERE currency_pair_id = " + pair)

        assertEquals(1, WalletDbCRUD.DeleteCurrencyPair(db, pair.toInt()))

        assertNotNull(longCol("currency_pair", "deleted_at", pair))
        assertEquals(1, intCol("currency_pair", "dirty", pair))
        val c = db.rawQuery(
            "SELECT deleted_at, dirty FROM currency_pair_rate WHERE currency_pair_id = ?",
            arrayOf(pair.toString())
        )
        try {
            assertEquals(2, c.count)
            while (c.moveToNext()) {
                assertFalse(c.isNull(0))
                assertEquals(1, c.getInt(1))
            }
        } finally {
            c.close()
        }
        // Повторно — уже удалена.
        assertEquals(0, WalletDbCRUD.DeleteCurrencyPair(db, pair.toInt()))
    }

    @Test
    fun deleteCurrencyPair_sharedPairUntouched() {
        currency("Test euro", "TSE")
        currency("Test dollar", "TSU")
        val pair = WalletDbCRUD.InsertCurrencyPair(db, "TSE", "TSU", 4)
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-01", 1.1)
        db.execSQL("UPDATE currency_pair SET is_shared = 1, dirty = 0 WHERE _id = " + pair)
        db.execSQL("UPDATE currency_pair_rate SET dirty = 0 WHERE currency_pair_id = " + pair)

        assertEquals(0, WalletDbCRUD.DeleteCurrencyPair(db, pair.toInt()))

        assertNull(longCol("currency_pair", "deleted_at", pair))
        assertEquals(0, intCol("currency_pair", "dirty", pair))
        val c = db.rawQuery(
            "SELECT COUNT(*) FROM currency_pair_rate WHERE currency_pair_id = ? "
                + "AND deleted_at IS NULL AND dirty = 0", arrayOf(pair.toString())
        )
        try {
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        } finally {
            c.close()
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun currency(name: String, iso: String): Long {
        return insert(
            WalletContract.CurrencyEntry.TABLE_NAME,
            WalletContract.CurrencyEntry.COLUMN_NAME, name,
            WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso,
            WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
            WalletContract.CurrencyEntry.COLUMN_SYMBOL, iso
        )
    }

    private fun intCol(table: String, column: String, id: Long): Int {
        val c = db.rawQuery(
            "SELECT " + column + " FROM " + table + " WHERE _id=?",
            arrayOf(id.toString())
        )
        try {
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun longCol(table: String, column: String, id: Long): Long? {
        val c = db.rawQuery(
            "SELECT " + column + " FROM " + table + " WHERE _id=?",
            arrayOf(id.toString())
        )
        try {
            assertTrue(c.moveToFirst())
            return if (c.isNull(0)) null else c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            val value = kv[i + 1]
            when (value) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
