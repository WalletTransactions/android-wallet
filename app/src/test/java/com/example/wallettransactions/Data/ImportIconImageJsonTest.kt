package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Singleton
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Своя картинка счёта в файловом JSON: поле {@code accounts[].icon_image} (base64) — импорт,
 * приведение к 256×256, защита серверных иконок и круг «экспорт → импорт».
 */
@RunWith(RobolectricTestRunner::class)
class ImportIconImageJsonTest {

    companion object {
        private val ALL_TABLES = arrayOf(
                "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
                "account_operating", "account_asset", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "operation_template_step", "operation_template"
        )

        /** base64 PNG заданного размера. */
        private fun png(w: Int, h: Int): String {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF3366CC.toInt())
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        private fun account(name: String, iconFields: String): String =
                "{\"accounts\":[{\"name\":\"" + name + "\"," + iconFields + "}]}"
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    @Throws(Exception::class)
    fun setUp() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        db.execSQL("DELETE FROM sqlite_sequence")
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun importJson(json: String): WalletImportData.ImportResult {
        return WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun scalar(sql: String, vararg args: String): Int {
        val c = db.rawQuery(sql, args)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun iconBlob(name: String): ByteArray? {
        val c = db.rawQuery("SELECT image FROM icon WHERE name = ? AND deleted_at IS NULL", arrayOf(name))
        try {
            return if (c.moveToFirst()) c.getBlob(0) else null
        } finally {
            c.close()
        }
    }

    @Test
    fun iconImage_createsOwnIconNamedByIconField_andAccountUsesIt() {
        val r = importJson(account("Продукты",
                "\"icon\":\"Корзина\",\"icon_image\":\"" + png(256, 256) + "\""))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, scalar("SELECT count(*) FROM icon WHERE name = 'Корзина'"
                + " AND origin = " + WalletContract.IconEntry.ORIGIN_LOCAL + " AND icon_type = 0"))
        assertEquals("счёт ссылается на созданную иконку", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Продукты' AND i.name = 'Корзина'"))
    }

    @Test
    fun iconImage_withoutIconName_isNamedAfterAccount() {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""))

        assertNotNull(iconBlob("Спорт"))
        assertEquals(1, scalar("SELECT count(*) FROM account a JOIN icon i ON i._id = a.icon_id"
                + " WHERE a.name = 'Спорт' AND i.name = 'Спорт'"))
    }

    @Test
    fun iconImage_anySizeIsNormalizedTo256Square() {
        importJson(account("Широкая", "\"icon_image\":\"" + png(600, 300) + "\""))
        importJson(account("Мелкая", "\"icon_image\":\"" + png(32, 32) + "\""))

        for (name in arrayOf("Широкая", "Мелкая")) {
            val blob = iconBlob(name)
            assertNotNull(name, blob)
            val stored = BitmapFactory.decodeByteArray(blob, 0, blob!!.size)
            assertNotNull(name, stored)
            assertEquals(name, WalletImportData.ICON_SIZE_PX.toLong(), stored.width.toLong())
            assertEquals(name, WalletImportData.ICON_SIZE_PX.toLong(), stored.height.toLong())
        }
    }

    @Test
    fun iconImage_dataUriPrefixIsAccepted() {
        val r = importJson(account("Кафе",
                "\"icon_image\":\"data:image/png;base64," + png(64, 64) + "\""))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertNotNull(iconBlob("Кафе"))
    }

    @Test
    fun iconImage_broken_accountStillImportedWithUnknownIcon() {
        // Слишком большая строка: детерминированная защита. Недекодируемые байты здесь не годятся —
        // теневой BitmapFactory Robolectric «декодирует» что угодно, а настоящий Android вернул бы
        // null; этот путь смотрим на телефоне.
        val huge = StringBuilder()
        for (i in 0 until 1_500_001) huge.append('A')
        val r = importJson(account("Битый", "\"icon_image\":\"" + huge + "\""))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals("счёт заведён", 1, scalar("SELECT count(*) FROM account WHERE name = 'Битый'"))
        assertEquals("иконка «неизвестно»", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Битый' AND i.name = 'unknown'"))
        assertEquals("своя иконка не заведена", 0, scalar("SELECT count(*) FROM icon WHERE name = 'Битый'"))
    }

    @Test
    fun iconImage_notBase64_accountStillImportedWithNamedOrUnknownIcon() {
        importJson(account("Мусор", "\"icon\":\"Нет такой\",\"icon_image\":\"@@@ not base64 @@@\""))

        assertEquals("счёт заведён", 1, scalar("SELECT count(*) FROM account WHERE name = 'Мусор'"))
        assertEquals("такой иконки нет — «неизвестно», как раньше", 1, scalar("SELECT count(*) FROM account a"
                + " JOIN icon i ON i._id = a.icon_id WHERE a.name = 'Мусор' AND i.name = 'unknown'"))
        assertEquals(0, scalar("SELECT count(*) FROM icon WHERE name = 'Нет такой'"))
    }

    @Test
    fun iconImage_doesNotOverwriteServerIcon() {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "Groceries")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(1, 2, 3))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 0)
        cv.put(WalletContract.IconEntry.COLUMN_UUID, "server-icon")
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_SERVER)
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)

        val r = importJson(account("Еда",
                "\"icon\":\"Groceries\",\"icon_image\":\"" + png(64, 64) + "\""))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(3, iconBlob("Groceries")!!.size)
        assertEquals("новой иконки с тем же именем нет", 1,
                scalar("SELECT count(*) FROM icon WHERE name = 'Groceries'"))
        assertEquals(1, scalar("SELECT count(*) FROM account a JOIN icon i ON i._id = a.icon_id"
                + " WHERE a.name = 'Еда' AND i.name = 'Groceries'"))
    }

    @Test
    fun iconImage_reimportReplacesOwnIconImage() {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""))
        val first = iconBlob("Спорт")
        importJson(account("Спорт", "\"icon_image\":\"" + png(100, 100) + "\""))

        assertEquals("одна иконка, не две", 1, scalar("SELECT count(*) FROM icon WHERE name = 'Спорт'"))
        assertNotNull(first)
        assertNotNull(iconBlob("Спорт"))
    }

    @Test
    fun export_writesImageOnlyForOwnIcons_andRoundTrips() {
        importJson(account("Спорт", "\"icon_image\":\"" + png(256, 256) + "\""))

        val exported = WalletExportData.BuildExportJson(db)
        var sport: JSONObject? = null
        for (i in 0 until exported.getJSONArray("accounts").length()) {
            val a = exported.getJSONArray("accounts").getJSONObject(i)
            if (a.getString("name") == "Спорт") sport = a
        }
        assertNotNull(sport)
        assertTrue(sport!!.has("icon_image"))
        assertFalse("заглушка unknown без картинки", exported.toString().contains("\"icon\":\"unknown\",\"icon_image\""))

        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)

        val r = importJson(exported.toString())
        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertNotNull("картинка пережила круг", iconBlob("Спорт"))
    }
}
