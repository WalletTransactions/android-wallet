package com.example.wallettransactions.sync;

import android.content.Context;
import android.util.Log;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.TokenStore;
import com.example.wallettransactions.ai.ParseExpenseResult;
import com.example.wallettransactions.ai.StatementImport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Транспорт к wallet-ai через гейтвей ({@code /api/ai/*}): статус и задания
 * {@code parse_expense} (контракт — wallet-ai/openapi.yaml, operationId
 * {@code aiStatus}, {@code createJob}, {@code getJob}, {@code cancelJob}).
 * <p>
 * В logcat — только id задания, статусы и длительности: текст траты и
 * черновик — данные пользователя (spec ai-expense-entry, «Приватность»).
 */
public final class AiApiClient {

    private static final String TAG = "AiApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Long-poll сервера — до 25 с; чтение должно ждать дольше. */
    public static final int WAIT_SECS = 25;

    public enum SessionState { OK, NO_SESSION, NEEDS_RELINK, NETWORK_ERROR }

    /**
     * Не-2xx ответ: код и {@code error} из тела ({@code busy}, {@code unavailable}, …);
     * {@code importId} — у 429 {@code import_in_progress} (какой импорт уже идёт), иначе пусто.
     */
    public static final class HttpError extends IOException {
        public final int code;
        public final String error;
        public final String importId;

        HttpError(String what, int code, String error, String importId) {
            super(what + " -> HTTP " + code + (error.isEmpty() ? "" : " " + error));
            this.code = code;
            this.error = error;
            this.importId = importId;
        }
    }

    public static final class AiStatus {
        public final boolean enabled;
        public final List<String> features;

        AiStatus(boolean enabled, List<String> features) {
            this.enabled = enabled;
            this.features = features;
        }

        public boolean canParseExpense() {
            return enabled && features.contains("parse_expense");
        }
    }

    /** Снимок задания ({@code Job} в openapi). */
    public static final class Job {
        public final String id;
        public final String status;
        public final String failure;
        public final ParseExpenseResult result;

        Job(String id, String status, String failure, ParseExpenseResult result) {
            this.id = id;
            this.status = status;
            this.failure = failure;
            this.result = result;
        }

        public boolean isPending() {
            return "queued".equals(status) || "running".equals(status);
        }
    }

    private final OkHttpClient http;
    private final String baseUrl;
    private final String accessToken;

    AiApiClient(OkHttpClient http, String baseUrl, String accessToken) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
    }

    /** Клиент на общем {@link HttpClients} с чтением дольше long-poll. Токен — уже свежий, см. {@link #refreshSession}. */
    public static AiApiClient create(Context ctx) {
        OkHttpClient http = HttpClients.get(ctx).newBuilder()
                .readTimeout(WAIT_SECS + 10, TimeUnit.SECONDS)
                .build();
        return new AiApiClient(http, AppSettings.getServerBaseUrl(ctx), TokenStore.getAccessToken(ctx));
    }

    /** Свежий access-токен перед запросами; блокирующий — не с главного потока. */
    public static SessionState refreshSession(Context ctx) {
        switch (AuthSession.ensureFreshForRequest(ctx)) {
            case OK: return SessionState.OK;
            case NO_SESSION: return SessionState.NO_SESSION;
            case NEEDS_RELINK: return SessionState.NEEDS_RELINK;
            case NETWORK_ERROR:
            default: return SessionState.NETWORK_ERROR;
        }
    }

    /** Есть ли сохранённая сессия — без сети, для решения на главном потоке. */
    public static boolean hasSession(Context ctx) {
        return TokenStore.getRefreshToken(ctx) != null && !AppSettings.isNeedsRelink(ctx);
    }

    public AiStatus status() throws IOException, JSONException {
        JSONObject json = call("GET /api/ai/status", request("api/ai/status").get().build());
        List<String> features = new ArrayList<>();
        JSONArray arr = json.optJSONArray("features");
        if (arr != null) for (int i = 0; i < arr.length(); i++) features.add(arr.optString(i));
        return new AiStatus(json.optBoolean("enabled", false), features);
    }

    public Job createJob(String text, LocalDateTime now, String defaultWalletUuid, String idempotencyKey)
            throws IOException, JSONException {
        JSONObject input = new JSONObject()
                .put("text", text)
                .put("now", new JSONObject().put("date", now.format(DATE)).put("time", now.format(TIME)));
        if (defaultWalletUuid != null) input.put("default_wallet_uuid", defaultWalletUuid);
        JSONObject payload = new JSONObject()
                .put("kind", "parse_expense")
                .put("idempotency_key", idempotencyKey)
                .put("input", input);

        JSONObject json = call("POST /api/ai/jobs",
                request("api/ai/jobs").post(RequestBody.create(payload.toString(), JSON)).build());
        Job job = new Job(json.optString("job_id", ""), json.optString("status", ""), null, null);
        Log.i(TAG, "job " + job.id + " created: " + job.status);
        return job;
    }

    public Job getJob(String id, int waitSecs) throws IOException, JSONException {
        long started = System.currentTimeMillis();
        JSONObject json = call("GET /api/ai/jobs/{id}",
                request("api/ai/jobs/" + id + "?wait=" + waitSecs).get().build());
        JSONObject result = json.optJSONObject("result");
        Job job = new Job(json.optString("job_id", id), json.optString("status", ""),
                json.optString("failure", null), result == null ? null : ParseExpenseResult.fromJson(result));
        Log.i(TAG, "job " + job.id + ": " + job.status
                + (job.result != null ? " (" + job.result.outcome + ")" : "")
                + " in " + (System.currentTimeMillis() - started) + " ms");
        return job;
    }

    /** 404 (уже завершено или истекло) — не ошибка для отмены. */
    public void cancelJob(String id) throws IOException {
        Request req = request("api/ai/jobs/" + id).delete().build();
        try (Response response = http.newCall(req).execute()) {
            Log.i(TAG, "job " + id + " cancel: HTTP " + response.code());
        }
    }

    // ── Импорт выписки (openspec add-ai-statement-import) ─────────────────────
    // В logcat — только id импорта, статус, число строк и длительность: суммы и
    // описания строк — данные пользователя.

    /** {@code createImport}: файл base64 в JSON (wallet-ai D9, не multipart). Ответ — 202 без строк. */
    public StatementImport createImport(String walletUuid, String fileName, byte[] bytes)
            throws IOException, JSONException {
        JSONObject payload = new JSONObject()
                .put("wallet_uuid", walletUuid)
                .put("file", new JSONObject()
                        .put("name", fileName)
                        .put("content_base64", Base64.getEncoder().encodeToString(bytes)));
        long started = System.currentTimeMillis();
        JSONObject json = call("POST /api/ai/imports",
                request("api/ai/imports").post(RequestBody.create(payload.toString(), JSON)).build());
        StatementImport imp = StatementImport.fromJson(json);
        Log.i(TAG, "import " + imp.id + " created: " + imp.status
                + (imp.failure != null ? " (" + imp.failure + ")" : "")
                + " in " + (System.currentTimeMillis() - started) + " ms");
        return imp;
    }

    /** {@code listImports}: импорты пользователя, новые первыми, без строк. */
    public List<StatementImport> listImports() throws IOException, JSONException {
        JSONObject json = call("GET /api/ai/imports", request("api/ai/imports").get().build());
        List<StatementImport> list = StatementImport.listFromJson(json);
        Log.i(TAG, "imports: " + list.size());
        return list;
    }

    /** {@code getImport}: статус и прогресс, в ready/done — строки. */
    public StatementImport getImport(String id) throws IOException, JSONException {
        long started = System.currentTimeMillis();
        JSONObject json = call("GET /api/ai/imports/{id}", request("api/ai/imports/" + id).get().build());
        return logged(StatementImport.fromJson(json), started);
    }

    /**
     * {@code patchImportRows} — только категория: сервер выучит правило продавца и
     * проставит её похожим строкам. Сумму и дату телефон на сервер не шлёт.
     */
    public StatementImport patchImportRowCategory(String id, String rowId, String categoryUuid)
            throws IOException, JSONException {
        JSONObject row = new JSONObject().put("row_id", rowId).put("category_uuid", categoryUuid);
        JSONObject payload = new JSONObject().put("rows", new JSONArray().put(row));
        long started = System.currentTimeMillis();
        JSONObject json = call("PATCH /api/ai/imports/{id}/rows",
                request("api/ai/imports/" + id + "/rows").patch(RequestBody.create(payload.toString(), JSON)).build());
        return logged(StatementImport.fromJson(json), started);
    }

    /** {@code commitImportRows}: строки записаны на телефоне — сервер их замораживает. */
    public StatementImport commitImportRows(String id, List<String> rowIds) throws IOException, JSONException {
        JSONArray ids = new JSONArray();
        for (String r : rowIds) ids.put(r);
        JSONObject payload = new JSONObject().put("row_ids", ids);
        long started = System.currentTimeMillis();
        JSONObject json = call("POST /api/ai/imports/{id}/committed",
                request("api/ai/imports/" + id + "/committed").post(RequestBody.create(payload.toString(), JSON)).build());
        return logged(StatementImport.fromJson(json), started);
    }

    private static StatementImport logged(StatementImport imp, long started) {
        Log.i(TAG, "import " + imp.id + ": " + imp.status + ", rows " + imp.rows.size()
                + " in " + (System.currentTimeMillis() - started) + " ms");
        return imp;
    }

    private Request.Builder request(String path) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer " + accessToken);
    }

    private JSONObject call(String what, Request req) throws IOException, JSONException {
        try (Response response = http.newCall(req).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = body.isEmpty() ? new JSONObject() : parseOrEmpty(body);
            if (!response.isSuccessful()) {
                throw new HttpError(what, response.code(), json.optString("error", ""),
                        json.optString("import_id", ""));
            }
            if (body.isEmpty()) throw new JSONException(what + ": пустой ответ");
            return new JSONObject(body);
        }
    }

    private static JSONObject parseOrEmpty(String body) {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
