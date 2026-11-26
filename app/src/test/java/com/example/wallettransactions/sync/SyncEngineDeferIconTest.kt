package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * openspec server-managed-icons, задачи 2.1 и 2.3: push без иконок и откладывание строк,
 * у своей иконки которых нет заместителя с сервера.
 *
 * Раньше (инцидент 2026-10-11) откладывались строки из-за незалитой иконки; заливки больше
 * нет, [SyncEngine.deferDependents] откладывает строки без иконки-заместителя.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineDeferIconTest {

    companion object {
        private val SYNC_TABLES = arrayOf(
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link",
        )

        private fun row(uuid: String, vararg refs: String): JSONObject {
            val r = JSONObject().put("uuid", uuid)
            var i = 0
            while (i < refs.size) {
                r.put(refs[i], refs[i + 1])
                i += 2
            }
            return r
        }
    }

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var currencyId = 0L

    /** Транспорт без сети: push отвечает 200 и запоминает тело, к /sync/blobs и pull ходить нельзя. */
    private class RecordingApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        val pushed = ArrayList<JSONObject>()
        var blobRequests = 0

        override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
            pushed.add(changes)
            return SyncApiClient.HttpResult(200, JSONObject())
        }

        override fun downloadBlob(sha256Hex: String): ByteArray {
            blobRequests++
            throw AssertionError("push не должен обращаться к /sync/blobs")
        }

        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            throw AssertionError("pull в этом тесте не вызывается")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        // Сид чистой базы — не предмет теста: всё «уже отправлено», имена иконок свободны.
        db.execSQL("UPDATE icon SET deleted_at = 1")
        for (t in SYNC_TABLES) db.execSQL("UPDATE $t SET dirty = 0 WHERE dirty <> 0")
        val seedIcon = icon("test_currency_icon", byteArrayOf(9), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val cur = ContentValues()
        cur.put("name", "Test coin")
        cur.put("iso_code", "TSC")
        cur.put("icon_id", seedIcon)
        cur.put("symbol", "T")
        cur.put("dirty", 0)
        currencyId = db.insertOrThrow("currency", null, cur)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 2.1: push без icon, без /sync/blobs ─────────────────────────────────────

    @Test
    fun push_hasNoIconTable_andNoBlobRequests() {
        icon("Моя картинка", byteArrayOf(1, 2, 3), WalletContract.IconEntry.ORIGIN_LOCAL, 1)
        val server = icon("Server icon", byteArrayOf(4), WalletContract.IconEntry.ORIGIN_SERVER, 1)
        val acc = account("Test wallet", server)

        val api = RecordingApi(context)
        val log = SyncLog()
        SyncEngine.pushDirty(db, api, log)

        assertEquals(1, api.pushed.size)
        val changes = api.pushed[0]
        assertFalse("иконки в push не уходят", changes.has("icon"))
        assertEquals(0, api.blobRequests)
        val row = changes.getJSONArray("account").getJSONObject(0)
        assertEquals(uuidOf("icon", server), row.getString("icon_uuid"))
        assertEquals(0L, intOf("account", acc, "dirty"))
        assertTrue(log.getProblems().isEmpty())
    }

    // ── 2.3: заместитель своей иконки ───────────────────────────────────────────

    /** Сценарий спеки «Совпадает имя». */
    @Test
    fun ownIcon_pushedWithSameNameSubstitute_localLinkKept() {
        icon("unknown", byteArrayOf(7), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val serverCinema = icon("cinema", byteArrayOf(5), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val ownCinema = icon("Cinema", byteArrayOf(6), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Test wallet", ownCinema)

        val api = RecordingApi(context)
        SyncEngine.pushDirty(db, api, SyncLog())

        val row = api.pushed[0].getJSONArray("account").getJSONObject(0)
        assertEquals(uuidOf("icon", serverCinema), row.getString("icon_uuid"))
        assertEquals(ownCinema, intOf("account", acc, "icon_id"))
        assertEquals(0L, intOf("account", acc, "dirty"))
    }

    /** Сценарий спеки «Ничего похожего»; валюта — тем же путём, что счёт. */
    @Test
    fun ownIcon_withoutMatches_pushedWithUnknown() {
        val unknown = icon("unknown", byteArrayOf(7), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        icon("cinema", byteArrayOf(5), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val own = icon("Моё фото", byteArrayOf(6), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val cv = ContentValues()
        cv.put("icon_id", own)
        db.update("currency", cv, "_id=?", arrayOf(currencyId.toString()))

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val row = byTable["currency"]!!.getJSONObject(0)
        assertEquals(uuidOf("icon", unknown), row.getString("icon_uuid"))
    }

    /** Сценарий спеки «На сервере нет даже unknown». */
    @Test
    fun ownIcon_withoutSubstitute_rowAndDependentsDeferred_restSent() {
        val own = icon("Моё фото", byteArrayOf(6), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val server = icon("Server icon", byteArrayOf(4), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val accOwn = account("With own icon", own)
        val accOk = account("With server icon", server)
        val op = operation(accOk, accOwn)

        val api = RecordingApi(context)
        val log = SyncLog()
        SyncEngine.pushDirty(db, api, log)

        assertEquals(1, api.pushed.size)
        val changes = api.pushed[0]
        val accounts = changes.getJSONArray("account")
        assertEquals(1, accounts.length())
        assertEquals(uuidOf("account", accOk), accounts.getJSONObject(0).getString("uuid"))
        assertFalse("операция по отложенному счёту тоже ждёт", changes.has("operation"))

        assertEquals(1L, intOf("account", accOwn, "dirty"))
        assertEquals(1L, intOf("operation", op, "dirty"))
        assertEquals(0L, intOf("account", accOk, "dirty"))
        assertTrue(
            log.getProblems().toString(),
            log.getProblems().contains("нет иконки-заместителя для «Моё фото»: строка отложена")
        )
    }

    // ── android-wallet#50: иконки с сервера, удалённые только на телефоне ───────────

    /**
     * Сценарий спеки «Иконка с сервера удалена только на телефоне»: прежний «Удалить данные»
     * мягко удалил все иконки с сервера (`dirty = 1`), пересев завёл свои с теми же
     * именами. Заместитель находится — ни одна строка не откладывается.
     */
    @Test
    fun serverIconDeletedLocally_isSubstitute_nothingDeferred() {
        val serverHouseware = deletedIcon("Houseware", byteArrayOf(5), 1)
        deletedIcon("unknown", byteArrayOf(7), 1)
        val ownHouseware = icon("Houseware", byteArrayOf(6), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val ownPhoto = icon("Моё фото", byteArrayOf(8), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val accHouse = account("Дом", ownHouseware)
        val accPhoto = account("Фото", ownPhoto)
        val op = operation(accHouse, accPhoto)

        val api = RecordingApi(context)
        val log = SyncLog()
        SyncEngine.pushDirty(db, api, log)

        assertTrue(log.getProblems().toString(), log.getProblems().isEmpty())
        val changes = api.pushed[0]
        val accounts = changes.getJSONArray("account")
        assertEquals(2, accounts.length())
        for (i in 0 until accounts.length()) {
            val row = accounts.getJSONObject(i)
            if (row.getString("uuid") == uuidOf("account", accHouse)) {
                assertEquals(uuidOf("icon", serverHouseware), row.getString("icon_uuid"))
            } else {
                assertEquals(
                    "без совпадений — локально удалённая unknown с сервера",
                    IconSubstitutes.UNKNOWN_NAME, nameOfIconByUuid(row.getString("icon_uuid"))
                )
            }
        }
        assertEquals(1, changes.getJSONArray("operation").length())
        assertEquals(0L, intOf("operation", op, "dirty"))
        assertEquals(ownHouseware, intOf("account", accHouse, "icon_id"))
    }

    /** Сценарий спеки «Иконка удалена на сервере»: tombstone из pull (`dirty = 0`) не заместитель. */
    @Test
    fun serverIconDeletedByServer_isNotSubstitute() {
        deletedIcon("Houseware", byteArrayOf(5), 0)
        val unknown = icon("unknown", byteArrayOf(7), WalletContract.IconEntry.ORIGIN_SERVER, 0)
        val own = icon("Houseware", byteArrayOf(5), WalletContract.IconEntry.ORIGIN_LOCAL, 0)
        val acc = account("Дом", own)

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val accounts = byTable["account"]!!
        for (i in 0 until accounts.length()) {
            val row = accounts.getJSONObject(i)
            if (row.getString("uuid") == uuidOf("account", acc)) {
                assertEquals(uuidOf("icon", unknown), row.getString("icon_uuid"))
                return
            }
        }
        throw AssertionError("счёт не попал в push")
    }

    @Test
    fun deferDependents_defersWholeChainButKeepsUnrelatedRows() {
        val byTable = LinkedHashMap<String, JSONArray>()
        byTable["account"] = JSONArray()
            .put(row("acc-bad", "icon_uuid", "icon-own"))
            .put(row("acc-ok", "icon_uuid", "icon-server"))
        byTable["account_operating"] = JSONArray()
            .put(row("acc-bad", "currency_uuid", "cur"))
            .put(row("acc-ok", "currency_uuid", "cur"))
        byTable["operation"] = JSONArray()
            .put(row("op-bad", "acc_fr_uuid", "acc-bad", "acc_to_uuid", "acc-ok"))
            .put(row("op-ok", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok"))
            .put(row("op-deleted", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok").put("deleted", true))
        byTable["operation_link"] = JSONArray()
            .put(row("link-bad", "main_uuid", "op-ok", "link_uuid", "op-bad"))

        val deferred = HashSet<String>()
        deferred.add("icon-own")
        val problems = ArrayList<String>()

        SyncEngine.deferDependents(byTable, deferred, problems)

        assertEquals("acc-ok", byTable["account"]!!.getJSONObject(0).getString("uuid"))
        assertEquals(1, byTable["account"]!!.length())
        assertEquals(1, byTable["account_operating"]!!.length())
        assertEquals(2, byTable["operation"]!!.length())
        // Таблица, из которой ушли все строки, не должна оставаться пустой в changes.
        assertFalse(byTable.containsKey("operation_link"))
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("нет иконки-заместителя"))
        assertTrue(problems[0].contains("4 строк"))
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

    /** Иконка с сервера, мягко удалённая: `dirty = 1` — на телефоне, `0` — tombstone из pull. */
    private fun deletedIcon(name: String, image: ByteArray, dirty: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("image", image)
        cv.put("icon_type", 0)
        cv.put("origin", WalletContract.IconEntry.ORIGIN_SERVER)
        cv.put("uuid", UUID.randomUUID().toString())
        cv.put("deleted_at", 1_000L)
        cv.put("dirty", dirty)
        return db.insertOrThrow("icon", null, cv)
    }

    private fun nameOfIconByUuid(uuid: String): String {
        val c = db.rawQuery("SELECT name FROM icon WHERE uuid=?", arrayOf(uuid))
        try {
            assertTrue(uuid, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun account(name: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("uuid", UUID.randomUUID().toString())
        return db.insertOrThrow("account", null, cv)
    }

    private fun operation(from: Long, to: Long): Long {
        val cv = ContentValues()
        cv.put("date", "2026-11-02 12:00")
        cv.put("acc_fr_id", from)
        cv.put("acc_to_id", to)
        cv.put("amount", 10.5)
        cv.put("comment", "")
        cv.put("uuid", UUID.randomUUID().toString())
        return db.insertOrThrow("operation", null, cv)
    }

    private fun uuidOf(table: String, id: Long): String {
        val c = db.rawQuery("SELECT uuid FROM $table WHERE _id=?", arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            assertNotNull(c.getString(0))
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun intOf(table: String, id: Long, column: String): Long {
        val c = db.rawQuery("SELECT $column FROM $table WHERE _id=?", arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }
}
