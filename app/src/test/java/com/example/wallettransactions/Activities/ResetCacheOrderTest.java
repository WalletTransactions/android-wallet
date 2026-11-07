package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * «Сброс данных» с пересевом счетов и иконок (ResetDataActivity.runReset): после сброса
 * категории набора были без иконок до перезапуска. Счета перечитывались раньше списка
 * иконок и искали новые {@code icon_id} в старом списке.
 */
@RunWith(RobolectricTestRunner.class)
public class ResetCacheOrderTest {

    private static final String[] TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        // Лёгкие иконки вместо настоящего набора: PNG набора не помещаются в CursorWindow
        // Robolectric (см. HardWipeCacheInvalidationTest).
        for (String table : TABLES) db.execSQL("DELETE FROM " + table);
    }

    @Test
    public void resetWithReseed_categoryGetsNewIconWithoutRestart() {
        insertIcon("unknown");
        long oldIcon = insertIcon("Taxi");
        long accountId = insertAccount("Такси", oldIcon);
        Singleton.instance.getIconList(context);
        Singleton.instance.getAccounts(context);

        // Что делает сброс с пересевом: старые иконки помечены удалёнными, набор заведён
        // заново с новыми _id, счёт перепривязан по имени иконки.
        ContentValues del = new ContentValues();
        del.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis());
        db.update(WalletContract.IconEntry.TABLE_NAME, del, null, null);
        insertIcon("unknown");
        long newIcon = insertIcon("Taxi");
        ContentValues relink = new ContentValues();
        relink.put(WalletContract.AccountEntry.COLUMN_ICON_ID, newIcon);
        db.update(WalletContract.AccountEntry.TABLE_NAME, relink,
                WalletContract.AccountEntry._ID + "=" + accountId, null);

        ResetDataActivity.reloadCachesAfterReset(context, true, true, false);

        AccountItem account = Singleton.instance.getAccounts(context).getAccountById((int) accountId);
        Icon icon = account.getIcon();
        assertNotNull(icon);
        assertEquals(newIcon, icon.getId());
    }

    private long insertIcon(String name) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 2);
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private long insertAccount(String name, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name);
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, "");
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
        return db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv);
    }

    /** Singleton — статическое поле, переживает тесты (как в HardWipeCacheInvalidationTest). */
    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance,
                new com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList());
    }
}
