package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.R
import com.example.wallettransactions.Texts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * android-wallet#50 (openspec fix-icon-reset-substitutes), задачи 2.3 и 4.3: «Удалить данные»
 * с «Иконки» не трогает иконки с сервера, пересев не заводит дублей, миграция v90 чинит
 * уже пострадавшие базы. Картинки в тестовых строках — по байту: настоящие PNG сида не
 * читаются курсором Robolectric (см. HardWipeCacheInvalidationTest), здесь их и не читаем.
 */
@RunWith(RobolectricTestRunner::class)
class IconResetFixTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 2: ResetIcons ───────────────────────────────────────────────────────────

    /** Сценарий спеки «Пересев при иконках с сервера». */
    @Test
    fun resetWithReseed_keepsServerIcons_noOwnDuplicates_relinks() {
        // Сид чистой базы: «Groceries» и флаг USD будто пришли с сервера, «Bar» — своя.
        val groceries = liveIdByName("Groceries")
        val flagUsd = liveIdByName("Flag USD")
        val bar = liveIdByName("Bar")
        makeServer(groceries)
        makeServer(flagUsd)
        val accGroceries = account("T groceries", groceries)
        val accBar = account("T bar", bar)
        val usd = currencyIdByIso("USD")
        assertEquals(flagUsd, longOf("currency", usd, "icon_id"))

        WalletFillData.ResetIcons(db, context, true)

        assertNull("серверная не удалена", longOrNull("icon", groceries, "deleted_at"))
        assertNull(longOrNull("icon", flagUsd, "deleted_at"))
        assertEquals("своя «Groceries» не заведена", 1L, liveCount("Groceries"))
        assertEquals(1L, liveCount("Flag USD"))
        assertEquals(groceries, longOf("account", accGroceries, "icon_id"))
        assertEquals(flagUsd, longOf("currency", usd, "icon_id"))

        val newBar = liveIdByName("Bar")
        assertNotEquals("своя пересеяна заново", bar, newBar)
        assertEquals(newBar, longOf("account", accBar, "icon_id"))
        assertEquals(
            "ни одна иконка с сервера не удалена", 0L,
            count(
                "SELECT COUNT(*) FROM icon WHERE deleted_at IS NOT NULL AND origin = "
                    + WalletContract.IconEntry.ORIGIN_SERVER
            )
        )
    }

    /** Сценарий спеки «Очистка без пересева». */
    @Test
    fun resetWithoutReseed_deletesOnlyUnusedOwnIcons() {
        val server = icon("T server unused", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_SERVER, 0, null)
        val own = icon("T own unused", byteArrayOf(2), WalletContract.IconEntry.ORIGIN_LOCAL, 0, null)

        WalletFillData.ResetIcons(db, context, false)

        assertNull(longOrNull("icon", server, "deleted_at"))
        assertTrue(longOrNull("icon", own, "deleted_at") != null)
        assertEquals(
            "ни одна иконка с сервера не удалена", 0L,
            count(
                "SELECT COUNT(*) FROM icon WHERE deleted_at IS NOT NULL AND origin = "
                    + WalletContract.IconEntry.ORIGIN_SERVER
            )
        )
    }

    // ── 4: миграция v90 ─────────────────────────────────────────────────────────

    /** Сценарии спеки «Та же картинка» и «Другая картинка» плюс tombstone и осиротевшие ссылки. */
    @Test
    fun upgradeTo90_revivesLocallyDeletedServerIcons() {
        val server = WalletContract.IconEntry.ORIGIN_SERVER
        val own = WalletContract.IconEntry.ORIGIN_LOCAL
        // Та же картинка: своя уходит, ссылки — на серверную.
        val srvHouse = icon("T Houseware", byteArrayOf(5), server, 1, 1000L)
        val ownHouse = icon("T Houseware", byteArrayOf(5), own, 0, null)
        val accHouse = account("T house", ownHouse)
        val curHouse = currency("XTA", ownHouse)
        // Другая картинка: своя переименовывается, ссылки не трогаются.
        val srvCoin = icon("T Coin", byteArrayOf(1), server, 1, 1000L)
        val ownCoin = icon("T Coin", byteArrayOf(2), own, 0, null)
        val accCoin = account("T coin", ownCoin)
        // Удалена сервером (tombstone из pull) — остаётся удалённой.
        val srvGone = icon("T Gone", byteArrayOf(3), server, 0, 1000L)
        // Без своей пары — просто возвращается.
        val srvLone = icon("T Lone", byteArrayOf(4), server, 1, 1000L)
        // Счёт на удалённой своей при живой одноимённой (22 счёта на «Groceries»).
        val deadGroceries = icon("T Groceries", byteArrayOf(6), own, 1, 1000L)
        val liveGroceries = icon("T Groceries", byteArrayOf(6), own, 0, null)
        val accGroceries = account("T groceries", deadGroceries)

        helper.onUpgrade(db, 89, 90)

        assertNull(longOrNull("icon", srvHouse, "deleted_at"))
        assertEquals(0L, longOf("icon", srvHouse, "dirty"))
        assertTrue(longOrNull("icon", ownHouse, "deleted_at") != null)
        assertEquals(srvHouse, longOf("account", accHouse, "icon_id"))
        assertEquals(srvHouse, longOf("currency", curHouse, "icon_id"))
        assertEquals(1L, liveCount("T Houseware"))

        assertNull(longOrNull("icon", srvCoin, "deleted_at"))
        assertEquals(Texts.get(R.string.icon_own_suffix, "T Coin"), stringOf("icon", ownCoin, "name"))
        assertNull(longOrNull("icon", ownCoin, "deleted_at"))
        assertEquals(ownCoin, longOf("account", accCoin, "icon_id"))

        assertTrue("tombstone сервера не возвращается", longOrNull("icon", srvGone, "deleted_at") != null)
        assertNull(longOrNull("icon", srvLone, "deleted_at"))
        assertEquals(liveGroceries, longOf("account", accGroceries, "icon_id"))
    }

    @Test
    fun upgradeTo90_healthyDb_changesNoIcons() {
        val before = count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NULL")
        val dirtyBefore = count("SELECT COUNT(*) FROM icon WHERE dirty <> 0")

        helper.onUpgrade(db, 89, 90)

        assertEquals(before, count("SELECT COUNT(*) FROM icon WHERE deleted_at IS NULL"))
        assertEquals(dirtyBefore, count("SELECT COUNT(*) FROM icon WHERE dirty <> 0"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun makeServer(iconId: Long) {
        // Иконки с сервера чистые; второй UPDATE снимает dirty, если его поднял trg_icon_dirty.
        db.execSQL(
            "UPDATE icon SET origin = ?, uuid = ?, dirty = 0 WHERE _id = ?",
            arrayOf<Any?>(WalletContract.IconEntry.ORIGIN_SERVER, UUID.randomUUID().toString(), iconId)
        )
        db.execSQL("UPDATE icon SET dirty = 0 WHERE _id = ? AND dirty <> 0", arrayOf<Any?>(iconId))
    }

    private fun icon(name: String, image: ByteArray, origin: Int, dirty: Int, deletedAt: Long?): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("image", image)
        cv.put("icon_type", 0)
        cv.put("origin", origin)
        cv.put("uuid", UUID.randomUUID().toString())
        cv.put("dirty", dirty)
        if (deletedAt != null) cv.put("deleted_at", deletedAt)
        return db.insertOrThrow("icon", null, cv)
    }

    private fun account(name: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("uuid", UUID.randomUUID().toString())
        return db.insertOrThrow("account", null, cv)
    }

    private fun currency(iso: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put("name", "Test " + iso)
        cv.put("iso_code", iso)
        cv.put("icon_id", iconId)
        cv.put("symbol", iso)
        cv.put("uuid", UUID.randomUUID().toString())
        return db.insertOrThrow("currency", null, cv)
    }

    private fun liveIdByName(name: String): Long {
        return count("SELECT _id FROM icon WHERE name = '" + name.replace("'", "''") + "' AND deleted_at IS NULL")
    }

    private fun liveCount(name: String): Long {
        return count("SELECT COUNT(*) FROM icon WHERE name = '" + name.replace("'", "''") + "' AND deleted_at IS NULL")
    }

    private fun currencyIdByIso(iso: String): Long {
        return count("SELECT _id FROM currency WHERE iso_code = '" + iso + "' AND deleted_at IS NULL")
    }

    private fun count(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun longOf(table: String, id: Long, column: String): Long {
        val v = longOrNull(table, id, column)
        assertTrue(table + "." + column + " #" + id, v != null)
        return v!!
    }

    private fun longOrNull(table: String, id: Long, column: String): Long? {
        val c = db.rawQuery(
            "SELECT " + column + " FROM " + table + " WHERE _id = ?",
            arrayOf(id.toString())
        )
        try {
            assertTrue(c.moveToFirst())
            return if (c.isNull(0)) null else c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun stringOf(table: String, id: Long, column: String): String {
        val c = db.rawQuery(
            "SELECT " + column + " FROM " + table + " WHERE _id = ?",
            arrayOf(id.toString())
        )
        try {
            assertTrue(c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }
}
