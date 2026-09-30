package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.AppSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Тонкая обёртка над {@code POST /sync/push}/{@code GET /sync/pull} и
 * {@code POST}/{@code GET /sync/blobs} (servers/TODO_mobile_sync.md, Фаза 3,
 * п. 4, "Иконки"; протокол — README wallet-api, "Синхронизация"/"Иконки —
 * content-addressable blob") — только транспорт (HTTP + заголовок Bearer).
 * Вся бизнес-логика (что отправлять, как применять входящее) — в
 * {@link SyncEngine}, не здесь.
 * <p>
 * Токен ожидается уже свежим — обновление по истёкшей сессии делает
 * {@link AuthSession} ДО того, как этот класс вообще создаётся (см.
 * {@link SyncWorker}): и push, и pull относятся к одной "сессии синка", не
 * должны каждый сам по себе решать, что делать при 401.
 */
final class SyncApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient();
    private final String baseUrl;
    private final String accessToken;

    SyncApiClient(Context ctx, String accessToken) {
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
     * {@code POST /sync/blobs} — тело КАК ЕСТЬ (сырые байты изображения),
     * без JSON-обёртки. Сервер сам считает SHA-256 и отвечает {@code
     * {"sha256": "<64 hex>"}} — идемпотентно (повторная заливка тех же
     * байт ничего не меняет). НЕ часть push/pull-батча — отдельный вызов,
     * ДО того как строка {@code icon} с этим {@code content_sha256} попадёт
     * в {@code changes} (см. {@link SyncEngine#collectDirtyIcons}).
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

    /** Один из двух независимых потоков ответа pull — см. README wallet-api, "Потоков два". */
    static final class PullStream {
        final long nextSeq;
        final JSONObject changes;

        PullStream(long nextSeq, JSONObject changes) {
            this.nextSeq = nextSeq;
            this.changes = changes;
        }
    }

    static final class PullResult {
        final PullStream ledger;
        final PullStream reference;

        PullResult(PullStream ledger, PullStream reference) {
            this.ledger = ledger;
            this.reference = reference;
        }
    }

    PullResult pull(long ledgerSeq, long referenceSeq, int limit) throws IOException, JSONException {
        String url = baseUrl + "sync/pull?ledger_seq=" + ledgerSeq
                + "&reference_seq=" + referenceSeq + "&limit=" + limit;
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("GET /sync/pull -> HTTP " + response.code() + " " + body);
            }
            JSONObject json = new JSONObject(body);
            JSONObject ledgerJson = json.getJSONObject("ledger");
            JSONObject referenceJson = json.getJSONObject("reference");
            return new PullResult(
                    new PullStream(ledgerJson.getLong("next_seq"), ledgerJson.getJSONObject("changes")),
                    new PullStream(referenceJson.getLong("next_seq"), referenceJson.getJSONObject("changes")));
        }
    }
}
