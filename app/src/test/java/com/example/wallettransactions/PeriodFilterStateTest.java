package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.DataItems.DataFilter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * openspec similar-search: {@link PeriodFilterState#presetFilter} снимает прежний фильтр
 * целиком и ставит только переданное — этим пользуются кнопка на экране счёта и
 * «Найти подобные».
 */
@RunWith(RobolectricTestRunner.class)
public class PeriodFilterStateTest {

    private Context context;
    private int card;
    private int food;

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
        for (String table : new String[]{"operation", "operation_link", "account_operating",
                "account", "currency_conversion", "currency_pair_rate", "currency_pair",
                "currency", "icon", "recurring_operation"}) {
            db.execSQL("DELETE FROM " + table);
        }
        db.execSQL("DELETE FROM sqlite_sequence");

        long icon = insert(db, "icon", "name", "unknown", "image", new byte[]{0}, "icon_type", 1);
        long rub = insert(db, "currency", "name", "Рубль", "iso_code", "RUB", "icon_id", icon, "symbol", "₽");
        card = (int) WalletDbCRUD.InsertOperatingAccount(db, "Карта", "", (int) icon, (int) rub);
        food = (int) insert(db, "account", "name", "Продукты", "comment", "", "icon_id", icon,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
        Singleton.instance.reloadAccounts(context);
    }

    @Test
    public void presetFilter_replacesEverythingWithGivenParameters() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.setSearchText(context, "старый текст");
        state.setOperationKind(context, DataFilter.OperationKind.income);
        state.setAmountRange(context, 10.0, 99.0);
        state.setEqRange(context, 1.0, 2.0);

        state.presetFilter(context, Arrays.asList(food, card), DataFilter.OperationKind.expense, "молоко");

        assertTrue(state.hasFilter());
        assertEquals(new LinkedHashSet<>(Arrays.asList(food, card)), state.getAccountIds());
        assertEquals(DataFilter.OperationKind.expense, state.getOperationKind());
        assertEquals("молоко", state.getSearchText());
        assertNull(state.getAmountMin());
        assertNull(state.getAmountMax());
        assertNull(state.getEqMin());
        assertNull(state.getEqMax());

        // То, что записано в настройки, переживёт перезапуск.
        assertEquals(state.getAccountIdsCsv(), AppSettings.getFilterAccounts(context));
        assertEquals("expense", AppSettings.getFilterOperationKind(context));
        assertEquals("молоко", AppSettings.getFilterSearchText(context));
    }

    @Test
    public void presetFilter_withoutKindAndText_leavesOnlyAccount() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.setSearchText(context, "старый текст");
        state.setOperationKind(context, DataFilter.OperationKind.transfer);

        state.presetFilter(context, Collections.singletonList(food), null, null);

        assertEquals(new LinkedHashSet<>(Collections.singletonList(food)), state.getAccountIds());
        assertEquals(DataFilter.OperationKind.all, state.getOperationKind());
        assertEquals("", state.getSearchText());
    }

    @Test
    public void presetFilter_sameDataTwice_doesNotBumpRevisionAgain() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.presetFilter(context, Arrays.asList(food, card), DataFilter.OperationKind.expense, "молоко");
        int revision = state.getRevision();

        state.presetFilter(context, Arrays.asList(food, card), DataFilter.OperationKind.expense, "молоко");

        assertEquals(revision, state.getRevision());
    }

    @Test
    public void presetFilter_emptyEverything_clearsFilter() {
        PeriodFilterState state = PeriodFilterState.get(context);
        state.presetFilter(context, Collections.singletonList(card), DataFilter.OperationKind.income, "x");

        state.presetFilter(context, null, null, null);

        assertFalse(state.hasFilter());
    }

    private static long insert(SQLiteDatabase db, String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            if (value instanceof Integer) cv.put(key, (Integer) value);
            else if (value instanceof Long) cv.put(key, (Long) value);
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
