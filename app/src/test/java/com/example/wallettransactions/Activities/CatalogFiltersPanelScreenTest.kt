package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
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
 * openspec catalog-filters-panel, задачи 4.1–4.4 (спеки account-screens и more-and-settings):
 * панель «Фильтры» справочника «Кошельки и категории», её исходное состояние из настроек,
 * чипы вида и «Показывать архивные». База — настоящая, с первичным справочником; свои
 * категории начинаются с «Ктест», чтобы не мешать записям сида.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogFiltersPanelScreenTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var controller: ActivityController<AccountPickerActivity>? = null
    private var iconId = 0

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    // ── панель и её исходное состояние ──────────────────────────────────────

    @Test
    fun defaultSettings_panelCollapsed_searchAndButtonVisible() {
        val a = openCatalog()

        assertEquals(View.GONE, panel(a).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.btnFilters).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.searchInput).visibility)
        assertEquals(a.getString(R.string.picker_filters_closed), button(a).text.toString())
        // Старых строк над списком нет: ни чипов типов, ни флажка — они внутри свёрнутой панели.
        assertEquals(View.GONE, panel(a).visibility)
    }

    @Test
    fun slotMode_hasNoButtonAndNoPanel() {
        val a = start(
            Intent(context, AccountPickerActivity::class.java)
                .putExtra(AccountPickerActivity.cAccountSelectedType, AccountPickerActivity.cSelectedTypeChargeAccounts)
        )

        assertEquals(View.GONE, a.findViewById<View>(R.id.btnFilters).visibility)
        assertEquals(View.GONE, panel(a).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.searchInput).visibility)
    }

    @Test
    fun slotMode_ignoresPanelSettings() {
        AppSettings.setCatalogPanelOpen(context, true, true)
        AppSettings.setCatalogPanelOpen(context, false, true)
        val a = start(
            Intent(context, AccountPickerActivity::class.java)
                .putExtra(AccountPickerActivity.cAccountSelectedType, AccountPickerActivity.cSelectedTypeChargeAccounts)
        )

        assertEquals(View.GONE, panel(a).visibility)
    }

    @Test
    fun tapOpensAndClosesPanel() {
        val a = openCatalog()

        button(a).performClick()
        assertEquals(View.VISIBLE, panel(a).visibility)
        assertEquals(a.getString(R.string.picker_filters_open), button(a).text.toString())
        assertEquals("поле поиска на месте при развёрнутой панели", View.VISIBLE, a.findViewById<View>(R.id.searchInput).visibility)

        button(a).performClick()
        assertEquals(View.GONE, panel(a).visibility)
    }

    @Test
    fun categoriesSettingOn_openExpandedOnCategoriesOnly() {
        AppSettings.setCatalogPanelOpen(context, false, true)
        val a = openCatalog()

        assertEquals("справочник открывается на «Кошельках»", View.GONE, panel(a).visibility)
        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals(View.VISIBLE, panel(a).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rowCategoryKindFilter).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.rowWalletTypeFilter).visibility)
    }

    @Test
    fun settingsAreIndependent_walletsOnly() {
        AppSettings.setCatalogPanelOpen(context, true, true)
        val a = openCatalog()

        assertEquals(View.VISIBLE, panel(a).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rowWalletTypeFilter).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.rowCategoryKindFilter).visibility)
        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals(View.GONE, panel(a).visibility)
    }

    @Test
    fun manualCollapse_doesNotChangeSetting_reopenTakesSetting() {
        AppSettings.setCatalogPanelOpen(context, false, true)
        var a = openCatalog()
        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals(View.VISIBLE, panel(a).visibility)

        button(a).performClick()
        assertEquals(View.GONE, panel(a).visibility)
        assertTrue("настройка осталась включённой", AppSettings.isCatalogPanelOpen(context, false))

        a = openCatalog()
        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals("новое открытие — снова по настройке", View.VISIBLE, panel(a).visibility)
    }

    @Test
    fun manualOpen_followsItsTab_whenSwitchingTabs() {
        val a = openCatalog()
        a.findViewById<View>(R.id.kindCategories).performClick()
        button(a).performClick()
        assertEquals(View.VISIBLE, panel(a).visibility)

        a.findViewById<View>(R.id.kindWallets).performClick()
        assertEquals("у «Кошельков» своё состояние — по настройке", View.GONE, panel(a).visibility)

        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals("«Категории» остались раскрытыми вручную", View.VISIBLE, panel(a).visibility)
        assertFalse("настройка не менялась", AppSettings.isCatalogPanelOpen(context, false))
    }

    // ── фильтр вида категорий ───────────────────────────────────────────────

    @Test
    fun oneKind_isExact() {
        category("Ктест Расход", AccountItem.CategoryKind.EXPENSE)
        category("Ктест Доход", AccountItem.CategoryKind.INCOME)
        category("Ктест Оба", AccountItem.CategoryKind.BOTH)
        val a = openCatalogOnCategories()

        a.findViewById<View>(R.id.catalogKindIncome).performClick()

        assertEquals(listOf("Ктест Доход"), mine(a))
    }

    @Test
    fun twoKinds_areOr_andExpenseExcludesBoth() {
        category("Ктест Расход", AccountItem.CategoryKind.EXPENSE)
        category("Ктест Доход", AccountItem.CategoryKind.INCOME)
        category("Ктест Оба", AccountItem.CategoryKind.BOTH)
        val a = openCatalogOnCategories()

        a.findViewById<View>(R.id.catalogKindExpense).performClick()
        assertEquals("«Оба» не входят в «Расход»", listOf("Ктест Расход"), mine(a))

        a.findViewById<View>(R.id.catalogKindBoth).performClick()
        assertEquals(listOf("Ктест Оба", "Ктест Расход"), mine(a))
    }

    @Test
    fun clearingAllKinds_showsEverythingAgain() {
        category("Ктест Расход", AccountItem.CategoryKind.EXPENSE)
        category("Ктест Доход", AccountItem.CategoryKind.INCOME)
        val a = openCatalogOnCategories()

        a.findViewById<View>(R.id.catalogKindIncome).performClick()
        assertEquals(listOf("Ктест Доход"), mine(a))
        a.findViewById<View>(R.id.catalogKindIncome).performClick()

        assertEquals(listOf("Ктест Доход", "Ктест Расход"), mine(a))
    }

    @Test
    fun systemCategories_hiddenWithAKind_shownWithout() {
        val transfer = WalletDbCRUD.GetOrCreateSystemCategory(
            db, AccountItem.SystemKind.TRANSFER.value, AccountItem.SYSTEM_TRANSFER_NAME, "unknown"
        )
        Singleton.instance.reloadAccounts(context)
        val systemName = Singleton.instance.getAccounts(context).getAccountById(transfer)!!.displayName
        val a = openCatalogOnCategories()
        assertTrue("без выбранных видов системная категория видна", allNames(a).contains(systemName))

        a.findViewById<View>(R.id.catalogKindBoth).performClick()
        assertFalse(allNames(a).contains(systemName))

        a.findViewById<View>(R.id.catalogKindBoth).performClick()
        assertTrue(allNames(a).contains(systemName))
    }

    @Test
    fun kindAndSearch_combine() {
        category("Ктест Такси", AccountItem.CategoryKind.EXPENSE)
        category("Ктест Кафе", AccountItem.CategoryKind.EXPENSE)
        category("Ктест Касса", AccountItem.CategoryKind.INCOME)
        val a = openCatalogOnCategories()

        a.findViewById<View>(R.id.catalogKindExpense).performClick()
        a.findViewById<EditText>(R.id.searchInput).setText("Ктест Ка")

        assertEquals(listOf("Ктест Кафе"), mine(a))
    }

    @Test
    fun kindSelection_survivesTabSwitch_andWalletsAreUnaffected() {
        category("Ктест Доход", AccountItem.CategoryKind.INCOME)
        category("Ктест Расход", AccountItem.CategoryKind.EXPENSE)
        val a = openCatalogOnCategories()
        a.findViewById<View>(R.id.catalogKindIncome).performClick()

        a.findViewById<View>(R.id.kindWallets).performClick()
        assertTrue("на «Кошельках» список кошельков не режется видом", listNames(a).isNotEmpty())
        a.findViewById<View>(R.id.kindCategories).performClick()

        assertEquals(listOf("Ктест Доход"), mine(a))
    }

    // ── «Показывать архивные» и бейдж ───────────────────────────────────────

    @Test
    fun archivedChip_togglesBothLists_andStaysOnTabSwitch() {
        val archived = category("Ктест Архив", AccountItem.CategoryKind.EXPENSE)
        db.execSQL("UPDATE account SET is_archived = 1 WHERE _id = $archived")
        Singleton.instance.reloadAccounts(context)
        val a = openCatalogOnCategories()
        assertTrue("по умолчанию архивных нет", mine(a).isEmpty())

        a.findViewById<View>(R.id.pickerShowArchived).performClick()
        assertEquals(
            listOf(a.getString(R.string.picker_archived_name, "Ктест Архив")), mine(a)
        )

        a.findViewById<View>(R.id.kindWallets).performClick()
        a.findViewById<View>(R.id.kindCategories).performClick()
        assertEquals("чип остался включённым", 1, mine(a).size)

        a.findViewById<View>(R.id.pickerShowArchived).performClick()
        assertTrue(mine(a).isEmpty())
    }

    @Test
    fun badge_countsGroupsOfCurrentTab() {
        val a = openCatalogOnCategories()
        button(a).performClick() // развернуть
        assertEquals(a.getString(R.string.picker_filters_open), button(a).text.toString())

        a.findViewById<View>(R.id.catalogKindExpense).performClick()
        a.findViewById<View>(R.id.catalogKindIncome).performClick()
        assertEquals(
            "группа видов — одно условие",
            a.getString(R.string.picker_filters_open_count, 1), button(a).text.toString()
        )

        a.findViewById<View>(R.id.pickerShowArchived).performClick()
        assertEquals(a.getString(R.string.picker_filters_open_count, 2), button(a).text.toString())

        a.findViewById<View>(R.id.kindWallets).performClick()
        assertEquals(
            "на «Кошельках» виды не считаются, архив общий",
            a.getString(R.string.picker_filters_closed_count, 1), button(a).text.toString()
        )
    }

    // ── строка без подписи вида ─────────────────────────────────────────────

    @Test
    fun categoryRow_hasNoKindCaption() {
        category("Ктест Зарплата", AccountItem.CategoryKind.INCOME)
        val a = openCatalogOnCategories()

        val lv = a.findViewById<ListView>(R.id.listAccounts)
        val row = (0 until lv.adapter.count).map { lv.adapter.getView(it, null, lv) }
            .first { visibleTexts(it).contains("Ктест Зарплата") }

        assertEquals(listOf("Ктест Зарплата"), visibleTexts(row))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun category(name: String, kind: AccountItem.CategoryKind): Int {
        WalletDbCRUD.InsertAccount(db, name, "", iconId, false, kind)
        Singleton.instance.reloadAccounts(context)
        return intQuery("SELECT _id FROM account WHERE name = '$name'")
    }

    private fun openCatalog(): AccountPickerActivity = start(AccountPickerActivity.buildCatalogIntent(context))

    private fun openCatalogOnCategories(): AccountPickerActivity {
        val a = openCatalog()
        a.findViewById<View>(R.id.kindCategories).performClick()
        return a
    }

    private fun start(intent: Intent): AccountPickerActivity {
        controller?.pause()?.stop()?.destroy()
        val c = Robolectric.buildActivity(AccountPickerActivity::class.java, intent).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun panel(a: AccountPickerActivity): View = a.findViewById(R.id.catalogFilterPanel)

    private fun button(a: AccountPickerActivity): TextView = a.findViewById(R.id.btnFilters)

    private fun listNames(a: AccountPickerActivity): List<String> {
        val lv = a.findViewById<ListView>(R.id.listAccounts)
        return (0 until lv.adapter.count).map { i ->
            lv.adapter.getView(i, null, lv).findViewById<TextView>(R.id.pickerName).text.toString()
        }
    }

    private fun allNames(a: AccountPickerActivity): List<String> = listNames(a)

    /** Только свои категории теста, по алфавиту — записи сида в проверках не участвуют. */
    private fun mine(a: AccountPickerActivity): List<String> =
        listNames(a).filter { it.startsWith("Ктест") }.sorted()

    private fun visibleTexts(v: View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x is TextView && x.text.isNotEmpty()) out.add(x.text.toString())
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
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
