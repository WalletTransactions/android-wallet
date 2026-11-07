package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.util.Date;

/**
 * Кнопка «Сохранить» в окне операции сразу после открытия (changes/fix-prefill-save-enabled).
 * Черновик из ИИ открывает окно новой операции, где заполнено всё; раньше onCreate
 * гасил кнопку безусловно, и сохранить можно было только после любой правки.
 * База — настоящая, с первичным справочником (WalletFillData): из неё берётся категория,
 * кошелёк заводит setUp.
 */
@RunWith(RobolectricTestRunner.class)
public class TransactionSaveButtonTest {

    private Context context;
    private ActivityController<TransactionActivity> controller;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        // Картинки первичного справочника не влезают в CursorWindow Robolectric,
        // а кнопке «Сохранить» они не нужны.
        android.database.sqlite.SQLiteDatabase db = Singleton.instance.getWritableDatabase(context);
        db.execSQL("UPDATE icon SET image = x'00'");
        // Во встроенном наборе кошельков нет (new-user-default-set) — заводим один.
        android.database.Cursor c = db.rawQuery("SELECT (SELECT _id FROM icon LIMIT 1),"
                + " (SELECT _id FROM currency WHERE iso_code = 'EUR')", null);
        try {
            c.moveToFirst();
            com.example.wallettransactions.Data.WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "",
                    c.getInt(0), c.getInt(1));
        } finally {
            c.close();
        }
        Singleton.instance.reloadAccounts(context);
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        resetSingleton();
    }

    @Test
    public void ai_prefilled_new_operation_can_be_saved_at_once() {
        View save = open(prefilledExpense(150.0));
        assertTrue("заполненный черновик сохраняется без лишнего касания", save.isEnabled());
    }

    @Test
    public void new_operation_without_amount_stays_disabled() {
        View save = open(prefilledExpense(0.0));
        assertFalse("без суммы сохранять нечего", save.isEnabled());
    }

    @Test
    public void edit_without_changes_stays_disabled() {
        Intent in = prefilledExpense(150.0)
                .putExtra(TransactionActivity.cIsEdit, true)
                .putExtra(TransactionActivity.cTranId, 1);
        View save = open(in);
        assertFalse("правка без изменений по-прежнему погашена", save.isEnabled());
    }

    @Test
    public void big_amount_is_shown_without_exponent() {
        open(prefilledExpense(10_000_000.0));
        android.widget.EditText amount = controller.get().findViewById(R.id.editAmount);
        assertEquals("не «1.0E7»", "10000000", amount.getText().toString());
    }

    /** Те же extras, что кладёт AiActivity для черновика из ИИ. */
    private Intent prefilledExpense(double amount) {
        AccountItem wallet = null;
        AccountItem category = null;
        for (AccountItem a : Singleton.instance.getAccounts(context).getAllAccounts()) {
            if (a.getSystemKind() != AccountItem.SystemKind.NONE) continue;
            if (a.isOperatingAccount() && wallet == null) wallet = a;
            if (!a.isOperatingAccount() && category == null) category = a;
        }
        assertNotNull("в первичном справочнике есть кошелёк", wallet);
        assertNotNull("в первичном справочнике есть категория", category);
        return new Intent(context, TransactionActivity.class)
                .putExtra(TransactionActivity.cDate, DateUtils.DateToStr(new Date()))
                .putExtra(TransactionActivity.cAmount, amount)
                .putExtra(TransactionActivity.cComment, "кофе")
                .putExtra(TransactionActivity.cFrom, wallet.getId())
                .putExtra(TransactionActivity.cTo, category.getId())
                .putExtra(TransactionActivity.cIsEdit, false)
                .putExtra(TransactionActivity.cKind, "expense")
                .putExtra(TransactionActivity.cHint, "Распознано ИИ");
    }

    private View open(Intent intent) {
        controller = Robolectric.buildActivity(TransactionActivity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get().findViewById(R.id.buttonSave);
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
