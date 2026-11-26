package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Встроенный набор чистой установки (openspec new-user-default-set, spec starter-set):
 * состав без личных данных, язык набора, метка «из набора», пересев после полной очистки.
 */
@RunWith(RobolectricTestRunner::class)
class StarterSetTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_ru_generalCategoriesTwoSeedWalletsNoRates() {
        val names = accountNames()
        assertTrue(names.contains("Продукты"))
        assertTrue(names.contains("Такси"))
        assertTrue(names.contains("Transfer"))
        assertTrue(names.contains("Transfer commission"))
        // Кошельков ровно два — «Наличные» и «Кредитная карта» (design D2), других нет.
        assertEquals(2, count("SELECT count(*) FROM account_operating"))
        assertEquals(2, count("SELECT count(*) FROM account WHERE kind = 1"))
        assertEquals(0, count("SELECT count(*) FROM currency_pair_rate"))
        assertTrue(count("SELECT count(*) FROM currency_pair WHERE symbol = 'USDRUB'") == 1)
        assertEquals("Евро", currencyName("EUR"))
    }

    // ── Кошельки набора («Наличные / Cash», «Кредитная карта / Credit card») ─────

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_ru_seedWalletsInRublesWithChosenIcons() {
        assertEquals("RUB", walletCurrency("Наличные"))
        assertEquals("RUB", walletCurrency("Кредитная карта"))
        assertEquals("USD banknote", accountIconName("Наличные"))
        assertEquals("Visa card", accountIconName("Кредитная карта"))
        assertEquals("иконки набора на месте, а не unknown", 0,
                count("SELECT count(*) FROM account a JOIN icon i ON i._id = a.icon_id"
                        + " WHERE a.kind = 1 AND i.name = 'unknown'"))
    }

    @Test
    @Config(qualifiers = "de")
    fun cleanInstall_otherLanguage_seedWalletsInDollars() {
        val names = accountNames()
        assertTrue(names.contains("Cash"))
        assertTrue(names.contains("Credit card"))
        assertFalse(names.contains("Наличные"))
        assertEquals("USD", walletCurrency("Cash"))
        assertEquals("USD", walletCurrency("Credit card"))
        assertEquals("USD banknote", accountIconName("Cash"))
        assertEquals("Visa card", accountIconName("Credit card"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_seedWalletsAreEmptyPlainAndMarkedFromSeed() {
        assertEquals("баланс 0 — операций нет", 0, count("SELECT count(*) FROM operation"))
        assertEquals("не активы", 0, count("SELECT count(*) FROM account_asset"))
        assertEquals("не займы", 0, count("SELECT count(*) FROM account_debt"))
        assertEquals(2, count("SELECT count(*) FROM account WHERE kind = 1 AND from_seed = 1"))
        assertEquals("кошельки набора — не системные", 0,
                count("SELECT count(*) FROM account WHERE kind = 1 AND system_kind <> 0"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun renameSeedWallet_clearsMark() {
        val id = accountId("Наличные")
        val icon = intValue("SELECT icon_id FROM account WHERE _id = $id")
        WalletDbCRUD.UpdateAccount(db, id, "Копилка", "Копилка", icon, false)
        assertEquals(0, intValue("SELECT from_seed FROM account WHERE _id = $id"))
    }

    @Test
    @Config(qualifiers = "ru")
    @Throws(Exception::class)
    fun seedCurrencyIso_stringOrByLanguage() {
        val plain = JSONObject("{\"currency_iso\": \"EUR\"}")
        val byLanguage = JSONObject("{\"currency_iso\": {\"ru\": \"RUB\", \"en\": \"USD\"}}")
        val none = JSONObject("{}")
        assertEquals("EUR", WalletFillData.seedCurrencyIso(plain, "ru"))
        assertEquals("EUR", WalletFillData.seedCurrencyIso(plain, "en"))
        assertEquals("RUB", WalletFillData.seedCurrencyIso(byLanguage, "ru"))
        assertEquals("USD", WalletFillData.seedCurrencyIso(byLanguage, "en"))
        assertEquals("нет перевода — по en", "USD", WalletFillData.seedCurrencyIso(byLanguage, "fr"))
        assertEquals("", WalletFillData.seedCurrencyIso(none, "ru"))
    }

    @Test
    fun cleanInstall_spareFlagsWithoutCurrency() {
        assertEquals(1, count("SELECT count(*) FROM icon WHERE name = 'Flag ALL' AND icon_type = 1"))
        assertEquals(1, count("SELECT count(*) FROM icon WHERE name = 'Croatian flag'"))
        assertEquals(0, count("SELECT count(*) FROM currency WHERE iso_code IN ('ALL', 'HRK', 'TZS')"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_noPersonalRowsOfOldSeed() {
        val names = accountNames()
        for (personal in arrayOf("Квартира Рисан", "Черногорские Внж", "От Саши",
                "Альфа Саша Руб", "Баба О")) {
            assertFalse(personal, names.contains(personal))
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun cleanInstall_otherLanguage_englishNames() {
        val names = accountNames()
        assertTrue(names.contains("Groceries"))
        assertTrue(names.contains("Taxi"))
        assertFalse(names.contains("Продукты"))
        assertTrue(names.contains("Transfer"))
        assertEquals("Euro", currencyName("EUR"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun languageChangedAfterSeed_namesStay() {
        helper.close()
        RuntimeEnvironment.setQualifiers("en")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        assertTrue(accountNames().contains("Продукты"))
        assertFalse(accountNames().contains("Groceries"))
        assertTrue("кошельки набора тоже не переименовываются", accountNames().contains("Наличные"))
        assertFalse(accountNames().contains("Cash"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun systemCategoriesStamped() {
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer' AND system_kind = 1"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer commission' AND system_kind = 2"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun seedRowsMarkedFromSeed() {
        assertEquals(0, count("SELECT count(*) FROM account WHERE from_seed <> 1"))
        assertEquals(0, count("SELECT count(*) FROM currency WHERE from_seed <> 1"))
        assertEquals(0, count("SELECT count(*) FROM currency_pair WHERE from_seed <> 1"))
        assertTrue(count("SELECT count(*) FROM account") > 20)
    }

    /** Полная очистка с пересевом (ResetDataActivity.runHardWipe) — снова набор с меткой. */
    @Test
    @Config(qualifiers = "ru")
    fun hardWipeWithReseed_markedAgain() {
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            for (t in arrayOf("operation_link", "operation", "recurring_operation", "account_operating",
                    "account", "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon")) {
                db.delete(t, null, null)
            }
            WalletFillData.InsertFirstDictionary(db, context)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        assertTrue(accountNames().contains("Продукты"))
        assertEquals(0, count("SELECT count(*) FROM account WHERE from_seed <> 1"))
        assertEquals("кошельки набора заведены заново", "RUB", walletCurrency("Наличные"))
        assertEquals("RUB", walletCurrency("Кредитная карта"))
    }

    /** Каждая иконка и флаг набора есть в res/drawable — ни одна не подменилась на unknown. */
    @Test
    @Throws(Exception::class)
    fun everySeedDrawableExists() {
        val seed = readSeed()
        val missing = ArrayList<String>()
        val icons = seed.getJSONArray("category_icons")
        for (i in 0 until icons.length()) {
            val res = icons.getJSONObject(i).getString("resource")
            if (drawableId(res) == 0) missing.add(res)
        }
        val currencies = seed.getJSONArray("currencies")
        for (i in 0 until currencies.length()) {
            val res = currencies.getJSONObject(i).getString("icon_resource")
            if (drawableId(res) == 0) missing.add(res)
        }
        val flags = seed.getJSONArray("flag_icons")
        for (i in 0 until flags.length()) {
            val res = flags.getJSONObject(i).getString("resource")
            if (drawableId(res) == 0) missing.add(res)
        }
        assertTrue("нет drawable: $missing", missing.isEmpty())
        assertFalse(seed.has("rates"))
    }

    @Test
    @Throws(Exception::class)
    fun seedAccountName_namesOrFallback() {
        val both = JSONObject().put("names", JSONObject().put("ru", "Такси").put("en", "Taxi"))
        assertEquals("Такси", WalletFillData.seedAccountName(both, "ru"))
        assertEquals("Taxi", WalletFillData.seedAccountName(both, "en"))
        assertEquals("Transfer", WalletFillData.seedAccountName(JSONObject().put("name", "Transfer"), "ru"))
    }

    @Test
    fun currencyDisplayName_knownAndUnknownCodes() {
        assertEquals("Евро", CurrencyNames.displayName("EUR", "x", Locale("ru")))
        assertEquals("Serbian Dinar", CurrencyNames.displayName("RSD", "x", Locale.ENGLISH))
        assertEquals("Tether", CurrencyNames.displayName("USDT", "Tether", Locale.ENGLISH))
        assertEquals("Fallback", CurrencyNames.displayName(null, "Fallback", Locale.ENGLISH))
    }

    /** spec currency-directory, «Название общей валюты на языке телефона». */
    @Test
    fun sharedCurrencyShownInPhoneLanguage_ownKeepsItsName() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Serbian Dinar", com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    1, "RSD", "Сербский динар", null, "din.", 2, true, true).name)
            assertEquals("Доллары нал", com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    2, "USD", "Доллары нал", null, "$", 2, true, false).name)
            assertEquals("Tether", com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    3, "USDT", "Tether", null, "₮", 2, true, true).name)
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ── Метка «из набора» при правке (spec starter-set) ─────────────────────

    @Test
    @Config(qualifiers = "ru")
    fun renameSeedCategory_clearsMark() {
        val id = accountId("Кафе и рестораны")
        val icon = intValue("SELECT icon_id FROM account WHERE _id = $id")
        WalletDbCRUD.UpdateAccount(db, id, "Кофейни", "Кофейни", icon, false)
        assertEquals(0, intValue("SELECT from_seed FROM account WHERE _id = $id"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun saveSeedCategoryUnchanged_keepsMark() {
        val id = accountId("Такси")
        val icon = intValue("SELECT icon_id FROM account WHERE _id = $id")
        WalletDbCRUD.UpdateAccount(db, id, "Такси", "другой комментарий", icon, false)
        assertEquals(1, intValue("SELECT from_seed FROM account WHERE _id = $id"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun operationOnSeedCategory_keepsMark() {
        val taxi = accountId("Такси")
        val salary = accountId("Зарплата")
        WalletDbCRUD.InsertOperation(db, "2026-11-07 10:00", salary, taxi, 10.0, "")
        assertEquals(1, intValue("SELECT from_seed FROM account WHERE _id = $taxi"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun editSeedCurrency_clearsMark() {
        val id = intValue("SELECT _id FROM currency WHERE iso_code = 'GBP'")
        val icon = intValue("SELECT icon_id FROM currency WHERE _id = $id")
        WalletDbCRUD.UpdateCurrency(db, id, "Фунт", "GBP", "£", 2, icon, true)
        assertEquals(0, intValue("SELECT from_seed FROM currency WHERE _id = $id"))
        val eur = intValue("SELECT _id FROM currency WHERE iso_code = 'EUR'")
        val eurIcon = intValue("SELECT icon_id FROM currency WHERE _id = $eur")
        WalletDbCRUD.UpdateCurrency(db, eur, "Евро", "EUR", "€", 2, eurIcon, false)
        assertEquals(1, intValue("SELECT from_seed FROM currency WHERE _id = $eur"))
    }

    /** Апгрейд v87→v88: колонка с 0 у существующих строк, dirty не трогается (spec «Существующие строки без метки»). */
    @Test
    fun upgradeV88_existingRowsNotFromSeed() {
        val old = SQLiteDatabase.create(null)
        old.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY, name TEXT, dirty INTEGER NOT NULL DEFAULT 1)")
        old.execSQL("CREATE TABLE currency (_id INTEGER PRIMARY KEY, iso_code TEXT, dirty INTEGER NOT NULL DEFAULT 1)")
        old.execSQL("CREATE TABLE currency_pair (_id INTEGER PRIMARY KEY, symbol TEXT, dirty INTEGER NOT NULL DEFAULT 1)")
        val cv = ContentValues()
        cv.put("name", "Квартира Рисан")
        cv.put("dirty", 0)
        old.insert("account", null, cv)
        WalletDbHelper.addFromSeedV88(old)
        WalletDbHelper.addFromSeedV88(old) // повторный вызов безопасен
        val c = old.rawQuery("SELECT from_seed, dirty FROM account", null)
        try {
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
        } finally {
            c.close()
            old.close()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun accountNames(): List<String> {
        val names = ArrayList<String>()
        val c = db.rawQuery("SELECT name FROM account WHERE deleted_at IS NULL", null)
        try {
            while (c.moveToNext()) names.add(c.getString(0))
        } finally {
            c.close()
        }
        return names
    }

    private fun currencyName(iso: String): String {
        val c = db.rawQuery("SELECT name FROM currency WHERE iso_code = ?", arrayOf(iso))
        try {
            assertTrue(iso, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    /** ISO-код валюты кошелька набора по имени кошелька. */
    private fun walletCurrency(walletName: String): String {
        val c = db.rawQuery("SELECT c.iso_code FROM account a"
                + " JOIN account_operating o ON o._id = a._id"
                + " JOIN currency c ON c._id = o.currency_id WHERE a.name = ?", arrayOf(walletName))
        try {
            assertTrue(walletName, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    /** Имя иконки счёта в таблице icon (запасная — «unknown»). */
    private fun accountIconName(accountName: String): String {
        val c = db.rawQuery("SELECT i.name FROM account a JOIN icon i ON i._id = a.icon_id"
                + " WHERE a.name = ?", arrayOf(accountName))
        try {
            assertTrue(accountName, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun accountId(name: String): Int {
        val c = db.rawQuery("SELECT _id FROM account WHERE name = ?", arrayOf(name))
        try {
            assertTrue(name, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun count(sql: String): Int {
        return intValue(sql)
    }

    private fun intValue(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun drawableId(name: String): Int {
        return context.resources.getIdentifier(name, "drawable", context.packageName)
    }

    @Throws(Exception::class)
    private fun readSeed(): JSONObject {
        context.assets.open("seed.json").use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (input.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
            return JSONObject(bos.toString(StandardCharsets.UTF_8.name()))
        }
    }
}
