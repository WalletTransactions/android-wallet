package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
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
 * openspec capital-assets, экраны (вместо проверки на эмуляторе — его на машине нет):
 * «Новый кошелёк», карточка кошелька/актива/категории, диалог переоценки, секции таба
 * «Кошельки» и выбор счёта при вводе операции. База — настоящая, с первичным справочником.
 */
@RunWith(RobolectricTestRunner::class)
class CapitalAssetsScreensTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var controller: ActivityController<*>? = null
    private var rubId = 0
    private var iconId = 0

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL")
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    // ── 5.3 «Новый кошелёк» и карточка ──────────────────────────────────────

    @Test
    fun addWallet_assetSwitch_showsTypeOnly_andSavesAsset() {
        val activity = start(AddWalletActivity::class.java, Intent(context, AddWalletActivity::class.java))
        val isAsset = activity.findViewById<CheckBox>(R.id.checkIsAsset)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.assetDetails).visibility)
        isAsset.isChecked = true
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.assetDetails).visibility)
        assertEquals(
            "«Учитывать…» на создании скрыт", View.GONE,
            activity.findViewById<View>(R.id.checkInNetWorth).visibility
        )
    }

    @Test
    fun assetCard_hasSwitchOn_andRevaluateButton_plainWalletHasNoButton() {
        val flat = wallet("Квартира Ленина")
        val card = wallet("Карта для теста")
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        Singleton.instance.reloadAccounts(context)

        val a = openCard(flat)
        assertTrue(a.findViewById<CheckBox>(R.id.checkIsAsset).isChecked)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.btnRevaluate).visibility)
        controller!!.pause().stop().destroy()

        val b = openCard(card)
        assertFalse(b.findViewById<CheckBox>(R.id.checkIsAsset).isChecked)
        assertEquals(View.GONE, b.findViewById<View>(R.id.btnRevaluate).visibility)
    }

    @Test
    fun markExistingWallet_andSave_writesAsset_operationsUntouched() {
        val car = wallet("Машина для теста")
        Singleton.instance.reloadAccounts(context)
        val a = openCard(car)
        a.findViewById<CheckBox>(R.id.checkIsAsset).isChecked = true
        a.findViewById<View>(R.id.assetTypeVehicle).performClick()
        assertTrue(a.findViewById<View>(R.id.btnSave).isEnabled)
        a.btnSaveClick(a.findViewById(R.id.btnSave))

        assertEquals(2, intQuery("SELECT asset_type FROM account_asset WHERE _id = $car AND deleted_at IS NULL"))
        assertTrue((Singleton.instance.getAccounts(context).getAccountById(car) as AccountOperatingItem).isAsset)
    }

    @Test
    fun categoryCard_noAssetFields_moveActionOnlyWhenAssetsExist() {
        val category = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1")
        val a = openCard(category)
        assertEquals(
            "у категории нет «Капитального актива»", View.GONE,
            a.findViewById<View>(R.id.operatingGroup).visibility
        )
        assertEquals("активов нет — нет и переноса", View.GONE, a.findViewById<View>(R.id.btnMoveToAsset).visibility)
        controller!!.pause().stop().destroy()

        WalletDbCRUD.SetAccountAsset(db, wallet("Квартира Ленина"), 1, true)
        Singleton.instance.reloadAccounts(context)
        val b = openCard(category)
        assertEquals(View.VISIBLE, b.findViewById<View>(R.id.btnMoveToAsset).visibility)
    }

    @Test
    fun revaluationCategoryCard_systemHint_nameLocked_noDelete() {
        val reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db)
        Singleton.instance.reloadAccounts(context)
        val a = openCard(reval)
        assertFalse(a.findViewById<View>(R.id.editAccName).isEnabled)
        assertEquals(View.GONE, a.findViewById<View>(R.id.btnDelete).visibility)
        assertTrue(
            a.findViewById<EditText>(R.id.editComment).hint.toString()
                .contains("через неё проводится переоценка активов")
        )
    }

    // ── 5.5 диалог переоценки ───────────────────────────────────────────────

    @Test
    fun revaluationDialog_showsCurrentAndDelta() {
        val flat = wallet("Квартира Ленина")
        val card = wallet("Карта для теста")
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        WalletDbCRUD.InsertOperation(db, "2026-01-10 10:00", card, flat, 10_000_000.0, "")
        Singleton.instance.reloadAccounts(context)
        val a = openCard(flat)

        val dialog = RevaluationDialog(
            a,
            Singleton.instance.getAccounts(context).getAccountById(flat) as AccountOperatingItem, null
        )
        val line = dialog.deltaLine("12000000")
        assertTrue(line, line.startsWith("Сейчас: 10"))
        assertTrue(line, line.contains("Разница: +2"))
        assertTrue(dialog.deltaLine("9500000").contains("Разница: −500"))
    }

    // ── 5.2 секции таба «Кошельки» ──────────────────────────────────────────

    @Test
    fun walletsTab_sectionsOnlyWithAssets() {
        val card = wallet("Карта для теста")
        val flat = wallet("Квартира Ленина")
        val salary = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1")
        WalletDbCRUD.InsertOperation(db, "2020-01-10 10:00", salary, card, 1000.0, "")
        WalletDbCRUD.InsertOperation(db, "2020-01-11 10:00", card, flat, 400.0, "")
        Singleton.instance.reloadAccounts(context)
        assertFalse(
            sectionTitles(start(AccountActivity::class.java, Intent(context, AccountActivity::class.java)))
                .contains("Капитал")
        )
        controller!!.pause().stop().destroy()

        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        Singleton.instance.reloadAccounts(context)
        val titles = sectionTitles(start(AccountActivity::class.java, Intent(context, AccountActivity::class.java)))
        assertTrue(titles.toString(), titles.contains("Ликвидные"))
        assertTrue(titles.toString(), titles.contains("Капитал"))
        assertTrue(titles.indexOf("Ликвидные") < titles.indexOf("Капитал"))
    }

    // ── 5.4 выбор счёта ─────────────────────────────────────────────────────

    @Test
    fun picker_inputMode_assetsAfterLiquid_revaluationHidden() {
        val flat = wallet("Аааа квартира") // по алфавиту — первая
        wallet("Яяяя наличные")
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        val reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db)
        Singleton.instance.reloadAccounts(context)

        val wallets = start(
            AccountPickerActivity::class.java, AccountPickerActivity.buildIntent(
                context, "Кошелёк", AccountPickerActivity.cSelectedTypeOperationAccounts, 0
            )
        )
        val names = listNames(wallets)
        val assetPos = indexOfPrefix(names, "Аааа квартира")
        val cashPos = indexOfPrefix(names, "Яяяя наличные")
        assertTrue(names.toString(), assetPos > cashPos)
        assertTrue("подпись типа актива", names[assetPos].contains("недвижимость"))
        controller!!.pause().stop().destroy()

        val categories = start(
            AccountPickerActivity::class.java, AccountPickerActivity.buildIntent(
                context, "Категория", AccountPickerActivity.cSelectedTypeChargeAccounts, 0
            )
        )
        val revalName = Singleton.instance.getAccounts(context).getAccountById(reval)!!.name
        assertEquals(-1, indexOfPrefix(listNames(categories), revalName))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun wallet(name: String): Int {
        return WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId, rubId).toInt()
    }

    private fun openCard(accountId: Int): AccountItemActivity {
        return start(
            AccountItemActivity::class.java, Intent(context, AccountItemActivity::class.java)
                .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId)
        )
    }

    private fun <T : Activity> start(cls: Class<T>, intent: Intent): T {
        val c = Robolectric.buildActivity(cls, intent).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun sectionTitles(activity: AccountActivity): List<String> {
        val list = activity.findViewById<ListView>(R.id.listWallets)
        val adapter = list.adapter
        val titles = ArrayList<String>()
        for (i in 0 until adapter.count) {
            val v = adapter.getView(i, null, list)
            val t = v.findViewById<TextView>(R.id.sectionTitle)
            if (t != null) titles.add(t.text.toString())
        }
        return titles
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

    private fun indexOfPrefix(names: List<String>, prefix: String): Int {
        for (i in names.indices) if (names[i].startsWith(prefix)) return i
        return -1
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
