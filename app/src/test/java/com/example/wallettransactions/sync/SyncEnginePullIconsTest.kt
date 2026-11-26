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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * openspec server-managed-icons, задачи 3.1 и 3.2: иконки из pull — `origin = 0`,
 * конфликт имени со своей иконкой, своя иконка счёта/валюты не затирается заместителем.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePullIconsTest {

    companion object {
        private const val LOCAL = WalletContract.IconEntry.ORIGIN_LOCAL
        private const val SERVER = WalletContract.IconEntry.ORIGIN_SERVER

        private fun iconRow(uuid: String, name: String, image: ByteArray): JSONObject =
            JSONObject()
                .put("uuid", uuid)
                .put("ledger_id", JSONObject.NULL)
                .put("name", name)
                .put("icon_type", 0)
                .put("content_sha256", IconSubstitutes.sha256Hex(image))
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun accountRow(uuid: String, name: String, iconUuid: String): JSONObject =
            JSONObject()
                .put("uuid", uuid)
                .put("name", name)
                .put("comment", "")
                .put("icon_uuid", iconUuid)
                .put("is_archived", false)
                .put("system_kind", 0)
                .put("kind", 1)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun blobs(image: ByteArray): Map<String, ByteArray> {
            val m = HashMap<String, ByteArray>()
            m[IconSubstitutes.sha256Hex(image)] = image
            return m
        }
    }

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private lateinit var log: SyncLog

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("UPDATE icon SET deleted_at = 1") // сид — не предмет теста, имена свободны
        log = SyncLog()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 3.1: applyIcon ──────────────────────────────────────────────────────────

    @Test
    fun newServerIcon_insertedAsFromServer() {
        val image = byteArrayOf(1, 2)
        val uuid = UUID.randomUUID().toString()
        SyncEngine.applyIcon(db, iconRow(uuid, "Admin icon", image), blobs(image), log)

        assertEquals(SERVER.toLong(), intWhere("icon", "origin", "uuid=?", uuid))
        assertEquals(0L, intWhere("icon", "dirty", "uuid=?", uuid))
    }

    @Test
    fun rowWithUuidOfOwnIcon_becomesFromServer() {
        val uuid = UUID.randomUUID().toString()
        val id = icon("Pushed before", byteArrayOf(3), LOCAL, uuid)
        SyncEngine.applyIcon(db, iconRow(uuid, "Pushed before", byteArrayOf(3)), HashMap(), log)

        assertEquals(SERVER.toLong(), intWhere("icon", "origin", "_id=?", id.toString()))
        assertEquals(0L, intWhere("icon", "dirty", "_id=?", id.toString()))
    }

    /** Сценарий спеки «Администратор добавил иконку с именем своей». */
    @Test
    fun nameConflictWithOwnIcon_ownRenamed_serverAdded_linksKept() {
        val own = icon("Cinema", byteArrayOf(5), LOCAL, UUID.randomUUID().toString())
        val acc = account("Кино", own)

        val serverImage = byteArrayOf(6)
        val serverUuid = UUID.randomUUID().toString()
        SyncEngine.applyIcon(db, iconRow(serverUuid, "Cinema", serverImage), blobs(serverImage), log)

        assertEquals("Cinema (своя)", stringWhere("icon", "name", "_id=?", own.toString()))
        assertEquals(LOCAL.toLong(), intWhere("icon", "origin", "_id=?", own.toString()))
        assertEquals("Cinema", stringWhere("icon", "name", "uuid=?", serverUuid))
        assertEquals(SERVER.toLong(), intWhere("icon", "origin", "uuid=?", serverUuid))
        assertEquals(own, intWhere("account", "icon_id", "_id=?", acc.toString()))

        // «(своя)» уже занято — следующая своя станет «(своя 2)».
        val own2 = icon("Bar", byteArrayOf(7), LOCAL, UUID.randomUUID().toString())
        icon("Bar (своя)", byteArrayOf(8), LOCAL, UUID.randomUUID().toString())
        val barImage = byteArrayOf(9)
        SyncEngine.applyIcon(db, iconRow(UUID.randomUUID().toString(), "Bar", barImage), blobs(barImage), log)
        assertEquals("Bar (своя 2)", stringWhere("icon", "name", "_id=?", own2.toString()))
    }

    /** Своя с той же картинкой (seed.json после чистой установки) — та же иконка, без дубля. */
    @Test
    fun nameConflictWithSameImage_ownAdoptsServerIdentity() {
        val image = byteArrayOf(4, 4, 4)
        val own = icon("Bar", image, LOCAL, UUID.randomUUID().toString())
        val serverUuid = UUID.randomUUID().toString()

        val prefetched = SyncEngine.prefetchNewIconBlobs(
            db,
            JSONArray().put(iconRow(serverUuid, "Bar", image)), null
        ) // api = null: качать нельзя
        assertTrue(prefetched.isEmpty())

        SyncEngine.applyIcon(db, iconRow(serverUuid, "Bar", image), prefetched, log)

        assertEquals(serverUuid, stringWhere("icon", "uuid", "_id=?", own.toString()))
        assertEquals(SERVER.toLong(), intWhere("icon", "origin", "_id=?", own.toString()))
        assertEquals(0L, intWhere("icon", "dirty", "_id=?", own.toString()))
        assertEquals(1L, intWhere("icon", "COUNT(*)", "name=? AND deleted_at IS NULL", "Bar"))
    }

    @Test
    fun nameConflictWithServerIcon_skippedAsBefore() {
        val existing = icon("Cinema", byteArrayOf(5), SERVER, UUID.randomUUID().toString())
        val image = byteArrayOf(6)
        val uuid = UUID.randomUUID().toString()
        SyncEngine.applyIcon(db, iconRow(uuid, "Cinema", image), blobs(image), log)

        assertEquals(0L, intWhere("icon", "COUNT(*)", "uuid=?", uuid))
        assertEquals("Cinema", stringWhere("icon", "name", "_id=?", existing.toString()))
    }

    // ── 3.2: своя иконка не затирается заместителем ────────────────────────────

    /** Сценарий спеки «Сервер вернул заместитель». */
    @Test
    fun accountWithOwnIcon_substituteFromServer_ownIconKept_otherFieldsUpdated() {
        val unknownUuid = UUID.randomUUID().toString()
        icon("unknown", byteArrayOf(1), SERVER, unknownUuid)
        val own = icon("Моё фото", byteArrayOf(2), LOCAL, UUID.randomUUID().toString())
        val acc = account("Кошелёк", own)
        val accUuid = stringWhere("account", "uuid", "_id=?", acc.toString())

        SyncEngine.applyAccount(
            db, accountRow(accUuid, "Кошелёк 2", unknownUuid), log,
            SyncEngine.PullIconGuard(db)
        )

        assertEquals(own, intWhere("account", "icon_id", "_id=?", acc.toString()))
        assertEquals("Кошелёк 2", stringWhere("account", "name", "_id=?", acc.toString()))
        assertEquals(0L, intWhere("account", "dirty", "_id=?", acc.toString()))
    }

    /** Сценарий спеки «Иконку сменили на другом устройстве». */
    @Test
    fun accountWithOwnIcon_otherServerIcon_applied() {
        icon("unknown", byteArrayOf(1), SERVER, UUID.randomUUID().toString())
        val otherUuid = UUID.randomUUID().toString()
        val other = icon("Taxi", byteArrayOf(3), SERVER, otherUuid)
        val own = icon("Моё фото", byteArrayOf(2), LOCAL, UUID.randomUUID().toString())
        val acc = account("Кошелёк", own)
        val accUuid = stringWhere("account", "uuid", "_id=?", acc.toString())

        SyncEngine.applyAccount(
            db, accountRow(accUuid, "Кошелёк", otherUuid), log,
            SyncEngine.PullIconGuard(db)
        )

        assertEquals(other, intWhere("account", "icon_id", "_id=?", acc.toString()))
    }

    @Test
    fun currencyWithOwnIcon_sameNameSubstitute_ownIconKept() {
        val substitute = UUID.randomUUID().toString()
        icon("flag xx", byteArrayOf(1), SERVER, substitute)
        val own = icon("Flag XX", byteArrayOf(2), LOCAL, UUID.randomUUID().toString())
        val cv = ContentValues()
        cv.put("name", "Test XX")
        cv.put("iso_code", "TXX")
        cv.put("icon_id", own)
        cv.put("symbol", "X")
        val curUuid = UUID.randomUUID().toString()
        cv.put("uuid", curUuid)
        val cur = db.insertOrThrow("currency", null, cv)

        val row = JSONObject()
            .put("uuid", curUuid).put("ledger_id", JSONObject.NULL)
            .put("name", "Test XX").put("iso_code", "TXX").put("icon_uuid", substitute)
            .put("symbol", "X").put("precision", 3)
            .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)
        SyncEngine.applyCurrency(db, row, log)

        assertEquals(own, intWhere("currency", "icon_id", "_id=?", cur.toString()))
        assertEquals(3L, intWhere("currency", "precision", "_id=?", cur.toString()))
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun icon(name: String, image: ByteArray, origin: Int, uuid: String): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("image", image)
        cv.put("icon_type", 0)
        cv.put("origin", origin)
        cv.put("uuid", uuid)
        cv.put("dirty", 0)
        return db.insertOrThrow("icon", null, cv)
    }

    private fun account(name: String, iconId: Long): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("kind", 1)
        cv.put("uuid", UUID.randomUUID().toString())
        cv.put("dirty", 0)
        return db.insertOrThrow("account", null, cv)
    }

    private fun intWhere(table: String, column: String, where: String, arg: String): Long {
        val c = db.rawQuery("SELECT $column FROM $table WHERE $where", arrayOf(arg))
        try {
            assertTrue("$table $where $arg не найдена", c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun stringWhere(table: String, column: String, where: String, arg: String): String {
        val c = db.rawQuery("SELECT $column FROM $table WHERE $where", arrayOf(arg))
        try {
            assertTrue("$table $where $arg не найдена", c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }
}
