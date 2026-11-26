package com.example.wallettransactions.Activities

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * «Сброс данных» с пересевом счетов и иконок (ResetDataActivity.runReset): после сброса
 * категории набора были без иконок до перезапуска. Счета перечитывались раньше списка
 * иконок и искали новые `icon_id` в старом списке.
 */
@RunWith(RobolectricTestRunner::class)
class ResetCacheOrderTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        // Лёгкие иконки вместо настоящего набора: PNG набора не помещаются в CursorWindow
        // Robolectric (см. HardWipeCacheInvalidationTest).
        for (table in TABLES) db.execSQL("DELETE FROM $table")
    }

    @Test
    fun resetWithReseed_categoryGetsNewIconWithoutRestart() {
        insertIcon("unknown")
        val oldIcon = insertIcon("Taxi")
        val accountId = insertAccount("Такси", oldIcon)
        Singleton.instance.getIconList(context)
        Singleton.instance.getAccounts(context)

        // Что делает сброс с пересевом: старые иконки помечены удалёнными, набор заведён
        // заново с новыми _id, счёт перепривязан по имени иконки.
        val del = ContentValues()
        del.put(WalletContract.IconEntry.COLUMN_DELETED_AT, System.currentTimeMillis())
        db.update(WalletContract.IconEntry.TABLE_NAME, del, null, null)
        insertIcon("unknown")
        val newIcon = insertIcon("Taxi")
        val relink = ContentValues()
        relink.put(WalletContract.AccountEntry.COLUMN_ICON_ID, newIcon)
        db.update(WalletContract.AccountEntry.TABLE_NAME, relink,
                WalletContract.AccountEntry._ID + "=" + accountId, null)

        ResetDataActivity.reloadCachesAfterReset(context, true, true, false)

        val account = Singleton.instance.getAccounts(context).getAccountById(accountId.toInt())
        val icon = account!!.icon
        assertNotNull(icon)
        assertEquals(newIcon, icon!!.id.toLong())
    }

    private fun insertIcon(name: String): Long {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 2)
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun insertAccount(name: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put(WalletContract.AccountEntry.COLUMN_NAME, name)
        cv.put(WalletContract.AccountEntry.COLUMN_COMMENT, "")
        cv.put(WalletContract.AccountEntry.COLUMN_ICON_ID, iconId)
        return db.insert(WalletContract.AccountEntry.TABLE_NAME, null, cv)
    }

    /** Singleton — статическое поле, переживает тесты (как в HardWipeCacheInvalidationTest). */
    private fun resetSingleton() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, CurrencyPairList())
    }

    companion object {
        private val TABLES = arrayOf(
                "operation", "operation_link", "account_operating", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "recurring_operation")
    }
}
