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
