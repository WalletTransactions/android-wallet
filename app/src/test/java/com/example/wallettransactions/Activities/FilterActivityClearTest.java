package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;

/**
 * openspec similar-search / operations-filter: у каждого заданного параметра на экране
 * «Фильтр» есть свой крестик, он очищает только этот параметр, а у пустого фильтра
 * крестиков нет.
 */
@RunWith(RobolectricTestRunner.class)
public class FilterActivityClearTest {

    private Context context;
    private int categoryId;
    private int walletId;

    @Before
    public void setUp() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field state = PeriodFilterState.class.getDeclaredField("instance");
        state.setAccessible(true);
        state.set(null, null);

        context = ApplicationProvider.getApplicationContext();
        AppSettings.setFilterAccounts(context, "");
        AppSettings.setFilterOperationKind(context, "all");
        AppSettings.setFilterSearchText(context, "");
        AppSettings.setFilterAmountRange(context, null, null);
        AppSettings.setFilterEqRange(context, null, null);

        SQLiteDatabase db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'"); // картинки сида не влезают в CursorWindow
        int rub = intQuery(db, "SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
        int icon = intQuery(db, "SELECT _id FROM icon WHERE name = 'unknown'");
        categoryId = intQuery(db, "SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 "
                + "AND deleted_at IS NULL LIMIT 1");
        walletId = (int) WalletDbCRUD.InsertOperatingAccount(db, "Тестовый кошелёк", "", icon, rub);
        Singleton.instance.reloadAccounts(context);
    }

    private ActivityController<FilterActivity> open(String accountIdsCsv) {
        Intent intent = new Intent(context, FilterActivity.class);
        intent.putExtra(MainActivity.cFilterAccountList, accountIdsCsv);
        return Robolectric.buildActivity(FilterActivity.class, intent).setup();
    }

    private static boolean visible(FilterActivity a, int id) {
        return a.findViewById(id).getVisibility() == View.VISIBLE;
    }

    private static String text(FilterActivity a, int id) {
        return ((EditText) a.findViewById(id)).getText().toString();
    }

    private static void click(FilterActivity a, int id) {
        assertTrue("крестик должен быть виден", visible(a, id));
        a.findViewById(id).performClick();
    }

    @Test
    public void emptyFilter_hasNoCrosses() {
        FilterActivity a = open("").get();

        for (int id : new int[]{R.id.btnClearSearch, R.id.btnClearKind, R.id.btnClearAmountMin,
                R.id.btnClearAmountMax, R.id.btnClearEqMin, R.id.btnClearEqMax,
                R.id.btnClearCategories, R.id.btnClearWallets}) {
            assertFalse(visible(a, id));
        }
        assertTrue(visible(a, R.id.chevronCategories));
        assertTrue(visible(a, R.id.chevronWallets));
    }

    @Test
    public void everyCross_clearsOnlyItsOwnParameter() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.setSearchText(context, "молоко");
        state.setOperationKind(context, DataFilter.OperationKind.expense);
        state.setAmountRange(context, 10.0, 99.0);
        state.setEqRange(context, 1.0, 2.0);

        FilterActivity a = open(categoryId + "," + walletId).get();

        // Всё задано — все крестики на месте, шевронов у строк счетов нет.
        for (int id : new int[]{R.id.btnClearSearch, R.id.btnClearKind, R.id.btnClearAmountMin,
                R.id.btnClearAmountMax, R.id.btnClearEqMin, R.id.btnClearEqMax,
                R.id.btnClearCategories, R.id.btnClearWallets}) {
            assertTrue(visible(a, id));
        }
        assertFalse(visible(a, R.id.chevronCategories));
        assertFalse(visible(a, R.id.chevronWallets));

        // Одна граница суммы: «до» очищена, «от» осталась.
        click(a, R.id.btnClearAmountMax);
        assertEquals("", text(a, R.id.inputAmountMax));
        assertEquals("10", text(a, R.id.inputAmountMin));
        assertFalse(visible(a, R.id.btnClearAmountMax));
        assertTrue(visible(a, R.id.btnClearAmountMin));

        // Текст.
        click(a, R.id.btnClearSearch);
        assertEquals("", text(a, R.id.inputSearchText));
        assertEquals("1", text(a, R.id.inputEqMin));

        // Вид: крестик исчезает, счета не тронуты.
        click(a, R.id.btnClearKind);
        assertFalse(visible(a, R.id.btnClearKind));
        assertTrue(visible(a, R.id.btnClearCategories));

        // Категории очищены — кошелёк остался, у строки категорий вернулся шеврон.
        click(a, R.id.btnClearCategories);
        assertEquals(a.getString(R.string.filter_all),
                ((TextView) a.findViewById(R.id.categorySummary)).getText().toString());
        assertTrue(visible(a, R.id.chevronCategories));
        assertFalse(visible(a, R.id.btnClearCategories));
        assertTrue(visible(a, R.id.btnClearWallets));
        assertFalse(visible(a, R.id.chevronWallets));

        // Оставшиеся поля.
        click(a, R.id.btnClearAmountMin);
        click(a, R.id.btnClearEqMin);
        click(a, R.id.btnClearEqMax);
        click(a, R.id.btnClearWallets);
        assertFalse(visible(a, R.id.btnClearWallets));
        assertTrue(visible(a, R.id.chevronWallets));
    }

    @Test
    public void crosses_doNotChangeSavedFilterUntilApplied() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.setSearchText(context, "молоко");

        FilterActivity a = open("").get();
        click(a, R.id.btnClearSearch);

        // Крестик меняет форму, а общий фильтр — только «Показать».
        assertEquals("молоко", state.getSearchText());
    }

    private static int intQuery(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
