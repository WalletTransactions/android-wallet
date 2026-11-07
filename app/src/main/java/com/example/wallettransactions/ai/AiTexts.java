package com.example.wallettransactions.ai;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Texts;
import com.example.wallettransactions.sync.SyncRunner;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Тексты экрана «ИИ», на которые опираются сценарии spec ai-expense-entry —
 * в одном месте, чтобы их можно было проверить юнит-тестом. Формулировки
 * общие с вебом (wallet-web, spec ai-expense-entry). Сами строки — ресурсы
 * values/strings_ai.xml (openspec android-i18n).
 */
public final class AiTexts {

    private AiTexts() {}

    /** «Кошелька «X» нет на телефоне», «Категория «X» в архиве» и т.п. */
    public static String problem(boolean wallet, String name, DraftReconciler.State state) {
        switch (state) {
            case MISSING:
                return Texts.get(wallet ? R.string.ai_wallet_missing : R.string.ai_category_missing, name);
            case DELETED:
                return Texts.get(wallet ? R.string.ai_wallet_deleted : R.string.ai_category_deleted, name);
            case ARCHIVED:
                return Texts.get(wallet ? R.string.ai_wallet_archived : R.string.ai_category_archived, name);
            case OK:
            default:
                return "";
        }
    }

    /**
     * Детали блока ошибки: синк перед разбором (если не прошёл), последний
     * успешный синк, хвост uuid — без текста пользователя.
     *
     * @param syncBeforeParse исход синка перед этим разбором; {@code null} — не было
     * @param lastSuccessAt   epoch ms, 0 — не было ни разу
     * @param now             «сейчас» для решения, писать ли дату
     */
    public static String details(SyncRunner.Outcome syncBeforeParse, long lastSuccessAt, String uuid, Date now) {
        StringBuilder sb = new StringBuilder();
        if (syncBeforeParse != null && syncBeforeParse.status != SyncRunner.Status.OK) {
            sb.append(Texts.get(R.string.ai_details_sync_before, syncLine(syncBeforeParse))).append('\n');
        }
        sb.append(Texts.get(R.string.ai_details_last_sync,
                lastSuccessAt == 0 ? Texts.get(R.string.ai_details_never) : when(lastSuccessAt, now)));
        if (uuid != null && !uuid.isEmpty()) {
            String plain = uuid.replace("-", "");
            sb.append("\nuuid: …").append(plain.length() > 8 ? plain.substring(plain.length() - 8) : plain);
        }
        return sb.toString();
    }

    /** Одна строка про исход синка — для деталей и предупреждения под полем. */
    public static String syncLine(SyncRunner.Outcome outcome) {
        switch (outcome.status) {
            case NO_SESSION:
                return Texts.get(R.string.ai_sync_no_session);
            case NEEDS_RELINK:
                return Texts.get(R.string.ai_sync_relink);
            case PARTIAL:
            case NETWORK_ERROR:
            case ERROR:
            case STILL_RUNNING:
            case AWAITING_DECISION:
                return outcome.errorMessage != null ? outcome.errorMessage : outcome.status.name();
            case OK:
            default:
                return Texts.get(R.string.ai_sync_ok);
        }
    }

    /** Предупреждение под полем, если синк перед разбором не прошёл. */
    public static String syncWarning(SyncRunner.Outcome outcome) {
        return Texts.get(R.string.ai_sync_warning, syncLine(outcome));
    }

    /** Текст при {@code outcome: rejected} по первой причине. */
    public static String rejected(List<String> reasons) {
        String first = reasons.isEmpty() ? "" : reasons.get(0);
        int why;
        switch (first) {
            case "amount_not_in_text": why = R.string.ai_rejected_no_amount; break;
            case "future_date": why = R.string.ai_rejected_future_date; break;
            case "too_old": why = R.string.ai_rejected_too_old; break;
            default: why = R.string.ai_rejected_amount_or_date;
        }
        return Texts.get(R.string.ai_rejected, Texts.get(why));
    }

    /** Отказ resolver по кошельку ({@code kind: reject}). */
    public static String walletReject(String why, String currency) {
        if ("no_wallets".equals(why)) return Texts.get(R.string.ai_wallet_reject_none);
        if ("no_wallet_in_currency".equals(why)) return Texts.get(R.string.ai_wallet_reject_currency, currency);
        return Texts.get(R.string.ai_wallet_reject_not_found);
    }

    /**
     * Строка «Подсказка ИИ: …» в окне операции: исходный текст, вопросы resolver,
     * несовпадение валюты, отказ по кошельку и пометки {@code notes} — по строке
     * на каждое (spec ai-expense-entry, «Вопрос resolver», «Отказ resolver»,
     * «Проверки в окне операции»).
     */
    public static String operationHint(String sourceText, ParseExpenseResult r) {
        StringBuilder sb = new StringBuilder(Texts.get(R.string.ai_hint_source, sourceText));
        ParseExpenseResult.Choice w = r.resolution != null ? r.resolution.wallet : null;
        ParseExpenseResult.Choice c = r.resolution != null ? r.resolution.category : null;
        String currency = r.expense != null ? r.expense.currencyHint : "";
        if (w != null && ParseExpenseResult.Choice.ASK.equals(w.kind)) {
            sb.append('\n').append(Texts.get(R.string.ai_hint_which_wallet));
            if ("currency_mismatch".equals(w.why) && w.force != null) {
                sb.append('\n').append(Texts.get(R.string.ai_hint_currency_mismatch,
                        currency, w.force.name, w.force.isoCode));
            }
        } else if (w != null && ParseExpenseResult.Choice.REJECT.equals(w.kind)) {
            sb.append('\n').append(walletReject(w.why, currency));
        }
        if (c != null && ParseExpenseResult.Choice.ASK.equals(c.kind)) {
            sb.append('\n').append(Texts.get(R.string.ai_hint_which_category));
        }
        if (r.notes.contains("currency_dropped")) sb.append('\n').append(Texts.get(R.string.ai_hint_currency_dropped));
        if (r.notes.contains("comment_truncated")) sb.append('\n').append(Texts.get(R.string.ai_hint_comment_truncated));
        return sb.toString();
    }

