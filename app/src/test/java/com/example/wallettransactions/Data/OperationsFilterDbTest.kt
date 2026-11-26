package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * openspec similar-search / operations-filter: «И» между группой категорий и группой
 * кошельков, «ИЛИ» внутри группы — на реальной базе, и в ленте, и в отчёте «Расход».
 */
@RunWith(RobolectricTestRunner::class)
class OperationsFilterDbTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var rub: Int = 0
    private var card: Int = 0
    private var cash: Int = 0
    private var food: Int = 0
    private var cafe: Int = 0

    private lateinit var from: Date
    private lateinit var to: Date
    private lateinit var eq: Currency
    private lateinit var accounts: AccountList
    private lateinit var currencies: CurrencyList

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM operation_template")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        db.execSQL("DELETE FROM currency_conversion")
        db.execSQL("DELETE FROM currency_pair_rate")
        db.execSQL("DELETE FROM currency_pair")
        db.execSQL("DELETE FROM currency")
        db.execSQL("DELETE FROM icon")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        rub = insert("currency", "name", "Рубль", "iso_code", "RUB", "icon_id", iconId, "symbol", "₽").toInt()

        card = WalletDbCRUD.InsertOperatingAccount(db, "Карта", "", iconId.toInt(), rub).toInt()
        cash = WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", iconId.toInt(), rub).toInt()
        food = category("Продукты")
        cafe = category("Кафе")

        WalletDbCRUD.InsertOperation(db, "2026-05-02 10:00", card, food, 100.0, "")
        WalletDbCRUD.InsertOperation(db, "2026-05-03 10:00", cash, food, 200.0, "")
        WalletDbCRUD.InsertOperation(db, "2026-05-04 10:00", card, cafe, 50.0, "")

        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        currencies = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, currencies, icons)
        accounts = AccountList()
        WalletDbLoader.LoadAccounts(db, accounts, currencies, icons, null)

        from = DateUtils.StrToDate("2026-05-01 00:00")
        to = DateUtils.StrToDate("2026-05-31 23:59")
        eq = currencies.getCurrencyById(rub)!!
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun filter(vararg ids: Int): DataFilter {
        val filter = DataFilter()
        for (id in ids) filter.addAccount(accounts.getAccountById(id)!!)
        return filter
    }

    private fun count(filter: DataFilter): Int {
        return WalletDbLoader.CountOperations(db, from, to, eq, filter)
    }

    private fun expense(filter: DataFilter): Double {
        return WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, filter)
                .total.totalAmountEq!!
    }

    @Test
    fun categoryAndWallet_giveIntersection() {
        val f = filter(food, card)
        assertEquals(1, count(f))
        assertEquals(100.0, expense(f), 0.001)
    }

    @Test
    fun onlyCategory_keepsOldBehavior() {
        val f = filter(food)
        assertEquals(2, count(f))
        assertEquals(300.0, expense(f), 0.001)
    }

    @Test
    fun onlyWallet_keepsOldBehavior() {
        val f = filter(card)
        assertEquals(2, count(f))
        assertEquals(150.0, expense(f), 0.001)
    }

    @Test
    fun twoWalletsInGroup_areOr() {
        val f = filter(food, card, cash)
        assertEquals(2, count(f))
        assertEquals(300.0, expense(f), 0.001)
    }

    @Test
    fun twoCategoriesInGroup_areOr() {
        val f = filter(food, cafe, card)
        assertEquals(2, count(f))
        assertEquals(150.0, expense(f), 0.001)
    }

    @Test
    fun emptyFilter_countsAll() {
        assertEquals(3, count(DataFilter()))
    }

    private fun category(name: String): Int {
        return insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()).toInt()
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
