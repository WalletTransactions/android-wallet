package com.example.wallettransactions.sync;

import android.content.Context;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.TokenStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Транспорт к wallet-docs через гейтвей ({@code /api/docs/*}): статус и вопрос
 * (контракт — wallet-docs/openapi.yaml, operationId статуса и {@code ask}).
 * <p>
 * Клиент не сообщает, кто он: {@code platform} и роль сервер берёт из токена
 * (openspec docs-assistant, D4). Вопросы и ответы в logcat не пишутся.
 */
public final class DocsApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Ответ ИИ-помощника ждёт LLM — чтение дольше обычного. */
    private static final int READ_TIMEOUT_SECS = 60;

    /** Класс ошибки запроса — по нему экран выбирает одну из трёх строк (design D5). */
    public enum ErrorKind { NETWORK, RELOGIN, BAD_REQUEST, UNAVAILABLE }

    /** Раздел спеки, из которого собран ответ. */
    public static final class Source {
        public final String repo;
        public final String capability;
        public final String heading;

        Source(String repo, String capability, String heading) {
            this.repo = repo;
            this.capability = capability;
            this.heading = heading;
        }
    }

    public static final class Answer {
        public final String answer;
        public final List<Source> sources;

        Answer(String answer, List<Source> sources) {
            this.answer = answer;
            this.sources = sources;
        }

        /** Названия разделов через запятую, без повторов; пусто — источников нет. */
        public String sourcesLine() {
            List<String> seen = new ArrayList<>();
            for (Source s : sources) {
                if (!s.heading.isEmpty() && !seen.contains(s.heading)) seen.add(s.heading);
            }
            return String.join(", ", seen);
        }
    }

    private final OkHttpClient http;
    private final String baseUrl;
    private final String accessToken;

    DocsApiClient(OkHttpClient http, String baseUrl, String accessToken) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
    }

    /** Клиент на общем {@link HttpClients}; токен — уже свежий ({@link AiApiClient#refreshSession}). */
    public static DocsApiClient create(Context ctx) {
        OkHttpClient http = HttpClients.get(ctx).newBuilder()
                .readTimeout(READ_TIMEOUT_SECS, TimeUnit.SECONDS)
                .build();
        return new DocsApiClient(http, AppSettings.getServerBaseUrl(ctx), TokenStore.getAccessToken(ctx));
    }

    /** Показывать ли пункт: только {@code 200} на статусе (spec, «Пункт в «Ещё» только при доступном сервисе»). */
    public static boolean isVisibleFor(int statusCode) {
        return statusCode == 200;
    }

    /**
     * {@code GET /api/docs/status}: код ответа. Сетевая ошибка — {@link IOException}
     * (для пункта это то же «скрыть»).
     */
    public int status() throws IOException {
        Request req = request("api/docs/status").get().build();
        try (Response response = http.newCall(req).execute()) {
            return response.code();
        }
    }

    /** {@code POST /api/docs/ask}: один вопрос — один ответ. Не-2xx — {@link AiApiClient.HttpError}. */
    public Answer ask(String question) throws IOException, JSONException {
        JSONObject payload = new JSONObject().put("question", question);
        Request req = request("api/docs/ask")
                .post(RequestBody.create(payload.toString(), JSON)).build();
        try (Response response = http.newCall(req).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new AiApiClient.HttpError("POST /api/docs/ask", response.code(),
                        errorOf(body), "");
            }
            return parseAnswer(body);
        }
    }

    /** Разбор тела {@code 200}: {@code answer} и {@code sources}. */
    static Answer parseAnswer(String body) throws JSONException {
        JSONObject json = new JSONObject(body);
        if (!json.has("answer")) throw new JSONException("no answer in response");
        List<Source> sources = new ArrayList<>();
        JSONArray arr = json.optJSONArray("sources");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                sources.add(new Source(s.optString("repo", ""), s.optString("capability", ""),
                        s.optString("heading", "")));
            }
        }
        return new Answer(json.getString("answer"), sources);
    }

    /** Класс ошибки по исключению запроса (design D5). */
    public static ErrorKind classify(Exception e) {
        if (e instanceof AiApiClient.HttpError) {
            int code = ((AiApiClient.HttpError) e).code;
            if (code == 401) return ErrorKind.RELOGIN;
            if (code == 400) return ErrorKind.BAD_REQUEST;
            return ErrorKind.UNAVAILABLE;
        }
        if (e instanceof JSONException) return ErrorKind.UNAVAILABLE;
        return ErrorKind.NETWORK;
    }

    private static String errorOf(String body) {
        try {
            return new JSONObject(body).optString("error", "");
        } catch (JSONException e) {
            return "";
        }
    }

    private Request.Builder request(String path) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer " + accessToken);
    }
}
