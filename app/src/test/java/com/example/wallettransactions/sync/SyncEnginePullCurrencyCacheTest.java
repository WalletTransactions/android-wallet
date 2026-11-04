package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Правки валют с веба (android-wallet#5, пункт 4.6) видны в кэше {@link Singleton}
 * сразу после pull — список «Валюты» и пикеры читают именно его, без перезапуска.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEnginePullCurrencyCacheTest {

    private static final String LEDGER = "4e195796-4f2a-492d-b6b9-4761d6b0d25b";
    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String GEL = "736cb366-1336-4b33-560d-5b6fad9f0236";
    private static final String KES = "3d180d39-d091-72ff-498e-15c7124fea43";
    private static final String CHF = "86243e00-85e1-4af6-91cc-dbf2c0376ab2";

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
        new File(context.getNoBackupFilesDir(), PullDeferredRows.FILE_NAME).delete();
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        db.insertOrThrow("icon", null, icon);
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new CurrencyPairList());
    }

    private static JSONObject currency(String uuid, String iso, String name, boolean deleted) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER)
                .put("name", name).put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2).put("show_in_equivalent", true)
                .put("deleted_at", deleted ? "2026-11-04T17:34:29Z" : JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static JSONObject currencies(JSONObject... rows) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject r : rows) arr.put(r);
        return new JSONObject().put("currency", arr);
    }

    /** Первый синк: GEL и KES в базе и в кэше, курсоры на голове. */
    private void syncedTwoCurrencies(CurrencyPullApi api) throws Exception {
        api.steps.add((l, r) -> CurrencyPullApi.page(10,
                currencies(currency(GEL, "GEL", "Georgian lari", false),
                        currency(KES, "KES", "Kenyan shilling", false)), r));
        AppSettings.setSyncLedgerId(context, LEDGER);
        SyncEngine.pullAll(db, api, context, new SyncLog());
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void pull_newRenamedDeletedCurrency_visibleInSingletonCache() throws Exception {
        CurrencyPullApi api = new CurrencyPullApi(context);
        // Кэш загружен ДО синка — как при открытом приложении.
        CurrencyList cache = Singleton.instance.getCurrencyList(context);
        syncedTwoCurrencies(api);
        assertNotNull(cache.getCurrencyByISO("GEL"));
        assertNotNull(cache.getCurrencyByISO("KES"));

        // С веба: создана CHF, GEL переименована, KES удалена.
        api.steps.add((l, r) -> CurrencyPullApi.page(13,
                currencies(currency(CHF, "CHF", "Швейцарский франк", false),
                        currency(GEL, "GEL", "Лари", false),
                        currency(KES, "KES", "Kenyan shilling", true)), r));
        SyncEngine.pullAll(db, api, context, new SyncLog());
        ShadowLooper.idleMainLooper();

        CurrencyList after = Singleton.instance.getCurrencyList(context);
        assertNotNull("новая валюта с веба", after.getCurrencyByISO("CHF"));
        assertEquals("Лари", after.getCurrencyByISO("GEL").getName());
        assertNull("удалённая на вебе", after.getCurrencyByISO("KES"));
        assertEquals(2, after.size());
    }

    private static final class CurrencyPullApi extends SyncApiClient {
        interface Step {
            PullResult answer(long ledgerSeq, long referenceSeq) throws Exception;
        }

        final Deque<Step> steps = new ArrayDeque<>();

        CurrencyPullApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) throws IOException {
            Step step = steps.poll();
            if (step == null) return page(ledgerSeq, new JSONObject(), referenceSeq);
            try {
                return step.answer(ledgerSeq, referenceSeq);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        static PullResult page(long nextSeq, JSONObject ledgerChanges, long referenceSeq) {
            return new PullResult(new PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }
    }
}
