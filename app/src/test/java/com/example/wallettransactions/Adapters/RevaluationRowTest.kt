package com.example.wallettransactions.Adapters

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.DataItems.Operations.OperationList
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec capital-assets, задача 5.6: строка переоценки в ленте — актив, подпись
 * «Переоценка», «+»/«−» цветом перевода; итог дня в шапке без переоценки.
 */
@RunWith(RobolectricTestRunner::class)
class RevaluationRowTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'")
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    @Test
    fun revaluationRow_signLabelColor_andDayTotalWithoutIt() {
        val rub = single("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL").toInt()
        val icon = single("SELECT _id FROM icon WHERE name = 'unknown'").toInt()
        val card = WalletDbCRUD.InsertOperatingAccount(db, "Карта для теста", "", icon, rub).toInt()
        val flat = WalletDbCRUD.InsertOperatingAccount(db, "Квартира Ленина", "", icon, rub).toInt()
        val food = single("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1").toInt()
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        WalletDbCRUD.InsertOperation(db, "2026-06-15 10:00", card, food, 500.0, "")
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-06-15", 2_000_000.0, 2, "")
        Singleton.instance.reloadAccounts(context)

        val rubCurrency = Singleton.instance.getCurrencyList(context).getCurrencyById(rub)
        val ops = OperationList()
        WalletDbLoader.LoadOperations(
            db, ops, Singleton.instance.getAccounts(context),
            DateUtils.StrToDate("2026-06-15 00:00"), DateUtils.StrToDate("2026-06-15 23:59"),
            rubCurrency!!, DataFilter()
        )
        assertEquals(2, ops.size)
        assertTrue("сначала переоценка (23:59)", ops[0].isRevaluation)

        val adapter = TransactionArrayAdapter(context, ops, rubCurrency, TransactionArrayAdapter.GroupByTime.day)
        assertEquals(2, adapter.getItemViewType(0))
        val row = adapter.getView(0, null, FrameLayout(context))

        assertEquals("Квартира Ленина", row.findViewById<TextView>(R.id.revaluationAsset).text.toString())
        assertTrue(row.findViewById<TextView>(R.id.revaluationLabel).text.toString().startsWith("Переоценка"))
        val amount = row.findViewById<TextView>(R.id.revaluationAmount)
        assertTrue(amount.text.toString(), amount.text.toString().startsWith("+2"))
        assertEquals(ContextCompat.getColor(context, R.color.colortransfer), amount.currentTextColor)
        val dayTotal = row.findViewById<TextView>(R.id.textAmountEq).text.toString()
        assertTrue("итог дня — только расход 500: $dayTotal", dayTotal.contains("500") && !dayTotal.contains("2 000"))
    }

    private fun single(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun resetSingleton() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase", "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, CurrencyPairList())
    }
}