    // ── Экран «Выписка» (openspec add-ai-statement-import, spec ai-statement-import) ──

    public static String statementFileTooBig() { return Texts.get(R.string.statement_file_too_big); }
    public static String statementFileUnreadable() { return Texts.get(R.string.statement_file_unreadable); }
    public static String statementNoNetwork() { return Texts.get(R.string.statement_no_network); }
    public static String statementPaused() { return Texts.get(R.string.statement_paused); }
    public static String statementWalletMissing() { return Texts.get(R.string.statement_wallet_missing); }

    /** Отказ сразу при создании ({@code status: failed}) и провал разбора. */
    public static String statementFailure(String failure) {
        if ("no_text".equals(failure)) return Texts.get(R.string.statement_failure_no_text);
        if ("no_operations".equals(failure)) return Texts.get(R.string.statement_failure_no_operations);
        if ("timeout".equals(failure)) return Texts.get(R.string.statement_failure_timeout);
        if ("unavailable".equals(failure)) return Texts.get(R.string.statement_failure_unavailable);
        return Texts.get(R.string.statement_failure_other);
    }

    /** Ошибка {@code POST /api/ai/imports} по коду и {@code error}. */
    public static String statementHttpError(int code, String error) {
        switch (code) {
            case 429:
                return Texts.get("import_in_progress".equals(error)
                        ? R.string.statement_http_in_progress : R.string.statement_http_busy);
            case 503: return Texts.get(R.string.more_ai_off);
            case 413: return Texts.get(R.string.statement_http_too_big);
            case 401: return Texts.get(R.string.more_ai_sign_in);
            case 403: return Texts.get(R.string.ai_http_forbidden);
            case 400:
                if ("unsupported_file".equals(error)) return Texts.get(R.string.statement_http_unsupported);
                if ("empty".equals(error)) return Texts.get(R.string.statement_http_empty);
                if ("unknown_wallet".equals(error)) return Texts.get(R.string.statement_http_unknown_wallet);
                return Texts.get(R.string.statement_http_rejected, error);
            default: return Texts.get(R.string.ai_http_error, code);
        }
    }

    /** Состояние разбора, пока строк ещё нет. */
    public static String statementProgress(StatementImport imp) {
        if (imp.pausedSince != null) return statementPaused();
        if (StatementImport.QUEUED.equals(imp.status)) return Texts.get(R.string.statement_progress_queued);
        if (StatementImport.CLASSIFYING.equals(imp.status)) return Texts.get(R.string.statement_progress_classifying);
        if (imp.progressTotal > 0) {
            return Texts.get(R.string.statement_progress_counted, imp.progressDone, imp.progressTotal);
        }
        return Texts.get(R.string.statement_progress_parsing);
    }

    /** Подпись состояния строки ({@link StatementRowState.Kind}); у дубля — с операцией. */
    public static String statementRowState(StatementRowState.Kind kind, StatementDuplicates.Match duplicate) {
        switch (kind) {
            case ADDED: return Texts.get(R.string.statement_row_added);
            case NOT_FOR_WRITE: return Texts.get(R.string.statement_row_not_for_write);
            case CHECK: return Texts.get(R.string.statement_row_check);
            case DUPLICATE: return duplicate != null ? statementDuplicate(duplicate) : Texts.get(R.string.statement_row_duplicate);
            case NEEDS_CATEGORY: return Texts.get(R.string.statement_row_needs_category);
            case WAITING:
            default: return Texts.get(R.string.statement_row_waiting);
        }
    }

    /** «Похоже, уже есть: 12.10 450,00 Такси». */
    public static String statementDuplicate(StatementDuplicates.Match m) {
        String day = m.date.length() >= 10 ? m.date.substring(8, 10) + "." + m.date.substring(5, 7) : m.date;
        String text = Texts.get(R.string.statement_duplicate_of, day + " " + statementAmount(m.amount));
        return m.category.isEmpty() ? text : text + " " + m.category;
    }

    /** Сумма строки: «1 290,00». */
    public static String statementAmount(double amount) {
        // Разделитель тысяч у ru — NBSP или узкий NBSP (CLDR); в списке — обычный пробел.
        return String.format(Locale.forLanguageTag("ru"), "%,.2f", amount)
                .replace(' ', ' ').replace(' ', ' ');
    }

    /** Категория строки не годится для записи: нет на телефоне (или удалена) / в архиве. */
    public static String statementCategoryProblem(String name, DraftReconciler.State state) {
        if (state == DraftReconciler.State.ARCHIVED) return Texts.get(R.string.ai_category_archived, name);
        return Texts.get(R.string.ai_category_missing, name);
    }

    /** Что не так с текстом строки ({@code check}). */
    public static String statementCheck(String check) {
        if ("amount_not_in_text".equals(check)) return Texts.get(R.string.statement_check_amount);
        if ("date_not_in_text".equals(check)) return Texts.get(R.string.statement_check_date);
        if ("currency_mismatch".equals(check)) return Texts.get(R.string.statement_check_currency);
        return Texts.get(R.string.statement_check_other);
    }

    private static String when(long at, Date now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(at);
        Calendar n = Calendar.getInstance();
        n.setTime(now);
        boolean today = a.get(Calendar.YEAR) == n.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "dd.MM HH:mm", Locale.ROOT).format(new Date(at));
    }
}
