package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
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

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * openspec summary-other-section, спека period-summary: раскрытие «Прочего» по кнопке и по
 * настройке. Таб — «Кошельки»: карточка у всех табов общая ({@link BaseTabActivity}), а
 * поднимается он в Robolectric без диалога восстановления. Помеченный актив делает строку
 * «Прочее» видимой независимо от сумм.
 */
@RunWith(RobolectricTestRunner.class)
public class SummaryOtherExpandTest {

    private Context context;
    private SQLiteDatabase db;
    private Object originalPass;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow Robolectric
        int rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
        int iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'");
        originalPass = swapSyncPass(noNetworkPass());

        int flat = (int) WalletDbCRUD.InsertOperatingAccount(db, "Квартира для теста", "", iconId, rubId);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        Singleton.instance.reloadAccounts(context);
    }

    @After
    public void tearDown() throws Exception {
        swapSyncPass(originalPass);
        resetSingleton();
    }

    private ActivityController<AccountActivity> openTab(Bundle saved) {
        ActivityController<AccountActivity> c = Robolectric.buildActivity(AccountActivity.class,
                new Intent(context, AccountActivity.class));
        if (saved == null) c.setup(); else c.setup(saved);
        idle();
        return c;
    }

    private static int detailsVisibility(ActivityController<AccountActivity> c) {
        return c.get().findViewById(R.id.otherDetails).getVisibility();
    }

    @Test
    public void defaultSetting_isCollapsed() {
        assertFalse("по умолчанию настройка выключена", AppSettings.isSummaryOtherExpanded(context));
        ActivityController<AccountActivity> tab = openTab(null);

        assertEquals(View.VISIBLE, tab.get().findViewById(R.id.otherRow).getVisibility());
        assertEquals("видна только сумма", View.GONE, detailsVisibility(tab));
        tab.pause().stop().destroy();
    }

    @Test
    public void settingOn_opensExpanded() {
        AppSettings.setSummaryOtherExpanded(context, true);
        ActivityController<AccountActivity> tab = openTab(null);

        assertEquals(View.VISIBLE, detailsVisibility(tab));
        assertEquals("строка «Капитал» в деталях", View.VISIBLE,
                tab.get().findViewById(R.id.otherCapitalRow).getVisibility());
        tab.pause().stop().destroy();
    }

    @Test
    public void tapExpands_settingStays_reopenTakesSetting() {
        ActivityController<AccountActivity> tab = openTab(null);

        tab.get().findViewById(R.id.otherRow).performClick();
        assertEquals("раскрыли вручную", View.VISIBLE, detailsVisibility(tab));
        assertFalse("настройка осталась выключенной", AppSettings.isSummaryOtherExpanded(context));

        tab.get().findViewById(R.id.otherRow).performClick();
        assertEquals("повторное нажатие сворачивает", View.GONE, detailsVisibility(tab));

        tab.get().findViewById(R.id.otherRow).performClick();
        tab.pause().stop().destroy();

        ActivityController<AccountActivity> reopened = openTab(null);
        assertEquals("экран открыт заново — вид из настройки", View.GONE, detailsVisibility(reopened));
        reopened.pause().stop().destroy();
    }

    @Test
    public void reload_keepsManualState() {
        ActivityController<AccountActivity> tab = openTab(null);
        tab.get().findViewById(R.id.otherRow).performClick();

        tab.get().reloadSharedAndTabData(); // смена периода, фильтра или валюты
        idle();

        assertEquals("смена периода не сворачивает «Прочее»", View.VISIBLE, detailsVisibility(tab));
        tab.pause().stop().destroy();
    }

    @Test
    public void settingChangedWhileOpen_doesNotFlipOpenScreen() {
        ActivityController<AccountActivity> tab = openTab(null);
        tab.get().findViewById(R.id.otherRow).performClick();

        AppSettings.setSummaryOtherExpanded(context, false);
        tab.get().reloadSharedAndTabData();
        idle();

        assertEquals("раскрытое вручную не переворачивается настройкой", View.VISIBLE, detailsVisibility(tab));
        tab.pause().stop().destroy();
    }

    @Test
    public void rotation_keepsManualState() {
        ActivityController<AccountActivity> tab = openTab(null);
        tab.get().findViewById(R.id.otherRow).performClick();
        Bundle state = new Bundle();
        tab.pause().saveInstanceState(state).stop().destroy();

        ActivityController<AccountActivity> recreated = openTab(state);

        assertEquals("после поворота вид сохранён", View.VISIBLE, detailsVisibility(recreated));
        assertFalse(AppSettings.isSummaryOtherExpanded(context));
        recreated.pause().stop().destroy();
    }

    @Test
    public void chevron_pointsDownCollapsedUpExpanded() {
        ActivityController<AccountActivity> tab = openTab(null);
        View chevron = tab.get().findViewById(R.id.otherChevron);
        assertEquals(90f, chevron.getRotation(), 0.01f);

        tab.get().findViewById(R.id.otherRow).performClick();
        assertEquals(-90f, chevron.getRotation(), 0.01f);
        tab.pause().stop().destroy();
    }

    // ── помощники ───────────────────────────────────────────────────────────

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
