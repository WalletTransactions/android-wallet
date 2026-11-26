package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Регресс на баг из ResetDataActivity.onHardWipe (2026-09-16, живое устройство): после
 * физической очистки БД ({@code DELETE FROM} по всем таблицам + сброс {@code sqlite_sequence})
 * и пересева {@link WalletFillData#InsertFirstDictionary} иконки/счета/валюты заводятся заново
 * с новыми {@code _id}. {@link Singleton} — процесс-wide кэш, который переживает
 * {@code FLAG_ACTIVITY_CLEAR_TASK} (тот чистит только стек активностей, не процесс): без
 * явного {@code reload*} после вайпа он продолжает отдавать {@link com.example.wallettransactions.DataItems.Icon}
 * со СТАРЫМ {@code _id}, которого в свежей {@code icon} уже нет. Первая же вставка счёта через
 * JSON-импорт ({@link WalletImportData#FillOperationDataFromJson}), взявшая {@code icon_id} из
 * такого протухшего кэша, бьётся об {@code FOREIGN KEY constraint failed} — ровно то, что
 * показал живой репорт (см. коммит, добавивший {@code Singleton.instance.reload*} вызовы в
 * {@code ResetDataActivity.runHardWipe}).
 */
@RunWith(RobolectricTestRunner::class)
class HardWipeCacheInvalidationTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    @Throws(Exception::class)
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)

        // getWritableDatabase выше сам создал схему и один раз честно прогнал
        // WalletFillData.InsertFirstDictionary (WalletDbHelper.onCreate) — но её реальные
        // PNG-иконки (WalletFillData.InsertIconEntry рендерит настоящие drawable в BLOB) не
        // помещаются в CursorWindow из-под Robolectric ("Row too big to fit into
        // CursorWindow" на LoadIcons) — это ограничение самого харнесса, к делу не
        // относится. Тесту важен только факт "иконка/валюта заведены сидом, и их _id
        // детерминирован порядком сидирования" — заводим свой лёгкий эквивалент вместо
        // тяжёлого InsertFirstDictionary.
        for (table in ALL_TABLES) db.execSQL("DELETE FROM " + table)
        db.execSQL("DELETE FROM sqlite_sequence")

        // Симулируем «повидавшую виды» установку: к моменту сидирования в icon уже есть
        // чужие строки (в реальности — иконки, накопленные за месяцы использования до
        // хард-вайпа). Из-за этого сид-иконка "unknown" получает _id ЗАВЕДОМО выше
        // диапазона, который тот же сид заведёт на пустой таблице ПОСЛЕ вайпа — ровно та
        // яма, из-за которой протухший Singleton.iconList бьётся об FK на реальном
        // устройстве. На честной "с нуля" установке первый и повторный сид дали бы один и
        // тот же _id, и баг было бы нечем воспроизвести детерминированно.
        for (i in 0 until 5) insertIcon("legacy_icon_" + i)
        seedMinimal()
    }

    private fun insertIcon(name: String): Long {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    /** Лёгкий эквивалент нужной части WalletFillData.InsertFirstDictionary — см. комментарий
     *  в setUp про CursorWindow. */
    private fun seedMinimal() {
        val unknownIconId = insertIcon("unknown")

        val currency = ContentValues()
        currency.put(WalletContract.CurrencyEntry.COLUMN_NAME, "Euro")
        currency.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "EUR")
        currency.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, unknownIconId)
        currency.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "€")
        db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, currency)
    }

    /** Singleton — статическое поле, переживает JUnit/Robolectric между тестами метода. */
    @Throws(Exception::class)
    private fun resetSingleton() {
        for (f in arrayOf(
            "dbHelper", "readableDatabase", "writeableDatabase",
            "iconList", "accounts", "currencyList"
        )) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList())
    }

    /** То же самое, что ResetDataActivity.runHardWipe делает в транзакции — без reload* следом. */
    private fun hardWipeAndReseed() {
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            for (table in ALL_TABLES) db.execSQL("DELETE FROM " + table)
            db.execSQL("DELETE FROM sqlite_sequence")
            seedMinimal() // без legacy-иконок на этот раз — как настоящий reseed на пустой БД
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun importPayload(): ByteArrayInputStream {
        val json = "{\"accounts\":[{\"name\":\"Test Wallet\",\"currency\":{\"iso\":\"EUR\"}}]}"
        return ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Раньше здесь ждали {@code FOREIGN KEY constraint failed}: импорт брал {@code icon_id} из
     * протухшего кэша. С android-wallet#50 иконку счёта импорт ищет в базе
     * ({@code WalletImportData.LiveIconIdOrUnknown}) — протухший кэш иконок ему больше не мешает.
     */
    @Test
    @Throws(Exception::class)
    fun importAfterHardWipe_withoutCacheReload_takesLiveIconFromDb() {
        // Прогреваем Singleton.iconList ДО вайпа — так же, как ImportActivity успевает
        // прочитать иконки на предыдущем экране до открытия ResetDataActivity.
        Singleton.instance.getIconList(context)

        hardWipeAndReseed() // без reload* — кэш иконок протухший

        val result = WalletImportData.FillOperationDataFromJson(context, importPayload())

        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty())
        assertEquals(1, result.imported)
    }

    @Test
    @Throws(Exception::class)
    fun importAfterHardWipe_withCacheReload_succeeds() {
        Singleton.instance.getIconList(context)

        hardWipeAndReseed()

        // Фикс из ResetDataActivity.runHardWipe: без этих вызовов выше кидает FK-исключение.
        Singleton.instance.reloadIconList(context)
        Singleton.instance.reloadAccounts(context)
        Singleton.instance.reloadCurrencyList(context)
        Singleton.instance.reloadCurrencyPairList(context)

        val result = WalletImportData.FillOperationDataFromJson(context, importPayload())

        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty())
        assertEquals(1, result.imported)
    }

    companion object {
        /** Таблицы, которые чистит ResetDataActivity.onHardWipe — тот же список, что ALL_TABLES там. */
        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation"
        )
    }
}
