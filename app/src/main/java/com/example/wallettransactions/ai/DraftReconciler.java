package com.example.wallettransactions.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.ai.ParseExpenseResult.Choice;
import com.example.wallettransactions.ai.ParseExpenseResult.Ref;
import com.example.wallettransactions.sync.LocalUuid;

import java.util.ArrayList;
import java.util.List;

/**
 * Сверка черновика wallet-ai с базой телефона (spec ai-expense-entry,
 * «Кошелёк или категория пропали с телефона»). Resolver решает по справочнику
 * сервера, а записывать можно только на живую локальную строку {@code account}:
 * каждый uuid из {@code resolution} (found, force, candidates) переводится в
 * локальный {@code _id} с состоянием «есть / нет / удалён / в архиве».
 */
public final class DraftReconciler {

    public enum State { OK, MISSING, DELETED, ARCHIVED }

    /** Счёт из черновика и что с ним на телефоне. */
    public static final class Local {
        public final Ref ref;
        public final State state;
        /** Локальный {@code account._id}; -1, если строки нет. */
        public final long id;

        public Local(Ref ref, State state, long id) {
            this.ref = ref;
            this.state = state;
            this.id = id;
        }
    }

    /** Итог по одному полю карточки. */
    public static final class Field {
        public final Choice choice;
        /** Выбран resolver-ом и жив на телефоне; иначе {@code null}. */
        public final Local selected;
        /** {@code force} при {@code currency_mismatch}, только если жив. */
        public final Local force;
        /** Живые кандидаты {@code ask} в порядке resolver. */
        public final List<Local> candidates;
        /** Что показать в блоке ошибки; {@code null} — ошибки нет. */
        public final Local problem;

        Field(Choice choice, Local selected, Local force, List<Local> candidates, Local problem) {
            this.choice = choice;
            this.selected = selected;
            this.force = force;
            this.candidates = candidates;
            this.problem = problem;
        }
    }

    private DraftReconciler() {}

    public static Field reconcile(SQLiteDatabase db, Choice choice) {
        if (choice == null) return new Field(null, null, null, new ArrayList<>(), null);

        Local selected = null;
        Local problem = null;
        if (Choice.FOUND.equals(choice.kind) && choice.found != null) {
            Local found = lookup(db, choice.found);
            if (found.state == State.OK) selected = found;
            else problem = found;
        }

        Local force = null;
        Local firstMissing = null;
        if (choice.force != null) {
            Local f = lookup(db, choice.force);
            if (f.state == State.OK) force = f;
            else firstMissing = f;
        }

        List<Local> candidates = new ArrayList<>();
        for (Ref ref : choice.candidates) {
            Local c = lookup(db, ref);
            if (c.state == State.OK) candidates.add(c);
            else if (firstMissing == null) firstMissing = c;
        }

        // ask, а предложить нечего: все кандидаты (и force) пропали с телефона.
        if (Choice.ASK.equals(choice.kind) && candidates.isEmpty() && force == null && firstMissing != null) {
            problem = firstMissing;
        }
        return new Field(choice, selected, force, candidates, problem);
    }

    /** Состояние одной локальной строки {@code account} по uuid сервера. */
    public static Local lookup(SQLiteDatabase db, Ref ref) {
        String local = LocalUuid.find(db, "account", ref.uuid);
        if (local == null) return new Local(ref, State.MISSING, -1);
        Cursor c = db.rawQuery("SELECT _id, is_archived, deleted_at FROM account WHERE uuid=? LIMIT 1",
                new String[]{local});
        try {
            if (!c.moveToFirst()) return new Local(ref, State.MISSING, -1);
            long id = c.getLong(0);
            if (!c.isNull(2)) return new Local(ref, State.DELETED, id);
            if (c.getInt(1) != 0) return new Local(ref, State.ARCHIVED, id);
            return new Local(ref, State.OK, id);
        } finally {
            c.close();
        }
    }

    /** Состояние по локальному {@code _id} — повторная сверка перед записью. */
    public static State stateById(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT is_archived, deleted_at FROM account WHERE _id=? LIMIT 1",
                new String[]{String.valueOf(id)});
        try {
            if (!c.moveToFirst()) return State.MISSING;
            if (!c.isNull(1)) return State.DELETED;
            if (c.getInt(0) != 0) return State.ARCHIVED;
            return State.OK;
        } finally {
            c.close();
        }
    }
}
