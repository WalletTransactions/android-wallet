package com.example.wallettransactions.ai

import com.example.wallettransactions.sync.SyncLog
import com.example.wallettransactions.sync.SyncRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Date

/** Тексты экрана «ИИ» — дословно как в spec ai-expense-entry. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru") // тексты — ресурсы (openspec android-i18n), сверяются по-русски
class AiTextsTest {

    @Test
    fun problemTexts() {
        assertEquals(
            "Кошелька «Revolut» нет на телефоне",
            AiTexts.problem(true, "Revolut", DraftReconciler.State.MISSING)
        )
        assertEquals(
            "Кошелёк «Наличные» удалён на телефоне",
            AiTexts.problem(true, "Наличные", DraftReconciler.State.DELETED)
        )
        assertEquals(
            "Категория «Спорт» в архиве",
            AiTexts.problem(false, "Спорт", DraftReconciler.State.ARCHIVED)
        )
        assertEquals(
            "Категории «Такси» нет на телефоне",
            AiTexts.problem(false, "Такси", DraftReconciler.State.MISSING)
        )
        assertEquals(
            "Категория «Такси» удалена на телефоне",
            AiTexts.problem(false, "Такси", DraftReconciler.State.DELETED)
        )
    }

    /** «Детали после неудачного синка» из спеки. */
    @Test
    fun details_afterFailedSync() {
        val now = at(2026, 10, 24, 11, 0)
        val failed = SyncRunner.Outcome(
            SyncRunner.Status.NETWORK_ERROR, SyncLog(),
            "Сервер недоступен: timeout"
        )
        val text = AiTexts.details(
            failed, at(2026, 10, 24, 9, 12).time,
            "11111111-2222-3333-4444-5555abcdef12", now
        )

        assertEquals(
            "Синхронизация перед разбором: Сервер недоступен: timeout\n" +
                "Последний успешный синк: 09:12\n" +
                "uuid: …abcdef12", text
        )
    }

    @Test
    fun details_okSync_noSyncLine_otherDayWithDate() {
        val now = at(2026, 10, 24, 11, 0)
        val ok = SyncRunner.Outcome(SyncRunner.Status.OK, SyncLog(), null)
        val text = AiTexts.details(ok, at(2026, 10, 22, 18, 30).time, null, now)
        assertFalse(text.contains("Синхронизация перед разбором"))
        assertTrue(text.contains("Последний успешный синк: 22.10 18:30"))
    }

    @Test
    fun details_neverSynced() {
        assertTrue(AiTexts.details(null, 0, null, Date()).contains("Последний успешный синк: не было"))
    }

    @Test
    fun syncWarning_stillRunning() {
        val running = SyncRunner.Outcome(
            SyncRunner.Status.STILL_RUNNING, SyncLog(),
            "синхронизация ещё идёт"
        )
        assertEquals("Справочник мог не дойти до сервера: синхронизация ещё идёт", AiTexts.syncWarning(running))
    }

    @Test
    fun rejectedReasons() {
        assertEquals(
            "Не получилось разобрать: суммы нет в тексте",
            AiTexts.rejected(listOf("amount_not_in_text"))
        )
        assertEquals(
            "Не получилось разобрать: дата в будущем",
            AiTexts.rejected(listOf("future_date"))
        )
        assertEquals(
            "Не получилось разобрать: дата старше года",
            AiTexts.rejected(listOf("too_old"))
        )
        assertEquals(
            "Не получилось разобрать: сумма или дата",
            AiTexts.rejected(listOf("bad_amount", "bad_date"))
        )
    }

    @Test
    fun walletRejectTexts() {
        assertEquals("Нет кошелька в USD", AiTexts.walletReject("no_wallet_in_currency", "USD"))
        assertEquals(
            "В кошельке нет ни одного счёта — создайте его в приложении",
            AiTexts.walletReject("no_wallets", "")
        )
    }

    /** Подсказка над полями окна операции — дословно из сценариев спеки. */
    @Test
    fun operationHint_texts() {
        assertEquals(
            "Подсказка ИИ: «такси 450 с тинькоффа»",
            AiTexts.operationHint("такси 450 с тинькоффа", result("found", "none", "found", "[]"))
        )

        assertEquals(
            "Подсказка ИИ: «кофе 200»\nКакая категория?",
            AiTexts.operationHint("кофе 200", result("found", "none", "ask", "[]"))
        )

        assertEquals(
            "Подсказка ИИ: «ужин 40 евро @тинькофф»\nС какого кошелька?\nВ тексте EUR, а «Тинькофф» в RUB",
            AiTexts.operationHint("ужин 40 евро @тинькофф", result("ask", "currency_mismatch", "found", "[]"))
        )

        assertEquals(
            "Подсказка ИИ: «x»\nНет кошелька в EUR",
            AiTexts.operationHint("x", result("reject", "no_wallet_in_currency", "found", "[]"))
        )

        assertEquals(
            "Подсказка ИИ: «x»\nВ кошельке нет ни одного счёта — создайте его в приложении",
            AiTexts.operationHint("x", result("reject", "no_wallets", "found", "[]"))
        )

        assertEquals(
            "Подсказка ИИ: «x»\nВалюту из текста пришлось убрать — проверьте кошелёк\nКомментарий сокращён",
            AiTexts.operationHint("x", result("found", "none", "found", "[\"currency_dropped\",\"comment_truncated\"]"))
        )
    }

    private fun result(walletKind: String, walletWhy: String, categoryKind: String, notes: String): ParseExpenseResult {
        val force = "{\"uuid\":\"f\",\"name\":\"Тинькофф\",\"iso_code\":\"RUB\"}"
        return ParseExpenseResult.fromJson(
            org.json.JSONObject(
                "{\"outcome\":\"draft\",\"prompt_version\":\"v\"," +
                    "\"notes\":" + notes + ",\"expense\":{\"amount\":\"40\",\"currency_hint\":\"EUR\"}," +
                    "\"resolution\":{\"wallet\":{\"kind\":\"" + walletKind + "\",\"why\":\"" + walletWhy +
                    "\",\"force\":" + force + "},\"category\":{\"kind\":\"" + categoryKind + "\"}}}"
            )
        )
    }

    private fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int): Date {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, hh, mm)
        return c.time
    }
}
