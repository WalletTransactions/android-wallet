package com.example.wallettransactions.ai

import com.example.wallettransactions.R
import com.example.wallettransactions.Texts
import com.example.wallettransactions.sync.SyncRunner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Тексты экрана «ИИ», на которые опираются сценарии spec ai-expense-entry —
 * в одном месте, чтобы их можно было проверить юнит-тестом. Формулировки
 * общие с вебом (wallet-web, spec ai-expense-entry). Сами строки — ресурсы
 * values/strings_ai.xml (openspec android-i18n).
 */
object AiTexts {

    /** «Кошелька «X» нет на телефоне», «Категория «X» в архиве» и т.п. */
    @JvmStatic
    fun problem(wallet: Boolean, name: String?, state: DraftReconciler.State): String =
        when (state) {
            DraftReconciler.State.MISSING ->
                Texts.get(if (wallet) R.string.ai_wallet_missing else R.string.ai_category_missing, name)
            DraftReconciler.State.DELETED ->
                Texts.get(if (wallet) R.string.ai_wallet_deleted else R.string.ai_category_deleted, name)
            DraftReconciler.State.ARCHIVED ->
                Texts.get(if (wallet) R.string.ai_wallet_archived else R.string.ai_category_archived, name)
            DraftReconciler.State.OK -> ""
        }

    /**
     * Детали блока ошибки: синк перед разбором (если не прошёл), последний
     * успешный синк, хвост uuid — без текста пользователя.
     *
     * @param syncBeforeParse исход синка перед этим разбором; `null` — не было
     * @param lastSuccessAt   epoch ms, 0 — не было ни разу
     * @param now             «сейчас» для решения, писать ли дату
     */
    @JvmStatic
    fun details(syncBeforeParse: SyncRunner.Outcome?, lastSuccessAt: Long, uuid: String?, now: Date): String {
        val sb = StringBuilder()
        if (syncBeforeParse != null && syncBeforeParse.status != SyncRunner.Status.OK) {
            sb.append(Texts.get(R.string.ai_details_sync_before, syncLine(syncBeforeParse))).append('\n')
        }
        sb.append(
            Texts.get(
                R.string.ai_details_last_sync,
                if (lastSuccessAt == 0L) Texts.get(R.string.ai_details_never) else whenAt(lastSuccessAt, now)
            )
        )
        if (uuid != null && uuid.isNotEmpty()) {
            val plain = uuid.replace("-", "")
            sb.append("\nuuid: …").append(if (plain.length > 8) plain.substring(plain.length - 8) else plain)
        }
        return sb.toString()
    }

    /** Одна строка про исход синка — для деталей и предупреждения под полем. */
    @JvmStatic
    fun syncLine(outcome: SyncRunner.Outcome): String =
        when (outcome.status) {
            SyncRunner.Status.NO_SESSION -> Texts.get(R.string.ai_sync_no_session)
            SyncRunner.Status.NEEDS_RELINK -> Texts.get(R.string.ai_sync_relink)
            SyncRunner.Status.PARTIAL,
            SyncRunner.Status.NETWORK_ERROR,
            SyncRunner.Status.ERROR,
            SyncRunner.Status.STILL_RUNNING,
            SyncRunner.Status.AWAITING_DECISION ->
                outcome.errorMessage ?: outcome.status.name
            SyncRunner.Status.OK -> Texts.get(R.string.ai_sync_ok)
        }

    /** Предупреждение под полем, если синк перед разбором не прошёл. */
    @JvmStatic
    fun syncWarning(outcome: SyncRunner.Outcome): String =
        Texts.get(R.string.ai_sync_warning, syncLine(outcome))

    /** Текст при `outcome: rejected` по первой причине. */
    @JvmStatic
    fun rejected(reasons: List<String>): String {
        val first = if (reasons.isEmpty()) "" else reasons[0]
        val why = when (first) {
            "amount_not_in_text" -> R.string.ai_rejected_no_amount
            "future_date" -> R.string.ai_rejected_future_date
            "too_old" -> R.string.ai_rejected_too_old
            else -> R.string.ai_rejected_amount_or_date
        }
        return Texts.get(R.string.ai_rejected, Texts.get(why))
    }

