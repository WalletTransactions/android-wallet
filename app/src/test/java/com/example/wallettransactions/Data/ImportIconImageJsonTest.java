package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Singleton;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Своя картинка счёта в файловом JSON: поле {@code accounts[].icon_image} (base64) — импорт,
 * приведение к 256×256, защита серверных иконок и круг «экспорт → импорт».
 */
@RunWith(RobolectricTestRunner.class)
public class ImportIconImageJsonTest {

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
            "account_operating", "account_asset", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private WalletImportData.ImportResult importJson(String json) throws Exception {
        return WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** base64 PNG заданного размера. */
    private static String png(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0xFF3366CC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String account(String name, String iconFields) {
        return "{\"accounts\":[{\"name\":\"" + name + "\"," + iconFields + "}]}";
    }

    private int scalar(String sql, String... args) {
        Cursor c = db.rawQuery(sql, args);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private byte[] iconBlob(String name) {
        Cursor c = db.rawQuery("SELECT image FROM icon WHERE name = ? AND deleted_at IS NULL", new String[]{name});
        try {
            return c.moveToFirst() ? c.getBlob(0) : null;
        } finally {
            c.close();
        }
    }

    @Test
    public void iconImage_createsOwnIconNamedByIconField_andAccountUsesIt() throws Exception {
        WalletImportData.ImportResult r = importJson(account("Продукты",
                "\"icon\":\"Корзина\",\"icon_image\":\"" + png(256, 256) + "\""));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, scalar("SELECT count(*) FROM icon WHERE name = 'Корзина'"
                + " AND origin = " + WalletContract.IconEntry.ORIGIN_LOCAL + " AND icon_type = 0"));
        assertEquals("счёт ссылается на созданную иконку", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Продукты' AND i.name = 'Корзина'"));
    }

    @Test
    public void iconImage_withoutIconName_isNamedAfterAccount() throws Exception {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""));

        assertNotNull(iconBlob("Спорт"));
        assertEquals(1, scalar("SELECT count(*) FROM account a JOIN icon i ON i._id = a.icon_id"
                + " WHERE a.name = 'Спорт' AND i.name = 'Спорт'"));
    }

    @Test
    public void iconImage_anySizeIsNormalizedTo256Square() throws Exception {
        importJson(account("Широкая", "\"icon_image\":\"" + png(600, 300) + "\""));
        importJson(account("Мелкая", "\"icon_image\":\"" + png(32, 32) + "\""));

        for (String name : new String[]{"Широкая", "Мелкая"}) {
            byte[] blob = iconBlob(name);
            assertNotNull(name, blob);
            Bitmap stored = BitmapFactory.decodeByteArray(blob, 0, blob.length);
            assertNotNull(name, stored);
            assertEquals(name, WalletImportData.ICON_SIZE_PX, stored.getWidth());
            assertEquals(name, WalletImportData.ICON_SIZE_PX, stored.getHeight());
        }
    }

    @Test
    public void iconImage_dataUriPrefixIsAccepted() throws Exception {
        WalletImportData.ImportResult r = importJson(account("Кафе",
                "\"icon_image\":\"data:image/png;base64," + png(64, 64) + "\""));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertNotNull(iconBlob("Кафе"));
    }

    @Test
    public void iconImage_broken_accountStillImportedWithUnknownIcon() throws Exception {
        // Слишком большая строка: детерминированная защита. Недекодируемые байты здесь не годятся —
        // теневой BitmapFactory Robolectric «декодирует» что угодно, а настоящий Android вернул бы
        // null; этот путь смотрим на телефоне.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1_500_001; i++) huge.append('A');
        WalletImportData.ImportResult r = importJson(account("Битый", "\"icon_image\":\"" + huge + "\""));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals("счёт заведён", 1, scalar("SELECT count(*) FROM account WHERE name = 'Битый'"));
        assertEquals("иконка «неизвестно»", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Битый' AND i.name = 'unknown'"));
        assertEquals("своя иконка не заведена", 0, scalar("SELECT count(*) FROM icon WHERE name = 'Битый'"));
    }

    @Test
    public void iconImage_notBase64_accountStillImportedWithNamedOrUnknownIcon() throws Exception {
        importJson(account("Мусор", "\"icon\":\"Нет такой\",\"icon_image\":\"@@@ not base64 @@@\""));

        assertEquals("счёт заведён", 1, scalar("SELECT count(*) FROM account WHERE name = 'Мусор'"));
        assertEquals("такой иконки нет — «неизвестно», как раньше", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Мусор' AND i.name = 'unknown'"));
        assertEquals(0, scalar("SELECT count(*) FROM icon WHERE name = 'Нет такой'"));
    }

    @Test
    public void iconImage_doesNotOverwriteServerIcon() throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "Groceries");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{1, 2, 3});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 0);
        cv.put(WalletContract.IconEntry.COLUMN_UUID, "server-icon");
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_SERVER);
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);

        WalletImportData.ImportResult r = importJson(account("Еда",
                "\"icon\":\"Groceries\",\"icon_image\":\"" + png(64, 64) + "\""));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(3, iconBlob("Groceries").length);
        assertEquals("новой иконки с тем же именем нет", 1,
                scalar("SELECT count(*) FROM icon WHERE name = 'Groceries'"));
        assertEquals(1, scalar("SELECT count(*) FROM account a JOIN icon i ON i._id = a.icon_id"
                + " WHERE a.name = 'Еда' AND i.name = 'Groceries'"));
    }

    @Test
    public void iconImage_reimportReplacesOwnIconImage() throws Exception {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""));
        byte[] first = iconBlob("Спорт");
        importJson(account("Спорт", "\"icon_image\":\"" + png(100, 100) + "\""));

        assertEquals("одна иконка, не две", 1, scalar("SELECT count(*) FROM icon WHERE name = 'Спорт'"));
        assertNotNull(first);
        assertNotNull(iconBlob("Спорт"));
    }

    @Test
    public void export_writesImageOnlyForOwnIcons_andRoundTrips() throws Exception {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""));

        JSONObject exported = WalletExportData.BuildExportJson(db);
        JSONObject sport = null;
        for (int i = 0; i < exported.getJSONArray("accounts").length(); i++) {
            JSONObject a = exported.getJSONArray("accounts").getJSONObject(i);
            if (a.getString("name").equals("Спорт")) sport = a;
        }
        assertNotNull(sport);
        assertTrue(sport.has("icon_image"));
        assertFalse("заглушка unknown без картинки", exported.toString().contains("\"icon\":\"unknown\",\"icon_image\""));

        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);

        WalletImportData.ImportResult r = importJson(exported.toString());
        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertNotNull("картинка пережила круг", iconBlob("Спорт"));
    }
}
