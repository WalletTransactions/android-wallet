package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * openspec category-kind-income-expense, задача 5.1: вид категорий стартового набора
 * (спека starter-set / category-kind): 23 расходные — «Расход», 4 доходные — «Доход»,
 * кошельки и системные категории — «Оба».
 */
@RunWith(RobolectricTestRunner.class)
public class SeedCategoryKindTest {

    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase(); // чистая установка сеет набор
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    @Config(qualifiers = "ru")
    public void cleanInstall_ru_expenseIncomeAndBoth() {
        assertEquals(0, kindOf("Продукты"));
        assertEquals(0, kindOf("Такси"));
        assertEquals(0, kindOf("Прочие расходы"));
        assertEquals(1, kindOf("Зарплата"));
        assertEquals(1, kindOf("Подработка"));
        assertEquals(1, kindOf("Проценты"));
        assertEquals(1, kindOf("Прочие доходы"));
        // Кошельки набора и системные категории вида не имеют.
        assertEquals(2, kindOf("Наличные"));
        assertEquals(2, kindOf("Кредитная карта"));
        assertEquals(2, kindOf("Transfer"));
        assertEquals(2, kindOf("Transfer commission"));
    }

    @Test
    @Config(qualifiers = "en")
    public void cleanInstall_en_sameKindsUnderEnglishNames() {
        assertEquals(0, kindOf("Groceries"));
        assertEquals(0, kindOf("Other expenses"));
        assertEquals(1, kindOf("Salary"));
        assertEquals(1, kindOf("Other income"));
        assertEquals(2, kindOf("Cash"));
        assertEquals(2, kindOf("Transfer"));
    }

    @Test
    @Config(qualifiers = "ru")
    public void cleanInstall_countsByKind() {
        assertEquals("расходные категории набора", 23,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 0"));
        assertEquals("доходные категории набора", 4,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 1"));
        assertEquals("кошельки набора", 2,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 1 AND category_kind = 2"));
        assertEquals("обычных категорий «Оба» в наборе нет", 0,
                count("SELECT count(*) FROM account WHERE from_seed = 1 AND kind = 0 AND system_kind = 0 AND category_kind = 2"));
    }

    @Test
    public void seedEntryWithoutField_orOutOfRange_isBoth() throws Exception {
        assertEquals(AccountItem.CategoryKind.BOTH,
                WalletFillData.seedCategoryKind(new JSONObject("{\"name\":\"X\"}")));
        assertEquals(AccountItem.CategoryKind.BOTH,
                WalletFillData.seedCategoryKind(new JSONObject("{\"name\":\"X\",\"category_kind\":9}")));
        assertEquals(AccountItem.CategoryKind.INCOME,
                WalletFillData.seedCategoryKind(new JSONObject("{\"name\":\"X\",\"category_kind\":1}")));
        assertEquals(AccountItem.CategoryKind.EXPENSE,
                WalletFillData.seedCategoryKind(new JSONObject("{\"name\":\"X\",\"category_kind\":0}")));
    }

    private int kindOf(String accountName) {
        return count("SELECT category_kind FROM account WHERE name = '" + accountName + "' AND deleted_at IS NULL");
    }

    private int count(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
