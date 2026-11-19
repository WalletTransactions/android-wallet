package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Кэш составных курсов на живой SQLite (openspec currency-conversion-routes): вьюха
 * {@code v_currency_conversion_rate} отдаёт строки кэша, триггеры помечают его устаревшим при
 * изменении курса, пары, валюты и шага маршрута, таблица шагов держит ограничения, апгрейд с
 * версии 92 создаёт кэш и пересоздаёт таблицу.
 */
@RunWith(RobolectricTestRunner.class)
public class DerivedRatesDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long icon;
    private long btc, eur, rub, usd;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        icon = queryLong("SELECT _id FROM icon LIMIT 1");
        // Чистый лист: стартовый набор (USD/EUR/RUB с парами) гасим — кэш читает только живые строки.
        db.execSQL("UPDATE currency_pair_rate SET deleted_at = 1");
        db.execSQL("UPDATE currency_pair SET deleted_at = 1");
        db.execSQL("UPDATE currency SET deleted_at = 1");
        btc = currency("BTC", false);
        eur = currency("EUR", false);
        rub = currency("RUB", true); // эквивалент — рубли
        usd = currency("USD", false);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── вьюха и кэш ─────────────────────────────────────────────────────────────

    @Test
    public void routeRows_areVisibleInRateView_withoutChangingConsumerQueries() {
        long p1 = pair(btc, eur), p2 = pair(eur, rub);
        rate(p1, "2026-11-01", 90000);
        rate(p2, "2026-11-01", 100);
        step(btc, rub, 1, p1, 0);
        step(btc, rub, 2, p2, 0);
        DerivedRates.ensureFresh(db);

        assertEquals(9000000.0, viewRate(btc, rub, "2026-11-05"), 1e-6);
        assertEquals(1.0 / 9000000.0, viewRate(rub, btc, "2026-11-05"), 1e-15);
        assertEquals("кэш перестроен", 0, queryLong("SELECT stale FROM derived_rates_state"));
    }

    @Test
    public void usdCross_isVisibleInRateView() {
        long p1 = pair(btc, usd), p2 = pair(usd, rub);
        rate(p1, "2026-11-01", 60000);
        rate(p2, "2026-11-01", 90);
        DerivedRates.ensureFresh(db);
        assertEquals(5400000.0, viewRate(btc, rub, "2026-11-05"), 1e-6);
    }

    @Test
    public void routeStep_doesNotSubstituteDirectionOfExistingPair() {
        long p1 = pair(btc, eur), p2 = pair(eur, rub);
        rate(p1, "2026-11-01", 90000);
        rate(p2, "2026-11-01", 100);
        step(btc, rub, 1, p1, 0);
        step(btc, rub, 2, p2, 0);
        // v_currency_conversion — только направления пар: шаги маршрута (v93) в неё не подмешиваются
        assertEquals(0, queryLong("SELECT count(*) FROM v_currency_conversion WHERE from_currency_id = " + btc
                + " AND to_currency_id = " + rub));
    }

    // ── устаревание кэша ────────────────────────────────────────────────────────

    @Test
    public void cacheBecomesStale_afterEverySourceChange() {
        long p1 = pair(btc, eur), p2 = pair(eur, rub);
        DerivedRates.ensureFresh(db);
        assertFresh();

        rate(p1, "2026-11-01", 90000);                       // курс пары
        assertStale();
        DerivedRates.ensureFresh(db);
        assertFresh();

        db.execSQL("UPDATE currency_pair SET precision = 4 WHERE _id = " + p2);   // пара
        assertStale();
        DerivedRates.ensureFresh(db);
        assertFresh();

        db.execSQL("UPDATE currency SET show_in_equivalent = 1 WHERE _id = " + eur); // валюта
        assertStale();
        DerivedRates.ensureFresh(db);
        assertFresh();

        step(btc, rub, 1, p1, 0);                            // шаг маршрута
        assertStale();
        DerivedRates.ensureFresh(db);
        assertFresh();

        db.execSQL("DELETE FROM currency_conversion");       // удаление шага
        assertStale();
    }

    @Test
    public void rateEditedAfterBuild_isPickedUpByNextEnsureFresh() {
        long p1 = pair(btc, usd), p2 = pair(usd, rub);
        rate(p1, "2026-11-01", 60000);
        rate(p2, "2026-11-01", 90);
        DerivedRates.ensureFresh(db);
        assertEquals(5400000.0, viewRate(btc, rub, "2026-11-05"), 1e-6);
        db.execSQL("UPDATE currency_pair_rate SET rate = 100 WHERE currency_pair_id = " + p2);
        DerivedRates.ensureFresh(db);
        assertEquals(6000000.0, viewRate(btc, rub, "2026-11-05"), 1e-6);
    }

    @Test
    public void singletonStyleHandle_getWritableDatabaseRefreshesStaleCache() {
        long p1 = pair(btc, usd), p2 = pair(usd, rub);
        rate(p1, "2026-11-01", 60000);
        rate(p2, "2026-11-01", 90);
        assertStale();
        helper.getWritableDatabase(); // тот путь, которым базу берёт код приложения
        assertFresh();
    }

    // ── ограничения таблицы шагов ───────────────────────────────────────────────

    @Test
    public void routeStep_rejectsNumOutsideRangeAndSameCurrencies() {
        long p1 = pair(btc, eur);
        assertRejected(btc, rub, 0, p1);
        assertRejected(btc, rub, 4, p1);
        assertRejected(btc, btc, 1, p1);
        step(btc, rub, 3, p1, 0); // граница допустима
    }

    @Test
    public void routeStep_primaryKeyRejectsDuplicateStep() {
        long p1 = pair(btc, eur);
        step(btc, rub, 1, p1, 0);
        assertRejected(btc, rub, 1, p1);
    }

    // ── апгрейд с версии 92 ─────────────────────────────────────────────────────

    @Test
    public void upgradeFrom92_createsCacheRecreatesRouteTableAndRebuildsViews() {
        // Откат схемы к 92: старая таблица без CHECK, без кэша и триггеров устаревания.
        db.execSQL("DROP VIEW IF EXISTS v_operation_eq");
        db.execSQL("DROP VIEW IF EXISTS v_currency_conversion_rate");
        db.execSQL("DROP TABLE currency_rate_derived");
        db.execSQL("DROP TABLE derived_rates_state");
        db.execSQL("DROP TABLE currency_conversion");
        db.execSQL("CREATE TABLE currency_conversion (from_currency_id integer, to_currency_id integer, "
                + "num integer, currency_pair_id integer, is_reverse integer, "
                + "uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), dirty INTEGER NOT NULL DEFAULT 1, "
                + "deleted_at INTEGER, updated_by_device TEXT, PRIMARY KEY (from_currency_id, to_currency_id, num))");
        db.setVersion(92);
        helper.close();

        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();

        assertEquals(93, db.getVersion());
        assertEquals(1, queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'currency_rate_derived'"));
        assertEquals(1, queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'trg_currency_pair_rate_derived_insert'"));
        assertRejected(btc, rub, 0, pair(btc, eur)); // CHECK вернулся
        // вьюха курса работает на новой схеме
        queryLong("SELECT count(*) FROM v_currency_conversion_rate");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private long currency(String iso, boolean equivalent) {
        ContentValues cv = new ContentValues();
        cv.put("name", iso + " test");
        cv.put("iso_code", iso);
        cv.put("icon_id", icon);
        cv.put("symbol", iso);
        cv.put("show_in_equivalent", equivalent ? 1 : 0);
        return db.insertOrThrow("currency", null, cv);
    }

    private long pair(long base, long quote) {
        ContentValues cv = new ContentValues();
        cv.put("symbol", "P" + base + "_" + quote);
        cv.put("base_currency_id", base);
        cv.put("quote_currency_id", quote);
        return db.insertOrThrow("currency_pair", null, cv);
    }

    private void rate(long pairId, String date, double rate) {
        ContentValues cv = new ContentValues();
        cv.put("currency_pair_id", pairId);
        cv.put("date", date);
        cv.put("rate", rate);
        db.insertOrThrow("currency_pair_rate", null, cv);
    }

    private void step(long from, long to, int num, long pairId, int reverse) {
        ContentValues cv = new ContentValues();
        cv.put("from_currency_id", from);
        cv.put("to_currency_id", to);
        cv.put("num", num);
        cv.put("currency_pair_id", pairId);
        cv.put("is_reverse", reverse);
        db.insertOrThrow("currency_conversion", null, cv);
    }

    private void assertRejected(long from, long to, int num, long pairId) {
        try {
            step(from, to, num, pairId, 0);
            fail("ожидали отказ ограничения");
        } catch (SQLiteConstraintException expected) {
            // ok
        }
    }

    private void assertStale() {
        assertEquals("кэш помечен устаревшим", 1, queryLong("SELECT stale FROM derived_rates_state"));
    }

    private void assertFresh() {
        assertEquals("кэш актуален", 0, queryLong("SELECT stale FROM derived_rates_state"));
    }

    /** Последний курс from→to не позже даты — тот же шаблон, что у потребителей курса (max(date) + join). */
    private double viewRate(long from, long to, String date) {
        Cursor c = db.rawQuery("SELECT vccr.rate FROM v_currency_conversion_rate vccr "
                + "JOIN (SELECT max(date) d FROM v_currency_conversion_rate WHERE from_currency_id = ? "
                + "AND to_currency_id = ? AND date <= ?) mx ON vccr.date = mx.d "
                + "WHERE vccr.from_currency_id = ? AND vccr.to_currency_id = ?",
                new String[]{String.valueOf(from), String.valueOf(to), date, String.valueOf(from), String.valueOf(to)});
        try {
            assertTrue("нет курса " + from + "→" + to, c.moveToFirst());
            return c.getDouble(0);
        } finally {
            c.close();
        }
    }

    private long queryLong(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }
}
