package com.example.wallettransactions.sync

import com.example.wallettransactions.ai.StatementImport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * [AiApiClient] — импорт выписки (openspec add-ai-statement-import): форма
 * запросов по wallet-ai/openapi.yaml (`createImport`, `listImports`,
 * `getImport`, `patchImportRows`, `commitImportRows`), разбор
 * [StatementImport] и ошибок, тишина в logcat о суммах и описаниях.
 */
@RunWith(RobolectricTestRunner::class)
class AiApiClientImportTest {

    companion object {
        /** `ImportDetail` из openapi: группы, включая незнакомую `ignored`, и лишние поля. */
        internal const val DETAIL = "{\"import_id\":\"imp-1\",\"status\":\"ready\",\"failure\":null," +
            "\"wallet_uuid\":\"w-1\",\"source_name\":\"statement.pdf\",\"created_at\":\"2026-10-24T10:00:00Z\"," +
            "\"expires_at\":\"2027-01-22T10:00:00Z\",\"period\":null,\"profile\":null,\"profile_detect\":\"none\"," +
            "\"reconciled\":true,\"groups\":{\"ready\":1},\"unparsed\":[],\"operations\":[],\"rows\":[" +
            "{\"row_id\":\"r-1\",\"group\":\"ready\",\"lines\":[{\"n\":17,\"text\":\"12.10 YANDEX*GO -450,00\"}]," +
            "\"date\":\"2026-10-12\",\"amount\":\"450.00\",\"dir\":\"expense\",\"currency\":\"RUB\"," +
            "\"description\":\"YANDEX*GO\",\"pattern\":\"YANDEX*GO\",\"comment\":null,\"category_uuid\":\"c-taxi\"," +
            "\"source\":\"rule\",\"suggestion\":null,\"check\":null,\"match\":null,\"match_candidates\":null," +
            "\"decision\":null,\"edited\":false,\"committed\":false}," +
            "{\"row_id\":\"r-2\",\"group\":\"ignored\",\"lines\":[],\"date\":\"2026-10-13\",\"amount\":\"10.00\"," +
            "\"dir\":\"transfer\",\"description\":\"ATM\",\"category_uuid\":null,\"suggestion\":\"Кафе\"," +
            "\"check\":\"amount_not_in_text\",\"committed\":true,\"future_field\":{\"x\":1}}]}"
    }

    private val requests = ArrayList<Request>()
    private val requestBodies = ArrayList<String?>()
    private var nextCode = 200
    private var nextBody = "{}"

    private lateinit var client: AiApiClient

    @Before
    fun setUp() {
        ShadowLog.clear()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests.add(req)
                val reqBody = req.body
                if (reqBody != null) {
                    val buf = Buffer()
                    reqBody.writeTo(buf)
                    requestBodies.add(buf.readUtf8())
                } else {
                    requestBodies.add(null)
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextCode)
                    .message("x")
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        client = AiApiClient(http, "http://gw/", "tok")
    }

    @Test
    fun createImport_sendsBase64FileJson() {
        nextCode = 202
        nextBody = "{\"import_id\":\"imp-1\",\"status\":\"queued\"}"
        val pdf = "%PDF-1.4 test".toByteArray(StandardCharsets.UTF_8)

        val imp = client.createImport("w-1", "statement.pdf", pdf)

        assertEquals("imp-1", imp.id)
        assertTrue(imp.isActive())
        val req = requests[0]
        assertEquals("POST", req.method)
        assertEquals("http://gw/api/ai/imports", req.url.toString())
        assertEquals("Bearer tok", req.header("Authorization"))
        val body = JSONObject(requestBodies[0]!!)
        assertEquals("w-1", body.getString("wallet_uuid"))
        assertEquals("statement.pdf", body.getJSONObject("file").getString("name"))
        assertTrue(pdf.contentEquals(Base64.getDecoder().decode(body.getJSONObject("file").getString("content_base64"))))
        assertFalse(body.has("text"))
    }

    @Test
    fun createImport_failedAtOnce_carriesFailure() {
        nextCode = 202
        nextBody = "{\"import_id\":\"imp-2\",\"status\":\"failed\",\"failure\":\"no_text\"}"
        val imp = client.createImport("w-1", "scan.pdf", byteArrayOf(1))
        assertEquals(StatementImport.FAILED, imp.status)
        assertEquals("no_text", imp.failure)
    }

    @Test
    fun errors_carryCodeReasonAndImportId() {
        assertCreateError(429, "{\"error\":\"import_in_progress\",\"import_id\":\"imp-9\"}", "import_in_progress", "imp-9")
        assertCreateError(413, "{\"error\":\"too_large\"}", "too_large", "")
        assertCreateError(400, "{\"error\":\"unsupported_file\"}", "unsupported_file", "")
        assertCreateError(503, "{\"error\":\"unavailable\"}", "unavailable", "")
    }

