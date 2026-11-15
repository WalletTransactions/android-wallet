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
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
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

/**
 * openspec category-kind-income-expense, задача 4.2: выбор вида в «Новой категории» и в
 * карточке категории (спека account-screens). База — настоящая, с первичным справочником.
 */
@RunWith(RobolectricTestRunner.class)
public class CategoryKindScreensTest {

    private Context context;
    private SQLiteDatabase db;
    private ActivityController<?> controller;
    private int iconId;
    private int rubId;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow Robolectric
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'");
        rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        resetSingleton();
    }

    // ── «Новая категория» ───────────────────────────────────────────────────

    @Test
    public void newCategory_defaultsToExpense_andSavesIt() {
        AddCategoryActivity activity = start(AddCategoryActivity.class,
                new Intent(context, AddCategoryActivity.class));
        ((EditText) activity.findViewById(R.id.editCategoryName)).setText("Ктест Подарки");

        activity.btnSaveClick(null);

        assertEquals(0, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Подарки'"));
        assertEquals(0, intQuery("SELECT kind FROM account WHERE name = 'Ктест Подарки'"));
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE name = 'Ктест Подарки'"));
    }

    @Test
    public void newCategory_incomeChip_savesIncome() {
        AddCategoryActivity activity = start(AddCategoryActivity.class,
                new Intent(context, AddCategoryActivity.class));
        ((EditText) activity.findViewById(R.id.editCategoryName)).setText("Ктест Премия");
        activity.findViewById(R.id.categoryKindIncome).performClick();

        activity.btnSaveClick(null);

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Премия'"));
    }

    @Test
    public void newCategory_bothChip_savesBoth() {
        AddCategoryActivity activity = start(AddCategoryActivity.class,
                new Intent(context, AddCategoryActivity.class));
        ((EditText) activity.findViewById(R.id.editCategoryName)).setText("Ктест Прочее");
        activity.findViewById(R.id.categoryKindBoth).performClick();

        activity.btnSaveClick(null);

        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE name = 'Ктест Прочее'"));
    }

    // ── карточка категории ──────────────────────────────────────────────────

    @Test
    public void card_regularCategory_showsKind_saveDisabledUntilChanged() {
        int id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE);

        AccountItemActivity activity = openCard(id);

        assertEquals(View.VISIBLE, activity.findViewById(R.id.categoryKindGroup).getVisibility());
        assertFalse("без изменений «Сохранить» неактивна", activity.findViewById(R.id.btnSave).isEnabled());
    }

    @Test
    public void card_changeKind_enablesSave_andWritesKind_withoutTouchingOperations() {
        int id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE);
        int wallet = (int) WalletDbCRUD.InsertOperatingAccount(db, "Ктест Кошелёк", "", iconId, rubId);
        WalletDbCRUD.InsertOperation(db, "2026-11-01 12:00", wallet, id, 50.0, "");
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + id);
        int opsBefore = intQuery("SELECT count(*) FROM operation");

        AccountItemActivity activity = openCard(id);
        activity.findViewById(R.id.categoryKindBoth).performClick();
        assertTrue("после смены вида «Сохранить» активна", activity.findViewById(R.id.btnSave).isEnabled());
        activity.btnSaveClick(null);

        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + id));
        assertEquals("правка помечает строку для синка", 1, intQuery("SELECT dirty FROM account WHERE _id = " + id));
        assertEquals("операции не меняются", opsBefore, intQuery("SELECT count(*) FROM operation"));
    }

    @Test
    public void card_changingKindBackToOriginal_disablesSaveAgain() {
        int id = category("Ктест Подарки", AccountItem.CategoryKind.EXPENSE);

        AccountItemActivity activity = openCard(id);
        activity.findViewById(R.id.categoryKindIncome).performClick();
        assertTrue(activity.findViewById(R.id.btnSave).isEnabled());
        activity.findViewById(R.id.categoryKindExpense).performClick();

        assertFalse(activity.findViewById(R.id.btnSave).isEnabled());
    }

    @Test
    public void card_wallet_hasNoKindChooser_andKindStaysBoth() {
        int wallet = (int) WalletDbCRUD.InsertOperatingAccount(db, "Ктест Кошелёк", "", iconId, rubId);
        Singleton.instance.reloadAccounts(context);

        AccountItemActivity activity = openCard(wallet);

        assertEquals(View.GONE, activity.findViewById(R.id.categoryKindGroup).getVisibility());
        // Правка названия кошелька вид не трогает.
        ((EditText) activity.findViewById(R.id.editAccName)).setText("Ктест Кошелёк 2");
        activity.btnSaveClick(null);
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + wallet));
    }

    @Test
    public void card_systemCategory_hasNoKindChooser() {
        int transfer = WalletDbCRUD.GetOrCreateSystemCategory(db,
                AccountItem.SystemKind.TRANSFER.getValue(), AccountItem.SYSTEM_TRANSFER_NAME, "unknown");
        Singleton.instance.reloadAccounts(context);

        AccountItemActivity activity = openCard(transfer);

        assertEquals(View.GONE, activity.findViewById(R.id.categoryKindGroup).getVisibility());
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private int category(String name, AccountItem.CategoryKind kind) {
        WalletDbCRUD.InsertAccount(db, name, "", iconId, false, kind);
        Singleton.instance.reloadAccounts(context);
        return intQuery("SELECT _id FROM account WHERE name = '" + name + "'");
    }

    private AccountItemActivity openCard(int accountId) {
        return start(AccountItemActivity.class, new Intent(context, AccountItemActivity.class)
                .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId));
    }

    @SuppressWarnings("unchecked")
    private <T extends android.app.Activity> T start(Class<T> cls, Intent intent) {
        if (controller != null) controller.pause().stop().destroy();
        ActivityController<T> c = Robolectric.buildActivity(cls, intent).setup();
        controller = c;
        shadowOf(Looper.getMainLooper()).idle();
        return c.get();
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
