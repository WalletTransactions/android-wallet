package com.example.wallettransactions.ai

import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.ai.ParseExpenseResult.Choice
import com.example.wallettransactions.ai.ParseExpenseResult.Ref
import com.example.wallettransactions.sync.LocalUuid

/**
 * Сверка черновика wallet-ai с базой телефона (spec ai-expense-entry,
 * «Кошелёк или категория пропали с телефона»). Resolver решает по справочнику
 * сервера, а записывать можно только на живую локальную строку `account`:
 * каждый uuid из `resolution` (found, force, candidates) переводится в
 * локальный `_id` с состоянием «есть / нет / удалён / в архиве».
 */
object DraftReconciler {

    enum class State { OK, MISSING, DELETED, ARCHIVED }

    /** Счёт из черновика и что с ним на телефоне. */
    class Local(
        @JvmField val ref: Ref,
        @JvmField val state: State,
        /** Локальный `account._id`; -1, если строки нет. */
        @JvmField val id: Long
    )

    /** Итог по одному полю карточки. */
    class Field internal constructor(
        @JvmField val choice: Choice?,
        /** Выбран resolver-ом и жив на телефоне; иначе `null`. */
        @JvmField val selected: Local?,
        /** `force` при `currency_mismatch`, только если жив. */
        @JvmField val force: Local?,
        /** Живые кандидаты `ask` в порядке resolver. */
        @JvmField val candidates: List<Local>,
        /** Что показать в блоке ошибки; `null` — ошибки нет. */
        @JvmField val problem: Local?
    )

    @JvmStatic
    fun reconcile(db: SQLiteDatabase, choice: Choice?): Field {
        if (choice == null) return Field(null, null, null, ArrayList(), null)

        var selected: Local? = null
        var problem: Local? = null
        if (Choice.FOUND == choice.kind && choice.found != null) {
            val found = lookup(db, choice.found)
            if (found.state == State.OK) selected = found
            else problem = found
        }

        var force: Local? = null
        var firstMissing: Local? = null
        if (choice.force != null) {
            val f = lookup(db, choice.force)
            if (f.state == State.OK) force = f
            else firstMissing = f
        }

        val candidates = ArrayList<Local>()
        for (ref in choice.candidates) {
            val c = lookup(db, ref)
            if (c.state == State.OK) candidates.add(c)
            else if (firstMissing == null) firstMissing = c
        }

        // ask, а предложить нечего: все кандидаты (и force) пропали с телефона.
        if (Choice.ASK == choice.kind && candidates.isEmpty() && force == null && firstMissing != null) {
            problem = firstMissing
        }
        return Field(choice, selected, force, candidates, problem)
    }

    /** Состояние одной локальной строки `account` по uuid сервера. */
    @JvmStatic
    fun lookup(db: SQLiteDatabase, ref: Ref): Local {
        val local = LocalUuid.find(db, "account", ref.uuid) ?: return Local(ref, State.MISSING, -1)
        val c = db.rawQuery(
            "SELECT _id, is_archived, deleted_at FROM account WHERE uuid=? LIMIT 1",
            arrayOf(local)
        )
        try {
            if (!c.moveToFirst()) return Local(ref, State.MISSING, -1)
            val id = c.getLong(0)
            if (!c.isNull(2)) return Local(ref, State.DELETED, id)
            if (c.getInt(1) != 0) return Local(ref, State.ARCHIVED, id)
            return Local(ref, State.OK, id)
        } finally {
            c.close()
        }
    }

    /** Состояние по локальному `_id` — повторная сверка перед записью. */
    @JvmStatic
    fun stateById(db: SQLiteDatabase, id: Long): State {
        val c = db.rawQuery(
            "SELECT is_archived, deleted_at FROM account WHERE _id=? LIMIT 1",
            arrayOf(id.toString())
        )
        try {
            if (!c.moveToFirst()) return State.MISSING
            if (!c.isNull(1)) return State.DELETED
            if (c.getInt(0) != 0) return State.ARCHIVED
            return State.OK
        } finally {
            c.close()
        }
    }
}
