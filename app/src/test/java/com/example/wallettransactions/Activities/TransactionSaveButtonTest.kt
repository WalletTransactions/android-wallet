package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.Date

/**
 * Кнопка «Сохранить» в окне операции сразу после открытия (changes/fix-prefill-save-enabled).
 * Черновик из ИИ открывает окно новой операции, где заполнено всё; раньше onCreate
 * гасил кнопку безусловно, и сохранить можно было только после любой правки.
 * База — настоящая, с первичным справочником (WalletFillData): из неё берётся категория,
 * кошелёк заводит setUp.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionSaveButtonTest {

    private lateinit var context: Context
    private var controller: ActivityController<TransactionActivity>? = null

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        // Картинки первичного справочника не влезают в CursorWindow Robolectric,
        // а кнопке «Сохранить» они не нужны.
        val db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'")
        // Во встроенном наборе кошельков нет (new-user-default-set) — заводим один.
        val c = db.rawQuery("SELECT (SELECT _id FROM icon LIMIT 1),"
                + " (SELECT _id FROM currency WHERE iso_code = 'EUR')", null)
        try {
            c.moveToFirst()
            com.example.wallettransactions.Data.WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "",
                    c.getInt(0), c.getInt(1))
        } finally {
            c.close()
        }
        Singleton.instance.reloadAccounts(context)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    @Test
    fun ai_prefilled_new_operation_can_be_saved_at_once() {
        val save = open(prefilledExpense(150.0))
        assertTrue("заполненный черновик сохраняется без лишнего касания", save.isEnabled)
    }

    @Test
    fun new_operation_without_amount_stays_disabled() {
        val save = open(prefilledExpense(0.0))
        assertFalse("без суммы сохранять нечего", save.isEnabled)
    }

    @Test
    fun edit_without_changes_stays_disabled() {
        val intent = prefilledExpense(150.0)
                .putExtra(TransactionActivity.cIsEdit, true)
                .putExtra(TransactionActivity.cTranId, 1)
        val save = open(intent)
        assertFalse("правка без изменений по-прежнему погашена", save.isEnabled)
    }

    @Test
    fun big_amount_is_shown_without_exponent() {
        open(prefilledExpense(10_000_000.0))
        val amount = controller!!.get().findViewById<android.widget.EditText>(R.id.editAmount)
        assertEquals("не «1.0E7»", "10000000", amount.text.toString())
    }

    @Test
    fun expense_to_transfer_keeps_wallet_as_source() {
        val intent = prefilledExpense(150.0)
        val wallet = Singleton.instance.getAccounts(context).getAccountById(intent.getIntExtra(TransactionActivity.cFrom, 0))
        open(intent)
        val a = controller!!.get()
        val top = a.findViewById<android.widget.TextView>(R.id.editSlotTop)
        val bottom = a.findViewById<android.widget.TextView>(R.id.editSlotBottom)
        assertEquals("расход: кошелёк — нижняя строка", wallet!!.displayName, bottom.text.toString())

        a.onKindClick(a.findViewById(R.id.kindTransfer))
        assertEquals("деньги уходили с кошелька — в переводе он «Откуда»", wallet!!.displayName, top.text.toString())
        assertEquals("", bottom.text.toString())

        a.onKindClick(a.findViewById(R.id.kindExpense))
        assertEquals("обратно в расход — снова кошелёк снизу", wallet!!.displayName, bottom.text.toString())
    }

    @Test
    fun transfer_swap_button_swaps_from_and_to() {
        val db = Singleton.instance.getWritableDatabase(context)
        val c = db.rawQuery("SELECT _id FROM currency WHERE deleted_at IS NULL LIMIT 1", null)
        c.moveToFirst()
        val cur = c.getInt(0)
        c.close()
        val a0 = com.example.wallettransactions.Data.WalletDbCRUD.InsertOperatingAccount(db, "Swap A", "", 1, cur).toInt()
        val b = com.example.wallettransactions.Data.WalletDbCRUD.InsertOperatingAccount(db, "Swap B", "", 1, cur).toInt()
        Singleton.instance.reloadAccounts(context)
        val intent = prefilledExpense(100.0)
                .putExtra(TransactionActivity.cFrom, a0)
                .putExtra(TransactionActivity.cTo, b)
                .putExtra(TransactionActivity.cKind, "transfer")
        open(intent)
        val a = controller!!.get()
        val top = a.findViewById<android.widget.TextView>(R.id.editSlotTop)
        val bottom = a.findViewById<android.widget.TextView>(R.id.editSlotBottom)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.btnSwapSlots).visibility)
        assertEquals("Swap A", top.text.toString())

        a.onSwapSlots(a.findViewById<View>(R.id.btnSwapSlots))
        assertEquals("Swap B", top.text.toString())
        assertEquals("Swap A", bottom.text.toString())

        a.onKindClick(a.findViewById(R.id.kindExpense))
        assertEquals("у расхода кнопки нет", View.GONE, a.findViewById<View>(R.id.btnSwapSlots).visibility)
    }

    /** Те же extras, что кладёт AiActivity для черновика из ИИ. */
    private fun prefilledExpense(amount: Double): Intent {
        var wallet: AccountItem? = null
        var category: AccountItem? = null
        for (a in Singleton.instance.getAccounts(context).allAccounts) {
            if (a.systemKind != AccountItem.SystemKind.NONE) continue
            if (a.isOperatingAccount && wallet == null) wallet = a
            if (!a.isOperatingAccount && category == null) category = a
        }
        assertNotNull("в первичном справочнике есть кошелёк", wallet)
        assertNotNull("в первичном справочнике есть категория", category)
        return Intent(context, TransactionActivity::class.java)
                .putExtra(TransactionActivity.cDate, DateUtils.DateToStr(Date()))
                .putExtra(TransactionActivity.cAmount, amount)
                .putExtra(TransactionActivity.cComment, "кофе")
                .putExtra(TransactionActivity.cFrom, wallet!!.id)
                .putExtra(TransactionActivity.cTo, category!!.id)
                .putExtra(TransactionActivity.cIsEdit, false)
                .putExtra(TransactionActivity.cKind, "expense")
                .putExtra(TransactionActivity.cHint, "Распознано ИИ")
    }

    private fun open(intent: Intent): View {
        controller = Robolectric.buildActivity(TransactionActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get().findViewById(R.id.buttonSave)
    }

    private fun resetSingleton() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, CurrencyPairList())
    }
}
