package com.example.wallettransactions.sync

import android.content.Context
import android.util.Log
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.TokenStore
import com.example.wallettransactions.ai.ParseExpenseResult
import com.example.wallettransactions.ai.StatementImport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Транспорт к wallet-ai через гейтвей (`/api/ai/…`): статус и задания
 * `parse_expense` (контракт — wallet-ai/openapi.yaml, operationId
 * `aiStatus`, `createJob`, `getJob`, `cancelJob`).
 *
 * В logcat — только id задания, статусы и длительности: текст траты и
 * черновик — данные пользователя (spec ai-expense-entry, «Приватность»).
 */
class AiApiClient internal constructor(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: String?
) {

    enum class SessionState { OK, NO_SESSION, NEEDS_RELINK, NETWORK_ERROR }

    /**
     * Не-2xx ответ: код и `error` из тела (`busy`, `unavailable`, …);
     * `importId` — у 429 `import_in_progress` (какой импорт уже идёт), иначе пусто.
     */
    class HttpError internal constructor(
        what: String,
        @JvmField val code: Int,
        @JvmField val error: String,
        @JvmField val importId: String
    ) : IOException(what + " -> HTTP " + code + (if (error.isEmpty()) "" else " $error"))

    class AiStatus internal constructor(
        @JvmField val enabled: Boolean,
        @JvmField val features: List<String>
    ) {
        fun canParseExpense(): Boolean = enabled && features.contains("parse_expense")
    }

    /** Снимок задания (`Job` в openapi). */
    class Job internal constructor(
        @JvmField val id: String,
        @JvmField val status: String,
        @JvmField val failure: String?,
        @JvmField val result: ParseExpenseResult?
    ) {
        fun isPending(): Boolean = "queued" == status || "running" == status
    }

    @Throws(IOException::class, JSONException::class)
    fun status(): AiStatus {
        val json = call("GET /api/ai/status", request("api/ai/status").get().build())
        val features = ArrayList<String>()
        val arr = json.optJSONArray("features")
        if (arr != null) for (i in 0 until arr.length()) features.add(arr.optString(i))
        return AiStatus(json.optBoolean("enabled", false), features)
    }

    @Throws(IOException::class, JSONException::class)
    fun createJob(text: String?, now: LocalDateTime, defaultWalletUuid: String?, idempotencyKey: String?): Job {
        val input = JSONObject()
            .put("text", text)
            .put("now", JSONObject().put("date", now.format(DATE)).put("time", now.format(TIME)))
        if (defaultWalletUuid != null) input.put("default_wallet_uuid", defaultWalletUuid)
        val payload = JSONObject()
            .put("kind", "parse_expense")
            .put("idempotency_key", idempotencyKey)
            .put("input", input)

        val json = call(
            "POST /api/ai/jobs",
            request("api/ai/jobs").post(payload.toString().toRequestBody(JSON)).build()
        )
        val job = Job(json.optString("job_id", ""), json.optString("status", ""), null, null)
        Log.i(TAG, "job " + job.id + " created: " + job.status)
        return job
    }

    @Throws(IOException::class, JSONException::class)
    fun getJob(id: String, waitSecs: Int): Job {
        val started = System.currentTimeMillis()
        val json = call(
            "GET /api/ai/jobs/{id}",
            request("api/ai/jobs/$id?wait=$waitSecs").get().build()
        )
        val result = json.optJSONObject("result")
        val job = Job(
            json.optString("job_id", id), json.optString("status", ""),
            json.optString("failure", null), if (result == null) null else ParseExpenseResult.fromJson(result)
        )
        Log.i(
            TAG, "job " + job.id + ": " + job.status +
                (if (job.result != null) " (" + job.result.outcome + ")" else "") +
                " in " + (System.currentTimeMillis() - started) + " ms"
        )
        return job
    }

    /** 404 (уже завершено или истекло) — не ошибка для отмены. */
    @Throws(IOException::class)
    fun cancelJob(id: String) {
        val req = request("api/ai/jobs/$id").delete().build()
        http.newCall(req).execute().use { response ->
            Log.i(TAG, "job $id cancel: HTTP " + response.code)
        }
    }

    // ── Импорт выписки (openspec add-ai-statement-import) ─────────────────────
    // В logcat — только id импорта, статус, число строк и длительность: суммы и
    // описания строк — данные пользователя.

    /** `createImport`: файл base64 в JSON (wallet-ai D9, не multipart). Ответ — 202 без строк. */
    @Throws(IOException::class, JSONException::class)
    fun createImport(walletUuid: String?, fileName: String?, bytes: ByteArray): StatementImport {
        val payload = JSONObject()
            .put("wallet_uuid", walletUuid)
            .put(
                "file", JSONObject()
                    .put("name", fileName)
                    .put("content_base64", Base64.getEncoder().encodeToString(bytes))
            )
        val started = System.currentTimeMillis()
        val json = call(
            "POST /api/ai/imports",
            request("api/ai/imports").post(payload.toString().toRequestBody(JSON)).build()
        )
        val imp = StatementImport.fromJson(json)
        Log.i(
            TAG, "import " + imp.id + " created: " + imp.status +
                (if (imp.failure != null) " (" + imp.failure + ")" else "") +
                " in " + (System.currentTimeMillis() - started) + " ms"
        )
        return imp
    }

    /** `listImports`: импорты пользователя, новые первыми, без строк. */
    @Throws(IOException::class, JSONException::class)
    fun listImports(): List<StatementImport> {
        val json = call("GET /api/ai/imports", request("api/ai/imports").get().build())
        val list = StatementImport.listFromJson(json)
        Log.i(TAG, "imports: " + list.size)
        return list
    }

    /** `getImport`: статус и прогресс, в ready/done — строки. */
    @Throws(IOException::class, JSONException::class)
    fun getImport(id: String): StatementImport {
        val started = System.currentTimeMillis()
        val json = call("GET /api/ai/imports/{id}", request("api/ai/imports/$id").get().build())
        return logged(StatementImport.fromJson(json), started)
    }

    /**
     * `patchImportRows` — только категория: сервер выучит правило продавца и
     * проставит её похожим строкам. Сумму и дату телефон на сервер не шлёт.
     */
    @Throws(IOException::class, JSONException::class)
    fun patchImportRowCategory(id: String, rowId: String?, categoryUuid: String?): StatementImport {
        val row = JSONObject().put("row_id", rowId).put("category_uuid", categoryUuid)
        val payload = JSONObject().put("rows", JSONArray().put(row))
        val started = System.currentTimeMillis()
        val json = call(
            "PATCH /api/ai/imports/{id}/rows",
            request("api/ai/imports/$id/rows").patch(payload.toString().toRequestBody(JSON)).build()
        )
        return logged(StatementImport.fromJson(json), started)
    }

    /** `commitImportRows`: строки записаны на телефоне — сервер их замораживает. */
    @Throws(IOException::class, JSONException::class)
    fun commitImportRows(id: String, rowIds: List<String>): StatementImport {
        val ids = JSONArray()
        for (r in rowIds) ids.put(r)
        val payload = JSONObject().put("row_ids", ids)
        val started = System.currentTimeMillis()
        val json = call(
            "POST /api/ai/imports/{id}/committed",
            request("api/ai/imports/$id/committed").post(payload.toString().toRequestBody(JSON)).build()
        )
        return logged(StatementImport.fromJson(json), started)
    }

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $accessToken")

    @Throws(IOException::class, JSONException::class)
    private fun call(what: String, req: Request): JSONObject {
        http.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = if (body.isEmpty()) JSONObject() else parseOrEmpty(body)
            if (!response.isSuccessful) {
                throw HttpError(
                    what, response.code, json.optString("error", ""),
                    json.optString("import_id", "")
                )
            }
            if (body.isEmpty()) throw JSONException("$what: empty response")
            return JSONObject(body)
        }
    }

    companion object {
        private const val TAG = "AiApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Long-poll сервера — до 25 с; чтение должно ждать дольше. */
        const val WAIT_SECS = 25

        /** Клиент на общем [HttpClients] с чтением дольше long-poll. Токен — уже свежий, см. [refreshSession]. */
        @JvmStatic
        fun create(ctx: Context): AiApiClient {
            val http = HttpClients.get(ctx).newBuilder()
                .readTimeout((WAIT_SECS + 10).toLong(), TimeUnit.SECONDS)
                .build()
            return AiApiClient(http, AppSettings.getServerBaseUrl(ctx), TokenStore.getAccessToken(ctx))
        }

        /** Свежий access-токен перед запросами; блокирующий — не с главного потока. */
        @JvmStatic
        fun refreshSession(ctx: Context): SessionState =
            when (AuthSession.ensureFreshForRequest(ctx)) {
                AuthSession.Outcome.OK -> SessionState.OK
                AuthSession.Outcome.NO_SESSION -> SessionState.NO_SESSION
                AuthSession.Outcome.NEEDS_RELINK -> SessionState.NEEDS_RELINK
                AuthSession.Outcome.NETWORK_ERROR -> SessionState.NETWORK_ERROR
            }

        /** Есть ли сохранённая сессия — без сети, для решения на главном потоке. */
        @JvmStatic
        fun hasSession(ctx: Context): Boolean =
            TokenStore.getRefreshToken(ctx) != null && !AppSettings.isNeedsRelink(ctx)

        private fun logged(imp: StatementImport, started: Long): StatementImport {
            Log.i(
                TAG, "import " + imp.id + ": " + imp.status + ", rows " + imp.rows.size +
                    " in " + (System.currentTimeMillis() - started) + " ms"
            )
            return imp
        }

        private fun parseOrEmpty(body: String): JSONObject =
            try {
                JSONObject(body)
            } catch (e: JSONException) {
                JSONObject()
            }
    }
}
