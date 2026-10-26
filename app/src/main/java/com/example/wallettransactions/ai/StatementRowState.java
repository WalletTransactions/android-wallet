package com.example.wallettransactions.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.sync.LocalUuid;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Состояние строки выписки на экране «Выписка» (openspec add-ai-statement-import,
 * spec ai-statement-import, «Состояния строки»): одно, по порядку проверки —
 * «Добавлена», «Не для записи», «Проверить», «Похоже, уже есть», «Нужна категория»,
 * «Ждёт». Серверные группы дублей не учитываются: дубли ищет телефон
 * ({@link StatementDuplicates}).
 */
public final class StatementRowState {

    public enum Kind { ADDED, NOT_FOR_WRITE, CHECK, DUPLICATE, NEEDS_CATEGORY, WAITING }

    /**
     * Группы сервера, в которых строка — расход, который можно записать. Остальные
     * ({@code income_transfer} и всё, чего приложение ещё не знает, — например
     * {@code ignored} из add-statement-transfers) — «не для записи».
     */
    static final Set<String> EXPENSE_GROUPS = new HashSet<>(Arrays.asList(
            "ready", "needs_category", "possible_duplicate", "check", "exists", "imported"));

    private StatementRowState() {}

    /**
     * @param added     на телефоне есть живая операция с {@code uuid = row_id}
     * @param duplicate похожая операция ({@link StatementDuplicates}) или null
     */
    public static Kind of(StatementImport.Row row, boolean added, StatementDuplicates.Match duplicate) {
        if (added) return Kind.ADDED;
        if (!row.isExpense() || !EXPENSE_GROUPS.contains(row.group)) return Kind.NOT_FOR_WRITE;
        if (row.check != null) return Kind.CHECK;
        if (duplicate != null) return Kind.DUPLICATE;
        if (row.categoryUuid == null) return Kind.NEEDS_CATEGORY;
        return Kind.WAITING;
    }

    /** Почему строка «Ждёт», но в «Записать готовые» не входит; ready — входит. */
    public static final class Readiness {
        public final boolean ready;
        /** Локальный {@code _id} категории, если она жива на телефоне; иначе -1. */
        public final long categoryId;
        /** Причина для строки списка; null — причины нет. */
        public final String reason;

        Readiness(boolean ready, long categoryId, String reason) {
            this.ready = ready;
            this.categoryId = categoryId;
            this.reason = reason;
        }
    }

    /**
     * «Записать готовые» — строка «Ждёт» с живыми на телефоне кошельком и категорией
     * ({@link DraftReconciler}), сумма больше нуля, дата не в будущем.
     *
     * @param walletState состояние кошелька выписки на телефоне
     */
    public static Readiness readiness(SQLiteDatabase db, StatementImport.Row row, Kind kind,
                                      DraftReconciler.State walletState, LocalDate today) {
        if (kind != Kind.WAITING) return new Readiness(false, -1, null);
        Category category = category(db, row.categoryUuid);
        if (category.state != DraftReconciler.State.OK) {
            return new Readiness(false, -1, AiTexts.statementCategoryProblem(category.name, category.state));
        }
        if (walletState != DraftReconciler.State.OK) {
            return new Readiness(false, category.id, AiTexts.STATEMENT_WALLET_MISSING);
        }
        if (row.amountValue() <= 0) return new Readiness(false, category.id, "Сумма не больше нуля");
        LocalDate day = parse(row.date);
        if (day == null) return new Readiness(false, category.id, "Дата не разобралась");
        if (day.isAfter(today)) return new Readiness(false, category.id, "Дата в будущем");
        return new Readiness(true, category.id, null);
    }

    /** Категория строки на телефоне: состояние, локальный {@code _id} и имя, если строка есть. */
    public static final class Category {
        public final DraftReconciler.State state;
        public final long id;
        public final String name;

        Category(DraftReconciler.State state, long id, String name) {
            this.state = state;
            this.id = id;
            this.name = name;
        }
    }

    /** Категория по серверному uuid; кошелёк вместо категории — как будто её нет. */
    public static Category category(SQLiteDatabase db, String uuid) {
        if (uuid == null) return new Category(DraftReconciler.State.MISSING, -1, "");
        String local = LocalUuid.find(db, "account", uuid);
        if (local == null) return new Category(DraftReconciler.State.MISSING, -1, "");
        Cursor c = db.rawQuery("SELECT _id, name, kind, is_archived, deleted_at FROM account WHERE uuid=? LIMIT 1",
                new String[]{local});
        try {
            if (!c.moveToFirst()) return new Category(DraftReconciler.State.MISSING, -1, "");
            long id = c.getLong(0);
            String name = c.isNull(1) ? "" : c.getString(1);
            if (c.getInt(2) == 1) return new Category(DraftReconciler.State.MISSING, -1, name);
            if (!c.isNull(4)) return new Category(DraftReconciler.State.DELETED, id, name);
            if (c.getInt(3) != 0) return new Category(DraftReconciler.State.ARCHIVED, id, name);
            return new Category(DraftReconciler.State.OK, id, name);
        } finally {
            c.close();
        }
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
