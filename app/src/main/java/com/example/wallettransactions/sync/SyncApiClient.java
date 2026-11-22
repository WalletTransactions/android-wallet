package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.AppSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Тонкая обёртка над {@code POST /sync/push}/{@code GET /sync/pull} и
 * {@code GET /sync/blobs} (org/docs/TODO_mobile_sync.md, Фаза 3,
 * п. 4, "Иконки"; протокол — README wallet-api, "Синхронизация"/"Иконки —
 * content-addressable blob") — только транспорт (HTTP + заголовок Bearer).
 * Свои иконки уходят на сервер только по настройке (openspec optional-icon-upload):
 * {@link #blobExists}/{@link #uploadBlob} зовёт лишь {@code SyncEngine.pushOwnIcons}.
 * Не {@code final} — юнит-тесты подменяют транспорт наследником.
 * Вся бизнес-логика (что отправлять, как применять входящее) — в
 * {@link SyncEngine}, не здесь.
 * <p>
 * Токен ожидается уже свежим — обновление по истёкшей сессии делает
 * {@link AuthSession} ДО того, как этот класс вообще создаётся (см.
 * {@link SyncWorker}): и push, и pull относятся к одной "сессии синка", не
 * должны каждый сам по себе решать, что делать при 401.
 */
class SyncApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String baseUrl;
    private final String accessToken;

    SyncApiClient(Context ctx, String accessToken) {
        this.http = HttpClients.get(ctx);
        this.baseUrl = AppSettings.getServerBaseUrl(ctx);
        this.accessToken = accessToken;
    }

    /** Код ответа + разобранное тело — и 200, и 409 несут осмысленный JSON, разбирать их одинаково. */
    static final class HttpResult {
        final int status;
        final JSONObject body;

        HttpResult(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    HttpResult push(String batchId, JSONObject changes) throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        payload.put("changes", changes);

        Request request = new Request.Builder()
                .url(baseUrl + "sync/push")
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return new HttpResult(response.code(), new JSONObject(body.isEmpty() ? "{}" : body));
        }
    }

    /**
     * {@code GET /sync/blobs/:sha256} — сырые байты содержимого. Только для
     * иконки, которой ещё нет локально (см. {@link SyncEngine#applyIcon}) —
     * уже существующая по uuid не перекачивается вообще.
     */
    byte[] downloadBlob(String sha256Hex) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "sync/blobs/" + sha256Hex)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("GET /sync/blobs/" + sha256Hex + " -> HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    /**
     * Сервер ответил, но не тем кодом — в отличие от «голого» {@link IOException} это не
     * обрыв сети. Нужен шагу отправки иконок: HTTP-отказ пропускает одну иконку, обрыв
     * сети завершает проход «нет сети».
     */
    static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    /** Код отказа сервера, когда приём своих иконок выключен (wallet-api, {@code LEDGER_ICON_UPLOAD}). */
    static final String ICON_UPLOAD_DISABLED = "icon_upload_disabled";

    /**
     * {@code HEAD /sync/blobs/:sha256} — есть ли уже такое содержимое на сервере (200 — есть,
     * 404 — нет; cpp-httplib отвечает на HEAD GET-обработчиком без тела). Не перезаливать
     * байты, которые сервер уже видел (инцидент 2026-10-11: 144 иконки на каждом синке через
     * медленный Tailscale-relay).
     */
    boolean blobExists(String sha256Hex) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "sync/blobs/" + sha256Hex)
                .header("Authorization", "Bearer " + accessToken)
                .head()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 200) return true;
            if (response.code() == 404) return false;
            throw new HttpStatusException("HEAD /sync/blobs/" + sha256Hex + " -> HTTP " + response.code(),
                    response.code());
        }
    }

    /**
     * {@code POST /sync/blobs} — сырые байты изображения; сервер сам считает SHA-256 и отвечает
     * {@code {"sha256": "<64 hex>"}}, идемпотентно. При выключенном приёме своих иконок —
     * {@code 403 {"error":"icon_upload_disabled"}} (см. {@link #isIconUploadDisabled}).
     */
    HttpResult uploadBlob(byte[] bytes) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(baseUrl + "sync/blobs")
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(bytes, MediaType.get("application/octet-stream")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return new HttpResult(response.code(), new JSONObject(body.isEmpty() ? "{}" : body));
        }
    }

    /** Отказ из-за флага приёма своих иконок: {@code 403} с {@code error = icon_upload_disabled}. */
    static boolean isIconUploadDisabled(int status, JSONObject body) {
        return status == 403 && body != null && ICON_UPLOAD_DISABLED.equals(body.optString("error", null));
    }

    /**
     * uuid строк {@code icon}, которые сервер отбросил в ответе {@code 200} push
     * ({@code icons_rejected.uuids}); поля нет — пустой список.
     */
    static List<String> iconsRejected(JSONObject pushBody) {
        List<String> out = new ArrayList<>();
        JSONObject rejected = pushBody == null ? null : pushBody.optJSONObject("icons_rejected");
        if (rejected == null) return out;
        JSONArray uuids = rejected.optJSONArray("uuids");
        if (uuids == null) return out;
        for (int i = 0; i < uuids.length(); i++) {
            String uuid = uuids.optString(i, null);
            if (uuid != null && !uuid.isEmpty()) out.add(uuid);
        }
        return out;
    }

    /** Один из двух независимых потоков ответа pull — см. README wallet-api, "Потоков два". */
    static final class PullStream {
        final long nextSeq;
        final JSONObject changes;
        /** Сервер отдал поток с нуля — курсор впереди головы или отстал от чистки (см. {@link #resetReason}). */
        final boolean reset;
        /** Леджер потока; {@code null} — у потока reference и у старого сервера без этого поля. */
        final String ledgerId;
        /**
         * Причина сброса: {@code "ahead"} — курсор дальше головы, {@code "purged"} —
         * сервер вычистил удаления, которых телефон ещё не видел (openspec
         * purge-synced-tombstones). {@code null} — без сброса или старый сервер.
         * Поведение для любой причины одно, поле — для журнала.
         */
        final String resetReason;

        PullStream(long nextSeq, JSONObject changes, boolean reset, String ledgerId) {
            this(nextSeq, changes, reset, ledgerId, null);
        }

        PullStream(long nextSeq, JSONObject changes, boolean reset, String ledgerId, String resetReason) {
            this.nextSeq = nextSeq;
            this.changes = changes;
            this.reset = reset;
            this.ledgerId = ledgerId;
            this.resetReason = resetReason;
        }

        static PullStream fromJson(JSONObject json) throws JSONException {
            return new PullStream(json.getLong("next_seq"), json.getJSONObject("changes"),
                    json.optBoolean("reset", false),
                    json.isNull("ledger_id") ? null : json.getString("ledger_id"),
                    json.isNull("reset_reason") ? null : json.getString("reset_reason"));
        }
    }

    static final class PullResult {
        final PullStream ledger;
        final PullStream reference;
        /**
         * {@code capabilities.icon_upload} — принимает ли сервер свои иконки (openspec
         * optional-icon-upload). Нет блока (старый сервер) — {@code false}.
         */
        final boolean iconUploadAllowed;

        PullResult(PullStream ledger, PullStream reference) {
            this(ledger, reference, false);
        }

        PullResult(PullStream ledger, PullStream reference, boolean iconUploadAllowed) {
            this.ledger = ledger;
            this.reference = reference;
            this.iconUploadAllowed = iconUploadAllowed;
        }

        static PullResult fromJson(JSONObject json) throws JSONException {
            JSONObject capabilities = json.optJSONObject("capabilities");
            boolean iconUpload = capabilities != null && capabilities.optBoolean("icon_upload", false);
            return new PullResult(PullStream.fromJson(json.getJSONObject("ledger")),
                    PullStream.fromJson(json.getJSONObject("reference")), iconUpload);
        }
    }

    /**
     * Адрес {@code GET /sync/pull}. {@code ledgerId} — леджер, к которому относится
     * {@code ledgerSeq} ({@code AppSettings.getSyncLedgerId}); сервер записывает
     * курсоры запроса как подтверждение устройства и по {@code ledger_id} решает,
     * засчитывать ли курсор потока леджера (openspec purge-synced-tombstones).
     * {@code null} — леджер ещё не известен, параметр не передаётся. Старый сервер
     * лишний параметр игнорирует.
     */
    static String pullUrl(String baseUrl, long ledgerSeq, long referenceSeq, int limit, String ledgerId) {
        String url = baseUrl + "sync/pull?ledger_seq=" + ledgerSeq
                + "&reference_seq=" + referenceSeq + "&limit=" + limit;
        if (ledgerId != null && !ledgerId.isEmpty()) {
            try {
                url += "&ledger_id=" + URLEncoder.encode(ledgerId, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e); // UTF-8 есть всегда
            }
        }
        return url;
    }

    PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId)
            throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(pullUrl(baseUrl, ledgerSeq, referenceSeq, limit, ledgerId))
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("GET /sync/pull -> HTTP " + response.code() + " " + body);
            }
            return PullResult.fromJson(new JSONObject(body));
        }
    }
}
