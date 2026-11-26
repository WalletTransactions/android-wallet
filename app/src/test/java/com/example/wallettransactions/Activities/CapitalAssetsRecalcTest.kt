package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.SyncLog
import com.example.wallettransactions.sync.SyncRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.lang.reflect.Proxy

/**
 * openspec capital-assets, design п. 10 (android-wallet#42): табы в стеке пересчитывают
 * карточку периода после изменения признака актива — без ручной смены даты. Таб здесь —
 * «Кошельки»: у него та же карточка ([BaseTabActivity.refreshSummaryCard]), что у
 * «Операций», а поднимается он в Robolectric без диалога восстановления.
 */
@RunWith(RobolectricTestRunner::class)
class CapitalAssetsRecalcTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var rubId = 0
    private var iconId = 0
    private var originalPass: Any? = null

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL")
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
        originalPass = swapSyncPass(noNetworkPass())
    }

    @After
    fun tearDown() {
        swapSyncPass(originalPass)
        resetSingleton()
    }

    @Test
    fun openTab_markWalletAsAsset_capitalRowRecalculatedOnReturn() {
        val card = wallet("Тинькофф для теста")
        val salary = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1")
        WalletDbCRUD.InsertOperation(db, "2026-01-10 10:00", salary, card, 1000.0, "")
        Singleton.instance.reloadAccounts(context)
        // Эквивалент — в валюте кошелька: курса RUB→EUR в сиде нет, капитал был бы «~0».
        PeriodFilterState.get(context).setCurrencyEq(
            context,
            Singleton.instance.getCurrencyList(context).getCurrencyById(rubId)
        )

        val tab = Robolectric.buildActivity(
            AccountActivity::class.java,
            Intent(context, AccountActivity::class.java)
        ).setup()
        idle()
        assertEquals(
            "активов нет — строки «Прочее» нет", View.GONE,
            tab.get().findViewById<View>(R.id.otherRow).visibility
        )
        assertEquals(
            "активов нет — строки «Капитал» нет", View.GONE,
            tab.get().findViewById<View>(R.id.otherCapitalRow).visibility
        )
        tab.pause().stop() // ушли в карточку кошелька — таб остался в стеке

        var item = Robolectric.buildActivity(
            AccountItemActivity::class.java,
            Intent(context, AccountItemActivity::class.java).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, card)
        ).setup()
        idle()
        item.get().findViewById<CheckBox>(R.id.checkIsAsset).isChecked = true
        item.get().btnSaveClick(item.get().findViewById(R.id.btnSave))
        item.pause().stop().destroy()

        tab.restart().start().resume()
        idle()
        assertEquals(
            "после пометки — строка «Прочее» без смены даты", View.VISIBLE,
            tab.get().findViewById<View>(R.id.otherRow).visibility
        )
        assertEquals(
            "после пометки — строка «Капитал» в деталях", View.VISIBLE,
            tab.get().findViewById<View>(R.id.otherCapitalRow).visibility
        )
        assertEquals(
            "после пометки — строка «Всего с капиталом и долгами»", View.VISIBLE,
            tab.get().findViewById<View>(R.id.otherTotalRow).visibility
        )
        // android-wallet#43: «Капитал» и «Всего…» — разные строки, у каждой своя подпись и сумма.
        assertTrue(
            "суммы в разных строках",
            tab.get().findViewById<View>(R.id.otherCapitalSum).parent == tab.get().findViewById<View>(R.id.otherCapitalRow)
                    && tab.get().findViewById<View>(R.id.otherTotalSum).parent == tab.get().findViewById<View>(R.id.otherTotalRow)
        )
        val capital = tab.get().findViewById<TextView>(R.id.otherCapitalSum).text.toString()
        assertTrue(capital, capital.replace(Regex("\\D"), "").startsWith("1000"))
        val netWorth = tab.get().findViewById<TextView>(R.id.otherTotalSum).text.toString()
        assertTrue(netWorth, netWorth.replace(Regex("\\D"), "").startsWith("1000"))
        tab.pause().stop()

        // Снятие признака — строка уходит так же сразу.
        item = Robolectric.buildActivity(
            AccountItemActivity::class.java,
            Intent(context, AccountItemActivity::class.java).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, card)
        ).setup()
        idle()
        item.get().findViewById<CheckBox>(R.id.checkIsAsset).isChecked = false
        item.get().btnSaveClick(item.get().findViewById(R.id.btnSave))
        item.pause().stop().destroy()

        tab.restart().start().resume()
        idle()
        assertEquals(View.GONE, tab.get().findViewById<View>(R.id.otherRow).visibility)
        assertEquals(View.GONE, tab.get().findViewById<View>(R.id.otherCapitalRow).visibility)
        tab.pause().stop().destroy()
    }

    @Test
    fun addWallet_asset_bumpsRevision_emptyNameDoesNot() {
        val c = Robolectric.buildActivity(
            AddWalletActivity::class.java,
            Intent(context, AddWalletActivity::class.java)
        ).setup()
        idle()
        val a = c.get()
        val before = PeriodFilterState.get(context).getRevision()

        a.btnSaveClick(a.findViewById(R.id.editWalletName)) // пустое имя — отказ, записи нет
        assertEquals(before, PeriodFilterState.get(context).getRevision())

        a.findViewById<EditText>(R.id.editWalletName).setText("Квартира для теста")
        val currency = AddWalletActivity::class.java.getDeclaredField("selectedCurrency")
        currency.isAccessible = true
        currency.set(a, Singleton.instance.getCurrencyList(context).getCurrencyById(rubId))
        a.findViewById<CheckBox>(R.id.checkIsAsset).isChecked = true
        a.btnSaveClick(a.findViewById(R.id.editWalletName))

        assertEquals(
            1, intQuery(
                "SELECT count(*) FROM account_asset a JOIN account c ON c._id = a._id"
                        + " WHERE c.name = 'Квартира для теста' AND a.deleted_at IS NULL"
            )
        )
        assertTrue("табы в стеке перечитаются", PeriodFilterState.get(context).getRevision() > before)
        c.pause().stop().destroy()
    }

    @Test
    fun revaluation_bumpsRevision() {
        val flat = wallet("Квартира для теста")
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        Singleton.instance.reloadAccounts(context)
        val c = Robolectric.buildActivity(
            AccountItemActivity::class.java,
            Intent(context, AccountItemActivity::class.java).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, flat)
        ).setup()
        idle()
        val before = PeriodFilterState.get(context).getRevision()

        RevaluationDialog(
            c.get(),
            Singleton.instance.getAccounts(context).getAccountById(flat) as AccountOperatingItem, null
        ).show()
        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<EditText>(R.id.inputValue).setText("5000000")
        dialog.findViewById<View>(R.id.btnSave).performClick()
        idle()

        assertTrue("переоценка пересчитывает табы", PeriodFilterState.get(context).getRevision() > before)
        c.pause().stop().destroy()
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun wallet(name: String): Int {
        return WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId, rubId).toInt()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun intQuery(sql: String): Int {
        val cur = db.rawQuery(sql, null)
        try {
            assertTrue(sql, cur.moveToFirst())
            return cur.getInt(0)
        } finally {
            cur.close()
        }
    }

    /** Синк в фоне после записи — без сети и без чужого потока в базе теста. */
    private fun noNetworkPass(): Any {
        val passType = Class.forName("com.example.wallettransactions.sync.SyncRunner\$Pass")
        return Proxy.newProxyInstance(passType.classLoader, arrayOf(passType)) { _, method, _ ->
            if (method.name == "run") SyncRunner.Outcome(SyncRunner.Status.NO_SESSION, SyncLog(), null)
            else null
        }
    }

    private fun swapSyncPass(pass: Any?): Any? {
        val f = SyncRunner::class.java.getDeclaredField("pass")
        f.isAccessible = true
        val old = f.get(null)
        f.set(null, pass)
        return old
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
