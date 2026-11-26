package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * android-wallet#8: перечитывание справочника валют (синк, форма валюты) обновляет
 * объекты {@link Currency} на месте — счета и валюта эквивалента, взявшие ссылку раньше,
 * видят новые флаг, символ и название без перезапуска.
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyReloadInPlaceTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    @Throws(Exception::class)
    fun setUp() {
        resetSingletons()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)

        // Свой лёгкий сид вместо InsertFirstDictionary — см. HardWipeCacheInvalidationTest
        // (реальные PNG-иконки не помещаются в CursorWindow под Robolectric).
        for (table in ALL_TABLES) db.execSQL("DELETE FROM " + table)
        db.execSQL("DELETE FROM sqlite_sequence")

        val iconId = insertIcon("unknown")
        insertCurrency("Euro", "EUR", "€", iconId)
        insertCurrency("Лари", "GEL", "₾", iconId)
    }

    // ── CurrencyList сам по себе ─────────────────────────────────────────────────

    @Test
    fun addCurrency_sameIdAfterClear_updatesExistingObject() {
        val list = CurrencyList()
        val first = list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false)

        list.clear()
        val second = list.addCurrency(7, "GEL", "Грузинский лари", null, "GEL", 2, false, false)

        assertSame(first, second)
        assertEquals("Грузинский лари", first.name)
        assertEquals("GEL", first.symbol)
        assertFalse(first.isShowInEquivalent)
        assertEquals(1, list.size)
        assertSame(first, list.getCurrencyById(7))
    }

    @Test
    fun addCurrency_sameIdWithoutClear_noDuplicate() {
        val list = CurrencyList()
        list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false)
        list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false)

        assertEquals(1, list.size)
    }

    // ── Синглтон: счёт и валюта эквивалента ──────────────────────────────────────

    @Test
    @Throws(Exception::class)
    fun reloadCurrencyList_accountSeesNewFlagAndSymbol() {
        importWallet("GEL")
        val walletCurrency = gelWallet().currency!!
        assertTrue(walletCurrency.isShowInEquivalent)

        // Правка с веба, пришедшая синком: флаг снят, символ сменён.
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT, 0)
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "GEL")
        db.update(
            WalletContract.CurrencyEntry.TABLE_NAME, cv,
            WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", arrayOf("GEL")
        )

        Singleton.instance.reloadCurrencyList(context)

        // Счёт не перечитывали (синк reloadAccounts не зовёт, если счета не менялись).
        assertSame(walletCurrency, gelWallet().currency)
        assertFalse(walletCurrency.isShowInEquivalent)
        assertEquals("GEL", walletCurrency.symbol)
        assertSame(walletCurrency, Singleton.instance.getCurrencyList(context).getCurrencyByISO("GEL"))
    }

    @Test
    fun reloadCurrencyList_currencyEqKeepsObjectAndBumpsRevision() {
        AppSettings.setCurrencyEqIso(context, "GEL")
        val state = PeriodFilterState.get(context)
        val eq = state.getCurrencyEq(context)
        val revision = state.getRevision()

        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "ლ")
        db.update(
            WalletContract.CurrencyEntry.TABLE_NAME, cv,
            WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", arrayOf("GEL")
        )

        Singleton.instance.reloadCurrencyList(context)

        assertSame(eq, state.getCurrencyEq(context))
        assertEquals("ლ", eq.symbol)
        assertTrue("открытые экраны должны перерисоваться", state.getRevision() > revision)
    }

    @Test
    fun reloadCurrencyList_deletedCurrencyEq_fallsBackToEur() {
        AppSettings.setCurrencyEqIso(context, "GEL")
        val state = PeriodFilterState.get(context)
        assertEquals("GEL", state.getCurrencyEq(context).iso)

        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, "2026-11-03T00:00:00Z")
        db.update(
            WalletContract.CurrencyEntry.TABLE_NAME, cv,
            WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", arrayOf("GEL")
        )

        Singleton.instance.reloadCurrencyList(context)

        val eq = state.getCurrencyEq(context)
        assertNotNull(eq)
        assertEquals("EUR", eq.iso)
        assertTrue(Singleton.instance.getCurrencyList(context).contains(eq))
    }

    @Test
    fun emptyDirectory_currencyEqIsPlaceholder_thenRealAfterReload() {
        // Полная очистка без сида + смена сервера: валют нет, пока не пришёл синк.
        db.execSQL("DELETE FROM currency")
        AppSettings.setCurrencyEqIso(context, "GEL")
        val state = PeriodFilterState.get(context)

        val eq = state.getCurrencyEq(context)
        assertNotNull("экраны зовут getId() без проверки — null ронял приложение", eq)
        assertTrue(PeriodFilterState.isPlaceholder(eq))
        assertEquals("GEL", eq.iso)

        // Синк принёс справочник.
        insertCurrency("Лари", "GEL", "₾", insertIcon("flag"))
        Singleton.instance.reloadCurrencyList(context)

        val real = state.getCurrencyEq(context)
        assertFalse(PeriodFilterState.isPlaceholder(real))
        assertSame(Singleton.instance.getCurrencyList(context).getCurrencyByISO("GEL"), real)
    }

    @Test
    fun emptyDirectory_noSavedIso_fallsBackToFirstCurrency() {
        db.execSQL("DELETE FROM currency")
        val state = PeriodFilterState.get(context)
        assertTrue(PeriodFilterState.isPlaceholder(state.getCurrencyEq(context)))

        insertCurrency("Тенге", "KZT", "₸", insertIcon("flag"))
        Singleton.instance.reloadCurrencyList(context)

        assertEquals("KZT", state.getCurrencyEq(context).iso)
    }

    // ── Вспомогательное ──────────────────────────────────────────────────────────

    private fun gelWallet(): AccountOperatingItem {
        for (a in Singleton.instance.getAccounts(context).operationAccounts) {
            if ("GEL" == a.currency!!.iso) return a
        }
        throw AssertionError("кошелёк в GEL не заведён")
    }

    @Throws(Exception::class)
    private fun importWallet(iso: String) {
        val json = "{\"accounts\":[{\"name\":\"Wallet " + iso + "\",\"currency\":{\"iso\":\"" + iso + "\"}}]}"
        val result = WalletImportData.FillOperationDataFromJson(
            context,
            ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
        )
        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty())
        Singleton.instance.reloadAccounts(context)
    }

    private fun insertIcon(name: String): Long {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun insertCurrency(name: String, iso: String, symbol: String, iconId: Long) {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, name)
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso)
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId)
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol)
        db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, cv)
    }

    /** Singleton и PeriodFilterState — статические, переживают тесты класса. */
    @Throws(Exception::class)
    private fun resetSingletons() {
        for (f in arrayOf(
            "dbHelper", "readableDatabase", "writeableDatabase",
            "iconList", "accounts", "currencyList"
        )) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList())

        val state = PeriodFilterState::class.java.getDeclaredField("instance")
        state.isAccessible = true
        state.set(null, null)
    }

    companion object {
        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "operation_template_step", "operation_template"
        )
    }
}
