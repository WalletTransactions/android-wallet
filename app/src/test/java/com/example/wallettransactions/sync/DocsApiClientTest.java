package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * {@link DocsApiClient}: форма запросов по wallet-docs/openapi.yaml, разбор ответа,
 * классы ошибок и таблица «ответ статуса → видимость пункта» (openspec docs-assistant).
 */
@RunWith(RobolectricTestRunner.class)
public class DocsApiClientTest {

    private final List<Request> requests = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private int nextCode = 200;
    private String nextBody = "{}";
    private boolean failNetwork;

    private DocsApiClient client;

    @Before
    public void setUp() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    requests.add(req);
                    if (req.body() != null) {
                        Buffer buf = new Buffer();
                        req.body().writeTo(buf);
                        requestBodies.add(buf.readUtf8());
                    } else {
                        requestBodies.add(null);
                    }
                    if (failNetwork) throw new IOException("no network");
                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(nextCode)
                            .message("x")
                            .body(ResponseBody.create(nextBody, MediaType.get("application/json")))
                            .build();
                })
                .build();
        client = new DocsApiClient(http, "http://gw/", "tok");
    }

    @Test
    public void ask_sendsQuestionOnly() throws Exception {
        nextBody = "{\"answer\":\"a\",\"sources\":[]}";

        client.ask("Как работает синхронизация?");

        Request req = requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("http://gw/api/docs/ask", req.url().toString());
        assertEquals("Bearer tok", req.header("Authorization"));
        JSONObject body = new JSONObject(requestBodies.get(0));
        assertEquals("Как работает синхронизация?", body.getString("question"));
        assertEquals("клиент не передаёт platform/client (D4)", 1, body.length());
    }

    @Test
    public void ask_parsesAnswerAndSources() throws Exception {
        nextBody = "{\"answer\":\"Раз в 15 минут.\",\"sources\":["
                + "{\"repo\":\"android-wallet\",\"capability\":\"sync-run\",\"heading\":\"Периодический запуск\"},"
                + "{\"repo\":\"wallet-api\",\"capability\":\"sync\",\"heading\":\"Периодический запуск\"},"
                + "{\"repo\":\"wallet-api\",\"capability\":\"sync\",\"heading\":\"Обмен изменениями\"}]}";

        DocsApiClient.Answer a = client.ask("q");

        assertEquals("Раз в 15 минут.", a.answer);
        assertEquals(3, a.sources.size());
        assertEquals("sync-run", a.sources.get(0).capability);
        assertEquals("повторы названий схлопываются", "Периодический запуск, Обмен изменениями", a.sourcesLine());
    }

    @Test
    public void ask_emptySources_hasEmptyLine() throws Exception {
        nextBody = "{\"answer\":\"В описании системы этого нет.\",\"sources\":[]}";
        assertEquals("", client.ask("q").sourcesLine());
    }

    @Test(expected = JSONException.class)
    public void ask_bodyWithoutAnswer_isMalformed() throws Exception {
        nextBody = "{\"sources\":[]}";
        client.ask("q");
    }

    @Test
    public void errors_classifiedByStatus() throws Exception {
        assertEquals(DocsApiClient.ErrorKind.RELOGIN, askFailure(401, "{\"error\":\"unauthorized\"}"));
        assertEquals(DocsApiClient.ErrorKind.BAD_REQUEST, askFailure(400, "{\"error\":\"question_too_long\"}"));
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(404, "{}"));
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(502, "<html>bad gateway</html>"));
        assertEquals(DocsApiClient.ErrorKind.UNAVAILABLE, askFailure(503, "{\"error\":\"llm_unavailable\"}"));
    }

    @Test
    public void badRequest_carriesServiceError() throws Exception {
        nextCode = 400;
        nextBody = "{\"error\":\"question_too_long\"}";
        try {
            client.ask("q");
            fail("ожидался HttpError 400");
        } catch (AiApiClient.HttpError e) {
            assertEquals("question_too_long", e.error);
        }
    }

    @Test
    public void networkFailure_isNetworkKind() throws Exception {
        failNetwork = true;
        try {
            client.ask("q");
            fail("ожидался IOException");
        } catch (IOException e) {
            assertEquals(DocsApiClient.ErrorKind.NETWORK, DocsApiClient.classify(e));
        }
    }

    @Test
    public void status_returnsCodeFromGatewayPath() throws Exception {
        nextCode = 200;
        assertEquals(200, client.status());
        assertEquals("http://gw/api/docs/status", requests.get(0).url().toString());
        assertEquals("GET", requests.get(0).method());
    }

    /** Таблица «ответ статуса → видимость пункта» (spec, «Пункт в «Ещё» только при доступном сервисе»). */
    @Test
    public void visibility_onlyOn200() throws Exception {
        assertTrue(DocsApiClient.isVisibleFor(200));
        for (int code : new int[]{204, 301, 401, 403, 404, 429, 500, 502, 503}) {
            assertFalse("код " + code, DocsApiClient.isVisibleFor(code));
        }
        // ошибка сети: status() бросает IOException — экран трактует как «скрыть»
        failNetwork = true;
        try {
            client.status();
            fail("ожидался IOException");
        } catch (IOException expected) {
            // пункт скрыт
        }
    }

    private DocsApiClient.ErrorKind askFailure(int code, String body) throws Exception {
        nextCode = code;
        nextBody = body;
        try {
            client.ask("q");
            fail("ожидался HttpError " + code);
            return null;
        } catch (AiApiClient.HttpError e) {
            assertEquals(code, e.code);
            return DocsApiClient.classify(e);
        }
    }
}
