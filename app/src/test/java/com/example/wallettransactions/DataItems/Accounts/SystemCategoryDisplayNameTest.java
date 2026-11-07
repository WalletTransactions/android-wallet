package com.example.wallettransactions.DataItems.Accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Системные категории на языке интерфейса (openspec android-i18n, spec ui-language):
 * показ — «Перевод» / «Комиссия за перевод», хранимое имя и имя в синке — прежние.
 */
@RunWith(RobolectricTestRunner.class)
public class SystemCategoryDisplayNameTest {

    @Test
    public void russianInterface_systemCategoriesTranslated_othersAsStored() {
        AccountItem.initDisplayNames(ApplicationProvider.getApplicationContext());
        AccountItem transfer = new AccountItem(1, "Transfer", "", null, false,
                AccountItem.SystemKind.TRANSFER, AccountItem.Kind.CATEGORY);
        AccountItem commission = new AccountItem(2, "Transfer commission", "", null, false,
                AccountItem.SystemKind.TRANSFER_COMMISSION, AccountItem.Kind.CATEGORY);
        AccountItem taxi = new AccountItem(3, "Такси", "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY);
        assertEquals("Перевод", transfer.getDisplayName());
        assertEquals("Комиссия за перевод", commission.getDisplayName());
        assertEquals("Такси", taxi.getDisplayName());
        assertEquals("Transfer", transfer.getName());
    }

    /** «Синк не затронут»: в базе и в push остаётся хранимое имя. */
    @Test
    public void storedNameUnchanged() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("wallet.db");
        WalletDbHelper helper = new WalletDbHelper(ctx);
        SQLiteDatabase db = helper.getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT name FROM account WHERE system_kind = 1", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("Transfer", c.getString(0));
        } finally {
            helper.close();
        }
    }
}
