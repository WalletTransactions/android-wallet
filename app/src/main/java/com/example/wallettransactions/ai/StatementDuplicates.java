package com.example.wallettransactions.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Проверка строк выписки на дубли по полям операций телефона (openspec
 * add-ai-statement-import, решение 7): живая операция с кошелька выписки на ту же
 * сумму с датой ±1 день, записанная не из этого импорта. Одна операция закрывает
 * не больше одной строки; строки идут по дате, кандидат — ближайший по дате.
 * Серверные группы дублей ({@code exists}, {@code imported},
 * {@code possible_duplicate}) здесь не учитываются.
 */
public final class StatementDuplicates {

    /** Похожая операция на телефоне. */
    public static final class Match {
        public final long operationId;
        /** {@code ГГГГ-ММ-ДД}. */
        public final String date;
        public final double amount;
        /** Имя категории операции; пусто, если счёта нет. */
        public final String category;

        Match(long operationId, String date, double amount, String category) {
            this.operationId = operationId;
            this.date = date;
            this.amount = amount;
            this.category = category;
        }
    }

    private StatementDuplicates() {}

    /**
     * @param walletId     локальный {@code _id} кошелька выписки
     * @param rows         строки-расходы, ещё не добавленные на телефон
     * @param importRowIds {@code row_id} всех строк импорта: их операции — «из этого импорта»
     * @return {@code row_id} → похожая операция; строк без кандидата в ответе нет
     */
    public static Map<String, Match> find(SQLiteDatabase db, long walletId, List<StatementImport.Row> rows,
                                          Collection<String> importRowIds) {
        Set<String> own = new HashSet<>();
        for (String id : importRowIds) own.add(plain(id));

        List<StatementImport.Row> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> String.valueOf(a.date).compareTo(String.valueOf(b.date)));

        Map<String, Match> out = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (StatementImport.Row row : sorted) {
            LocalDate day = parse(row.date);
            double amount = row.amountValue();
            if (day == null || amount <= 0) continue;
            Match best = null;
            long bestDiff = Long.MAX_VALUE;
            Cursor c = db.rawQuery("SELECT o._id, substr(o.date, 1, 10), o.amount, o.uuid, a.name FROM operation o"
                            + " LEFT JOIN account a ON a._id = o.acc_to_id"
                            + " WHERE o.acc_fr_id = ? AND o.deleted_at IS NULL AND abs(o.amount - ?) < 0.005"
                            + " AND substr(o.date, 1, 10) BETWEEN ? AND ? ORDER BY o._id",
                    new String[]{String.valueOf(walletId), String.valueOf(amount),
                            day.minusDays(1).toString(), day.plusDays(1).toString()});
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    if (used.contains(id) || (!c.isNull(3) && own.contains(plain(c.getString(3))))) continue;
                    LocalDate opDay = parse(c.getString(1));
                    if (opDay == null) continue;
                    long diff = Math.abs(ChronoUnit.DAYS.between(day, opDay));
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = new Match(id, c.getString(1), c.getDouble(2), c.isNull(4) ? "" : c.getString(4));
                    }
                }
            } finally {
                c.close();
            }
            if (best != null) {
                used.add(best.operationId);
                out.put(row.rowId, best);
            }
        }
        return out;
    }

    private static String plain(String uuid) {
        return uuid.toLowerCase(Locale.ROOT).replace("-", "");
    }

    private static LocalDate parse(String date) {
        if (date == null || date.length() < 10) return null;
        try {
            return LocalDate.parse(date.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
