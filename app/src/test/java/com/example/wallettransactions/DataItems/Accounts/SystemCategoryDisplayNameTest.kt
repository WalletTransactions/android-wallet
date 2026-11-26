package com.example.wallettransactions.DataItems.Accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Системные категории на языке интерфейса (openspec android-i18n, spec ui-language):
 * показ — «Перевод» / «Комиссия за перевод», хранимое имя и имя в синке — прежние.
 */
@RunWith(RobolectricTestRunner::class)
class SystemCategoryDisplayNameTest {

    @Test
    fun russianInterface_systemCategoriesTranslated_othersAsStored() {
        AccountItem.initDisplayNames(ApplicationProvider.getApplicationContext())
        val transfer = AccountItem(1, "Transfer", "", null, false,
                AccountItem.SystemKind.TRANSFER, AccountItem.Kind.CATEGORY)
        val commission = AccountItem(2, "Transfer commission", "", null, false,
                AccountItem.SystemKind.TRANSFER_COMMISSION, AccountItem.Kind.CATEGORY)
        val taxi = AccountItem(3, "Такси", "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY)
        assertEquals("Перевод", transfer.displayName)
        assertEquals("Комиссия за перевод", commission.displayName)
        assertEquals("Такси", taxi.displayName)
        assertEquals("Transfer", transfer.name)
    }

    /** «Синк не затронут»: в базе и в push остаётся хранимое имя. */
    @Test
    fun storedNameUnchanged() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("wallet.db")
        val helper = WalletDbHelper(ctx)
        val db = helper.writableDatabase
        try {
            db.rawQuery("SELECT name FROM account WHERE system_kind = 1", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Transfer", c.getString(0))
            }
        } finally {
            helper.close()
        }
    }
}