    /** Отказ resolver по кошельку (`kind: reject`). */
    @JvmStatic
    fun walletReject(why: String?, currency: String?): String {
        if ("no_wallets" == why) return Texts.get(R.string.ai_wallet_reject_none)
        if ("no_wallet_in_currency" == why) return Texts.get(R.string.ai_wallet_reject_currency, currency)
        return Texts.get(R.string.ai_wallet_reject_not_found)
    }

    /**
     * Строка «Подсказка ИИ: …» в окне операции: исходный текст, вопросы resolver,
     * несовпадение валюты, отказ по кошельку и пометки `notes` — по строке
     * на каждое (spec ai-expense-entry, «Вопрос resolver», «Отказ resolver»,
     * «Проверки в окне операции»).
     */
    @JvmStatic
    fun operationHint(sourceText: String?, r: ParseExpenseResult): String {
        val sb = StringBuilder(Texts.get(R.string.ai_hint_source, sourceText))
        val w = r.resolution?.wallet
        val c = r.resolution?.category
        val currency = r.expense?.currencyHint ?: ""
        if (w != null && ParseExpenseResult.Choice.ASK == w.kind) {
            sb.append('\n').append(Texts.get(R.string.ai_hint_which_wallet))
            if ("currency_mismatch" == w.why && w.force != null) {
                sb.append('\n').append(
                    Texts.get(R.string.ai_hint_currency_mismatch, currency, w.force.name, w.force.isoCode)
                )
            }
        } else if (w != null && ParseExpenseResult.Choice.REJECT == w.kind) {
            sb.append('\n').append(walletReject(w.why, currency))
        }
        if (c != null && ParseExpenseResult.Choice.ASK == c.kind) {
            sb.append('\n').append(Texts.get(R.string.ai_hint_which_category))
        }
        if (r.notes.contains("currency_dropped")) sb.append('\n').append(Texts.get(R.string.ai_hint_currency_dropped))
        if (r.notes.contains("comment_truncated")) sb.append('\n').append(Texts.get(R.string.ai_hint_comment_truncated))
        return sb.toString()
    }

    // ── Экран «Выписка» (openspec add-ai-statement-import, spec ai-statement-import) ──

    @JvmStatic fun statementFileTooBig(): String = Texts.get(R.string.statement_file_too_big)
    @JvmStatic fun statementFileUnreadable(): String = Texts.get(R.string.statement_file_unreadable)
    @JvmStatic fun statementNoNetwork(): String = Texts.get(R.string.statement_no_network)
    @JvmStatic fun statementPaused(): String = Texts.get(R.string.statement_paused)
    @JvmStatic fun statementWalletMissing(): String = Texts.get(R.string.statement_wallet_missing)

    /** Отказ сразу при создании (`status: failed`) и провал разбора. */
    @JvmStatic
    fun statementFailure(failure: String?): String {
        if ("no_text" == failure) return Texts.get(R.string.statement_failure_no_text)
        if ("no_operations" == failure) return Texts.get(R.string.statement_failure_no_operations)
        if ("timeout" == failure) return Texts.get(R.string.statement_failure_timeout)
        if ("unavailable" == failure) return Texts.get(R.string.statement_failure_unavailable)
        return Texts.get(R.string.statement_failure_other)
    }

    /** Ошибка `POST /api/ai/imports` по коду и `error`. */
    @JvmStatic
    fun statementHttpError(code: Int, error: String?): String =
        when (code) {
            429 -> Texts.get(
                if ("import_in_progress" == error) R.string.statement_http_in_progress else R.string.statement_http_busy
            )
            503 -> Texts.get(R.string.more_ai_off)
            413 -> Texts.get(R.string.statement_http_too_big)
            401 -> Texts.get(R.string.more_ai_sign_in)
            403 -> Texts.get(R.string.ai_http_forbidden)
            400 -> when {
                "unsupported_file" == error -> Texts.get(R.string.statement_http_unsupported)
                "empty" == error -> Texts.get(R.string.statement_http_empty)
                "unknown_wallet" == error -> Texts.get(R.string.statement_http_unknown_wallet)
                else -> Texts.get(R.string.statement_http_rejected, error)
            }
            else -> Texts.get(R.string.ai_http_error, code)
        }

