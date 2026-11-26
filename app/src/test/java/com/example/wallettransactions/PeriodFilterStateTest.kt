package com.example.wallettransactions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.DataFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.LinkedHashSet

/**
 * openspec similar-search: [PeriodFilterState.presetFilter] снимает прежний фильтр
 * целиком и ставит только переданное — этим пользуются кнопка на экране счёта и
 * «Найти подобные».
 */
@RunWith(RobolectricTestRunner::class)
class PeriodFilterStateTest {

    private lateinit var context: Context
    private var card = 0
    private var food = 0

    @Before
    fun setUp() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase", "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val state = PeriodFilterState::class.java.getDeclaredField("instance")
        state.isAccessible = true
        state.set(null, null)

        context = ApplicationProvider.getApplicationContext()
        AppSettings.setFilterAccounts(context, "")
        AppSettings.setFilterOperationKind(context, "all")
        AppSettings.setFilterSearchText(context, "")
        AppSettings.setFilterAmountRange(context, null, null)
        AppSettings.setFilterEqRange(context, null, null)

        val db = Singleton.instance.getWritableDatabase(context)
        for (table in arrayOf(
            "operation", "operation_link", "account_operating",
            "account", "currency_conversion", "currency_pair_rate", "currency_pair",
            "currency", "icon", "recurring_operation"
        )) {
            db.execSQL("DELETE FROM $table")
        }
        db.execSQL("DELETE FROM sqlite_sequence")

        val icon = insert(db, "icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        val rub = insert(db, "currency", "name", "Рубль", "iso_code", "RUB", "icon_id", icon, "symbol", "₽")
        card = WalletDbCRUD.InsertOperatingAccount(db, "Карта", "", icon.toInt(), rub.toInt()).toInt()
        food = insert(
            db, "account", "name", "Продукты", "comment", "", "icon_id", icon,
            "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()
        ).toInt()
        Singleton.instance.reloadAccounts(context)
    }

    @Test
    fun presetFilter_replacesEverythingWithGivenParameters() {
        val state = PeriodFilterState.get(context)
        state.setSearchText(context, "старый текст")
        state.setOperationKind(context, DataFilter.OperationKind.income)
        state.setAmountRange(context, 10.0, 99.0)
        state.setEqRange(context, 1.0, 2.0)

        state.presetFilter(context, listOf(food, card), DataFilter.OperationKind.expense, "молоко")

        assertTrue(state.hasFilter())
        assertEquals(LinkedHashSet(listOf(food, card)), state.getAccountIds())
        assertEquals(DataFilter.OperationKind.expense, state.getOperationKind())
        assertEquals("молоко", state.getSearchText())
        assertNull(state.getAmountMin())
        assertNull(state.getAmountMax())
        assertNull(state.getEqMin())
        assertNull(state.getEqMax())

        // То, что записано в настройки, переживёт перезапуск.
        assertEquals(state.getAccountIdsCsv(), AppSettings.getFilterAccounts(context))
        assertEquals("expense", AppSettings.getFilterOperationKind(context))
        assertEquals("молоко", AppSettings.getFilterSearchText(context))
    }

    @Test
    fun presetFilter_withoutKindAndText_leavesOnlyAccount() {
        val state = PeriodFilterState.get(context)
        state.setSearchText(context, "старый текст")
        state.setOperationKind(context, DataFilter.OperationKind.transfer)

        state.presetFilter(context, listOf(food), null, null)

        assertEquals(LinkedHashSet(listOf(food)), state.getAccountIds())
        assertEquals(DataFilter.OperationKind.all, state.getOperationKind())
        assertEquals("", state.getSearchText())
    }

    @Test
    fun presetFilter_sameDataTwice_doesNotBumpRevisionAgain() {
        val state = PeriodFilterState.get(context)
        state.presetFilter(context, listOf(food, card), DataFilter.OperationKind.expense, "молоко")
        val revision = state.getRevision()

        state.presetFilter(context, listOf(food, card), DataFilter.OperationKind.expense, "молоко")

        assertEquals(revision, state.getRevision())
    }

    @Test
    fun presetFilter_emptyEverything_clearsFilter() {
        val state = PeriodFilterState.get(context)
        state.presetFilter(context, listOf(card), DataFilter.OperationKind.income, "x")

        state.presetFilter(context, null, null, null)

        assertFalse(state.hasFilter())
    }

    private fun insert(db: SQLiteDatabase, table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            when (val value = kv[i + 1]) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
