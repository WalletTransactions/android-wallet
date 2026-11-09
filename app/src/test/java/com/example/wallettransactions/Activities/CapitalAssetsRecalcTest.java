package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.sync.SyncLog;
import com.example.wallettransactions.sync.SyncRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * openspec capital-assets, design п. 10 (android-wallet#42): табы в стеке пересчитывают
 * карточку периода после изменения признака актива — без ручной смены даты. Таб здесь —
 * «Кошельки»: у него та же карточка ({@link BaseTabActivity#refreshSummaryCard}), что у
 * «Операций», а поднимается он в Robolectric без диалога восстановления.
 */
@RunWith(RobolectricTestRunner.class)
public class CapitalAssetsRecalcTest {

    private Context context;
    private SQLiteDatabase db;
    private int rubId;
    private int iconId;
    private Object originalPass;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow Robolectric
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'");
        originalPass = swapSyncPass(noNetworkPass());
    }

    @After
    public void tearDown() throws Exception {
        swapSyncPass(originalPass);
        resetSingleton();
    }

    @Test
    public void openTab_markWalletAsAsset_capitalRowRecalculatedOnReturn() {
        int card = wallet("Тинькофф для теста");
        int salary = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1");
        WalletDbCRUD.InsertOperation(db, "2026-01-10 10:00", salary, card, 1000, "");
        Singleton.instance.reloadAccounts(context);
        // Эквивалент — в валюте кошелька: курса RUB→EUR в сиде нет, капитал был бы «~0».
        PeriodFilterState.get(context).setCurrencyEq(context,
                Singleton.instance.getCurrencyList(context).getCurrencyById(rubId));

        ActivityController<AccountActivity> tab = Robolectric.buildActivity(AccountActivity.class,
                new Intent(context, AccountActivity.class)).setup();
        idle();
        assertEquals("активов нет — строки «Капитал» нет", View.GONE,
                tab.get().findViewById(R.id.capitalRow).getVisibility());
        assertEquals("активов нет — строки «Всего с капиталом» нет", View.GONE,
                tab.get().findViewById(R.id.netWorthRow).getVisibility());
        tab.pause().stop(); // ушли в карточку кошелька — таб остался в стеке

        ActivityController<AccountItemActivity> item = Robolectric.buildActivity(AccountItemActivity.class,
                new Intent(context, AccountItemActivity.class).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, card)).setup();
        idle();
        ((CheckBox) item.get().findViewById(R.id.checkIsAsset)).setChecked(true);
        item.get().btnSaveClick(null);
        item.pause().stop().destroy();

        tab.restart().start().resume();
        idle();
        assertEquals("после пометки — строка «Капитал» без смены даты", View.VISIBLE,
                tab.get().findViewById(R.id.capitalRow).getVisibility());
        assertEquals("после пометки — строка «Всего с капиталом»", View.VISIBLE,
                tab.get().findViewById(R.id.netWorthRow).getVisibility());
        // android-wallet#43: «Капитал» и «Всего с капиталом» — разные строки карточки,
        // у каждой своя подпись и сумма.
        assertTrue("суммы в разных строках",
                tab.get().findViewById(R.id.capitalSum).getParent() == tab.get().findViewById(R.id.capitalRow)
                        && tab.get().findViewById(R.id.netWorthSum).getParent() == tab.get().findViewById(R.id.netWorthRow));
        String capital = ((TextView) tab.get().findViewById(R.id.capitalSum)).getText().toString();
        assertTrue(capital, capital.replaceAll("\\D", "").startsWith("1000"));
        String netWorth = ((TextView) tab.get().findViewById(R.id.netWorthSum)).getText().toString();
        assertTrue(netWorth, netWorth.replaceAll("\\D", "").startsWith("1000"));
        tab.pause().stop();

        // Снятие признака — строка уходит так же сразу.
        item = Robolectric.buildActivity(AccountItemActivity.class,
                new Intent(context, AccountItemActivity.class).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, card)).setup();
        idle();
        ((CheckBox) item.get().findViewById(R.id.checkIsAsset)).setChecked(false);
        item.get().btnSaveClick(null);
        item.pause().stop().destroy();

        tab.restart().start().resume();
        idle();
        assertEquals(View.GONE, tab.get().findViewById(R.id.capitalRow).getVisibility());
        assertEquals(View.GONE, tab.get().findViewById(R.id.netWorthRow).getVisibility());
        tab.pause().stop().destroy();
    }

    @Test
    public void addWallet_asset_bumpsRevision_emptyNameDoesNot() throws Exception {
        ActivityController<AddWalletActivity> c = Robolectric.buildActivity(AddWalletActivity.class,
                new Intent(context, AddWalletActivity.class)).setup();
        idle();
        AddWalletActivity a = c.get();
        int before = PeriodFilterState.get(context).getRevision();

        a.btnSaveClick(null); // пустое имя — отказ, записи нет
        assertEquals(before, PeriodFilterState.get(context).getRevision());

        ((EditText) a.findViewById(R.id.editWalletName)).setText("Квартира для теста");
        Field currency = AddWalletActivity.class.getDeclaredField("selectedCurrency");
        currency.setAccessible(true);
        currency.set(a, Singleton.instance.getCurrencyList(context).getCurrencyById(rubId));
        ((CheckBox) a.findViewById(R.id.checkIsAsset)).setChecked(true);
        a.btnSaveClick(null);

        assertEquals(1, intQuery("SELECT count(*) FROM account_asset a JOIN account c ON c._id = a._id"
                + " WHERE c.name = 'Квартира для теста' AND a.deleted_at IS NULL"));
        assertTrue("табы в стеке перечитаются", PeriodFilterState.get(context).getRevision() > before);
        c.pause().stop().destroy();
    }

    @Test
    public void revaluation_bumpsRevision() {
        int flat = wallet("Квартира для теста");
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        Singleton.instance.reloadAccounts(context);
        ActivityController<AccountItemActivity> c = Robolectric.buildActivity(AccountItemActivity.class,
                new Intent(context, AccountItemActivity.class).putExtra(AccountItemActivity.ARG_ACCOUNT_ID, flat)).setup();
        idle();
        int before = PeriodFilterState.get(context).getRevision();

        new RevaluationDialog(c.get(),
                (AccountOperatingItem) Singleton.instance.getAccounts(context).getAccountById(flat), null).show();
        Dialog dialog = ShadowDialog.getLatestDialog();
        ((EditText) dialog.findViewById(R.id.inputValue)).setText("5000000");
        dialog.findViewById(R.id.btnSave).performClick();
        idle();

        assertTrue("переоценка пересчитывает табы", PeriodFilterState.get(context).getRevision() > before);
        c.pause().stop().destroy();
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private int wallet(String name) {
        return (int) WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId, rubId);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int intQuery(String sql) {
        Cursor cur = db.rawQuery(sql, null);
        try {
            assertTrue(sql, cur.moveToFirst());
            return cur.getInt(0);
        } finally {
            cur.close();
        }
    }

    /** Синк в фоне после записи — без сети и без чужого потока в базе теста. */
    private static Object noNetworkPass() throws Exception {
        Class<?> passType = Class.forName("com.example.wallettransactions.sync.SyncRunner$Pass");
        return Proxy.newProxyInstance(passType.getClassLoader(), new Class<?>[]{passType},
                (proxy, method, args) -> method.getName().equals("run")
                        ? new SyncRunner.Outcome(SyncRunner.Status.NO_SESSION, new SyncLog(), null)
                        : null);
    }

    private static Object swapSyncPass(Object pass) throws Exception {
        Field f = SyncRunner.class.getDeclaredField("pass");
        f.setAccessible(true);
        Object old = f.get(null);
        f.set(null, pass);
        return old;
    }

    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new CurrencyPairList());
    }
}
