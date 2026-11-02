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
 * {@code GET /sync/blobs} (org/docs/TODO_mobile_sync.md, Фаза 3,
 * п. 4, "Иконки"; протокол — README wallet-api, "Синхронизация"/"Иконки —
 * content-addressable blob") — только транспорт (HTTP + заголовок Bearer).
 * Иконки телефон только скачивает (openspec server-managed-icons): заливки
 * {@code POST /sync/blobs} и проверки {@code HEAD /sync/blobs} здесь нет.
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

    /** Один из двух независимых потоков ответа pull — см. README wallet-api, "Потоков два". */
    static final class PullStream {
        final long nextSeq;
        final JSONObject changes;
        /** Курсор был дальше головы потока — сервер отдал поток с нуля (см. openapi.yaml wallet-api, syncPull). */
        final boolean reset;
        /** Леджер потока; {@code null} — у потока reference и у старого сервера без этого поля. */
        final String ledgerId;

        PullStream(long nextSeq, JSONObject changes, boolean reset, String ledgerId) {
            this.nextSeq = nextSeq;
            this.changes = changes;
            this.reset = reset;
            this.ledgerId = ledgerId;
        }

        static PullStream fromJson(JSONObject json) throws JSONException {
            return new PullStream(json.getLong("next_seq"), json.getJSONObject("changes"),
                    json.optBoolean("reset", false),
                    json.isNull("ledger_id") ? null : json.getString("ledger_id"));
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
            return new PullResult(PullStream.fromJson(ledgerJson), PullStream.fromJson(referenceJson));
        }
    }
}
