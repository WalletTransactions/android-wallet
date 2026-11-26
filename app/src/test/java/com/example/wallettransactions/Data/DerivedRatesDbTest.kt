package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Кэш составных курсов на живой SQLite (openspec currency-conversion-routes): вьюха
 * {@code v_currency_conversion_rate} отдаёт строки кэша, триггеры помечают его устаревшим при
 * изменении курса, пары, валюты и шага маршрута, таблица шагов держит ограничения, апгрейд с
 * версии 92 создаёт кэш и пересоздаёт таблицу.
 */
@RunWith(RobolectricTestRunner::class)
class DerivedRatesDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var icon: Long = 0
    private var btc: Long = 0
    private var eur: Long = 0
    private var rub: Long = 0
    private var usd: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        icon = queryLong("SELECT _id FROM icon LIMIT 1")
        // Чистый лист: стартовый набор (USD/EUR/RUB с парами) гасим — кэш читает только живые строки.
        db.execSQL("UPDATE currency_pair_rate SET deleted_at = 1")
        db.execSQL("UPDATE currency_pair SET deleted_at = 1")
        db.execSQL("UPDATE currency SET deleted_at = 1")
        btc = currency("BTC", false)
        eur = currency("EUR", false)
        rub = currency("RUB", true) // эквивалент — рубли
        usd = currency("USD", false)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── вьюха и кэш ─────────────────────────────────────────────────────────────

    @Test
    fun routeRows_areVisibleInRateView_withoutChangingConsumerQueries() {
        val p1 = pair(btc, eur)
        val p2 = pair(eur, rub)
        rate(p1, "2026-11-01", 90000.0)
        rate(p2, "2026-11-01", 100.0)
        step(btc, rub, 1, p1, 0)
        step(btc, rub, 2, p2, 0)
        DerivedRates.ensureFresh(db)

        assertEquals(9000000.0, viewRate(btc, rub, "2026-11-05"), 1e-6)
        assertEquals(1.0 / 9000000.0, viewRate(rub, btc, "2026-11-05"), 1e-15)
        assertEquals("кэш перестроен", 0L, queryLong("SELECT stale FROM derived_rates_state"))
    }

    @Test
    fun usdCross_isVisibleInRateView() {
        val p1 = pair(btc, usd)
        val p2 = pair(usd, rub)
        rate(p1, "2026-11-01", 60000.0)
        rate(p2, "2026-11-01", 90.0)
        DerivedRates.ensureFresh(db)
        assertEquals(5400000.0, viewRate(btc, rub, "2026-11-05"), 1e-6)
    }

    @Test
    fun routeStep_doesNotSubstituteDirectionOfExistingPair() {
        val p1 = pair(btc, eur)
        val p2 = pair(eur, rub)
        rate(p1, "2026-11-01", 90000.0)
        rate(p2, "2026-11-01", 100.0)
        step(btc, rub, 1, p1, 0)
        step(btc, rub, 2, p2, 0)
        // v_currency_conversion — только направления пар: шаги маршрута (v93) в неё не подмешиваются
        assertEquals(
            0L, queryLong(
                "SELECT count(*) FROM v_currency_conversion WHERE from_currency_id = " + btc
                    + " AND to_currency_id = " + rub
            )
        )
    }

    // ── устаревание кэша ────────────────────────────────────────────────────────

    @Test
    fun cacheBecomesStale_afterEverySourceChange() {
        val p1 = pair(btc, eur)
        val p2 = pair(eur, rub)
        DerivedRates.ensureFresh(db)
        assertFresh()

        rate(p1, "2026-11-01", 90000.0) // курс пары
        assertStale()
        DerivedRates.ensureFresh(db)
        assertFresh()

        db.execSQL("UPDATE currency_pair SET precision = 4 WHERE _id = " + p2) // пара
        assertStale()
        DerivedRates.ensureFresh(db)
        assertFresh()

        db.execSQL("UPDATE currency SET show_in_equivalent = 1 WHERE _id = " + eur) // валюта
        assertStale()
        DerivedRates.ensureFresh(db)
        assertFresh()

        step(btc, rub, 1, p1, 0) // шаг маршрута
        assertStale()
        DerivedRates.ensureFresh(db)
        assertFresh()

        db.execSQL("DELETE FROM currency_conversion") // удаление шага
        assertStale()
    }

    @Test
    fun rateEditedAfterBuild_isPickedUpByNextEnsureFresh() {
        val p1 = pair(btc, usd)
        val p2 = pair(usd, rub)
        rate(p1, "2026-11-01", 60000.0)
        rate(p2, "2026-11-01", 90.0)
        DerivedRates.ensureFresh(db)
        assertEquals(5400000.0, viewRate(btc, rub, "2026-11-05"), 1e-6)
        db.execSQL("UPDATE currency_pair_rate SET rate = 100 WHERE currency_pair_id = " + p2)
        DerivedRates.ensureFresh(db)
        assertEquals(6000000.0, viewRate(btc, rub, "2026-11-05"), 1e-6)
    }

    @Test
    fun singletonStyleHandle_getWritableDatabaseRefreshesStaleCache() {
        val p1 = pair(btc, usd)
        val p2 = pair(usd, rub)
        rate(p1, "2026-11-01", 60000.0)
        rate(p2, "2026-11-01", 90.0)
        assertStale()
        helper.writableDatabase // тот путь, которым базу берёт код приложения
        assertFresh()
    }

    // ── ограничения таблицы шагов ───────────────────────────────────────────────

    @Test
    fun routeStep_rejectsNumOutsideRangeAndSameCurrencies() {
        val p1 = pair(btc, eur)
        assertRejected(btc, rub, 0, p1)
        assertRejected(btc, rub, 4, p1)
        assertRejected(btc, btc, 1, p1)
        step(btc, rub, 3, p1, 0) // граница допустима
    }

    @Test
    fun routeStep_primaryKeyRejectsDuplicateStep() {
        val p1 = pair(btc, eur)
        step(btc, rub, 1, p1, 0)
        assertRejected(btc, rub, 1, p1)
    }

    // ── апгрейд с версии 92 ─────────────────────────────────────────────────────

    @Test
    fun upgradeFrom92_createsCacheRecreatesRouteTableAndRebuildsViews() {
        // Откат схемы к 92: старая таблица без CHECK, без кэша и триггеров устаревания.
        db.execSQL("DROP VIEW IF EXISTS v_operation_eq")
        db.execSQL("DROP VIEW IF EXISTS v_currency_conversion_rate")
        db.execSQL("DROP TABLE currency_rate_derived")
        db.execSQL("DROP TABLE derived_rates_state")
        db.execSQL("DROP TABLE currency_conversion")
        db.execSQL(
            "CREATE TABLE currency_conversion (from_currency_id integer, to_currency_id integer, "
                + "num integer, currency_pair_id integer, is_reverse integer, "
                + "uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), dirty INTEGER NOT NULL DEFAULT 1, "
                + "deleted_at INTEGER, updated_by_device TEXT, PRIMARY KEY (from_currency_id, to_currency_id, num))"
        )
        db.version = 92
        helper.close()

        helper = WalletDbHelper(context)
        db = helper.writableDatabase

        assertEquals(WalletDbHelper.DATABASE_VERSION, db.version)
        assertEquals(1L, queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'currency_rate_derived'"))
        assertEquals(1L, queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'trg_currency_pair_rate_derived_insert'"))
        assertRejected(btc, rub, 0, pair(btc, eur)) // CHECK вернулся
        // вьюха курса работает на новой схеме
        queryLong("SELECT count(*) FROM v_currency_conversion_rate")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun currency(iso: String, equivalent: Boolean): Long {
        val cv = ContentValues()
        cv.put("name", iso + " test")
        cv.put("iso_code", iso)
        cv.put("icon_id", icon)
        cv.put("symbol", iso)
        cv.put("show_in_equivalent", if (equivalent) 1 else 0)
        return db.insertOrThrow("currency", null, cv)
    }

    private fun pair(base: Long, quote: Long): Long {
        val cv = ContentValues()
        cv.put("symbol", "P" + base + "_" + quote)
        cv.put("base_currency_id", base)
        cv.put("quote_currency_id", quote)
        return db.insertOrThrow("currency_pair", null, cv)
    }

    private fun rate(pairId: Long, date: String, rate: Double) {
        val cv = ContentValues()
        cv.put("currency_pair_id", pairId)
        cv.put("date", date)
        cv.put("rate", rate)
        db.insertOrThrow("currency_pair_rate", null, cv)
    }

    private fun step(from: Long, to: Long, num: Int, pairId: Long, reverse: Int) {
        val cv = ContentValues()
        cv.put("from_currency_id", from)
        cv.put("to_currency_id", to)
        cv.put("num", num)
        cv.put("currency_pair_id", pairId)
        cv.put("is_reverse", reverse)
        db.insertOrThrow("currency_conversion", null, cv)
    }

    private fun assertRejected(from: Long, to: Long, num: Int, pairId: Long) {
        try {
            step(from, to, num, pairId, 0)
            fail("ожидали отказ ограничения")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
    }

    private fun assertStale() {
        assertEquals("кэш помечен устаревшим", 1L, queryLong("SELECT stale FROM derived_rates_state"))
    }

    private fun assertFresh() {
        assertEquals("кэш актуален", 0L, queryLong("SELECT stale FROM derived_rates_state"))
    }

    /** Последний курс from→to не позже даты — тот же шаблон, что у потребителей курса (max(date) + join). */
    private fun viewRate(from: Long, to: Long, date: String): Double {
        val c = db.rawQuery(
            "SELECT vccr.rate FROM v_currency_conversion_rate vccr "
                + "JOIN (SELECT max(date) d FROM v_currency_conversion_rate WHERE from_currency_id = ? "
                + "AND to_currency_id = ? AND date <= ?) mx ON vccr.date = mx.d "
                + "WHERE vccr.from_currency_id = ? AND vccr.to_currency_id = ?",
            arrayOf(from.toString(), to.toString(), date, from.toString(), to.toString())
        )
        try {
            assertTrue("нет курса " + from + "→" + to, c.moveToFirst())
            return c.getDouble(0)
        } finally {
            c.close()
        }
    }

    private fun queryLong(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            return if (c.moveToFirst()) c.getLong(0) else -1
        } finally {
            c.close()
        }
    }
}
