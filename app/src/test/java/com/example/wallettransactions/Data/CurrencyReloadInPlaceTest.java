package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * android-wallet#8: перечитывание справочника валют (синк, форма валюты) обновляет
 * объекты {@link Currency} на месте — счета и валюта эквивалента, взявшие ссылку раньше,
 * видят новые флаг, символ и название без перезапуска.
 */
@RunWith(RobolectricTestRunner.class)
public class CurrencyReloadInPlaceTest {

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingletons();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);

        // Свой лёгкий сид вместо InsertFirstDictionary — см. HardWipeCacheInvalidationTest
        // (реальные PNG-иконки не помещаются в CursorWindow под Robolectric).
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");

        long iconId = insertIcon("unknown");
        insertCurrency("Euro", "EUR", "€", iconId);
        insertCurrency("Лари", "GEL", "₾", iconId);
    }

    // ── CurrencyList сам по себе ─────────────────────────────────────────────────

    @Test
    public void addCurrency_sameIdAfterClear_updatesExistingObject() {
        CurrencyList list = new CurrencyList();
        Currency first = list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false);

        list.clear();
        Currency second = list.addCurrency(7, "GEL", "Грузинский лари", null, "GEL", 2, false, false);

        assertSame(first, second);
        assertEquals("Грузинский лари", first.getName());
        assertEquals("GEL", first.getSymbol());
        assertFalse(first.isShowInEquivalent());
        assertEquals(1, list.size());
        assertSame(first, list.getCurrencyById(7));
    }

    @Test
    public void addCurrency_sameIdWithoutClear_noDuplicate() {
        CurrencyList list = new CurrencyList();
        list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false);
        list.addCurrency(7, "GEL", "Лари", null, "₾", 2, true, false);

        assertEquals(1, list.size());
    }

    // ── Синглтон: счёт и валюта эквивалента ──────────────────────────────────────

    @Test
    public void reloadCurrencyList_accountSeesNewFlagAndSymbol() throws Exception {
        importWallet("GEL");
        Currency walletCurrency = gelWallet().getCurrency();
        assertTrue(walletCurrency.isShowInEquivalent());

        // Правка с веба, пришедшая синком: флаг снят, символ сменён.
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT, 0);
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "GEL");
        db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", new String[]{"GEL"});

        Singleton.instance.reloadCurrencyList(context);

        // Счёт не перечитывали (синк reloadAccounts не зовёт, если счета не менялись).
        assertSame(walletCurrency, gelWallet().getCurrency());
        assertFalse(walletCurrency.isShowInEquivalent());
        assertEquals("GEL", walletCurrency.getSymbol());
        assertSame(walletCurrency, Singleton.instance.getCurrencyList(context).getCurrencyByISO("GEL"));
    }

    @Test
    public void reloadCurrencyList_currencyEqKeepsObjectAndBumpsRevision() {
        AppSettings.setCurrencyEqIso(context, "GEL");
        PeriodFilterState state = PeriodFilterState.get(context);
        Currency eq = state.getCurrencyEq(context);
        int revision = state.getRevision();

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "ლ");
        db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", new String[]{"GEL"});

        Singleton.instance.reloadCurrencyList(context);

        assertSame(eq, state.getCurrencyEq(context));
        assertEquals("ლ", eq.getSymbol());
        assertTrue("открытые экраны должны перерисоваться", state.getRevision() > revision);
    }

    @Test
    public void reloadCurrencyList_deletedCurrencyEq_fallsBackToEur() {
        AppSettings.setCurrencyEqIso(context, "GEL");
        PeriodFilterState state = PeriodFilterState.get(context);
        assertEquals("GEL", state.getCurrencyEq(context).getIso());

        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, "2026-11-03T00:00:00Z");
        db.update(WalletContract.CurrencyEntry.TABLE_NAME, cv,
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " = ?", new String[]{"GEL"});

        Singleton.instance.reloadCurrencyList(context);

        Currency eq = state.getCurrencyEq(context);
        assertNotNull(eq);
        assertEquals("EUR", eq.getIso());
        assertTrue(Singleton.instance.getCurrencyList(context).contains(eq));
    }

    // ── Вспомогательное ──────────────────────────────────────────────────────────

    private AccountOperatingItem gelWallet() {
        for (AccountOperatingItem a : Singleton.instance.getAccounts(context).getOperationAccounts()) {
            if ("GEL".equals(a.getCurrency().getIso())) return a;
        }
        throw new AssertionError("кошелёк в GEL не заведён");
    }

    private void importWallet(String iso) throws Exception {
        String json = "{\"accounts\":[{\"name\":\"Wallet " + iso + "\",\"currency\":{\"iso\":\"" + iso + "\"}}]}";
        WalletImportData.ImportResult result = WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty());
        Singleton.instance.reloadAccounts(context);
    }

    private long insertIcon(String name) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private void insertCurrency(String name, String iso, String symbol, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, name);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso);
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId);
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, symbol);
        db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, cv);
    }

    /** Singleton и PeriodFilterState — статические, переживают тесты класса. */
    private void resetSingletons() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList());

        Field state = PeriodFilterState.class.getDeclaredField("instance");
        state.setAccessible(true);
        state.set(null, null);
    }
}
