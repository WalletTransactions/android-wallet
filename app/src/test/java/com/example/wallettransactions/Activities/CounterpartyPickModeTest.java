package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbCRUD;
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
 * Карточка займа выбирает контрагента на экране «Контрагенты» (спека debts, «Выбор контрагента
 * в карточке займа»): тап по строке возвращает контрагента, архивные не предлагаются.
 */
@RunWith(RobolectricTestRunner.class)
public class CounterpartyPickModeTest {

    private Context context;
    private SQLiteDatabase db;
    private ActivityController<CounterpartiesActivity> controller;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'");
        WalletDbCRUD.CreateCounterparty(db, "Ктест Вася", "");
        WalletDbCRUD.CreateCounterparty(db, "Ктест Петя", "");
        int archived = WalletDbCRUD.CreateCounterparty(db, "Ктест Архивный", "");
        WalletDbCRUD.ArchiveCounterparty(db, archived, true);
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        resetSingleton();
    }

    @Test
    public void pick_tapOnRow_returnsCounterparty() {
        CounterpartiesActivity activity = start(CounterpartiesActivity.buildPickIntent(context, null));

        View row = rowByName(activity, "Ктест Петя");
        assertNotNull("нет строки Ктест Петя", row);
        row.performClick();

        assertTrue(activity.isFinishing());
        Intent result = shadowOf(activity).getResultIntent();
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).getResultCode());
        assertEquals("Ктест Петя", result.getStringExtra(CounterpartiesActivity.RESULT_NAME));
        assertEquals(WalletDbCRUD.CounterpartyUuid(db, WalletDbCRUD.GetCounterpartyIdByName(db, "Ктест Петя")),
                result.getStringExtra(CounterpartiesActivity.RESULT_UUID));
    }

    @Test
    public void pick_hidesArchived_exceptSelected() {
        CounterpartiesActivity plain = start(CounterpartiesActivity.buildPickIntent(context, null));
        assertEquals(null, rowByName(plain, "Ктест Архивный"));

        String archivedUuid = WalletDbCRUD.CounterpartyUuid(db,
                WalletDbCRUD.GetCounterpartyIdByName(db, "Ктест Архивный"));
        CounterpartiesActivity withSelected = start(CounterpartiesActivity.buildPickIntent(context, archivedUuid));
        assertNotNull(rowByName(withSelected, "Ктест Архивный"));
    }

    @Test
    public void normalMode_tapOpensCard_notResult() {
        CounterpartiesActivity activity = start(new Intent(context, CounterpartiesActivity.class));

        rowByName(activity, "Ктест Вася").performClick();

        assertFalse(activity.isFinishing());
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(CounterpartyCardActivity.class.getName(), next.getComponent().getClassName());
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private CounterpartiesActivity start(Intent intent) {
        if (controller != null) controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(CounterpartiesActivity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }

    /** Строка списка, чей первый TextView — имя контрагента. */
    private static View rowByName(CounterpartiesActivity activity, String name) {
        LinearLayout list = activity.findViewById(R.id.counterpartiesList);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                View first = ((ViewGroup) child).getChildAt(0);
                if (first instanceof TextView && name.contentEquals(((TextView) first).getText())) return child;
            }
        }
        return null;
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
