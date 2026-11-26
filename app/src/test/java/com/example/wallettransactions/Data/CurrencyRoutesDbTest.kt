package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Маршруты пересчёта на устройстве (openspec currency-conversion-routes, spec currency-routes):
 * создание по пути из валют, список, удаление, оживление ключей, каскад удаления пары и эквивалент
 * кошелька по маршруту / через USD на уровне потребителя ({@code LoadBalancesPerWallet}).
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyRoutesDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var btc = 0
    private var eur = 0
    private var rub = 0
    private var usd = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM operation_template")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_debt")
        db.execSQL("DELETE FROM counterparty")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        db.execSQL("DELETE FROM currency_conversion")
        db.execSQL("DELETE FROM currency_pair_rate")
        db.execSQL("DELETE FROM currency_pair")
        db.execSQL("DELETE FROM currency")
        db.execSQL("DELETE FROM icon")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        btc = currency("Bitcoin", "BTC", "B")
        eur = currency("Euro", "EUR", "E")
        rub = currency("Ruble", "RUB", "R")
        usd = currency("Dollar", "USD", "$")
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── создание, список, удаление ──────────────────────────────────────────────

    @Test
    fun create_writesDirtyStepsAndListShowsRoute() {
        val p1 = pair("BTCEUR", btc, eur)
        val p2 = pair("EURRUB", eur, rub)
        val r = CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong()))
        assertTrue(r.ok())

        val routes = CurrencyRoutes.list(db)
        assertEquals(1, routes.size)
        assertEquals("BTC → EUR → RUB", routes[0].label())
        assertEquals("BTCEUR", routes[0].stepSymbols[0])
        assertEquals("EURRUB", routes[0].stepSymbols[1])
        assertFalse(routes[0].stepReverse[0])
        assertEquals(
            "оба шага к отправке на сервер", 2,
            intQuery("SELECT count(*) FROM currency_conversion WHERE dirty = 1 AND deleted_at IS NULL")
        )
        assertEquals(p1, intQuery("SELECT currency_pair_id FROM currency_conversion WHERE num = 1"))
        assertEquals(p2, intQuery("SELECT currency_pair_id FROM currency_conversion WHERE num = 2"))
    }

    @Test
    fun create_stepOverReversedPair_isMarkedReverse() {
        pair("BTCEUR", btc, eur)
        pair("RUBEUR", rub, eur) // шаг EUR→RUB возможен только по обратной паре
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        val route = CurrencyRoutes.list(db)[0]
        assertFalse(route.stepReverse[0])
        assertTrue(route.stepReverse[1])
    }

    @Test
    fun create_prefersForwardPair_andOwnPairOverShared() {
        pair("BTCEUR", btc, eur)
        val shared = pair("EURRUB", eur, rub)
        db.execSQL("UPDATE currency_pair SET is_shared = 1 WHERE _id = " + shared)
        val own = pair("EURRUB2", eur, rub)
        pair("RUBEUR", rub, eur)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(
            "своя прямая пара раньше общей и обратной", own,
            intQuery("SELECT currency_pair_id FROM currency_conversion WHERE num = 2")
        )
        assertEquals(0, intQuery("SELECT is_reverse FROM currency_conversion WHERE num = 2"))
    }

    @Test
    fun create_rejectsBadInput_withoutWritingAnything() {
        pair("BTCEUR", btc, eur)
        assertEquals(
            CurrencyRoutes.CreateError.BAD_LENGTH,
            CurrencyRoutes.create(db, longArrayOf(btc.toLong(), rub.toLong())).error
        )
        assertEquals(
            CurrencyRoutes.CreateError.BAD_LENGTH,
            CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong(), usd.toLong(), 99L)).error
        )
        assertEquals(
            CurrencyRoutes.CreateError.DUPLICATE_CURRENCY,
            CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), btc.toLong())).error
        )
        assertEquals(
            CurrencyRoutes.CreateError.CURRENCY_NOT_FOUND,
            CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), 9999L)).error
        )
        val noPair = CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong()))
        assertEquals(CurrencyRoutes.CreateError.NO_PAIR, noPair.error)
        assertEquals("EUR→RUB", noPair.detail)
        assertEquals(0, intQuery("SELECT count(*) FROM currency_conversion"))
    }

    @Test
    fun create_rejectsDuplicateRoute_inEitherOrder() {
        pair("BTCEUR", btc, eur); pair("EURRUB", eur, rub)
        pair("RUBEUR", rub, eur); pair("EURBTC", eur, btc)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(
            CurrencyRoutes.CreateError.ALREADY_EXISTS,
            CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).error
        )
        assertEquals(
            CurrencyRoutes.CreateError.ALREADY_EXISTS,
            CurrencyRoutes.create(db, longArrayOf(rub.toLong(), eur.toLong(), btc.toLong())).error
        )
        assertEquals(2, intQuery("SELECT count(*) FROM currency_conversion WHERE deleted_at IS NULL"))
    }

    @Test
    fun delete_softDeletesAllSteps_andCreateAgainRevivesSameRows() {
        pair("BTCEUR", btc, eur); pair("EURRUB", eur, rub)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        val uuid1 = stringQuery("SELECT uuid FROM currency_conversion WHERE num = 1")

        assertEquals(2, CurrencyRoutes.delete(db, btc.toLong(), rub.toLong()))
        assertEquals(0, CurrencyRoutes.list(db).size)
        assertEquals(
            "tombstone'ы к отправке", 2,
            intQuery("SELECT count(*) FROM currency_conversion WHERE deleted_at IS NOT NULL AND dirty = 1")
        )
        assertEquals("пары и курсы не тронуты", 2, intQuery("SELECT count(*) FROM currency_pair WHERE deleted_at IS NULL"))

        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(1, CurrencyRoutes.list(db).size)
        assertEquals(
            "тот же ключ — тот же uuid (новый дал бы серверу живой дубль)",
            uuid1, stringQuery("SELECT uuid FROM currency_conversion WHERE num = 1")
        )
        assertEquals(2, intQuery("SELECT count(*) FROM currency_conversion"))
    }

    @Test
    fun delete_ofUnknownRoute_changesNothing() {
        assertEquals(0, CurrencyRoutes.delete(db, btc.toLong(), rub.toLong()))
    }

    // ── оборванные маршруты и каскад ────────────────────────────────────────────

    @Test
    fun brokenRoute_isNotListed_andReplacedByCreate() {
        val p1 = pair("BTCEUR", btc, eur)
        val p2 = pair("EURRUB", eur, rub)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        // Пару шага удалили «на стороне» (как с телефона через синк) — шаги остались, маршрут оборван.
        db.execSQL("UPDATE currency_pair SET deleted_at = 1 WHERE _id = " + p2)
        assertEquals(0, CurrencyRoutes.list(db).size)
        // Новая пара того же шага: маршрут можно создать заново, а не упереться в «уже есть».
        val p2b = pair("EURRUB", eur, rub)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(1, CurrencyRoutes.list(db).size)
        assertEquals(p2b, intQuery("SELECT currency_pair_id FROM currency_conversion WHERE num = 2"))
        assertEquals(p1, intQuery("SELECT currency_pair_id FROM currency_conversion WHERE num = 1"))
    }

    @Test
    fun deletingPair_cascadesWholeRoute_otherRoutesUntouched() {
        pair("BTCEUR", btc, eur)
        val eurrub = pair("EURRUB", eur, rub)
        pair("USDEUR", usd, eur)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertTrue(CurrencyRoutes.create(db, longArrayOf(usd.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(2, CurrencyRoutes.list(db).size)

        assertEquals(1, WalletDbCRUD.DeleteCurrencyPair(db, eurrub))

        // Оба маршрута использовали EURRUB — гаснут целиком; шаг по BTCEUR тоже (все шаги маршрута).
        assertEquals(0, CurrencyRoutes.list(db).size)
        assertEquals(0, intQuery("SELECT count(*) FROM currency_conversion WHERE deleted_at IS NULL"))
    }

    @Test
    fun deletingPairOutsideRoutes_leavesRoutesAlone() {
        pair("BTCEUR", btc, eur); pair("EURRUB", eur, rub)
        val other = pair("USDRUB", usd, rub)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(1, WalletDbCRUD.DeleteCurrencyPair(db, other))
        assertEquals(1, CurrencyRoutes.list(db).size)
    }

    // ── потребитель: эквивалент кошелька ────────────────────────────────────────

    @Test
    fun walletEquivalent_goesThroughExplicitRoute() {
        val p1 = pair("BTCEUR", btc, eur)
        val p2 = pair("EURRUB", eur, rub)
        rate(p1, "2026-01-01", 90000.0)
        rate(p2, "2026-01-01", 100.0)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        val row = btcWalletBalance()
        assertTrue("курс по маршруту найден", row.hasEq)
        assertEquals(18000000.0, row.balanceEq, 0.01)
    }

    @Test
    fun walletEquivalent_goesThroughUsdCrossByDefault() {
        val p1 = pair("BTCUSD", btc, usd)
        val p2 = pair("USDRUB", usd, rub)
        rate(p1, "2026-01-01", 60000.0)
        rate(p2, "2026-01-01", 90.0)
        val row = btcWalletBalance()
        assertTrue(row.hasEq)
        assertEquals(10800000.0, row.balanceEq, 0.01)
    }

    @Test
    fun walletEquivalent_missingWithoutAnySource_notOneToOne() {
        pair("BTCEUR", btc, eur) // BTC→RUB: ни пары, ни маршрута, ни USD
        assertFalse(btcWalletBalance().hasEq)
    }

    @Test
    fun walletEquivalent_directPairBeatsRoute() {
        val p1 = pair("BTCEUR", btc, eur)
        val p2 = pair("EURRUB", eur, rub)
        val direct = pair("BTCRUB", btc, rub)
        rate(p1, "2026-01-01", 90000.0)
        rate(p2, "2026-01-01", 100.0)
        rate(direct, "2026-01-01", 5000000.0)
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc.toLong(), eur.toLong(), rub.toLong())).ok())
        assertEquals(10000000.0, btcWalletBalance().balanceEq, 0.01)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Кошелёк в BTC с поступлением 2 BTC; эквивалент — рубли, остаток на 2026-02-01. */
    private fun btcWalletBalance(): WalletDbLoader.WalletBalanceRow {
        val wallet = WalletDbCRUD.InsertOperatingAccount(db, "BTC wallet", "", iconId.toInt(), btc).toInt()
        val income = insert(
            "account", "name", "Income", "comment", "", "icon_id", iconId,
            "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()
        ).toInt()
        WalletDbCRUD.InsertOperation(db, "2026-01-10 10:00", income, wallet, 2.0, "")
        helper.writableDatabase // путь приложения к базе: кэш составных курсов перестроится, если устарел
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val list = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, list, icons)
        val d = DateUtils.StrToDate("2026-02-01 00:00")
        for (row in WalletDbLoader.LoadBalancesPerWallet(
            db, d, d,
            list.getCurrencyById(rub)!!
        )) {
            if (row.accountId == wallet) return row
        }
        throw AssertionError("нет строки кошелька")
    }

    private fun currency(name: String, iso: String, symbol: String): Int {
        return insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol).toInt()
    }

    private fun pair(symbol: String, base: Int, quote: Int): Int {
        return insert("currency_pair", "symbol", symbol, "base_currency_id", base, "quote_currency_id", quote).toInt()
    }

    private fun rate(pairId: Int, date: String, rate: Double) {
        insert("currency_pair_rate", "currency_pair_id", pairId, "date", date, "rate", rate)
    }

    private fun intQuery(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun stringQuery(sql: String): String {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            assertNotNull(c.getString(0))
            return c.getString(0)
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
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
