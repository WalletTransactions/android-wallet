package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

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
@RunWith(RobolectricTestRunner.class)
public class HardWipeCacheInvalidationTest {

    /** Таблицы, которые чистит ResetDataActivity.onHardWipe — тот же список, что ALL_TABLES там. */
    private static final String[] ALL_TABLES = {
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

        // getWritableDatabase выше сам создал схему и один раз честно прогнал
        // WalletFillData.InsertFirstDictionary (WalletDbHelper.onCreate) — но её реальные
        // PNG-иконки (WalletFillData.InsertIconEntry рендерит настоящие drawable в BLOB) не
        // помещаются в CursorWindow из-под Robolectric ("Row too big to fit into
        // CursorWindow" на LoadIcons) — это ограничение самого харнесса, к делу не
        // относится. Тесту важен только факт "иконка/валюта заведены сидом, и их _id
        // детерминирован порядком сидирования" — заводим свой лёгкий эквивалент вместо
        // тяжёлого InsertFirstDictionary.
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");

        // Симулируем «повидавшую виды» установку: к моменту сидирования в icon уже есть
        // чужие строки (в реальности — иконки, накопленные за месяцы использования до
        // хард-вайпа). Из-за этого сид-иконка "unknown" получает _id ЗАВЕДОМО выше
        // диапазона, который тот же сид заведёт на пустой таблице ПОСЛЕ вайпа — ровно та
        // яма, из-за которой протухший Singleton.iconList бьётся об FK на реальном
        // устройстве. На честной "с нуля" установке первый и повторный сид дали бы один и
        // тот же _id, и баг было бы нечем воспроизвести детерминированно.
        for (int i = 0; i < 5; i++) insertIcon("legacy_icon_" + i);
        seedMinimal();
    }

    private long insertIcon(String name) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        return db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    /** Лёгкий эквивалент нужной части WalletFillData.InsertFirstDictionary — см. комментарий
     *  в setUp про CursorWindow. */
    private void seedMinimal() {
        long unknownIconId = insertIcon("unknown");

        ContentValues currency = new ContentValues();
        currency.put(WalletContract.CurrencyEntry.COLUMN_NAME, "Euro");
        currency.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "EUR");
        currency.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, unknownIconId);
        currency.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "€");
        db.insert(WalletContract.CurrencyEntry.TABLE_NAME, null, currency);
    }

    /** Singleton — статическое поле, переживает JUnit/Robolectric между тестами метода. */
    private void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList());
    }

    /** То же самое, что ResetDataActivity.runHardWipe делает в транзакции — без reload* следом. */
    private void hardWipeAndReseed() {
        db.beginTransaction();
        try {
            db.execSQL("PRAGMA defer_foreign_keys = ON");
            for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
            db.execSQL("DELETE FROM sqlite_sequence");
            seedMinimal(); // без legacy-иконок на этот раз — как настоящий reseed на пустой БД
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private ByteArrayInputStream importPayload() {
        String json = "{\"accounts\":[{\"name\":\"Test Wallet\",\"currency\":{\"iso\":\"EUR\"}}]}";
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Раньше здесь ждали {@code FOREIGN KEY constraint failed}: импорт брал {@code icon_id} из
     * протухшего кэша. С android-wallet#50 иконку счёта импорт ищет в базе
     * ({@code WalletImportData.LiveIconIdOrUnknown}) — протухший кэш иконок ему больше не мешает.
     */
    @Test
    public void importAfterHardWipe_withoutCacheReload_takesLiveIconFromDb() throws Exception {
        // Прогреваем Singleton.iconList ДО вайпа — так же, как ImportActivity успевает
        // прочитать иконки на предыдущем экране до открытия ResetDataActivity.
        Singleton.instance.getIconList(context);

        hardWipeAndReseed(); // без reload* — кэш иконок протухший

        WalletImportData.ImportResult result =
                WalletImportData.FillOperationDataFromJson(context, importPayload());

        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty());
        assertEquals(1, result.imported);
    }

    @Test
    public void importAfterHardWipe_withCacheReload_succeeds() throws Exception {
        Singleton.instance.getIconList(context);

        hardWipeAndReseed();

        // Фикс из ResetDataActivity.runHardWipe: без этих вызовов выше кидает FK-исключение.
        Singleton.instance.reloadIconList(context);
        Singleton.instance.reloadAccounts(context);
        Singleton.instance.reloadCurrencyList(context);
        Singleton.instance.reloadCurrencyPairList(context);

        WalletImportData.ImportResult result =
                WalletImportData.FillOperationDataFromJson(context, importPayload());

        assertTrue("skipped: " + result.skipped, result.skipped.isEmpty());
        assertEquals(1, result.imported);
    }
}