    @Test
    fun getImport_parsesRowsAndUnknownGroups() {
        nextBody = DETAIL
        val imp = client.getImport("imp-1")

        assertEquals("http://gw/api/ai/imports/imp-1", requests[0].url.toString())
        assertTrue(imp.hasRows())
        assertEquals("w-1", imp.walletUuid)
        assertEquals(2, imp.rows.size)
        val r1 = imp.row("r-1")!!
        assertEquals("2026-10-12", r1.date)
        assertEquals(450.0, r1.amountValue(), 0.001)
        assertEquals("c-taxi", r1.categoryUuid)
        assertNull(r1.suggestion)
        assertNull(r1.check)
        assertEquals("12.10 YANDEX*GO -450,00", r1.sourceText())
        assertTrue(r1.isExpense())
        val r2 = imp.row("r-2")!!
        assertEquals("ignored", r2.group)
        assertNull(r2.categoryUuid)
        assertEquals("Кафе", r2.suggestion)
        assertEquals("amount_not_in_text", r2.check)
        assertTrue(r2.committed)
        assertFalse(r2.isExpense())
    }

    @Test
    fun getImport_progressAndPause() {
        nextBody = "{\"import_id\":\"imp-1\",\"status\":\"extracting\",\"progress\":{\"done\":2,\"total\":6}," +
            "\"paused_since\":\"2026-10-24T10:05:00Z\"}"
        val imp = client.getImport("imp-1")
        assertTrue(imp.isActive())
        assertFalse(imp.hasRows())
        assertEquals(2, imp.progressDone)
        assertEquals(6, imp.progressTotal)
        assertEquals("2026-10-24T10:05:00Z", imp.pausedSince)
        assertTrue(imp.rows.isEmpty())
    }

    @Test
    fun listImports_newestFirst() {
        nextBody = "{\"imports\":[{\"import_id\":\"a\",\"status\":\"classifying\"},{\"import_id\":\"b\",\"status\":\"done\"}]}"
        val list = client.listImports()
        assertEquals("GET", requests[0].method)
        assertEquals(2, list.size)
        assertEquals("a", list[0].id)
        assertTrue(list[0].isActive())
        assertFalse(list[1].isActive())
    }

    @Test
    fun patchCategory_onlyCategoryUuid() {
        nextBody = DETAIL
        client.patchImportRowCategory("imp-1", "r-1", "c-food")
        val req = requests[0]
        assertEquals("PATCH", req.method)
        assertEquals("http://gw/api/ai/imports/imp-1/rows", req.url.toString())
        val row = JSONObject(requestBodies[0]!!).getJSONArray("rows").getJSONObject(0)
        assertEquals("r-1", row.getString("row_id"))
        assertEquals("c-food", row.getString("category_uuid"))
        assertEquals(2, row.length())
    }

    @Test
    fun commitRows_sendsRowIds() {
        nextBody = DETAIL
        client.commitImportRows("imp-1", listOf("r-1", "r-3"))
        val req = requests[0]
        assertEquals("POST", req.method)
        assertEquals("http://gw/api/ai/imports/imp-1/committed", req.url.toString())
        assertEquals("[\"r-1\",\"r-3\"]", JSONObject(requestBodies[0]!!).getJSONArray("row_ids").toString())
    }

    /** В logcat — id, статусы, число строк и длительности; суммы и описания — нет. */
    @Test
    fun logcat_hasNoAmountsOrDescriptions() {
        nextBody = DETAIL
        client.getImport("imp-1")
        client.commitImportRows("imp-1", listOf("r-1"))
        nextBody = "{\"imports\":[{\"import_id\":\"imp-1\",\"status\":\"ready\"}]}"
        client.listImports()

        var sawId = false
        for (item in ShadowLog.getLogsForTag("AiApiClient")) {
            assertFalse(item.msg, item.msg.contains("450"))
            assertFalse(item.msg, item.msg.contains("YANDEX"))
            assertFalse(item.msg, item.msg.contains("statement.pdf"))
            if (item.msg.contains("imp-1")) sawId = true
        }
        assertTrue(sawId)
    }

    private fun assertCreateError(code: Int, body: String, error: String, importId: String) {
        nextCode = code
        nextBody = body
        try {
            client.createImport("w-1", "a.pdf", byteArrayOf(1))
            fail("ожидался HttpError $code")
        } catch (e: AiApiClient.HttpError) {
            assertEquals(code, e.code)
            assertEquals(error, e.error)
            assertEquals(importId, e.importId)
        }
    }
}
