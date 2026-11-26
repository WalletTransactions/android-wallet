package com.example.wallettransactions.ai

import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.R
import com.example.wallettransactions.Texts
import com.example.wallettransactions.sync.LocalUuid
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Состояние строки выписки на экране «Выписка» (openspec add-ai-statement-import,
 * spec ai-statement-import, «Состояния строки»): одно, по порядку проверки —
 * «Добавлена», «Не для записи», «Проверить», «Похоже, уже есть», «Нужна категория»,
 * «Ждёт». Серверные группы дублей не учитываются: дубли ищет телефон
 * ([StatementDuplicates]).
 */
object StatementRowState {

    enum class Kind { ADDED, NOT_FOR_WRITE, CHECK, DUPLICATE, NEEDS_CATEGORY, WAITING }

    /**
     * Группы сервера, в которых строка — расход, который можно записать. Остальные
     * (`income_transfer` и всё, чего приложение ещё не знает, — например
     * `ignored` из add-statement-transfers) — «не для записи».
     */
    internal val EXPENSE_GROUPS: Set<String> = hashSetOf(
        "ready", "needs_category", "possible_duplicate", "check", "exists", "imported"
    )

    /**
     * @param added     на телефоне есть живая операция с `uuid = row_id`
     * @param duplicate похожая операция ([StatementDuplicates]) или null
     */
    @JvmStatic
    fun of(row: StatementImport.Row, added: Boolean, duplicate: StatementDuplicates.Match?): Kind {
        if (added) return Kind.ADDED
        if (!row.isExpense() || !EXPENSE_GROUPS.contains(row.group)) return Kind.NOT_FOR_WRITE
        if (row.check != null) return Kind.CHECK
        if (duplicate != null) return Kind.DUPLICATE
        if (row.categoryUuid == null) return Kind.NEEDS_CATEGORY
        return Kind.WAITING
    }

    /** Почему строка «Ждёт», но в «Записать готовые» не входит; ready — входит. */
    class Readiness internal constructor(
        @JvmField val ready: Boolean,
        /** Локальный `_id` категории, если она жива на телефоне; иначе -1. */
        @JvmField val categoryId: Long,
        /** Причина для строки списка; null — причины нет. */
        @JvmField val reason: String?
    )

    /**
     * «Записать готовые» — строка «Ждёт» с живыми на телефоне кошельком и категорией
     * ([DraftReconciler]), сумма больше нуля, дата не в будущем.
     *
     * @param walletState состояние кошелька выписки на телефоне
     */
    @JvmStatic
    fun readiness(
        db: SQLiteDatabase, row: StatementImport.Row, kind: Kind,
        walletState: DraftReconciler.State, today: LocalDate
    ): Readiness {
        if (kind != Kind.WAITING) return Readiness(false, -1, null)
        val category = category(db, row.categoryUuid)
        if (category.state != DraftReconciler.State.OK) {
            return Readiness(false, -1, AiTexts.statementCategoryProblem(category.name, category.state))
        }
        if (walletState != DraftReconciler.State.OK) {
            return Readiness(false, category.id, AiTexts.statementWalletMissing())
        }
        if (row.amountValue() <= 0) return Readiness(false, category.id, Texts.get(R.string.statement_amount_not_positive))
        val day = parse(row.date) ?: return Readiness(false, category.id, Texts.get(R.string.statement_date_unparsed))
        if (day.isAfter(today)) return Readiness(false, category.id, Texts.get(R.string.statement_date_future))
        return Readiness(true, category.id, null)
    }

    /** Категория строки на телефоне: состояние, локальный `_id` и имя, если строка есть. */
    class Category internal constructor(
        @JvmField val state: DraftReconciler.State,
        @JvmField val id: Long,
        @JvmField val name: String
    )

    /** Категория по серверному uuid; кошелёк вместо категории — как будто её нет. */
    @JvmStatic
    fun category(db: SQLiteDatabase, uuid: String?): Category {
        if (uuid == null) return Category(DraftReconciler.State.MISSING, -1, "")
        val local = LocalUuid.find(db, "account", uuid) ?: return Category(DraftReconciler.State.MISSING, -1, "")
        val c = db.rawQuery(
            "SELECT _id, name, kind, is_archived, deleted_at FROM account WHERE uuid=? LIMIT 1",
            arrayOf(local)
        )
        try {
            if (!c.moveToFirst()) return Category(DraftReconciler.State.MISSING, -1, "")
            val id = c.getLong(0)
            val name = if (c.isNull(1)) "" else c.getString(1)
            if (c.getInt(2) == 1) return Category(DraftReconciler.State.MISSING, -1, name)
            if (!c.isNull(4)) return Category(DraftReconciler.State.DELETED, id, name)
            if (c.getInt(3) != 0) return Category(DraftReconciler.State.ARCHIVED, id, name)
            return Category(DraftReconciler.State.OK, id, name)
        } finally {
            c.close()
        }
    }

    private fun parse(date: String?): LocalDate? {
        if (date == null || date.length < 10) return null
        return try {
            LocalDate.parse(date.substring(0, 10))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