    /** Состояние разбора, пока строк ещё нет. */
    @JvmStatic
    fun statementProgress(imp: StatementImport): String {
        if (imp.pausedSince != null) return statementPaused()
        if (StatementImport.QUEUED == imp.status) return Texts.get(R.string.statement_progress_queued)
        if (StatementImport.CLASSIFYING == imp.status) return Texts.get(R.string.statement_progress_classifying)
        if (imp.progressTotal > 0) {
            return Texts.get(R.string.statement_progress_counted, imp.progressDone, imp.progressTotal)
        }
        return Texts.get(R.string.statement_progress_parsing)
    }

    /** Подпись состояния строки ([StatementRowState.Kind]); у дубля — с операцией. */
    @JvmStatic
    fun statementRowState(kind: StatementRowState.Kind, duplicate: StatementDuplicates.Match?): String =
        when (kind) {
            StatementRowState.Kind.ADDED -> Texts.get(R.string.statement_row_added)
            StatementRowState.Kind.NOT_FOR_WRITE -> Texts.get(R.string.statement_row_not_for_write)
            StatementRowState.Kind.CHECK -> Texts.get(R.string.statement_row_check)
            StatementRowState.Kind.DUPLICATE ->
                if (duplicate != null) statementDuplicate(duplicate) else Texts.get(R.string.statement_row_duplicate)
            StatementRowState.Kind.NEEDS_CATEGORY -> Texts.get(R.string.statement_row_needs_category)
            StatementRowState.Kind.WAITING -> Texts.get(R.string.statement_row_waiting)
        }

    /** «Похоже, уже есть: 12.10 450,00 Такси». */
    @JvmStatic
    fun statementDuplicate(m: StatementDuplicates.Match): String {
        val day = if (m.date.length >= 10) m.date.substring(8, 10) + "." + m.date.substring(5, 7) else m.date
        val text = Texts.get(R.string.statement_duplicate_of, day + " " + statementAmount(m.amount))
        return if (m.category.isEmpty()) text else "$text ${m.category}"
    }

    /** Сумма строки: «1 290,00». */
    @JvmStatic
    fun statementAmount(amount: Double): String =
        // Разделитель тысяч у ru — NBSP или узкий NBSP (CLDR); в списке — обычный пробел.
        String.format(Locale.forLanguageTag("ru"), "%,.2f", amount)
            .replace(' ', ' ').replace(' ', ' ')

    /** Категория строки не годится для записи: нет на телефоне (или удалена) / в архиве. */
    @JvmStatic
    fun statementCategoryProblem(name: String?, state: DraftReconciler.State): String {
        if (state == DraftReconciler.State.ARCHIVED) return Texts.get(R.string.ai_category_archived, name)
        return Texts.get(R.string.ai_category_missing, name)
    }

    /** Что не так с текстом строки (`check`). */
    @JvmStatic
    fun statementCheck(check: String?): String {
        if ("amount_not_in_text" == check) return Texts.get(R.string.statement_check_amount)
        if ("date_not_in_text" == check) return Texts.get(R.string.statement_check_date)
        if ("currency_mismatch" == check) return Texts.get(R.string.statement_check_currency)
        return Texts.get(R.string.statement_check_other)
    }

    private fun whenAt(at: Long, now: Date): String {
        val a = Calendar.getInstance()
        a.timeInMillis = at
        val n = Calendar.getInstance()
        n.time = now
        val today = a.get(Calendar.YEAR) == n.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "dd.MM HH:mm", Locale.ROOT).format(Date(at))
    }
}
