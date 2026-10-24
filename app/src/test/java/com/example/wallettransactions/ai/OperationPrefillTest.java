package com.example.wallettransactions.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link OperationPrefill}: поля окна операции из черновика — found, ask,
 * currency_mismatch, reject и пропавшие с телефона счета.
 */
@RunWith(RobolectricTestRunner.class)
public class OperationPrefillTest {

    private static final String TINKOFF = "11111111-1111-1111-1111-111111111111";
    private static final String REVOLUT = "22222222-2222-2222-2222-222222222222";
    private static final String TAXI = "33333333-3333-3333-3333-333333333333";
    private static final String CAFE = "44444444-4444-4444-4444-444444444444";
    private static final String COFFEE = "55555555-5555-5555-5555-555555555555";
    private static final String GONE = "66666666-6666-6666-6666-666666666666";
    private static final String UNKNOWN = "77777777-7777-7777-7777-777777777777";

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, kind INTEGER, "
                + "is_archived INTEGER DEFAULT 0, deleted_at INTEGER)");
        db.execSQL("INSERT INTO account (_id, uuid, name, kind) VALUES "
                + "(1, '" + TINKOFF + "', 'Тинькофф', 1), (2, '" + REVOLUT + "', 'Revolut EUR', 1), "
                + "(3, '" + TAXI + "', 'Такси', 0), (4, '" + CAFE + "', 'Кафе', 0), (5, '" + COFFEE + "', 'Кофейни', 0)");
        db.execSQL("INSERT INTO account (_id, uuid, name, kind, deleted_at) VALUES (6, '" + GONE + "', 'Наличные', 1, 5)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void allFound() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"found\",\"found\":" + ref(TINKOFF, "Тинькофф") + "}",
                "{\"kind\":\"found\",\"found\":" + ref(TAXI, "Такси") + "}", "[]"), "такси 450 с тинькоффа");

        assertEquals(1, p.walletId);
        assertEquals(3, p.categoryId);
        assertEquals(450.0, p.amount, 0.001);
        assertEquals("2026-10-23 12:30", p.date);
        assertEquals("такси", p.comment);
        assertFalse(p.hasProblem());
        assertEquals("Подсказка ИИ: «такси 450 с тинькоффа»", p.hint);
    }

    @Test
    public void askCategory_candidatesFirst_goneDropped() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"found\",\"found\":" + ref(TINKOFF, "Тинькофф") + "}",
                "{\"kind\":\"ask\",\"why\":\"ambiguous\",\"candidates\":[" + ref(CAFE, "Кафе") + ","
                        + ref(UNKNOWN, "Нет такой") + "," + ref(COFFEE, "Кофейни") + "]}", "[]"), "кофе 200");

        assertEquals(0, p.categoryId);
        assertArrayEquals(new int[]{4, 5}, p.priorityCategoryIds);
        assertFalse(p.hasProblem());
        assertTrue(p.hint.contains("Какая категория?"));
    }

    @Test
    public void currencyMismatch_candidatesThenForce() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"ask\",\"why\":\"currency_mismatch\",\"force\":" + ref(TINKOFF, "Тинькофф")
                        + ",\"candidates\":[" + ref(REVOLUT, "Revolut EUR") + "]}",
                "{\"kind\":\"found\",\"found\":" + ref(CAFE, "Кафе") + "}", "[]"), "ужин 40 евро @тинькофф");

        assertEquals(0, p.walletId);
        assertArrayEquals(new int[]{2, 1}, p.priorityWalletIds);
        assertTrue(p.hint.contains("В тексте EUR, а «Тинькофф» в RUB"));
    }

    @Test
    public void reject_walletEmpty_reasonInHint() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"reject\",\"why\":\"no_wallet_in_currency\"}",
                "{\"kind\":\"found\",\"found\":" + ref(CAFE, "Кафе") + "}", "[]"), "кофе 5 usd");

        assertEquals(0, p.walletId);
        assertFalse(p.hasProblem());
        assertTrue(p.hint.contains("Нет кошелька в EUR"));
    }

    @Test
    public void foundWalletGone_isProblem() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"found\",\"found\":" + ref(GONE, "Наличные") + "}",
                "{\"kind\":\"found\",\"found\":" + ref(TAXI, "Такси") + "}", "[]"), "такси 300 наличными");

        assertTrue(p.hasProblem());
        assertEquals(DraftReconciler.State.DELETED, p.walletProblem.state);
        assertNull(p.categoryProblem);
        assertEquals(0, p.walletId);
        assertEquals(3, p.categoryId);
    }

    @Test
    public void foundOfWrongKind_notSelected() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"found\",\"found\":" + ref(TAXI, "Такси") + "}",
                "{\"kind\":\"found\",\"found\":" + ref(TINKOFF, "Тинькофф") + "}", "[]"), "x");

        assertEquals(0, p.walletId);
        assertEquals(0, p.categoryId);
    }

    @Test
    public void notesInHint() throws Exception {
        OperationPrefill p = OperationPrefill.build(db, draft(
                "{\"kind\":\"found\",\"found\":" + ref(TINKOFF, "Тинькофф") + "}",
                "{\"kind\":\"found\",\"found\":" + ref(TAXI, "Такси") + "}",
                "[\"currency_dropped\"]"), "такси 450");
        assertTrue(p.hint.contains("Валюту из текста пришлось убрать — проверьте кошелёк"));
    }

    private static String ref(String uuid, String name) {
        return "{\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"iso_code\":\"RUB\",\"symbol\":\"₽\",\"precision\":2}";
    }

    private static ParseExpenseResult draft(String wallet, String category, String notes) throws Exception {
        return ParseExpenseResult.fromJson(new JSONObject("{\"outcome\":\"draft\",\"prompt_version\":\"v\","
                + "\"notes\":" + notes + ","
                + "\"expense\":{\"amount\":\"450\",\"currency_hint\":\"EUR\",\"datetime\":\"2026-10-23 12:30:00\","
                + "\"comment\":\"такси\"},"
                + "\"resolution\":{\"wallet\":" + wallet + ",\"category\":" + category + "}}"));
    }
}
