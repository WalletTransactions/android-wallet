package com.example.wallettransactions.sync

import android.content.Context
import com.example.wallettransactions.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * Тонкая обёртка над `POST /sync/push`/`GET /sync/pull` и
 * `GET /sync/blobs` (org/docs/TODO_mobile_sync.md, Фаза 3,
 * п. 4, "Иконки"; протокол — README wallet-api, "Синхронизация"/"Иконки —
 * content-addressable blob") — только транспорт (HTTP + заголовок Bearer).
 * Свои иконки уходят на сервер только по настройке (openspec optional-icon-upload):
 * [blobExists]/[uploadBlob] зовёт лишь `SyncEngine.pushOwnIcons`.
 * Не `final` — юнит-тесты подменяют транспорт наследником.
 * Вся бизнес-логика (что отправлять, как применять входящее) — в
 * [SyncEngine], не здесь.
 *
 * Токен ожидается уже свежим — обновление по истёкшей сессии делает
 * [AuthSession] ДО того, как этот класс вообще создаётся (см.
 * [SyncWorker]): и push, и pull относятся к одной "сессии синка", не
 * должны каждый сам по себе решать, что делать при 401.
 */
internal open class SyncApiClient(ctx: Context, private val accessToken: String?) {

    private val http: OkHttpClient = HttpClients.get(ctx)
    private val baseUrl: String = AppSettings.getServerBaseUrl(ctx)

    /** Код ответа + разобранное тело — и 200, и 409 несут осмысленный JSON, разбирать их одинаково. */
    class HttpResult(val status: Int, val body: JSONObject)

    @Throws(IOException::class, JSONException::class)
    open fun push(batchId: String, changes: JSONObject): HttpResult {
        val payload = JSONObject()
        payload.put("batch_id", batchId)
        payload.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        payload.put("changes", changes)

        val request = Request.Builder()
            .url(baseUrl + "sync/push")
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            return HttpResult(response.code, JSONObject(if (body.isEmpty()) "{}" else body))
        }
    }

    /**
     * `GET /sync/blobs/:sha256` — сырые байты содержимого. Только для
     * иконки, которой ещё нет локально (см. [SyncEngine.applyIcon]) —
     * уже существующая по uuid не перекачивается вообще.
     */
    @Throws(IOException::class)
    open fun downloadBlob(sha256Hex: String): ByteArray {
        val request = Request.Builder()
            .url(baseUrl + "sync/blobs/" + sha256Hex)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val responseBody = response.body
            if (!response.isSuccessful || responseBody == null) {
                throw IOException("GET /sync/blobs/" + sha256Hex + " -> HTTP " + response.code)
            }
            return responseBody.bytes()
        }
    }

    /**
     * Сервер ответил, но не тем кодом — в отличие от «голого» [IOException] это не
     * обрыв сети. Нужен шагу отправки иконок: HTTP-отказ пропускает одну иконку, обрыв
     * сети завершает проход «нет сети».
     */
    class HttpStatusException(message: String, val status: Int) : IOException(message)

    /**
     * `HEAD /sync/blobs/:sha256` — есть ли уже такое содержимое на сервере (200 — есть,
     * 404 — нет; cpp-httplib отвечает на HEAD GET-обработчиком без тела). Не перезаливать
     * байты, которые сервер уже видел (инцидент 2026-10-11: 144 иконки на каждом синке через
     * медленный Tailscale-relay).
     */
    @Throws(IOException::class)
    open fun blobExists(sha256Hex: String): Boolean {
        val request = Request.Builder()
            .url(baseUrl + "sync/blobs/" + sha256Hex)
            .header("Authorization", "Bearer $accessToken")
            .head()
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code == 200) return true
            if (response.code == 404) return false
            throw HttpStatusException(
                "HEAD /sync/blobs/" + sha256Hex + " -> HTTP " + response.code,
                response.code
            )
        }
    }

    /**
     * `POST /sync/blobs` — сырые байты изображения; сервер сам считает SHA-256 и отвечает
     * `{"sha256": "<64 hex>"}`, идемпотентно. При выключенном приёме своих иконок —
     * `403 {"error":"icon_upload_disabled"}` (см. [isIconUploadDisabled]).
     */
    @Throws(IOException::class, JSONException::class)
    open fun uploadBlob(bytes: ByteArray): HttpResult {
        val request = Request.Builder()
            .url(baseUrl + "sync/blobs")
            .header("Authorization", "Bearer $accessToken")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            return HttpResult(response.code, JSONObject(if (body.isEmpty()) "{}" else body))
        }
    }

    /** Один из двух независимых потоков ответа pull — см. README wallet-api, "Потоков два". */
    class PullStream(
        val nextSeq: Long,
        val changes: JSONObject,
        /** Сервер отдал поток с нуля — курсор впереди головы или отстал от чистки (см. [resetReason]). */
        val reset: Boolean,
        /** Леджер потока; `null` — у потока reference и у старого сервера без этого поля. */
        val ledgerId: String?,
        /**
         * Причина сброса: `"ahead"` — курсор дальше головы, `"purged"` —
         * сервер вычистил удаления, которых телефон ещё не видел (openspec
         * purge-synced-tombstones). `null` — без сброса или старый сервер.
         * Поведение для любой причины одно, поле — для журнала.
         */
        val resetReason: String?
    ) {
        constructor(nextSeq: Long, changes: JSONObject, reset: Boolean, ledgerId: String?) :
            this(nextSeq, changes, reset, ledgerId, null)

        companion object {
            @JvmStatic
            @Throws(JSONException::class)
            fun fromJson(json: JSONObject): PullStream =
                PullStream(
                    json.getLong("next_seq"), json.getJSONObject("changes"),
                    json.optBoolean("reset", false),
                    if (json.isNull("ledger_id")) null else json.getString("ledger_id"),
                    if (json.isNull("reset_reason")) null else json.getString("reset_reason")
                )
        }
    }

    class PullResult(
        val ledger: PullStream,
        val reference: PullStream,
        /**
         * `capabilities.icon_upload` — принимает ли сервер свои иконки (openspec
         * optional-icon-upload). Нет блока (старый сервер) — `false`.
         */
        val iconUploadAllowed: Boolean
    ) {
        constructor(ledger: PullStream, reference: PullStream) : this(ledger, reference, false)

        companion object {
            @JvmStatic
            @Throws(JSONException::class)
            fun fromJson(json: JSONObject): PullResult {
                val capabilities = json.optJSONObject("capabilities")
                val iconUpload = capabilities != null && capabilities.optBoolean("icon_upload", false)
                return PullResult(
                    PullStream.fromJson(json.getJSONObject("ledger")),
                    PullStream.fromJson(json.getJSONObject("reference")), iconUpload
                )
            }
        }
    }

    @Throws(IOException::class, JSONException::class)
    open fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): PullResult {
        val request = Request.Builder()
            .url(pullUrl(baseUrl, ledgerSeq, referenceSeq, limit, ledgerId))
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("GET /sync/pull -> HTTP " + response.code + " " + body)
            }
            return PullResult.fromJson(JSONObject(body))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Код отказа сервера, когда приём своих иконок выключен (wallet-api, `LEDGER_ICON_UPLOAD`). */
        const val ICON_UPLOAD_DISABLED = "icon_upload_disabled"

        /** Отказ из-за флага приёма своих иконок: `403` с `error = icon_upload_disabled`. */
        @JvmStatic
        fun isIconUploadDisabled(status: Int, body: JSONObject?): Boolean =
            status == 403 && body != null && ICON_UPLOAD_DISABLED == body.optString("error", null)

        /**
         * uuid строк `icon`, которые сервер отбросил в ответе `200` push
         * (`icons_rejected.uuids`); поля нет — пустой список.
         */
        @JvmStatic
        fun iconsRejected(pushBody: JSONObject?): List<String> {
            val out = ArrayList<String>()
            val rejected = pushBody?.optJSONObject("icons_rejected") ?: return out
            val uuids = rejected.optJSONArray("uuids") ?: return out
            for (i in 0 until uuids.length()) {
                val uuid = uuids.optString(i, null)
                if (uuid != null && uuid.isNotEmpty()) out.add(uuid)
            }
            return out
        }

        /**
         * Адрес `GET /sync/pull`. `ledgerId` — леджер, к которому относится
         * `ledgerSeq` (`AppSettings.getSyncLedgerId`); сервер записывает
         * курсоры запроса как подтверждение устройства и по `ledger_id` решает,
         * засчитывать ли курсор потока леджера (openspec purge-synced-tombstones).
         * `null` — леджер ещё не известен, параметр не передаётся. Старый сервер
         * лишний параметр игнорирует.
         */
        @JvmStatic
        fun pullUrl(baseUrl: String, ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): String {
            var url = baseUrl + "sync/pull?ledger_seq=" + ledgerSeq +
                "&reference_seq=" + referenceSeq + "&limit=" + limit
            if (ledgerId != null && ledgerId.isNotEmpty()) {
                try {
                    url += "&ledger_id=" + URLEncoder.encode(ledgerId, "UTF-8")
                } catch (e: UnsupportedEncodingException) {
                    throw IllegalStateException(e) // UTF-8 есть всегда
                }
            }
            return url
        }
    }
}
