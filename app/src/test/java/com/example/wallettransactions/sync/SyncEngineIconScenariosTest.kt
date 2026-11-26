package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbHelper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

/**
 * openspec optional-icon-upload — сценарии на стыке своих и серверных иконок: та же картинка,
 * то же имя, два устройства одного леджера отправляют одну иконку одновременно, потерянный
 * ответ. Сервер — модель с состоянием ([ModelServer]): blob по SHA-256, иконки леджера с
 * уникальным именем (`uq_icon_name_ledger` → `409 duplicate_key`) и upsert по uuid,
 * как wallet-api. Второе устройство — это состояние сервера плюс строка pull
 * ([SyncEngine.applyIcon]).
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineIconScenariosTest {

    companion object {
        private val SYNC_TABLES = arrayOf(
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link", "counterparty",
        )

        private fun pullRow(uuid: String, name: String, image: ByteArray): JSONObject =
            JSONObject().put("uuid", uuid).put("name", name)
                .put("content_sha256", IconSubstitutes.sha256Hex(image)).put("icon_type", 0)
                .put("ledger_id", UUID.randomUUID().toString()).put("seq", 10)
                .put("deleted_at", JSONObject.NULL).put("updated_at", "2026-11-22T12:00:00Z")
                .put("updated_by", JSONObject.NULL)
    }

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private lateinit var savedSleeper: SyncEngine.Sleeper

    /** Иконка леджера на сервере. */
    private class ServerIcon(val name: String, val sha256: String)

    /** wallet-api в объёме шага иконок: blob, icon (upsert по uuid, уникальное имя), прочее — запись. */
    private open class ModelServer(ctx: Context) : SyncApiClient(ctx, "test-token") {
        val blobs = HashSet<String>()
        val icons = LinkedHashMap<String, ServerIcon>()
        val pushed = ArrayList<JSONObject>()
        var uploads = 0
        var heads = 0

        /** Применить следующий push иконки, но «потерять» ответ — IOException клиенту. */
        var loseNextIconResponse = false

        /** Иконка, которую отправило другое устройство леджера (вместе с её содержимым). */
        fun addIconFromOtherDevice(name: String, image: ByteArray): String {
            val uuid = UUID.randomUUID().toString()
            val sha = IconSubstitutes.sha256Hex(image)
            blobs.add(sha)
            icons[uuid] = ServerIcon(name, sha)
            return uuid
        }

        override fun blobExists(sha256Hex: String): Boolean {
            heads++
            return blobs.contains(sha256Hex)
        }

        override fun uploadBlob(bytes: ByteArray): SyncApiClient.HttpResult {
            uploads++
            val sha = IconSubstitutes.sha256Hex(bytes)
            blobs.add(sha) // ON CONFLICT DO NOTHING
            try {
                return SyncApiClient.HttpResult(200, JSONObject().put("sha256", sha))
            } catch (e: Exception) {
                throw AssertionError(e)
            }
        }

        @Throws(IOException::class)
        override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
            pushed.add(changes)
            try {
                val iconRows = changes.optJSONArray("icon")
                if (iconRows != null) {
                    for (i in 0 until iconRows.length()) {
                        val row = iconRows.getJSONObject(i)
                        val uuid = row.getString("uuid")
                        val name = row.getString("name")
                        val sha = row.getString("content_sha256")
                        if (!blobs.contains(sha)) {
                            return SyncApiClient.HttpResult(
                                409, JSONObject().put("error", "missing_reference")
                                    .put("table", "blob").put("uuid", sha)
                            )
                        }
                        for ((key, value) in icons) {
                            if (key != uuid && value.name.equals(name, ignoreCase = true)) {
                                return SyncApiClient.HttpResult(
                                    409, JSONObject().put("error", "duplicate_key")
                                        .put("constraint", "uq_icon_name_ledger")
                                )
                            }
                        }
                        icons[uuid] = ServerIcon(name, sha) // upsert по uuid
                    }
                    if (loseNextIconResponse) {
                        loseNextIconResponse = false
                        throw IOException("connection reset after commit")
                    }
                }
                return SyncApiClient.HttpResult(200, JSONObject().put("seq", pushed.size).put("conflicts", JSONArray()))
            } catch (e: JSONException) {
                throw AssertionError(e)
            }
        }

        override fun downloadBlob(sha256Hex: String): ByteArray {
            throw AssertionError("не вызывается")
        }

        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            throw AssertionError("pull моделируется через applyIcon")
        }

        fun iconPushes(): Int {
            var n = 0
            for (c in pushed) if (c.has("icon")) n++
            return n
        }

        fun lastRowOf(table: String): JSONObject? {
            for (i in pushed.indices.reversed()) {
                val rows = pushed[i].optJSONArray(table)
                if (rows != null && rows.length() > 0) return rows.getJSONObject(rows.length() - 1)
            }
            return null
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("UPDATE icon SET deleted_at = 1")
        for (t in SYNC_TABLES) db.execSQL("UPDATE $t SET dirty = 0 WHERE dirty <> 0")
        savedSleeper = SyncEngine.sleeper
        SyncEngine.sleeper = SyncEngine.Sleeper { }
    }

    @After
    fun tearDown() {
        SyncEngine.sleeper = savedSleeper
        helper.close()
    }

    // ── Своя иконка = иконка с сервера ─────────────────────────────────────────

    /** Та же картинка, другое имя: не грузится ни в этом проходе, ни в следующем; счёт — на серверную. */
    @Test
    fun ownSameImageAsServerIcon_neverUploaded_accountReferencesServerIcon() {
        val img = byteArrayOf(1, 1, 1)
        val server = icon("Кафе", img, WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val own = icon("Моё кафе", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Кошелёк", own, 1)
        val api = ModelServer(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        SyncEngine.pushDirty(db, api, SyncLog())
        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(0, api.heads)
        assertEquals(0, api.iconPushes())
        assertEquals(uuidOf("icon", server), api.lastRowOf("account")!!.getString("icon_uuid"))
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", own, "origin"))
        assertEquals(0, intOf("account", acc, "dirty"))
    }

    /** Та же картинка у иконки с сервера, удалённой только на телефоне (dirty = 1): на сервере жива — не грузим. */
    @Test
    fun ownSameImageAsLocallyDeletedServerIcon_skipped() {
        val img = byteArrayOf(2, 2)
        val server = icon("Такси", img, WalletContract.IconEntry.ORIGIN_SERVER, 1)
        db.execSQL("UPDATE icon SET deleted_at = 5 WHERE _id = $server")
        icon("Моё такси", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val api = ModelServer(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(0, api.iconPushes())
    }

    /** Та же картинка у иконки, удалённой на сервере (tombstone из pull, dirty = 0): заместителем не считается — грузим. */
    @Test
    fun ownSameImageAsServerTombstone_uploaded() {
        val img = byteArrayOf(3, 3)
        // Вставкой, не UPDATE: UPDATE deleted_at поднял бы trg_icon_dirty, и это было бы локальное удаление.
        val tomb = ContentValues()
        tomb.put("name", "Старое")
        tomb.put("image", img)
        tomb.put("icon_type", 0)
        tomb.put("origin", WalletContract.IconEntry.ORIGIN_SERVER)
        tomb.put("uuid", UUID.randomUUID().toString())
        tomb.put("deleted_at", 5L)
        tomb.put("dirty", 0)
        db.insertOrThrow("icon", null, tomb)
        val own = icon("Моё", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val api = ModelServer(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(1, api.iconPushes())
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"))
    }

    /** Две свои с одной картинкой на одном телефоне: уходит первая, вторую заменяет она же. */
    @Test
    fun twoOwnIconsSameImage_onlyFirstUploaded_secondUsesIt() {
        val img = byteArrayOf(4, 4, 4)
        val first = icon("Собака", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val second = icon("Пёс", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Корм", second, 1)
        val api = ModelServer(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        SyncEngine.pushDirty(db, api, SyncLog())

        assertEquals(1, api.uploads)
        assertEquals(1, api.iconPushes())
        assertEquals(1, api.icons.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", first, "origin"))
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", second, "origin"))
        assertEquals(uuidOf("icon", first), api.lastRowOf("account")!!.getString("icon_uuid"))
        assertEquals(0, intOf("account", acc, "dirty"))
    }

    // ── Счета, ушедшие с заместителем ──────────────────────────────────────────

    /** Счёт и валюта уже на сервере с заместителем — после приёма иконки уходят снова, с её uuid. */
    @Test
    fun acceptedIcon_resendsSyncedAccountAndCurrencyWithOwnUuid() {
        icon("unknown", byteArrayOf(9), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val own = icon("Кот", byteArrayOf(5, 5), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Кошачий", own, 0)
        val deletedAcc = account("Удалённый", own, 0)
        db.execSQL("UPDATE account SET deleted_at = 7 WHERE _id = $deletedAcc")
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = $deletedAcc")
        val cur = currency("Котокоин", "KTC", own, 0)
        val api = ModelServer(context)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        assertEquals(1, intOf("account", acc, "dirty"))
        assertEquals(1, intOf("currency", cur, "dirty"))
        assertEquals("удалённый счёт не трогаем", 0, intOf("account", deletedAcc, "dirty"))

        SyncEngine.pushDirty(db, api, SyncLog())
        assertEquals(uuidOf("icon", own), api.lastRowOf("account")!!.getString("icon_uuid"))
        assertEquals(uuidOf("icon", own), api.lastRowOf("currency")!!.getString("icon_uuid"))
        assertEquals(0, intOf("account", acc, "dirty"))
    }

    // ── Два устройства одного леджера одновременно ─────────────────────────────

    /**
     * Одна и та же иконка (имя и картинка) с двух устройств: A успел первым. У B содержимое уже на
     * сервере (HEAD 200, без загрузки), push — 409 по имени: иконка остаётся своей, проход не
     * «частично». Pull приносит иконку A — своя B становится ею (uuid A), счёт B уходит снова
     * уже с uuid A, повторной отправки иконки нет.
     */
    @Test
    fun raceSameNameSameImage_loserAdoptsWinnersIcon_andResendsAccount() {
        val img = byteArrayOf(6, 6, 6)
        val api = ModelServer(context)
        val uuidA = api.addIconFromOtherDevice("Велосипед", img)
        icon("unknown", byteArrayOf(9), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val ownB = icon("Велосипед", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val accB = account("Велик", ownB, 0) // ушёл раньше с заместителем
        val log = SyncLog()

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log))
        assertEquals("содержимое уже есть — не грузим", 0, api.uploads)
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", ownB, "origin"))
        assertTrue(log.getProblems().isEmpty())
        assertTrue(log.toDisplayText().contains("Велосипед"))

        SyncEngine.applyIcon(db, pullRow(uuidA, "Велосипед", img), HashMap(), SyncLog(), true)

        assertTrue(IconSubstitutes.sameUuid(uuidA, uuidOf("icon", ownB)))
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"))
        assertEquals("перенятая — одна строка, не дубль", 1, countIcons("Велосипед"))
        assertEquals(1, intOf("account", accB, "dirty"))

        val pushesBefore = api.iconPushes()
        SyncEngine.pushOwnIcons(db, api, SyncLog())
        SyncEngine.pushDirty(db, api, SyncLog())
        assertEquals(pushesBefore, api.iconPushes())
        assertTrue(IconSubstitutes.sameUuid(uuidA, api.lastRowOf("account")!!.getString("icon_uuid")))
        assertEquals(1, api.icons.size)
    }

    /**
     * Одно имя, разные картинки: B получает 409, pull иконки A переименовывает свою B в
     * «<имя> (своя)», следующий проход её отправляет — на сервере обе.
     */
    @Test
    fun raceSameNameDifferentImage_loserRenamedOnPull_thenUploaded() {
        val imgA = byteArrayOf(7, 7)
        val imgB = byteArrayOf(8, 8)
        val api = ModelServer(context)
        val uuidA = api.addIconFromOtherDevice("Море", imgA)
        val ownB = icon("Море", imgB, WalletContract.IconEntry.ORIGIN_LOCAL, 0)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", ownB, "origin"))

        val blobs = HashMap<String, ByteArray>()
        blobs[IconSubstitutes.sha256Hex(imgA)] = imgA
        SyncEngine.applyIcon(db, pullRow(uuidA, "Море", imgA), blobs, SyncLog(), true)
        val renamed = stringOf("icon", ownB, "name")
        assertFalse("своя переименована", "Море" == renamed)
        assertTrue(renamed, renamed.startsWith("Море"))

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, SyncLog()))
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"))
        assertEquals(2, api.icons.size)
        assertEquals(renamed, api.icons[uuidOf("icon", ownB)]!!.name)
    }

    /**
     * Одна картинка, разные имена: A уже отправил, B ещё не видел его иконку — грузить содержимое
     * не нужно (HEAD 200), push проходит: на сервере две иконки с одной картинкой, это допустимо.
     * После pull иконки A у B больше нечего отправлять.
     */
    @Test
    fun raceSameImageDifferentName_bothAccepted_noDuplicateUpload() {
        val img = byteArrayOf(10, 10)
        val api = ModelServer(context)
        val uuidA = api.addIconFromOtherDevice("Горы", img)
        val ownB = icon("Скалы", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals(0, api.uploads)
        assertEquals(2, api.icons.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"))

        val blobs = HashMap<String, ByteArray>()
        blobs[IconSubstitutes.sha256Hex(img)] = img
        SyncEngine.applyIcon(db, pullRow(uuidA, "Горы", img), blobs, SyncLog(), true)
        val before = api.iconPushes()
        SyncEngine.pushOwnIcons(db, api, SyncLog())
        assertEquals(before, api.iconPushes())
    }

    /** B увидел иконку A раньше, чем отправил свою с той же картинкой: своя не грузится, счёт — на иконку A. */
    @Test
    fun otherDeviceIconPulledFirst_ownWithSameImageNotUploaded() {
        val img = byteArrayOf(11, 11)
        val api = ModelServer(context)
        val uuidA = api.addIconFromOtherDevice("Лес", img)
        val blobs = HashMap<String, ByteArray>()
        blobs[IconSubstitutes.sha256Hex(img)] = img
        SyncEngine.applyIcon(db, pullRow(uuidA, "Лес", img), blobs, SyncLog(), true)
        val ownB = icon("Мой лес", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        account("Дача", ownB, 1)

        SyncEngine.pushOwnIcons(db, api, SyncLog())
        SyncEngine.pushDirty(db, api, SyncLog())

        assertEquals(0, api.iconPushes())
        assertTrue(IconSubstitutes.sameUuid(uuidA, api.lastRowOf("account")!!.getString("icon_uuid")))
    }

    // ── Потерянный ответ ───────────────────────────────────────────────────────

    /** Сервер принял иконку, ответ потерялся: проход «нет сети»; следующий — тот же uuid, без загрузки, без дубля. */
    @Test
    fun lostResponse_nextPassIsIdempotent() {
        val own = icon("Чай", byteArrayOf(12, 12), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val api = ModelServer(context)
        api.loseNextIconResponse = true

        try {
            SyncEngine.pushOwnIcons(db, api, SyncLog())
            fail("ожидался IOException")
        } catch (expected: IOException) {
            // «нет сети»
        }
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", own, "origin"))
        assertEquals(1, api.icons.size)

        SyncEngine.pushOwnIcons(db, api, SyncLog())

        assertEquals("второй раз содержимое не грузится", 1, api.uploads)
        assertEquals(1, api.icons.size)
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"))
    }

    // ── Перенимание на pull ────────────────────────────────────────────────────

    /** Во время подключения к кошельку перенимание не помечает счета: LedgerConnection удаляет dirty <> 0. */
    @Test
    fun adoptionDuringLedgerConnection_doesNotRedirty() {
        val img = byteArrayOf(13, 13)
        val own = icon("Сад", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Огород", own, 0)

        SyncEngine.applyIcon(
            db, pullRow(UUID.randomUUID().toString(), "Сад", img), HashMap(),
            SyncLog(), false
        )

        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"))
        assertEquals(0, intOf("account", acc, "dirty"))
    }

    /** Контрагент хранит icon_uuid строкой — при перенимании ссылка переписывается на серверный uuid. */
    @Test
    fun adoption_rewritesCounterpartyIconUuid() {
        val img = byteArrayOf(14, 14)
        val own = icon("Друг", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val ownUuid = uuidOf("icon", own)
        val cp = ContentValues()
        cp.put("name", "Петя")
        cp.put("uuid", UUID.randomUUID().toString())
        cp.put("icon_uuid", ownUuid)
        val cpId = db.insertOrThrow("counterparty", null, cp)
        val serverUuid = UUID.randomUUID().toString()

        SyncEngine.applyIcon(db, pullRow(serverUuid, "Друг", img), HashMap(), SyncLog(), true)

        assertEquals(serverUuid, stringOf("counterparty", cpId, "icon_uuid"))
        assertNotNull(stringOf("icon", own, "uuid"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun icon(name: String, image: ByteArray, origin: Int, dirty: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("image", image)
        cv.put("icon_type", 0)
        cv.put("origin", origin)
        cv.put("uuid", UUID.randomUUID().toString())
        cv.put("dirty", dirty)
        return db.insertOrThrow("icon", null, cv)
    }

    private fun account(name: String, iconId: Long, dirty: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("uuid", UUID.randomUUID().toString())
        val id = db.insertOrThrow("account", null, cv)
        db.execSQL("UPDATE account SET dirty = $dirty WHERE _id = $id AND dirty <> $dirty")
        return id
    }

    private fun currency(name: String, iso: String, iconId: Long, dirty: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("iso_code", iso)
        cv.put("icon_id", iconId)
        cv.put("symbol", "K")
        cv.put("uuid", UUID.randomUUID().toString())
        val id = db.insertOrThrow("currency", null, cv)
        db.execSQL("UPDATE currency SET dirty = $dirty WHERE _id = $id AND dirty <> $dirty")
        return id
    }

    private fun countIcons(name: String): Int {
        val c = db.rawQuery("SELECT count(*) FROM icon WHERE name = ? AND deleted_at IS NULL", arrayOf(name))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun uuidOf(table: String, id: Long): String = stringOf(table, id, "uuid")

    private fun stringOf(table: String, id: Long, column: String): String {
        val c = db.rawQuery("SELECT $column FROM $table WHERE _id = ?", arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun intOf(table: String, id: Long, column: String): Int {
        val c = db.rawQuery("SELECT $column FROM $table WHERE _id = ?", arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
