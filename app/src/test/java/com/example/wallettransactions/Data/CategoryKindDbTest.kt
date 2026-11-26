package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.IconList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec category-kind-income-expense, группа 1: колонка {@code account.category_kind}
 * (шаг v92), модель {@link AccountItem.CategoryKind}, загрузка и запись вида.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryKindDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var rub = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        // Сид оставляем только справочником иконок — свои счета и валюта ниже.
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM operation_template")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        db.execSQL("DELETE FROM currency_conversion")
        db.execSQL("DELETE FROM currency_pair_rate")
        db.execSQL("DELETE FROM currency_pair")
        db.execSQL("DELETE FROM currency")
        // Картинки сида не влезают в CursorWindow Robolectric при LoadIcons — свои, маленькие.
        db.execSQL("DELETE FROM icon")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        rub = insert("currency", "name", "Рубль", "iso_code", "RUB", "icon_id", iconId, "symbol", "₽").toInt()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 1.1 схема и шаг v92 ─────────────────────────────────────────────────

    @Test
    fun freshInstall_hasColumnWithDefaultBoth() {
        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"))
        val id = insert(
            "account", "name", "Прочее", "comment", "", "icon_id", iconId,
            "uuid", java.util.UUID.randomUUID().toString()
        )
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + id))
    }

    @Test
    fun upgradeFrom91_addsColumnAsBoth_andDoesNotTouchRowsOrDirty() {
        val groceries = category("Продукты")
        val salary = category("Зарплата")
        // «Синхронизирована» (dirty = 0) и «ждёт отправки» (dirty = 1) до апгрейда.
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + groceries)
        db.execSQL("UPDATE account SET dirty = 1 WHERE _id = " + salary)
        // Имитация схемы v91: колонки category_kind ещё нет. DROP COLUMN в SQLite Robolectric
        // не поддерживается (нужен 3.35+), поэтому колонку переименовываем — для миграции это
        // то же самое: columnExists(category_kind) == false, и она идёт по ветке ADD COLUMN.
        db.execSQL("ALTER TABLE account RENAME COLUMN category_kind TO category_kind_v91_stub")
        assertTrue(!WalletDbHelper.columnExists(db, "account", "category_kind"))

        helper.onUpgrade(db, 91, 92)

        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"))
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + groceries))
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + salary))
        // Изменение схемы — не правка пользователя: dirty прежний, на сервер ничего не уходит.
        assertEquals(0, intQuery("SELECT dirty FROM account WHERE _id = " + groceries))
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE _id = " + salary))
    }

    @Test
    fun upgradeFrom91_whenColumnAlreadyExists_isNoOp() {
        // Апгрейд из очень старой базы: колонку уже завёл accountTableBody() при перестройке.
        category("Продукты")
        helper.onUpgrade(db, 91, 92)
        helper.onUpgrade(db, 91, 92)
        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"))
        assertEquals(1, intQuery("SELECT count(*) FROM account"))
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    fun categoryKindFromValue_knownValuesAndSafeDefault() {
        assertEquals(AccountItem.CategoryKind.EXPENSE, AccountItem.CategoryKind.fromValue(0))
        assertEquals(AccountItem.CategoryKind.INCOME, AccountItem.CategoryKind.fromValue(1))
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(2))
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(-1))
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(3))
        assertEquals(0, AccountItem.CategoryKind.EXPENSE.value)
        assertEquals(1, AccountItem.CategoryKind.INCOME.value)
        assertEquals(2, AccountItem.CategoryKind.BOTH.value)
    }

    @Test
    fun loadAccounts_readsKind_andOutOfRangeBecomesBoth() {
        val expense = category("Продукты")
        val income = category("Зарплата")
        val broken = category("Мусор")
        db.execSQL("UPDATE account SET category_kind = 0 WHERE _id = " + expense)
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + income)
        db.execSQL("UPDATE account SET category_kind = 7 WHERE _id = " + broken)

        val accounts = loadAccounts()

        assertEquals(AccountItem.CategoryKind.EXPENSE, accounts.getAccountById(expense)!!.categoryKind)
        assertEquals(AccountItem.CategoryKind.INCOME, accounts.getAccountById(income)!!.categoryKind)
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(broken)!!.categoryKind)
    }

    @Test
    fun loadAccounts_walletAndSystemCategoryStayBoth() {
        val wallet = WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", iconId.toInt(), rub).toInt()
        val transfer = WalletDbCRUD.GetOrCreateSystemCategory(
            db,
            AccountItem.SystemKind.TRANSFER.value, AccountItem.SYSTEM_TRANSFER_NAME, "unknown"
        )

        val accounts = loadAccounts()

        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(wallet)!!.categoryKind)
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(transfer)!!.categoryKind)
    }

    @Test
    fun loadAccounts_secondLoadUpdatesCachedKind() {
        val id = category("Подарки")
        val accounts = loadAccounts()
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(id)!!.categoryKind)

        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id)
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        WalletDbLoader.LoadAccounts(db, accounts, currencies(), icons, null)

        assertEquals(AccountItem.CategoryKind.INCOME, accounts.getAccountById(id)!!.categoryKind)
    }

    // ── 1.3 запись ──────────────────────────────────────────────────────────

    @Test
    fun insertAccount_withoutKind_isBoth() {
        WalletDbCRUD.InsertAccount(db, "Прочее", "", iconId.toInt(), false)
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE name = 'Прочее'"))
    }

    @Test
    fun insertAccount_withKind_storesItAndMarksDirty() {
        WalletDbCRUD.InsertAccount(db, "Премия", "", iconId.toInt(), false, AccountItem.CategoryKind.INCOME)
        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE name = 'Премия'"))
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE name = 'Премия'"))
        assertEquals(0, intQuery("SELECT kind FROM account WHERE name = 'Премия'"))
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE name = 'Премия'"))
    }

    @Test
    fun updateAccount_withKind_changesKindAndMarksDirty() {
        val id = category("Подарки")
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + id)

        WalletDbCRUD.UpdateAccount(db, id, "Подарки", "", iconId.toInt(), false, AccountItem.CategoryKind.EXPENSE)

        assertEquals(0, intQuery("SELECT category_kind FROM account WHERE _id = " + id))
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE _id = " + id))
    }

    @Test
    fun updateAccount_withoutKind_keepsKind() {
        val id = category("Зарплата")
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id)

        WalletDbCRUD.UpdateAccount(db, id, "Зарплата 2", "", iconId.toInt(), false)

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE _id = " + id))
    }

    @Test
    fun updateAccount_withNullKind_keepsKind() {
        val id = category("Зарплата")
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id)

        WalletDbCRUD.UpdateAccount(db, id, "Зарплата", "", iconId.toInt(), true, null)

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE _id = " + id))
        assertNotNull(loadAccounts().getAccountById(id))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun category(name: String): Int {
        return insert(
            "account", "name", name, "comment", "", "icon_id", iconId,
            "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()
        ).toInt()
    }

    private fun loadAccounts(): AccountList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val accounts = AccountList()
        WalletDbLoader.LoadAccounts(db, accounts, currencies(), icons, null)
        return accounts
    }

    private fun currencies(): CurrencyList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val list = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, list, icons)
        return list
    }

    private fun intQuery(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            val value = kv[i + 1]
            when (value) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
