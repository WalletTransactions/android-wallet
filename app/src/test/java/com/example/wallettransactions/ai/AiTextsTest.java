package com.example.wallettransactions.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.wallettransactions.sync.SyncLog;
import com.example.wallettransactions.sync.SyncRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

/** Тексты экрана «ИИ» — дословно как в spec ai-expense-entry. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "ru") // тексты — ресурсы (openspec android-i18n), сверяются по-русски
public class AiTextsTest {

    @Test
    public void problemTexts() {
        assertEquals("Кошелька «Revolut» нет на телефоне",
                AiTexts.problem(true, "Revolut", DraftReconciler.State.MISSING));
        assertEquals("Кошелёк «Наличные» удалён на телефоне",
                AiTexts.problem(true, "Наличные", DraftReconciler.State.DELETED));
        assertEquals("Категория «Спорт» в архиве",
                AiTexts.problem(false, "Спорт", DraftReconciler.State.ARCHIVED));
        assertEquals("Категории «Такси» нет на телефоне",
                AiTexts.problem(false, "Такси", DraftReconciler.State.MISSING));
        assertEquals("Категория «Такси» удалена на телефоне",
                AiTexts.problem(false, "Такси", DraftReconciler.State.DELETED));
    }

    /** «Детали после неудачного синка» из спеки. */
    @Test
    public void details_afterFailedSync() {
        Date now = at(2026, 10, 24, 11, 0);
        SyncRunner.Outcome failed = new SyncRunner.Outcome(SyncRunner.Status.NETWORK_ERROR, new SyncLog(),
                "Сервер недоступен: timeout");
        String text = AiTexts.details(failed, at(2026, 10, 24, 9, 12).getTime(),
                "11111111-2222-3333-4444-5555abcdef12", now);

        assertEquals("Синхронизация перед разбором: Сервер недоступен: timeout\n"
                + "Последний успешный синк: 09:12\n"
                + "uuid: …abcdef12", text);
    }

    @Test
    public void details_okSync_noSyncLine_otherDayWithDate() {
        Date now = at(2026, 10, 24, 11, 0);
        SyncRunner.Outcome ok = new SyncRunner.Outcome(SyncRunner.Status.OK, new SyncLog(), null);
        String text = AiTexts.details(ok, at(2026, 10, 22, 18, 30).getTime(), null, now);
        assertFalse(text.contains("Синхронизация перед разбором"));
        assertTrue(text.contains("Последний успешный синк: 22.10 18:30"));
    }

    @Test
    public void details_neverSynced() {
        assertTrue(AiTexts.details(null, 0, null, new Date()).contains("Последний успешный синк: не было"));
    }

    @Test
    public void syncWarning_stillRunning() {
        SyncRunner.Outcome running = new SyncRunner.Outcome(SyncRunner.Status.STILL_RUNNING, new SyncLog(),
                "синхронизация ещё идёт");
        assertEquals("Справочник мог не дойти до сервера: синхронизация ещё идёт", AiTexts.syncWarning(running));
    }

    @Test
    public void rejectedReasons() {
        assertEquals("Не получилось разобрать: суммы нет в тексте",
                AiTexts.rejected(Collections.singletonList("amount_not_in_text")));
        assertEquals("Не получилось разобрать: дата в будущем",
                AiTexts.rejected(Collections.singletonList("future_date")));
        assertEquals("Не получилось разобрать: дата старше года",
                AiTexts.rejected(Collections.singletonList("too_old")));
        assertEquals("Не получилось разобрать: сумма или дата",
                AiTexts.rejected(Arrays.asList("bad_amount", "bad_date")));
    }

    @Test
    public void walletRejectTexts() {
        assertEquals("Нет кошелька в USD", AiTexts.walletReject("no_wallet_in_currency", "USD"));
        assertEquals("В кошельке нет ни одного счёта — создайте его в приложении",
                AiTexts.walletReject("no_wallets", ""));
    }

    /** Подсказка над полями окна операции — дословно из сценариев спеки. */
    @Test
    public void operationHint_texts() throws Exception {
        assertEquals("Подсказка ИИ: «такси 450 с тинькоффа»",
                AiTexts.operationHint("такси 450 с тинькоффа", result("found", "none", "found", "[]")));

        assertEquals("Подсказка ИИ: «кофе 200»\nКакая категория?",
                AiTexts.operationHint("кофе 200", result("found", "none", "ask", "[]")));

        assertEquals("Подсказка ИИ: «ужин 40 евро @тинькофф»\nС какого кошелька?\nВ тексте EUR, а «Тинькофф» в RUB",
                AiTexts.operationHint("ужин 40 евро @тинькофф", result("ask", "currency_mismatch", "found", "[]")));

        assertEquals("Подсказка ИИ: «x»\nНет кошелька в EUR",
                AiTexts.operationHint("x", result("reject", "no_wallet_in_currency", "found", "[]")));

        assertEquals("Подсказка ИИ: «x»\nВ кошельке нет ни одного счёта — создайте его в приложении",
                AiTexts.operationHint("x", result("reject", "no_wallets", "found", "[]")));

        assertEquals("Подсказка ИИ: «x»\nВалюту из текста пришлось убрать — проверьте кошелёк\nКомментарий сокращён",
                AiTexts.operationHint("x", result("found", "none", "found", "[\"currency_dropped\",\"comment_truncated\"]")));
    }

    private static ParseExpenseResult result(String walletKind, String walletWhy, String categoryKind, String notes)
            throws Exception {
        String force = "{\"uuid\":\"f\",\"name\":\"Тинькофф\",\"iso_code\":\"RUB\"}";
        return ParseExpenseResult.fromJson(new org.json.JSONObject("{\"outcome\":\"draft\",\"prompt_version\":\"v\","
                + "\"notes\":" + notes + ",\"expense\":{\"amount\":\"40\",\"currency_hint\":\"EUR\"},"
                + "\"resolution\":{\"wallet\":{\"kind\":\"" + walletKind + "\",\"why\":\"" + walletWhy
                + "\",\"force\":" + force + "},\"category\":{\"kind\":\"" + categoryKind + "\"}}}"));
    }

    private static Date at(int y, int m, int d, int hh, int mm) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, hh, mm);
        return c.getTime();
    }
}
