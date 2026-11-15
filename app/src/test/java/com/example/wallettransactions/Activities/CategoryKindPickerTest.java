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
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.List;

/**
 * openspec category-kind-income-expense, задача 3.2: выбор «Статьи расхода» / «Статьи дохода»
 * (спека operation-entry, «Выбор счёта для строки») фильтрует категории по виду. Три тестовые
 * категории — «Расход», «Доход», «Оба» — заводятся поверх первичного справочника.
 */
@RunWith(RobolectricTestRunner.class)
public class CategoryKindPickerTest {

    private static final String EXPENSE = "Ктест-расход";
    private static final String INCOME = "Ктест-доход";
    private static final String BOTH = "Ктест-оба";
    /** Значение {@code kindExtra} для {@link #open}: extra пикеру не передаётся. */
    private static final int NO_EXTRA = Integer.MIN_VALUE;

    private Context context;
    private SQLiteDatabase db;
    private ActivityController<?> controller;
    private int iconId;
    private int incomeId;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow Robolectric
        iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'");
        WalletDbCRUD.InsertAccount(db, EXPENSE, "", iconId, false, AccountItem.CategoryKind.EXPENSE);
        WalletDbCRUD.InsertAccount(db, INCOME, "", iconId, false, AccountItem.CategoryKind.INCOME);
        WalletDbCRUD.InsertAccount(db, BOTH, "", iconId, false, AccountItem.CategoryKind.BOTH);
        incomeId = intQuery("SELECT _id FROM account WHERE name = '" + INCOME + "'");
        Singleton.instance.reloadAccounts(context);
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        resetSingleton();
    }

    @Test
    public void expenseSlot_showsExpenseAndBoth_hidesIncome() {
        List<String> names = listNames(open(0, 0, null));

        assertTrue(names.toString(), names.contains(EXPENSE));
        assertTrue(names.toString(), names.contains(BOTH));
        assertFalse(names.toString(), names.contains(INCOME));
    }

    @Test
    public void incomeSlot_showsIncomeAndBoth_hidesExpense() {
        List<String> names = listNames(open(1, 0, null));

        assertTrue(names.toString(), names.contains(INCOME));
        assertTrue(names.toString(), names.contains(BOTH));
        assertFalse(names.toString(), names.contains(EXPENSE));
    }

    @Test
    public void withoutKindExtra_showsEveryCategory() {
        List<String> names = listNames(open(NO_EXTRA, 0, null));

        assertTrue(names.toString(), names.contains(EXPENSE));
        assertTrue(names.toString(), names.contains(INCOME));
        assertTrue(names.toString(), names.contains(BOTH));
    }

    @Test
    public void alreadySelectedCategory_staysEvenWithWrongKind() {
        // Правка расхода по категории вида «Доход»: «Статья расхода» показывает её выбранной.
        List<String> names = listNames(open(0, incomeId, null));

        assertTrue(names.toString(), names.contains(INCOME));
        assertTrue(names.toString(), names.contains(EXPENSE));
        assertTrue(names.toString(), names.contains(BOTH));
    }

    @Test
    public void priorityCandidate_staysEvenWithWrongKind() {
        // Кандидат ИИ / импорта выписки предложен явно — скрывать его было бы сюрпризом.
        List<String> names = listNames(open(0, 0, new int[]{incomeId}));

        assertTrue(names.toString(), names.contains(INCOME));
    }

    @Test
    public void catalogMode_ignoresKindExtra() {
        Intent catalog = AccountPickerActivity.buildCatalogIntent(context)
                .putExtra(AccountPickerActivity.cCategoryKind, 0);
        AccountPickerActivity activity = start(AccountPickerActivity.class, catalog);
        // Справочник по умолчанию открыт на кошельках — переключаемся на категории.
        activity.findViewById(R.id.kindCategories).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        List<String> names = listNames(activity);
        assertTrue(names.toString(), names.contains(EXPENSE));
        assertTrue(names.toString(), names.contains(INCOME));
        assertTrue(names.toString(), names.contains(BOTH));
    }

    @Test
    public void wallets_areNotAffectedByKindExtra() {
        // Кошельки не фильтруются по виду: вид бывает только у категорий.
        Intent intent = AccountPickerActivity.buildIntent(context, "Кошелёк",
                AccountPickerActivity.cSelectedTypeOperationAccounts, 0)
                .putExtra(AccountPickerActivity.cCategoryKind, 0);
        List<String> names = listNames(start(AccountPickerActivity.class, intent));

        assertFalse(names.toString(), names.isEmpty());
        assertFalse(names.toString(), names.contains(EXPENSE));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    /** Выбор статьи: {@code kindExtra} — 0/1 или {@link #NO_EXTRA}. */
    private AccountPickerActivity open(int kindExtra, int currentId, int[] priority) {
        Intent intent = AccountPickerActivity.buildIntent(context, "Статья",
                AccountPickerActivity.cSelectedTypeChargeAccounts, currentId, null, priority);
        if (kindExtra != NO_EXTRA) {
            intent.putExtra(AccountPickerActivity.cCategoryKind, kindExtra);
        }
        return start(AccountPickerActivity.class, intent);
    }

    @SuppressWarnings("unchecked")
    private <T extends android.app.Activity> T start(Class<T> cls, Intent intent) {
        if (controller != null) controller.pause().stop().destroy();
        ActivityController<T> c = Robolectric.buildActivity(cls, intent).setup();
        controller = c;
        shadowOf(Looper.getMainLooper()).idle();
        return c.get();
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
