package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

/**
 * openspec optional-icon-upload, задачи 3.1–3.8: шаг отправки своих иконок
 * ([SyncEngine.pushOwnIcons]) и флаг сервера из pull ([SyncEngine.applyServerIconFlag]).
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineIconUploadTest {

    companion object {
        private val SYNC_TABLES = arrayOf(
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link",
        )
    }

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private lateinit var savedSleeper: SyncEngine.Sleeper

    /** Транспорт без сети: blob и push отвечают тем, что задал тест. */
    private open class FakeApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        val serverBlobs = HashSet<String>()
        val pushed = ArrayList<JSONObject>()
        var headRequests = 0
        var uploads = 0
        var uploadStatus = 200
        var uploadBody: JSONObject? = null
        var pushStatus = 200
        var pushBody = JSONObject()

        /** Сколько первых HEAD обрываются, как сеть. */
        var failHeads = 0

        @Throws(IOException::class)
        override fun blobExists(sha256Hex: String): Boolean {
            headRequests++
            if (failHeads > 0) {
                failHeads--
                throw IOException("connection reset")
            }
            return serverBlobs.contains(sha256Hex)
        }

        override fun uploadBlob(bytes: ByteArray): SyncApiClient.HttpResult {
            uploads++
            val sha = IconSubstitutes.sha256Hex(bytes)
            if (uploadStatus == 200) serverBlobs.add(sha)
            var body = uploadBody
            if (body == null) {
                body = JSONObject()
                try {
                    body.put("sha256", sha)
                } catch (e: Exception) {
                    throw AssertionError(e)
                }
            }
            return SyncApiClient.HttpResult(uploadStatus, body)
        }

        override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
            pushed.add(changes)
            return SyncApiClient.HttpResult(pushStatus, pushBody)
        }

        override fun downloadBlob(sha256Hex: String): ByteArray {
            throw AssertionError("не вызывается")
        }

        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            throw AssertionError("не вызывается")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        // Сид чистой базы — не предмет теста: иконки сида мягко удалены, всё «уже отправлено».
        db.execSQL("UPDATE icon SET deleted_at = 1")
        for (t in SYNC_TABLES) db.execSQL("UPDATE $t SET dirty = 0 WHERE dirty <> 0")
        savedSleeper = SyncEngine.sleeper
        SyncEngine.sleeper = SyncEngine.Sleeper { }
        AppSettings.setIconUploadEnabled(context, false)
        AppSettings.setServerIconUploadAllowed(context, false)
    }

    @After
    fun tearDown() {
        SyncEngine.sleeper = savedSleeper
        helper.close()
    }

    // ── Успех ───────────────────────────────────────────────────────────────────

    @Test
    fun ownIcon_uploadedPushedAndBecomesServerIcon() {
        val own = icon("Моя картинка", byteArrayOf(1, 2, 3), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        val log = SyncLog()

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log))

        assertEquals(1, api.uploads)
        assertEquals(1, api.pushed.size)
        val row = api.pushed[0].getJSONArray("icon").getJSONObject(0)
        assertEquals(uuidOf(own), row.getString("uuid"))
        assertEquals("Моя картинка", row.getString("name"))
        assertEquals(IconSubstitutes.sha256Hex(byteArrayOf(1, 2, 3)), row.getString("content_sha256"))
        assertEquals(1, api.pushed[0].length()) // только icon — счета уходят отдельно
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(own, "origin"))
        assertEquals(0, intOf(own, "dirty"))
        assertTrue(log.getProblems().isEmpty())
        assertTrue(log.toDisplayText().contains("Отправлено") || log.toDisplayText().contains("Sent"))
    }

    @Test
    fun acceptedIcon_isReferencedByItsOwnUuidInAccountPush() {
        val own = icon("Кино", byteArrayOf(4, 5), WalletContract.IconEntry.ORIGIN_LOCAL)
        val acc = account("Кошелёк", own)
        val api = FakeApi(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        api.pushed.clear()
        SyncEngine.pushDirty(db, api, SyncLog())

        val accountRow = api.pushed[0].getJSONArray("account").getJSONObject(0)
        assertEquals(uuidOf(own), accountRow.getString("icon_uuid"))
        assertEquals(0, intOfTable("account", acc, "dirty"))
    }

    @Test
    fun blobAlreadyOnServer_isNotUploadedAgain() {
        val image = byteArrayOf(7, 7, 7)
        icon("Уже там", image, WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.serverBlobs.add(IconSubstitutes.sha256Hex(image))

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(0, api.uploads)
        assertEquals(1, api.pushed.size)
    }

    @Test
    fun sameImageAsServerIcon_isNotSent() {
        val image = byteArrayOf(9, 9)
        icon("Сервер", image, WalletContract.IconEntry.ORIGIN_SERVER)
        val own = icon("Своя копия", image, WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, SyncLog()))

        assertEquals(0, api.headRequests)
        assertEquals(0, api.pushed.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"))
    }

    @Test
    fun deletedOwnIcon_isNotSent() {
        val own = icon("Удалённая", byteArrayOf(3), WalletContract.IconEntry.ORIGIN_LOCAL)
        db.execSQL("UPDATE icon SET deleted_at = 5 WHERE _id = $own")
        val api = FakeApi(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(0, api.headRequests)
        assertEquals(0, api.pushed.size)
    }

    // ── Отказ по флагу ─────────────────────────────────────────────────────────

    @Test
    fun blob403Disabled_refusesAndKeepsIconOwn() {
        val own = icon("Моя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.uploadStatus = 403
        api.uploadBody = JSONObject().put("error", "icon_upload_disabled")

        assertEquals(SyncEngine.IconUploadResult.REFUSED, SyncEngine.pushOwnIcons(db, api, SyncLog()))

        assertEquals(1, api.uploads) // без повторов
        assertEquals(0, api.pushed.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"))
    }

    @Test
    fun iconsRejectedInPush_refusesAndKeepsIconOwn() {
        val own = icon("Моя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        icon("Вторая", byteArrayOf(2), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.pushBody = JSONObject().put("seq", 5).put("conflicts", JSONArray())
            .put(
                "icons_rejected", JSONObject().put("error", "icon_upload_disabled")
                    .put("uuids", JSONArray().put(uuidOf(own)))
            )

        assertEquals(SyncEngine.IconUploadResult.REFUSED, SyncEngine.pushOwnIcons(db, api, SyncLog()))

        assertEquals("шаг прерван на первой иконке", 1, api.pushed.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"))
    }

    @Test
    fun disableIconUpload_turnsSettingsOffAndNotes() {
        AppSettings.setIconUploadEnabled(context, true)
        AppSettings.setServerIconUploadAllowed(context, true)
        val log = SyncLog()

        SyncEngine.disableIconUpload(context, log)

        assertFalse(AppSettings.isIconUploadEnabled(context))
        assertFalse(AppSettings.isServerIconUploadAllowed(context))
        assertTrue("заметка, не проблема", log.getProblems().isEmpty())
        assertFalse(log.isEmpty())
    }

    // ── Сбои отдельной иконки ──────────────────────────────────────────────────

    @Test
    fun push409ForOneIcon_skipsItAndSendsNext() {
        val first = icon("Занятое имя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        val second = icon("Свободное", byteArrayOf(2), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = object : FakeApi(context) {
            override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
                super.push(batchId, changes)
                try {
                    val uuid = changes.getJSONArray("icon").getJSONObject(0).getString("uuid")
                    if (uuid == uuidOf(first)) {
                        return SyncApiClient.HttpResult(409, JSONObject().put("error", "duplicate_key"))
                    }
                    return SyncApiClient.HttpResult(200, JSONObject())
                } catch (e: Exception) {
                    throw AssertionError(e)
                }
            }
        }
        val log = SyncLog()

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log))

        assertEquals(2, api.pushed.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(first, "origin"))
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(second, "origin"))
        assertTrue("не «частично»", log.getProblems().isEmpty())
        assertTrue(log.toDisplayText().contains("Занятое имя"))
    }

    @Test
    fun blob5xxAfterRetries_skipsIcon() {
        val own = icon("Моя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.uploadStatus = 503
        api.uploadBody = JSONObject()

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, SyncLog()))

        assertEquals(SyncEngine.BLOB_UPLOAD_ATTEMPTS, api.uploads)
        assertEquals(0, api.pushed.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"))
    }

    @Test
    fun transientNetworkDrop_retriedAndSucceeds() {
        val own = icon("Моя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.failHeads = 1

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, SyncLog()))

        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(own, "origin"))
    }

    @Test
    fun networkGoneAfterRetries_throwsAsNoNetwork() {
        val own = icon("Моя", byteArrayOf(1), WalletContract.IconEntry.ORIGIN_LOCAL)
        val api = FakeApi(context)
        api.failHeads = SyncEngine.BLOB_UPLOAD_ATTEMPTS

        try {
            SyncEngine.pushOwnIcons(db, api, SyncLog())
            fail("ожидался IOException")
        } catch (expected: IOException) {
            // проход завершится «нет сети»
        }
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"))
    }

    // ── Флаг сервера из pull ───────────────────────────────────────────────────

    @Test
    fun serverFlag_allowedIsRemembered() {
        SyncEngine.applyServerIconFlag(context, true, SyncLog())
        assertTrue(AppSettings.isServerIconUploadAllowed(context))
        assertFalse("выбор пользователя не меняется", AppSettings.isIconUploadEnabled(context))
        assertFalse(AppSettings.shouldUploadOwnIcons(context))
    }

    @Test
    fun serverFlag_turnedOffWhileEnabled_disablesAndNotes() {
        AppSettings.setServerIconUploadAllowed(context, true)
        AppSettings.setIconUploadEnabled(context, true)
        assertTrue(AppSettings.shouldUploadOwnIcons(context))
        val log = SyncLog()

        SyncEngine.applyServerIconFlag(context, false, log)

        assertFalse(AppSettings.isIconUploadEnabled(context))
        assertFalse(AppSettings.isServerIconUploadAllowed(context))
        assertFalse(log.isEmpty())
        assertTrue(log.getProblems().isEmpty())
    }

    @Test
    fun serverFlag_offWhileSettingOff_noNote() {
        val log = SyncLog()
        SyncEngine.applyServerIconFlag(context, false, log)
        assertTrue(log.isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun icon(name: String, image: ByteArray, origin: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("image", image)
        cv.put("icon_type", 0)
        cv.put("origin", origin)
        cv.put("uuid", UUID.randomUUID().toString())
        cv.put("dirty", 0)
        return db.insertOrThrow("icon", null, cv)
    }

    private fun account(name: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("uuid", UUID.randomUUID().toString())
        return db.insertOrThrow("account", null, cv)
    }

    private fun uuidOf(iconId: Long): String {
        val c = db.rawQuery("SELECT uuid FROM icon WHERE _id = ?", arrayOf(iconId.toString()))
        try {
            assertTrue(c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun intOf(iconId: Long, column: String): Int = intOfTable("icon", iconId, column)

    private fun intOfTable(table: String, id: Long, column: String): Int {
        val c = db.rawQuery("SELECT $column FROM $table WHERE _id = ?", arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
