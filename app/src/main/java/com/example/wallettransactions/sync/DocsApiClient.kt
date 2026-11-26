package com.example.wallettransactions.sync

import android.content.Context
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Транспорт к wallet-docs через гейтвей (`/api/docs/…`): статус и вопрос
 * (контракт — wallet-docs/openapi.yaml, operationId статуса и `ask`).
 *
 * Клиент не сообщает, кто он: `platform` и роль сервер берёт из токена
 * (openspec docs-assistant, D4). Вопросы и ответы в logcat не пишутся.
 */
class DocsApiClient internal constructor(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: String?
) {

    /** Класс ошибки запроса — по нему экран выбирает одну из трёх строк (design D5). */
    enum class ErrorKind { NETWORK, RELOGIN, BAD_REQUEST, UNAVAILABLE }

    /** Раздел спеки, из которого собран ответ. */
    class Source internal constructor(
        @JvmField val repo: String,
        @JvmField val capability: String,
        @JvmField val heading: String
    )

    class Answer internal constructor(
        @JvmField val answer: String,
        @JvmField val sources: List<Source>
    ) {
        /** Названия разделов через запятую, без повторов; пусто — источников нет. */
        fun sourcesLine(): String {
            val seen = ArrayList<String>()
            for (s in sources) {
                if (s.heading.isNotEmpty() && !seen.contains(s.heading)) seen.add(s.heading)
            }
            return seen.joinToString(", ")
        }
    }

    /**
     * `GET /api/docs/status`: код ответа. Сетевая ошибка — [IOException]
     * (для пункта это то же «скрыть»).
     */
    @Throws(IOException::class)
    fun status(): Int {
        val req = request("api/docs/status").get().build()
        http.newCall(req).execute().use { response ->
            return response.code
        }
    }

    /** `POST /api/docs/ask`: один вопрос — один ответ. Не-2xx — [AiApiClient.HttpError]. */
    @Throws(IOException::class, JSONException::class)
    fun ask(question: String?): Answer {
        val payload = JSONObject().put("question", question)
        val req = request("api/docs/ask")
            .post(payload.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw AiApiClient.HttpError("POST /api/docs/ask", response.code, errorOf(body), "")
            }
            return parseAnswer(body)
        }
    }

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $accessToken")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Ответ ИИ-помощника ждёт LLM — чтение дольше обычного. */
        private const val READ_TIMEOUT_SECS = 60L

        /** Клиент на общем [HttpClients]; токен — уже свежий ([AiApiClient.refreshSession]). */
        @JvmStatic
        fun create(ctx: Context): DocsApiClient {
            val http = HttpClients.get(ctx).newBuilder()
                .readTimeout(READ_TIMEOUT_SECS, TimeUnit.SECONDS)
                .build()
            return DocsApiClient(http, AppSettings.getServerBaseUrl(ctx), TokenStore.getAccessToken(ctx))
        }

        /** Показывать ли пункт: только `200` на статусе (spec, «Пункт в «Ещё» только при доступном сервисе»). */
        @JvmStatic
        fun isVisibleFor(statusCode: Int): Boolean = statusCode == 200

        /** Разбор тела `200`: `answer` и `sources`. */
        @JvmStatic
        @Throws(JSONException::class)
        internal fun parseAnswer(body: String): Answer {
            val json = JSONObject(body)
            if (!json.has("answer")) throw JSONException("no answer in response")
            val sources = ArrayList<Source>()
            val arr = json.optJSONArray("sources")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    sources.add(
                        Source(
                            s.optString("repo", ""), s.optString("capability", ""),
                            s.optString("heading", "")
                        )
                    )
                }
            }
            return Answer(json.getString("answer"), sources)
        }

        /** Класс ошибки по исключению запроса (design D5). */
        @JvmStatic
        fun classify(e: Exception): ErrorKind {
            if (e is AiApiClient.HttpError) {
                val code = e.code
                if (code == 401) return ErrorKind.RELOGIN
                if (code == 400) return ErrorKind.BAD_REQUEST
                return ErrorKind.UNAVAILABLE
            }
            if (e is JSONException) return ErrorKind.UNAVAILABLE
            return ErrorKind.NETWORK
        }

        private fun errorOf(body: String): String =
            try {
                JSONObject(body).optString("error", "")
            } catch (e: JSONException) {
                ""
            }
    }
}
