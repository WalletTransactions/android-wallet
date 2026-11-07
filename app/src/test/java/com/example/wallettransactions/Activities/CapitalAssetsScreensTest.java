package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * openspec capital-assets, экраны (вместо проверки на эмуляторе — его на машине нет):
 * «Новый кошелёк», карточка кошелька/актива/категории, диалог переоценки, секции таба
 * «Кошельки» и выбор счёта при вводе операции. База — настоящая, с первичным справочником.
 */
@RunWith(RobolectricTestRunner.class)
public class CapitalAssetsScreensTest {

    private Context context;
    private SQLiteDatabase db;
    private ActivityController<?> controller;
    private int rubId;
    private int iconId;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow Robolectric
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'");
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        resetSingleton();
    }

    // ── 5.3 «Новый кошелёк» и карточка ──────────────────────────────────────

    @Test
    public void addWallet_assetSwitch_showsTypeOnly_andSavesAsset() {
        AddWalletActivity activity = start(AddWalletActivity.class, new Intent(context, AddWalletActivity.class));
        CheckBox isAsset = activity.findViewById(R.id.checkIsAsset);
        assertEquals(View.GONE, activity.findViewById(R.id.assetDetails).getVisibility());
        isAsset.setChecked(true);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.assetDetails).getVisibility());
        assertEquals("«Учитывать…» на создании скрыт", View.GONE,
                activity.findViewById(R.id.checkInNetWorth).getVisibility());
    }

    @Test
    public void assetCard_hasSwitchOn_andRevaluateButton_plainWalletHasNoButton() {
        int flat = wallet("Квартира Ленина");
        int card = wallet("Карта для теста");
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        Singleton.instance.reloadAccounts(context);

        AccountItemActivity a = openCard(flat);
        assertTrue(((CheckBox) a.findViewById(R.id.checkIsAsset)).isChecked());
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnRevaluate).getVisibility());
        controller.pause().stop().destroy();

        AccountItemActivity b = openCard(card);
        assertFalse(((CheckBox) b.findViewById(R.id.checkIsAsset)).isChecked());
        assertEquals(View.GONE, b.findViewById(R.id.btnRevaluate).getVisibility());
    }

    @Test
    public void markExistingWallet_andSave_writesAsset_operationsUntouched() {
        int car = wallet("Машина для теста");
        Singleton.instance.reloadAccounts(context);
        AccountItemActivity a = openCard(car);
        ((CheckBox) a.findViewById(R.id.checkIsAsset)).setChecked(true);
        a.findViewById(R.id.assetTypeVehicle).performClick();
        assertTrue(a.findViewById(R.id.btnSave).isEnabled());
        a.btnSaveClick(null);

        assertEquals(2, intQuery("SELECT asset_type FROM account_asset WHERE _id = " + car + " AND deleted_at IS NULL"));
        assertTrue(((AccountOperatingItem) Singleton.instance.getAccounts(context).getAccountById(car)).isAsset());
    }

    @Test
    public void categoryCard_noAssetFields_moveActionOnlyWhenAssetsExist() {
        int category = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1");
        AccountItemActivity a = openCard(category);
        assertEquals("у категории нет «Капитального актива»", View.GONE,
                a.findViewById(R.id.operatingGroup).getVisibility());
        assertEquals("активов нет — нет и переноса", View.GONE, a.findViewById(R.id.btnMoveToAsset).getVisibility());
        controller.pause().stop().destroy();

        WalletDbCRUD.SetAccountAsset(db, wallet("Квартира Ленина"), 1, true);
        Singleton.instance.reloadAccounts(context);
        AccountItemActivity b = openCard(category);
        assertEquals(View.VISIBLE, b.findViewById(R.id.btnMoveToAsset).getVisibility());
    }

    @Test
    public void revaluationCategoryCard_systemHint_nameLocked_noDelete() {
        int reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db);
        Singleton.instance.reloadAccounts(context);
        AccountItemActivity a = openCard(reval);
        assertFalse(a.findViewById(R.id.editAccName).isEnabled());
        assertEquals(View.GONE, a.findViewById(R.id.btnDelete).getVisibility());
        assertTrue(((EditText) a.findViewById(R.id.editComment)).getHint().toString()
                .contains("через неё проводится переоценка активов"));
    }

    // ── 5.5 диалог переоценки ───────────────────────────────────────────────

    @Test
    public void revaluationDialog_showsCurrentAndDelta() {
        int flat = wallet("Квартира Ленина");
        int card = wallet("Карта для теста");
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        WalletDbCRUD.InsertOperation(db, "2026-01-10 10:00", card, flat, 10_000_000, "");
        Singleton.instance.reloadAccounts(context);
        AccountItemActivity a = openCard(flat);

        RevaluationDialog dialog = new RevaluationDialog(a,
                (AccountOperatingItem) Singleton.instance.getAccounts(context).getAccountById(flat), null);
        String line = dialog.deltaLine("12000000");
        assertTrue(line, line.startsWith("Сейчас: 10"));
        assertTrue(line, line.contains("Разница: +2"));
        assertTrue(dialog.deltaLine("9500000").contains("Разница: −500"));
    }

    // ── 5.2 секции таба «Кошельки» ──────────────────────────────────────────

    @Test
    public void walletsTab_sectionsOnlyWithAssets() {
        int card = wallet("Карта для теста");
        int flat = wallet("Квартира Ленина");
        int salary = intQuery("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1");
        WalletDbCRUD.InsertOperation(db, "2020-01-10 10:00", salary, card, 1000, "");
        WalletDbCRUD.InsertOperation(db, "2020-01-11 10:00", card, flat, 400, "");
        Singleton.instance.reloadAccounts(context);
        assertFalse(sectionTitles(start(AccountActivity.class, new Intent(context, AccountActivity.class)))
                .contains("Капитал"));
        controller.pause().stop().destroy();

        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        Singleton.instance.reloadAccounts(context);
        List<String> titles = sectionTitles(start(AccountActivity.class, new Intent(context, AccountActivity.class)));
        assertTrue(titles.toString(), titles.contains("Ликвидные"));
        assertTrue(titles.toString(), titles.contains("Капитал"));
        assertTrue(titles.indexOf("Ликвидные") < titles.indexOf("Капитал"));
    }

    // ── 5.4 выбор счёта ─────────────────────────────────────────────────────

    @Test
    public void picker_inputMode_assetsAfterLiquid_revaluationHidden() {
        int flat = wallet("Аааа квартира");   // по алфавиту — первая
        wallet("Яяяя наличные");
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        int reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db);
        Singleton.instance.reloadAccounts(context);

        AccountPickerActivity wallets = start(AccountPickerActivity.class, AccountPickerActivity.buildIntent(
                context, "Кошелёк", AccountPickerActivity.cSelectedTypeOperationAccounts, 0));
        List<String> names = listNames(wallets);
        int assetPos = indexOfPrefix(names, "Аааа квартира");
        int cashPos = indexOfPrefix(names, "Яяяя наличные");
        assertTrue(names.toString(), assetPos > cashPos);
        assertTrue("подпись типа актива", names.get(assetPos).contains("недвижимость"));
        controller.pause().stop().destroy();

        AccountPickerActivity categories = start(AccountPickerActivity.class, AccountPickerActivity.buildIntent(
                context, "Категория", AccountPickerActivity.cSelectedTypeChargeAccounts, 0));
        String revalName = Singleton.instance.getAccounts(context).getAccountById(reval).getName();
        assertEquals(-1, indexOfPrefix(listNames(categories), revalName));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private int wallet(String name) {
        return (int) WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId, rubId);
    }

    private AccountItemActivity openCard(int accountId) {
        return start(AccountItemActivity.class, new Intent(context, AccountItemActivity.class)
                .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId));
    }

    @SuppressWarnings("unchecked")
    private <T extends android.app.Activity> T start(Class<T> cls, Intent intent) {
        ActivityController<T> c = Robolectric.buildActivity(cls, intent).setup();
        controller = c;
        shadowOf(Looper.getMainLooper()).idle();
        return c.get();
    }

    private static List<String> sectionTitles(AccountActivity activity) {
        ListView list = activity.findViewById(R.id.listWallets);
        ListAdapter adapter = list.getAdapter();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) {
            View v = adapter.getView(i, null, list);
            TextView t = v.findViewById(R.id.sectionTitle);
            if (t != null) titles.add(t.getText().toString());
        }
        return titles;
    }

    private static List<String> listNames(AccountPickerActivity activity) {
        ListView list = activity.findViewById(R.id.listAccounts);
        ListAdapter adapter = list.getAdapter();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) {
            View v = adapter.getView(i, null, list);
            names.add(((TextView) v.findViewById(R.id.pickerName)).getText().toString());
        }
        return names;
    }

    private static int indexOfPrefix(List<String> names, String prefix) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith(prefix)) return i;
        return -1;
    }

    private int intQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
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
