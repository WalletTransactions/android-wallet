package com.example.wallettransactions.Adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.DataItems.Operations.OperationList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * openspec capital-assets, задача 5.6: строка переоценки в ленте — актив, подпись
 * «Переоценка», «+»/«−» цветом перевода; итог дня в шапке без переоценки.
 */
@RunWith(RobolectricTestRunner.class)
public class RevaluationRowTest {

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'");
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    @Test
    public void revaluationRow_signLabelColor_andDayTotalWithoutIt() {
        int rub = (int) single("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL");
        int icon = (int) single("SELECT _id FROM icon WHERE name = 'unknown'");
        int card = (int) WalletDbCRUD.InsertOperatingAccount(db, "Карта для теста", "", icon, rub);
        int flat = (int) WalletDbCRUD.InsertOperatingAccount(db, "Квартира Ленина", "", icon, rub);
        int food = (int) single("SELECT _id FROM account WHERE kind = 0 AND system_kind = 0 AND deleted_at IS NULL LIMIT 1");
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        WalletDbCRUD.InsertOperation(db, "2026-06-15 10:00", card, food, 500, "");
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-06-15", 2_000_000, 2, "");
        Singleton.instance.reloadAccounts(context);

        Currency rubCurrency = Singleton.instance.getCurrencyList(context).getCurrencyById(rub);
        OperationList ops = new OperationList();
        WalletDbLoader.LoadOperations(db, ops, Singleton.instance.getAccounts(context),
                DateUtils.StrToDate("2026-06-15 00:00"), DateUtils.StrToDate("2026-06-15 23:59"),
                rubCurrency, new DataFilter());
        assertEquals(2, ops.size());
        assertTrue("сначала переоценка (23:59)", ops.get(0).isRevaluation());

        TransactionArrayAdapter adapter = new TransactionArrayAdapter(context, ops, rubCurrency,
                TransactionArrayAdapter.GroupByTime.day);
        assertEquals(2, adapter.getItemViewType(0));
        android.view.View row = adapter.getView(0, null, new FrameLayout(context));

        assertEquals("Квартира Ленина", ((TextView) row.findViewById(R.id.revaluationAsset)).getText().toString());
        assertTrue(((TextView) row.findViewById(R.id.revaluationLabel)).getText().toString().startsWith("Переоценка"));
        TextView amount = row.findViewById(R.id.revaluationAmount);
        assertTrue(amount.getText().toString(), amount.getText().toString().startsWith("+2"));
        assertEquals(ContextCompat.getColor(context, R.color.colortransfer), amount.getCurrentTextColor());
        String dayTotal = ((TextView) row.findViewById(R.id.textAmountEq)).getText().toString();
        assertTrue("итог дня — только расход 500: " + dayTotal, dayTotal.contains("500") && !dayTotal.contains("2 000"));
    }

    private long single(String sql) {
        android.database.Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getLong(0);
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
