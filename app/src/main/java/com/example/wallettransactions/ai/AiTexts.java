package com.example.wallettransactions.ai;

import com.example.wallettransactions.sync.SyncRunner;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Тексты экрана «ИИ», на которые опираются сценарии spec ai-expense-entry —
 * в одном месте, чтобы их можно было проверить юнит-тестом. Формулировки
 * общие с вебом (wallet-web, spec ai-expense-entry).
 */
public final class AiTexts {

    private AiTexts() {}

    /** «Кошелька «X» нет на телефоне», «Категория «X» в архиве» и т.п. */
    public static String problem(boolean wallet, String name, DraftReconciler.State state) {
        switch (state) {
            case MISSING:
                return (wallet ? "Кошелька «" : "Категории «") + name + "» нет на телефоне";
            case DELETED:
                return (wallet ? "Кошелёк «" : "Категория «") + name + (wallet ? "» удалён" : "» удалена")
                        + " на телефоне";
            case ARCHIVED:
                return (wallet ? "Кошелёк «" : "Категория «") + name + "» в архиве";
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
            sb.append("Синхронизация перед разбором: ").append(syncLine(syncBeforeParse)).append('\n');
        }
        sb.append("Последний успешный синк: ").append(lastSuccessAt == 0 ? "не было" : when(lastSuccessAt, now));
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
                return "нет входа в аккаунт";
            case NEEDS_RELINK:
                return "нужен повторный вход";
            case PARTIAL:
            case NETWORK_ERROR:
            case ERROR:
            case STILL_RUNNING:
                return outcome.errorMessage != null ? outcome.errorMessage : outcome.status.name();
            case OK:
            default:
                return "успешно";
        }
    }

    /** Предупреждение под полем, если синк перед разбором не прошёл. */
    public static String syncWarning(SyncRunner.Outcome outcome) {
        return "Справочник мог не дойти до сервера: " + syncLine(outcome);
    }

    /** Текст при {@code outcome: rejected} по первой причине. */
    public static String rejected(List<String> reasons) {
        String first = reasons.isEmpty() ? "" : reasons.get(0);
        String why;
        switch (first) {
            case "amount_not_in_text": why = "суммы нет в тексте"; break;
            case "future_date": why = "дата в будущем"; break;
            case "too_old": why = "дата старше года"; break;
            default: why = "сумма или дата";
        }
        return "Не получилось разобрать: " + why;
    }

    /** Отказ resolver по кошельку ({@code kind: reject}). */
    public static String walletReject(String why, String currency) {
        if ("no_wallets".equals(why)) return "В кошельке нет ни одного счёта — создайте его в приложении";
        if ("no_wallet_in_currency".equals(why)) return "Нет кошелька в " + currency;
        return "Кошелёк не найден — выберите сам";
    }

    /**
     * Строка «Подсказка ИИ: …» в окне операции: исходный текст, вопросы resolver,
     * несовпадение валюты, отказ по кошельку и пометки {@code notes} — по строке
     * на каждое (spec ai-expense-entry, «Вопрос resolver», «Отказ resolver»,
     * «Проверки в окне операции»).
     */
    public static String operationHint(String sourceText, ParseExpenseResult r) {
        StringBuilder sb = new StringBuilder("Подсказка ИИ: «").append(sourceText).append('»');
        ParseExpenseResult.Choice w = r.resolution != null ? r.resolution.wallet : null;
        ParseExpenseResult.Choice c = r.resolution != null ? r.resolution.category : null;
        String currency = r.expense != null ? r.expense.currencyHint : "";
        if (w != null && ParseExpenseResult.Choice.ASK.equals(w.kind)) {
            sb.append("\nС какого кошелька?");
            if ("currency_mismatch".equals(w.why) && w.force != null) {
                sb.append("\nВ тексте ").append(currency).append(", а «").append(w.force.name)
                        .append("» в ").append(w.force.isoCode);
            }
        } else if (w != null && ParseExpenseResult.Choice.REJECT.equals(w.kind)) {
            sb.append('\n').append(walletReject(w.why, currency));
        }
        if (c != null && ParseExpenseResult.Choice.ASK.equals(c.kind)) sb.append("\nКакая категория?");
        if (r.notes.contains("currency_dropped")) sb.append("\nВалюту из текста пришлось убрать — проверьте кошелёк");
        if (r.notes.contains("comment_truncated")) sb.append("\nКомментарий сокращён");
        return sb.toString();
    }

    // ── Экран «Выписка» (openspec add-ai-statement-import, spec ai-statement-import) ──

    public static final String STATEMENT_FILE_TOO_BIG = "Файл больше 5 МБ — разбейте выписку по месяцам";
    public static final String STATEMENT_NO_NETWORK = "Нет связи с сервером — список недоступен";
    public static final String STATEMENT_PAUSED = "Разбор на паузе: ИИ недоступен";
    public static final String STATEMENT_WALLET_MISSING = "Кошелька выписки нет на телефоне";

    /** Отказ сразу при создании ({@code status: failed}) и провал разбора. */
    public static String statementFailure(String failure) {
        if ("no_text".equals(failure)) return "В PDF нет текста — это скан. Нужна выписка с текстовым слоем";
        if ("no_operations".equals(failure)) return "В файле не нашлось операций";
        if ("timeout".equals(failure)) return "ИИ не успел разобрать выписку";
        if ("unavailable".equals(failure)) return "ИИ был недоступен слишком долго";
        return "Разбор не удался";
    }

    /** Ошибка {@code POST /api/ai/imports} по коду и {@code error}. */
    public static String statementHttpError(int code, String error) {
        switch (code) {
            case 429:
                return "import_in_progress".equals(error) ? "Уже разбирается другая выписка"
                        : "ИИ занят другими выписками — попробуйте через несколько минут";
            case 503: return "ИИ сейчас выключен";
            case 413: return "Выписка слишком большая — разбейте её по месяцам";
            case 401: return "Войдите, чтобы пользоваться ИИ";
            case 403: return "Нет доступа к ИИ для этого аккаунта";
            case 400:
                if ("unsupported_file".equals(error)) return "Такой файл не подходит — нужен PDF с текстом, CSV или TXT";
                if ("empty".equals(error)) return "Файл пустой";
                if ("unknown_wallet".equals(error)) return "Кошелька нет на сервере — синхронизируйте и повторите";
                return "Сервер не принял файл (" + error + ")";
            default: return "Сервер ответил ошибкой (HTTP " + code + ")";
        }
    }

    /** Состояние разбора, пока строк ещё нет. */
    public static String statementProgress(StatementImport imp) {
        if (imp.pausedSince != null) return STATEMENT_PAUSED;
        if (StatementImport.QUEUED.equals(imp.status)) return "Выписка в очереди на разбор";
        if (StatementImport.CLASSIFYING.equals(imp.status)) return "Подбираю категории…";
        if (imp.progressTotal > 0) return "Разбираю выписку: " + imp.progressDone + " из " + imp.progressTotal;
        return "Разбираю выписку…";
    }

    /** Подпись состояния строки ({@link StatementRowState.Kind}); у дубля — с операцией. */
    public static String statementRowState(StatementRowState.Kind kind, StatementDuplicates.Match duplicate) {
        switch (kind) {
            case ADDED: return "Добавлена";
            case NOT_FOR_WRITE: return "Не для записи";
            case CHECK: return "Проверить";
            case DUPLICATE: return duplicate != null ? statementDuplicate(duplicate) : "Похоже, уже есть";
            case NEEDS_CATEGORY: return "Нужна категория";
            case WAITING:
            default: return "Ждёт";
        }
    }

    /** «Похоже, уже есть: 12.10 450,00 Такси». */
    public static String statementDuplicate(StatementDuplicates.Match m) {
        String day = m.date.length() >= 10 ? m.date.substring(8, 10) + "." + m.date.substring(5, 7) : m.date;
        String text = "Похоже, уже есть: " + day + " " + statementAmount(m.amount);
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
        if (state == DraftReconciler.State.ARCHIVED) return "Категория «" + name + "» в архиве";
        return "Категории «" + name + "» нет на телефоне";
    }

    /** Что не так с текстом строки ({@code check}). */
    public static String statementCheck(String check) {
        if ("amount_not_in_text".equals(check)) return "Сумма не найдена в тексте выписки";
        if ("date_not_in_text".equals(check)) return "Дата не найдена в тексте выписки";
        if ("currency_mismatch".equals(check)) return "Валюта строки не совпадает с кошельком";
        return "Проверьте строку";
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
