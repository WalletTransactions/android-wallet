package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
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
 * openspec category-kind-income-expense, задача 4.2: выбор вида в «Новой категории» и в
 * карточке категории (спека account-screens). База — настоящая, с первичным справочником.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryKindScreensTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var controller: ActivityController<*>? = null
    private var iconId = 0
    private var rubId = 0

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL")
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    // ── «Новая категория» ───────────────────────────────────────────────────

    @Test
    fun newCategory_defaultsToExpense_andSavesIt() {
        val activity = start(AddCategoryActivity::class.java, Intent(context, AddCategoryActivity::class.java))
        activity.findViewById<EditText>(R.id.editCategoryName).setText("Ктест Подарки")

        activity.btnSaveClick(activity.findViewById(R.id.editCategoryName))

        assertEquals(0, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Подарки'"))
        assertEquals(0, intQuery("SELECT kind FROM account WHERE name = 'Ктест Подарки'"))
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE name = 'Ктест Подарки'"))
    }

    @Test
    fun newCategory_incomeChip_savesIncome() {
        val activity = start(AddCategoryActivity::class.java, Intent(context, AddCategoryActivity::class.java))
        activity.findViewById<EditText>(R.id.editCategoryName).setText("Ктест Премия")
        activity.findViewById<View>(R.id.categoryKindIncome).performClick()

        activity.btnSaveClick(activity.findViewById(R.id.editCategoryName))

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Премия'"))
    }

    @Test
    fun newCategory_bothChip_savesBoth() {
        val activity = start(AddCategoryActivity::class.java, Intent(context, AddCategoryActivity::class.java))
        activity.findViewById<EditText>(R.id.editCategoryName).setText("Ктест Прочее")
        activity.findViewById<View>(R.id.categoryKindBoth).performClick()

        activity.btnSaveClick(activity.findViewById(R.id.editCategoryName))

        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Прочее'"))
    }

    // ── карточка категории ──────────────────────────────────────────────────

    @Test
    fun card_regularCategory_showsKind_saveDisabledUntilChanged() {
        val id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE)

        val activity = openCard(id)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.categoryKindGroup).visibility)
        assertFalse("без изменений «Сохранить» неактивна", activity.findViewById<View>(R.id.btnSave).isEnabled)
    }

    @Test
    fun card_changeKind_enablesSave_andWritesKind_withoutTouchingOperations() {
        val id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE)
        val wallet = WalletDbCRUD.InsertOperatingAccount(db, "Ктест Кошелёк", "", iconId, rubId).toInt()
        WalletDbCRUD.InsertOperation(db, "2026-11-01 12:00", wallet, id, 50.0, "")
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = $id")
        val opsBefore = intQuery("SELECT count(*) FROM operation")

        val activity = openCard(id)
        activity.findViewById<View>(R.id.categoryKindBoth).performClick()
        assertTrue("после смены вида «Сохранить» активна", activity.findViewById<View>(R.id.btnSave).isEnabled)
        activity.btnSaveClick(activity.findViewById(R.id.btnSave))

        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = $id"))
        assertEquals("правка помечает строку для синка", 1, intQuery("SELECT dirty FROM account WHERE _id = $id"))
        assertEquals("операции не меняются", opsBefore, intQuery("SELECT count(*) FROM operation"))
    }

    @Test
    fun card_changingKindBackToOriginal_disablesSaveAgain() {
        val id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE)

        val activity = openCard(id)
        activity.findViewById<View>(R.id.categoryKindIncome).performClick()
        assertTrue(activity.findViewById<View>(R.id.btnSave).isEnabled)
        activity.findViewById<View>(R.id.categoryKindExpense).performClick()

        assertFalse(activity.findViewById<View>(R.id.btnSave).isEnabled)
    }

    @Test
    fun card_wallet_hasNoKindChooser_andKindStaysBoth() {
        val wallet = WalletDbCRUD.InsertOperatingAccount(db, "Ктест Кошелёк", "", iconId, rubId).toInt()
        Singleton.instance.reloadAccounts(context)

        val activity = openCard(wallet)

        assertEquals(View.GONE, activity.findViewById<View>(R.id.categoryKindGroup).visibility)
        // Правка названия кошелька вид не трогает.
        activity.findViewById<EditText>(R.id.editAccName).setText("Ктест Кошелёк 2")
        activity.btnSaveClick(activity.findViewById(R.id.btnSave))
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = $wallet"))
    }

    @Test
    fun card_systemCategory_hasNoKindChooser() {
        val transfer = WalletDbCRUD.GetOrCreateSystemCategory(
            db,
            AccountItem.SystemKind.TRANSFER.value, AccountItem.SYSTEM_TRANSFER_NAME, "unknown"
        )
        Singleton.instance.reloadAccounts(context)

        val activity = openCard(transfer)

        assertEquals(View.GONE, activity.findViewById<View>(R.id.categoryKindGroup).visibility)
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun category(name: String, kind: AccountItem.CategoryKind): Int {
        WalletDbCRUD.InsertAccount(db, name, "", iconId, false, kind)
        Singleton.instance.reloadAccounts(context)
        return intQuery("SELECT _id FROM account WHERE name = '$name'")
    }

    private fun openCard(accountId: Int): AccountItemActivity {
        return start(
            AccountItemActivity::class.java, Intent(context, AccountItemActivity::class.java)
                .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId)
        )
    }

    private fun <T : Activity> start(cls: Class<T>, intent: Intent): T {
        controller?.pause()?.stop()?.destroy()
        val c = Robolectric.buildActivity(cls, intent).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
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
}
