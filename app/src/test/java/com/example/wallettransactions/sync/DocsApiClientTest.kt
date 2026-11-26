package com.example.wallettransactions.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [DocsApiClient]: форма запросов по wallet-docs/openapi.yaml, разбор ответа,
 * классы ошибок и таблица «ответ статуса → видимость пункта» (openspec docs-assistant).
 */
@RunWith(RobolectricTestRunner::class)
class DocsApiClientTest {

    private val requests = ArrayList<Request>()
    private val requestBodies = ArrayList<String?>()
    private var nextCode = 200
    private var nextBody = "{}"
    private var failNetwork = false

    private lateinit var client: DocsApiClient

    @Before
    fun setUp() {
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
                if (failNetwork) throw IOException("no network")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextCode)
                    .message("x")
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        client = DocsApiClient(http, "http://gw/", "tok")
    }

    @Test
    fun ask_sendsQuestionOnly() {
        nextBody = "{\"answer\":\"a\",\"sources\":[]}"

        client.ask("Как работает синхронизация?")

        val req = requests[0]
        assertEquals("POST", req.method)
        assertEquals("http://gw/api/docs/ask", req.url.toString())
        assertEquals("Bearer tok", req.header("Authorization"))
        val body = JSONObject(requestBodies[0]!!)
        assertEquals("Как работает синхронизация?", body.getString("question"))
        assertEquals("клиент не передаёт platform/client (D4)", 1, body.length())
    }

    @Test
    fun ask_parsesAnswerAndSources() {
        nextBody = "{\"answer\":\"Раз в 15 минут.\",\"sources\":[" +
            "{\"repo\":\"android-wallet\",\"capability\":\"sync-run\",\"heading\":\"Периодический запуск\"}," +
            "{\"repo\":\"wallet-api\",\"capability\":\"sync\",\"heading\":\"Периодический запуск\"}," +
            "{\"repo\":\"wallet-api\",\"capability\":\"sync\",\"heading\":\"Обмен изменениями\"}]}"

        val a = client.ask("q")

        assertEquals("Раз в 15 минут.", a.answer)
        assertEquals(3, a.sources.size)
        assertEquals("sync-run", a.sources[0].capability)
        assertEquals("повторы названий схлопываются", "Периодический запуск, Обмен изменениями", a.sourcesLine())
    }

    @Test
    fun ask_emptySources_hasEmptyLine() {
        nextBody = "{\"answer\":\"В описании системы этого нет.\",\"sources\":[]}"
        assertEquals("", client.ask("q").sourcesLine())
    }

    @Test(expected = JSONException::class)
    fun ask_bodyWithoutAnswer_isMalformed() {
        nextBody = "{\"sources\":[]}"
        client.ask("q")
    }

    @Test
    fun errors_classifiedByStatus() {
        assertEquals(DocsApiClient.ErrorKind.RELOGIN, askFailure(401, "{\"error\":\"unauthorized\"}"))
        assertEquals(DocsApiClient.ErrorKind.BAD_REQUEST, askFailure(400, "{\"error\":\"question_too_long\"}"))
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(404, "{}"))
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(502, "<html>bad gateway</html>"))
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(503, "{\"error\":\"llm_unavailable\"}"))
    }

    @Test
    fun badRequest_carriesServiceError() {
        nextCode = 400
        nextBody = "{\"error\":\"question_too_long\"}"
        try {
            client.ask("q")
            fail("ожидался HttpError 400")
        } catch (e: AiApiClient.HttpError) {
            assertEquals("question_too_long", e.error)
        }
    }

    @Test
    fun networkFailure_isNetworkKind() {
        failNetwork = true
        try {
            client.ask("q")
            fail("ожидался IOException")
        } catch (e: IOException) {
            assertEquals(DocsApiClient.ErrorKind.NETWORK, DocsApiClient.classify(e))
        }
    }

    @Test
    fun status_returnsCodeFromGatewayPath() {
        nextCode = 200
        assertEquals(200, client.status())
        assertEquals("http://gw/api/docs/status", requests[0].url.toString())
        assertEquals("GET", requests[0].method)
    }

    /** Таблица «ответ статуса → видимость пункта» (spec, «Пункт в «Ещё» только при доступном сервисе»). */
    @Test
    fun visibility_onlyOn200() {
        assertTrue(DocsApiClient.isVisibleFor(200))
        for (code in intArrayOf(204, 301, 401, 403, 404, 429, 500, 502, 503)) {
            assertFalse("код $code", DocsApiClient.isVisibleFor(code))
        }
        // ошибка сети: status() бросает IOException — экран трактует как «скрыть»
        failNetwork = true
        try {
            client.status()
            fail("ожидался IOException")
        } catch (expected: IOException) {
            // пункт скрыт
        }
    }

    private fun askFailure(code: Int, body: String): DocsApiClient.ErrorKind {
        nextCode = code
        nextBody = body
        try {
            client.ask("q")
        } catch (e: AiApiClient.HttpError) {
            assertEquals(code, e.code)
            return DocsApiClient.classify(e)
        }
        throw AssertionError("ожидался HttpError $code")
    }
}
