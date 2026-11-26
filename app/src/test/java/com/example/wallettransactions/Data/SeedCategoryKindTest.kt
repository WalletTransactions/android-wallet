package com.example.wallettransactions.Data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * openspec category-kind-income-expense, задача 5.1: вид категорий стартового набора
 * (спека starter-set / category-kind): 23 расходные — «Расход», 4 доходные — «Доход»,
 * кошельки и системные категории — «Оба».
 */
@RunWith(RobolectricTestRunner::class)
class SeedCategoryKindTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase // чистая установка сеет набор
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_ru_expenseIncomeAndBoth() {
        assertEquals(0, kindOf("Продукты"))
        assertEquals(0, kindOf("Такси"))
        assertEquals(0, kindOf("Прочие расходы"))
        assertEquals(1, kindOf("Зарплата"))
        assertEquals(1, kindOf("Подработка"))
        assertEquals(1, kindOf("Проценты"))
        assertEquals(1, kindOf("Прочие доходы"))
        // Кошельки набора и системные категории вида не имеют.
        assertEquals(2, kindOf("Наличные"))
        assertEquals(2, kindOf("Кредитная карта"))
        assertEquals(2, kindOf("Transfer"))
        assertEquals(2, kindOf("Transfer commission"))
    }

    @Test
    @Config(qualifiers = "en")
    fun cleanInstall_en_sameKindsUnderEnglishNames() {
        assertEquals(0, kindOf("Groceries"))
        assertEquals(0, kindOf("Other expenses"))
        assertEquals(1, kindOf("Salary"))
        assertEquals(1, kindOf("Other income"))
        assertEquals(2, kindOf("Cash"))
        assertEquals(2, kindOf("Transfer"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun cleanInstall_countsByKind() {
        assertEquals("расходные категории набора", 23,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 0"))
        assertEquals("доходные категории набора", 4,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 1"))
        assertEquals("кошельки набора", 2,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 1 AND category_kind = 2"))
        assertEquals("обычных категорий «Оба» в наборе нет", 0,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 2"))
    }

    @Test
    @Throws(Exception::class)
    fun seedEntryWithoutField_orOutOfRange_isBoth() {
        assertEquals(AccountItem.CategoryKind.BOTH,
                WalletFillData.seedCategoryKind(JSONObject("{\"name\":\"X\"}")))
        assertEquals(AccountItem.CategoryKind.BOTH,
                WalletFillData.seedCategoryKind(JSONObject("{\"name\":\"X\",\"category_kind\":9}")))
        assertEquals(AccountItem.CategoryKind.INCOME,
                WalletFillData.seedCategoryKind(JSONObject("{\"name\":\"X\",\"category_kind\":1}")))
        assertEquals(AccountItem.CategoryKind.EXPENSE,
                WalletFillData.seedCategoryKind(JSONObject("{\"name\":\"X\",\"category_kind\":0}")))
    }

    private fun kindOf(accountName: String): Int {
        return count("SELECT category_kind FROM account WHERE name = '$accountName' AND deleted_at IS NULL")
    }

    private fun count(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
