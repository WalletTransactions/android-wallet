package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * openspec category-kind-income-expense, задача 3.2: выбор «Статьи расхода» / «Статьи дохода»
 * (спека operation-entry, «Выбор счёта для строки») фильтрует категории по виду. Три тестовые
 * категории — «Расход», «Доход», «Оба» — заводятся поверх первичного справочника.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryKindPickerTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var controller: ActivityController<*>? = null
    private var iconId = 0
    private var incomeId = 0

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
        WalletDbCRUD.InsertAccount(db, EXPENSE, "", iconId, false, AccountItem.CategoryKind.EXPENSE)
        WalletDbCRUD.InsertAccount(db, INCOME, "", iconId, false, AccountItem.CategoryKind.INCOME)
        WalletDbCRUD.InsertAccount(db, BOTH, "", iconId, false, AccountItem.CategoryKind.BOTH)
        incomeId = intQuery("SELECT _id FROM account WHERE name = '$INCOME'")
        Singleton.instance.reloadAccounts(context)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    @Test
    fun expenseSlot_showsExpenseAndBoth_hidesIncome() {
        val names = listNames(open(0, 0, null))

        assertTrue(names.toString(), names.contains(EXPENSE))
        assertTrue(names.toString(), names.contains(BOTH))
        assertFalse(names.toString(), names.contains(INCOME))
    }

    @Test
    fun incomeSlot_showsIncomeAndBoth_hidesExpense() {
        val names = listNames(open(1, 0, null))

        assertTrue(names.toString(), names.contains(INCOME))
        assertTrue(names.toString(), names.contains(BOTH))
        assertFalse(names.toString(), names.contains(EXPENSE))
    }

    @Test
    fun withoutKindExtra_showsEveryCategory() {
        val names = listNames(open(NO_EXTRA, 0, null))

        assertTrue(names.toString(), names.contains(EXPENSE))
        assertTrue(names.toString(), names.contains(INCOME))
        assertTrue(names.toString(), names.contains(BOTH))
    }

    @Test
    fun alreadySelectedCategory_staysEvenWithWrongKind() {
        // Правка расхода по категории вида «Доход»: «Статья расхода» показывает её выбранной.
        val names = listNames(open(0, incomeId, null))

        assertTrue(names.toString(), names.contains(INCOME))
        assertTrue(names.toString(), names.contains(EXPENSE))
        assertTrue(names.toString(), names.contains(BOTH))
    }

    @Test
    fun priorityCandidate_staysEvenWithWrongKind() {
        // Кандидат ИИ / импорта выписки предложен явно — скрывать его было бы сюрпризом.
        val names = listNames(open(0, 0, intArrayOf(incomeId)))

        assertTrue(names.toString(), names.contains(INCOME))
    }

    @Test
    fun catalogMode_ignoresKindExtra() {
        val catalog = AccountPickerActivity.buildCatalogIntent(context)
            .putExtra(AccountPickerActivity.cCategoryKind, 0)
        val activity = start(AccountPickerActivity::class.java, catalog)
        // Справочник по умолчанию открыт на кошельках — переключаемся на категории.
        activity.findViewById<android.view.View>(R.id.kindCategories).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val names = listNames(activity)
        assertTrue(names.toString(), names.contains(EXPENSE))
        assertTrue(names.toString(), names.contains(INCOME))
        assertTrue(names.toString(), names.contains(BOTH))
    }

    @Test
    fun wallets_areNotAffectedByKindExtra() {
        // Кошельки не фильтруются по виду: вид бывает только у категорий.
        val intent = AccountPickerActivity.buildIntent(
            context, "Кошелёк",
            AccountPickerActivity.cSelectedTypeOperationAccounts, 0
        ).putExtra(AccountPickerActivity.cCategoryKind, 0)
        val names = listNames(start(AccountPickerActivity::class.java, intent))

        assertFalse(names.toString(), names.isEmpty())
        assertFalse(names.toString(), names.contains(EXPENSE))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    /** Выбор статьи: `kindExtra` — 0/1 или [NO_EXTRA]. */
    private fun open(kindExtra: Int, currentId: Int, priority: IntArray?): AccountPickerActivity {
        val intent = AccountPickerActivity.buildIntent(
            context, "Статья",
            AccountPickerActivity.cSelectedTypeChargeAccounts, currentId, null, priority
        )
        if (kindExtra != NO_EXTRA) {
            intent.putExtra(AccountPickerActivity.cCategoryKind, kindExtra)
        }
        return start(AccountPickerActivity::class.java, intent)
    }

    private fun <T : Activity> start(cls: Class<T>, intent: Intent): T {
        controller?.pause()?.stop()?.destroy()
        val c = Robolectric.buildActivity(cls, intent).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun listNames(activity: AccountPickerActivity): List<String> {
        val list = activity.findViewById<ListView>(R.id.listAccounts)
        val adapter = list.adapter
        val names = ArrayList<String>()
        for (i in 0 until adapter.count) {
            val v = adapter.getView(i, null, list)
            names.add(v.findViewById<TextView>(R.id.pickerName).text.toString())
        }
        return names
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

    companion object {
        private const val EXPENSE = "Ктест-расход"
        private const val INCOME = "Ктест-доход"
        private const val BOTH = "Ктест-оба"

        /** Значение `kindExtra` для [open]: extra пикеру не передаётся. */
        private const val NO_EXTRA = Int.MIN_VALUE
    }
}
