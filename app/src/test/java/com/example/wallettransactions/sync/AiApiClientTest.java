package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.wallettransactions.ai.ParseExpenseResult;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.time.LocalDateTime;
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
 * {@link AiApiClient} и разбор {@link ParseExpenseResult}: форма запросов по
 * wallet-ai/openapi.yaml, разбор ответов и кодов ошибок, тишина в logcat о
 * тексте траты. Сеть подменена перехватчиком OkHttp.
 */
@RunWith(RobolectricTestRunner.class)
public class AiApiClientTest {

    private final List<Request> requests = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private int nextCode = 200;
    private String nextBody = "{}";

    private AiApiClient client;

    @Before
    public void setUp() {
        ShadowLog.clear();
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
                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(nextCode)
                            .message("x")
                            .body(ResponseBody.create(nextBody, MediaType.get("application/json")))
                            .build();
                })
                .build();
        client = new AiApiClient(http, "http://gw/", "tok");
    }

    @Test
    public void createJob_sendsParseExpenseInput() throws Exception {
        nextCode = 202;
        nextBody = "{\"job_id\":\"j1\",\"status\":\"queued\"}";

        AiApiClient.Job job = client.createJob("кофе 200", LocalDateTime.of(2026, 10, 24, 9, 5, 7),
                "w-uuid", "android:k1");

        assertEquals("j1", job.id);
        assertTrue(job.isPending());
        Request req = requests.get(0);
        assertEquals("http://gw/api/ai/jobs", req.url().toString());
        assertEquals("Bearer tok", req.header("Authorization"));
        JSONObject body = new JSONObject(requestBodies.get(0));
        assertEquals("parse_expense", body.getString("kind"));
        assertEquals("android:k1", body.getString("idempotency_key"));
        JSONObject input = body.getJSONObject("input");
        assertEquals("кофе 200", input.getString("text"));
        assertEquals("2026-10-24", input.getJSONObject("now").getString("date"));
        assertEquals("09:05:07", input.getJSONObject("now").getString("time"));
        assertEquals("w-uuid", input.getString("default_wallet_uuid"));
    }

    @Test
    public void createJob_withoutDefaultWallet_omitsField() throws Exception {
        nextCode = 202;
        nextBody = "{\"job_id\":\"j1\",\"status\":\"queued\"}";
        client.createJob("обед 350", LocalDateTime.of(2026, 10, 24, 12, 0), null, "android:k2");
        assertFalse(new JSONObject(requestBodies.get(0)).getJSONObject("input").has("default_wallet_uuid"));
    }

    @Test
    public void getJob_parsesDraft() throws Exception {
        nextBody = "{\"job_id\":\"j1\",\"kind\":\"parse_expense\",\"status\":\"done\",\"result\":{"
                + "\"outcome\":\"draft\",\"prompt_version\":\"expense-v2\",\"notes\":[\"currency_dropped\"],"
                + "\"expense\":{\"amount\":\"450\",\"category\":\"Такси\",\"wallet\":\"тинькофф\","
                + "\"datetime\":\"2026-10-23 12:00:00\",\"comment\":\"такси\"},"
                + "\"resolution\":{"
                + "\"category\":{\"kind\":\"found\",\"why\":\"none\",\"found\":{\"uuid\":\"c1\",\"name\":\"Такси\"}},"
                + "\"wallet\":{\"kind\":\"ask\",\"why\":\"currency_mismatch\","
                + "\"force\":{\"uuid\":\"w1\",\"name\":\"Тинькофф\",\"iso_code\":\"RUB\",\"symbol\":\"₽\",\"precision\":2},"
                + "\"candidates\":[{\"uuid\":\"w2\",\"name\":\"Revolut EUR\",\"iso_code\":\"EUR\",\"symbol\":\"€\",\"precision\":2}]}}}}";

        AiApiClient.Job job = client.getJob("j1", 25);

        assertEquals("http://gw/api/ai/jobs/j1?wait=25", requests.get(0).url().toString());
        assertFalse(job.isPending());
        ParseExpenseResult r = job.result;
        assertTrue(r.isDraft());
        assertEquals("450", r.expense.amount);
        assertEquals("2026-10-23 12:00:00", r.expense.datetime);
        assertEquals("currency_dropped", r.notes.get(0));
        assertEquals(ParseExpenseResult.Choice.FOUND, r.resolution.category.kind);
        assertEquals("c1", r.resolution.category.found.uuid);
        assertEquals("currency_mismatch", r.resolution.wallet.why);
        assertEquals("RUB", r.resolution.wallet.force.isoCode);
        assertEquals("Revolut EUR", r.resolution.wallet.candidates.get(0).name);
    }

    @Test
    public void getJob_parsesRejectedAndFailed() throws Exception {
        nextBody = "{\"job_id\":\"j1\",\"status\":\"done\",\"result\":{\"outcome\":\"rejected\","
                + "\"prompt_version\":\"v\",\"reasons\":[\"amount_not_in_text\"]}}";
        ParseExpenseResult r = client.getJob("j1", 25).result;
        assertEquals(ParseExpenseResult.REJECTED, r.outcome);
        assertEquals("amount_not_in_text", r.reasons.get(0));
        assertNull(r.resolution);

        nextBody = "{\"job_id\":\"j1\",\"status\":\"failed\",\"failure\":\"timeout\"}";
        AiApiClient.Job failed = client.getJob("j1", 25);
        assertEquals("failed", failed.status);
        assertEquals("timeout", failed.failure);
        assertNull(failed.result);
    }

    @Test
    public void errorCodes_carryCodeAndReason() throws Exception {
        assertHttpError(429, "{\"error\":\"busy\"}", "busy");
        assertHttpError(503, "{\"error\":\"unavailable\"}", "unavailable");
        assertHttpError(404, "{\"error\":\"not_found\"}", "not_found");
        assertHttpError(502, "<html>bad gateway</html>", "");
    }

    @Test
    public void status_parsesFeatures() throws Exception {
        nextBody = "{\"enabled\":true,\"model\":\"wallet-parse\",\"reason\":null,\"features\":[\"parse_expense\"]}";
        assertTrue(client.status().canParseExpense());

        nextBody = "{\"enabled\":false,\"model\":null,\"reason\":\"unreachable\",\"features\":[]}";
        assertFalse(client.status().canParseExpense());
    }

    /** spec ai-expense-entry, «Приватность»: текст траты и черновик в logcat не попадают. */
    @Test
    public void logcat_hasNoUserText() throws Exception {
        nextCode = 202;
        nextBody = "{\"job_id\":\"j1\",\"status\":\"queued\"}";
        client.createJob("кофе 200", LocalDateTime.of(2026, 10, 24, 9, 0), null, "android:k3");
        nextCode = 200;
        nextBody = "{\"job_id\":\"j1\",\"status\":\"done\",\"result\":{\"outcome\":\"draft\",\"prompt_version\":\"v\","
                + "\"expense\":{\"amount\":\"200\",\"comment\":\"кофе\"}}}";
        client.getJob("j1", 25);

        boolean sawJobId = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("AiApiClient")) {
            assertFalse(item.msg, item.msg.contains("кофе"));
            assertFalse(item.msg, item.msg.contains("200 "));
            if (item.msg.contains("j1")) sawJobId = true;
        }
        assertTrue(sawJobId);
    }

    private void assertHttpError(int code, String body, String error) throws Exception {
        nextCode = code;
        nextBody = body;
        try {
            client.createJob("x", LocalDateTime.of(2026, 10, 24, 9, 0), null, "k");
            fail("ожидался HttpError " + code);
        } catch (AiApiClient.HttpError e) {
            assertEquals(code, e.code);
            assertEquals(error, e.error);
        }
    }
}
