package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * openspec similar-search / operations-filter: у каждого заданного параметра на экране
 * «Фильтр» есть свой крестик, он очищает только этот параметр, а у пустого фильтра
 * крестиков нет.
 */
@RunWith(RobolectricTestRunner::class)
class FilterActivityClearTest {

    private lateinit var context: Context
    private var categoryId = 0
    private var walletId = 0

    @Before
    fun setUp() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val state = PeriodFilterState::class.java.getDeclaredField("instance")
        state.isAccessible = true
        state.set(null, null)

        context = ApplicationProvider.getApplicationContext()
        AppSettings.setFilterAccounts(context, "")
        AppSettings.setFilterOperationKind(context, "all")
        AppSettings.setFilterSearchText(context, "")
        AppSettings.setFilterAmountRange(context, null, null)
        AppSettings.setFilterEqRange(context, null, null)

        val db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow
        val rub = intQuery(db, "SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL")
        val icon = intQuery(db, "SELECT _id FROM icon WHERE name = 'unknown'")
        categoryId = intQuery(db, "SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 "
                + "AND deleted_at IS NULL LIMIT 1")
        walletId = WalletDbCRUD.InsertOperatingAccount(db, "Тестовый кошелёк", "", icon, rub).toInt()
        Singleton.instance.reloadAccounts(context)
    }

    private fun open(accountIdsCsv: String): ActivityController<FilterActivity> {
        val intent = Intent(context, FilterActivity::class.java)
        intent.putExtra(MainActivity.cFilterAccountList, accountIdsCsv)
        return Robolectric.buildActivity(FilterActivity::class.java, intent).setup()
    }

    private fun visible(a: FilterActivity, id: Int): Boolean {
        return a.findViewById<View>(id).visibility == View.VISIBLE
    }

    private fun text(a: FilterActivity, id: Int): String {
        return a.findViewById<EditText>(id).text.toString()
    }

    private fun click(a: FilterActivity, id: Int) {
        assertTrue("крестик должен быть виден", visible(a, id))
        a.findViewById<View>(id).performClick()
    }

    @Test
    fun emptyFilter_hasNoCrosses() {
        val a = open("").get()

        for (id in intArrayOf(R.id.btnClearSearch, R.id.btnClearKind, R.id.btnClearAmountMin,
                R.id.btnClearAmountMax, R.id.btnClearEqMin, R.id.btnClearEqMax,
                R.id.btnClearCategories, R.id.btnClearWallets)) {
            assertFalse(visible(a, id))
        }
        assertTrue(visible(a, R.id.chevronCategories))
        assertTrue(visible(a, R.id.chevronWallets))
    }

    @Test
    fun everyCross_clearsOnlyItsOwnParameter() {
        val state = PeriodFilterState.get(context)
        state.setSearchText(context, "молоко")
        state.setOperationKind(context, DataFilter.OperationKind.expense)
        state.setAmountRange(context, 10.0, 99.0)
        state.setEqRange(context, 1.0, 2.0)

        val a = open("$categoryId,$walletId").get()

        // Всё задано — все крестики на месте, шевронов у строк счетов нет.
        for (id in intArrayOf(R.id.btnClearSearch, R.id.btnClearKind, R.id.btnClearAmountMin,
                R.id.btnClearAmountMax, R.id.btnClearEqMin, R.id.btnClearEqMax,
                R.id.btnClearCategories, R.id.btnClearWallets)) {
            assertTrue(visible(a, id))
        }
        assertFalse(visible(a, R.id.chevronCategories))
        assertFalse(visible(a, R.id.chevronWallets))

        // Одна граница суммы: «до» очищена, «от» осталась.
        click(a, R.id.btnClearAmountMax)
        assertEquals("", text(a, R.id.inputAmountMax))
        assertEquals("10", text(a, R.id.inputAmountMin))
        assertFalse(visible(a, R.id.btnClearAmountMax))
        assertTrue(visible(a, R.id.btnClearAmountMin))

        // Текст.
        click(a, R.id.btnClearSearch)
        assertEquals("", text(a, R.id.inputSearchText))
        assertEquals("1", text(a, R.id.inputEqMin))

        // Вид: крестик исчезает, счета не тронуты.
        click(a, R.id.btnClearKind)
        assertFalse(visible(a, R.id.btnClearKind))
        assertTrue(visible(a, R.id.btnClearCategories))

        // Категории очищены — кошелёк остался, у строки категорий вернулся шеврон.
        click(a, R.id.btnClearCategories)
        assertEquals(a.getString(R.string.filter_all),
                a.findViewById<TextView>(R.id.categorySummary).text.toString())
        assertTrue(visible(a, R.id.chevronCategories))
        assertFalse(visible(a, R.id.btnClearCategories))
        assertTrue(visible(a, R.id.btnClearWallets))
        assertFalse(visible(a, R.id.chevronWallets))

        // Оставшиеся поля.
        click(a, R.id.btnClearAmountMin)
        click(a, R.id.btnClearEqMin)
        click(a, R.id.btnClearEqMax)
        click(a, R.id.btnClearWallets)
        assertFalse(visible(a, R.id.btnClearWallets))
        assertTrue(visible(a, R.id.chevronWallets))
    }

    @Test
    fun crosses_doNotChangeSavedFilterUntilApplied() {
        val state = PeriodFilterState.get(context)
        state.setSearchText(context, "молоко")

        val a = open("").get()
        click(a, R.id.btnClearSearch)

        // Крестик меняет форму, а общий фильтр — только «Показать».
        assertEquals("молоко", state.getSearchText())
    }

    private fun intQuery(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { c ->
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        }
    }
}
