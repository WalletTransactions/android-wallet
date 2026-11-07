package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Встроенный набор чистой установки (openspec new-user-default-set, spec starter-set):
 * состав без личных данных, язык набора, метка «из набора», пересев после полной очистки.
 */
@RunWith(RobolectricTestRunner.class)
public class StarterSetTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    @Config(qualifiers = "ru")
    public void cleanInstall_ru_generalCategoriesNoWalletsNoRates() {
        List<String> names = accountNames();
        assertTrue(names.contains("Продукты"));
        assertTrue(names.contains("Такси"));
        assertTrue(names.contains("Transfer"));
        assertTrue(names.contains("Transfer commission"));
        assertEquals(0, count("SELECT count(*) FROM account_operating"));
        assertEquals(0, count("SELECT count(*) FROM account WHERE kind = 1"));
        assertEquals(0, count("SELECT count(*) FROM currency_pair_rate"));
        assertTrue(count("SELECT count(*) FROM currency_pair WHERE symbol = 'USDRUB'") == 1);
        assertEquals("Евро", currencyName("EUR"));
    }

    @Test
    public void cleanInstall_spareFlagsWithoutCurrency() {
        assertEquals(1, count("SELECT count(*) FROM icon WHERE name = 'Flag ALL' AND icon_type = 1"));
        assertEquals(1, count("SELECT count(*) FROM icon WHERE name = 'Croatian flag'"));
        assertEquals(0, count("SELECT count(*) FROM currency WHERE iso_code IN ('ALL', 'HRK', 'TZS')"));
    }

    @Test
    @Config(qualifiers = "ru")
    public void cleanInstall_noPersonalRowsOfOldSeed() {
        List<String> names = accountNames();
        for (String personal : new String[]{"Квартира Рисан", "Черногорские Внж", "От Саши",
                "Альфа Саша Руб", "Баба О"}) {
            assertFalse(personal, names.contains(personal));
        }
    }

    @Test
    @Config(qualifiers = "de")
    public void cleanInstall_otherLanguage_englishNames() {
        List<String> names = accountNames();
        assertTrue(names.contains("Groceries"));
        assertTrue(names.contains("Taxi"));
        assertFalse(names.contains("Продукты"));
        assertTrue(names.contains("Transfer"));
        assertEquals("Euro", currencyName("EUR"));
    }

    @Test
    @Config(qualifiers = "ru")
    public void languageChangedAfterSeed_namesStay() {
        helper.close();
        RuntimeEnvironment.setQualifiers("en");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        assertTrue(accountNames().contains("Продукты"));
        assertFalse(accountNames().contains("Groceries"));
    }

    @Test
    @Config(qualifiers = "ru")
    public void systemCategoriesStamped() {
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer' AND system_kind = 1"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer commission' AND system_kind = 2"));
    }

    @Test
    @Config(qualifiers = "ru")
    public void seedRowsMarkedFromSeed() {
        assertEquals(0, count("SELECT count(*) FROM account WHERE from_seed <> 1"));
        assertEquals(0, count("SELECT count(*) FROM currency WHERE from_seed <> 1"));
        assertEquals(0, count("SELECT count(*) FROM currency_pair WHERE from_seed <> 1"));
        assertTrue(count("SELECT count(*) FROM account") > 20);
    }

    /** Полная очистка с пересевом (ResetDataActivity.runHardWipe) — снова набор с меткой. */
    @Test
    @Config(qualifiers = "ru")
    public void hardWipeWithReseed_markedAgain() {
        db.beginTransaction();
        try {
            db.execSQL("PRAGMA defer_foreign_keys = ON");
            for (String t : new String[]{"operation_link", "operation", "recurring_operation", "account_operating",
                    "account", "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon"}) {
                db.delete(t, null, null);
            }
            WalletFillData.InsertFirstDictionary(db, context);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        assertTrue(accountNames().contains("Продукты"));
        assertEquals(0, count("SELECT count(*) FROM account WHERE from_seed <> 1"));
    }

    /** Каждая иконка и флаг набора есть в res/drawable — ни одна не подменилась на unknown. */
    @Test
    public void everySeedDrawableExists() throws Exception {
        JSONObject seed = readSeed();
        List<String> missing = new ArrayList<>();
        JSONArray icons = seed.getJSONArray("category_icons");
        for (int i = 0; i < icons.length(); i++) {
            String res = icons.getJSONObject(i).getString("resource");
            if (drawableId(res) == 0) missing.add(res);
        }
        JSONArray currencies = seed.getJSONArray("currencies");
        for (int i = 0; i < currencies.length(); i++) {
            String res = currencies.getJSONObject(i).getString("icon_resource");
            if (drawableId(res) == 0) missing.add(res);
        }
        JSONArray flags = seed.getJSONArray("flag_icons");
        for (int i = 0; i < flags.length(); i++) {
            String res = flags.getJSONObject(i).getString("resource");
            if (drawableId(res) == 0) missing.add(res);
        }
        assertTrue("нет drawable: " + missing, missing.isEmpty());
        assertFalse(seed.has("rates"));
    }

    @Test
    public void seedAccountName_namesOrFallback() throws Exception {
        JSONObject both = new JSONObject().put("names", new JSONObject().put("ru", "Такси").put("en", "Taxi"));
        assertEquals("Такси", WalletFillData.seedAccountName(both, "ru"));
        assertEquals("Taxi", WalletFillData.seedAccountName(both, "en"));
        assertEquals("Transfer", WalletFillData.seedAccountName(new JSONObject().put("name", "Transfer"), "ru"));
    }

    @Test
    public void currencyDisplayName_knownAndUnknownCodes() {
        assertEquals("Евро", CurrencyNames.displayName("EUR", "x", new Locale("ru")));
        assertEquals("Serbian Dinar", CurrencyNames.displayName("RSD", "x", Locale.ENGLISH));
        assertEquals("Tether", CurrencyNames.displayName("USDT", "Tether", Locale.ENGLISH));
        assertEquals("Fallback", CurrencyNames.displayName(null, "Fallback", Locale.ENGLISH));
    }

    /** spec currency-directory, «Название общей валюты на языке телефона». */
    @Test
    public void sharedCurrencyShownInPhoneLanguage_ownKeepsItsName() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertEquals("Serbian Dinar", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    1, "RSD", "Сербский динар", null, "din.", 2, true, true).getName());
            assertEquals("Доллары нал", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    2, "USD", "Доллары нал", null, "$", 2, true, false).getName());
            assertEquals("Tether", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    3, "USDT", "Tether", null, "₮", 2, true, true).getName());
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ── Метка «из набора» при правке (spec starter-set) ─────────────────────

    @Test
    @Config(qualifiers = "ru")
    public void renameSeedCategory_clearsMark() {
        int id = accountId("Кафе и рестораны");
        int icon = intValue("SELECT icon_id FROM account WHERE _id = " + id);
        WalletDbCRUD.UpdateAccount(db, id, "Кофейни", "Кофейни", icon, false);
        assertEquals(0, intValue("SELECT from_seed FROM account WHERE _id = " + id));
    }

    @Test
    @Config(qualifiers = "ru")
    public void saveSeedCategoryUnchanged_keepsMark() {
        int id = accountId("Такси");
        int icon = intValue("SELECT icon_id FROM account WHERE _id = " + id);
        WalletDbCRUD.UpdateAccount(db, id, "Такси", "другой комментарий", icon, false);
        assertEquals(1, intValue("SELECT from_seed FROM account WHERE _id = " + id));
    }

    @Test
    @Config(qualifiers = "ru")
    public void operationOnSeedCategory_keepsMark() {
        int taxi = accountId("Такси");
        int salary = accountId("Зарплата");
        WalletDbCRUD.InsertOperation(db, "2026-11-07 10:00", salary, taxi, 10.0, "");
        assertEquals(1, intValue("SELECT from_seed FROM account WHERE _id = " + taxi));
    }

    @Test
    @Config(qualifiers = "ru")
    public void editSeedCurrency_clearsMark() {
        int id = intValue("SELECT _id FROM currency WHERE iso_code = 'GBP'");
        int icon = intValue("SELECT icon_id FROM currency WHERE _id = " + id);
        WalletDbCRUD.UpdateCurrency(db, id, "Фунт", "GBP", "£", 2, icon, true);
        assertEquals(0, intValue("SELECT from_seed FROM currency WHERE _id = " + id));
        int eur = intValue("SELECT _id FROM currency WHERE iso_code = 'EUR'");
        int eurIcon = intValue("SELECT icon_id FROM currency WHERE _id = " + eur);
        WalletDbCRUD.UpdateCurrency(db, eur, "Евро", "EUR", "€", 2, eurIcon, false);
        assertEquals(1, intValue("SELECT from_seed FROM currency WHERE _id = " + eur));
    }

    /** Апгрейд v87→v88: колонка с 0 у существующих строк, dirty не трогается (spec «Существующие строки без метки»). */
    @Test
    public void upgradeV88_existingRowsNotFromSeed() {
        SQLiteDatabase old = SQLiteDatabase.create(null);
        old.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY, name TEXT, dirty INTEGER NOT NULL DEFAULT 1)");
        old.execSQL("CREATE TABLE currency (_id INTEGER PRIMARY KEY, iso_code TEXT, dirty INTEGER NOT NULL DEFAULT 1)");
        old.execSQL("CREATE TABLE currency_pair (_id INTEGER PRIMARY KEY, symbol TEXT, dirty INTEGER NOT NULL DEFAULT 1)");
        ContentValues cv = new ContentValues();
        cv.put("name", "Квартира Рисан");
        cv.put("dirty", 0);
        old.insert("account", null, cv);
        WalletDbHelper.addFromSeedV88(old);
        WalletDbHelper.addFromSeedV88(old); // повторный вызов безопасен
        Cursor c = old.rawQuery("SELECT from_seed, dirty FROM account", null);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(0, c.getInt(1));
        } finally {
            c.close();
            old.close();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<String> accountNames() {
        List<String> names = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT name FROM account WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) names.add(c.getString(0));
        } finally {
            c.close();
        }
        return names;
    }

    private String currencyName(String iso) {
        Cursor c = db.rawQuery("SELECT name FROM currency WHERE iso_code = ?", new String[]{iso});
        try {
            assertTrue(iso, c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private int accountId(String name) {
        Cursor c = db.rawQuery("SELECT _id FROM account WHERE name = ?", new String[]{name});
        try {
            assertTrue(name, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int count(String sql) {
        return intValue(sql);
    }

    private int intValue(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int drawableId(String name) {
        return context.getResources().getIdentifier(name, "drawable", context.getPackageName());
    }

    private JSONObject readSeed() throws Exception {
        try (InputStream in = context.getAssets().open("seed.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
        }
    }
}
