package com.example.wallettransactions.ai

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Проверка строк выписки на дубли по полям операций телефона (openspec
 * add-ai-statement-import, решение 7): живая операция с кошелька выписки на ту же
 * сумму с датой ±1 день, записанная не из этого импорта. Одна операция закрывает
 * не больше одной строки; строки идут по дате, кандидат — ближайший по дате.
 * Серверные группы дублей (`exists`, `imported`,
 * `possible_duplicate`) здесь не учитываются.
 */
object StatementDuplicates {

    /** Похожая операция на телефоне. */
    class Match internal constructor(
        @JvmField val operationId: Long,
        /** `ГГГГ-ММ-ДД`. */
        @JvmField val date: String,
        @JvmField val amount: Double,
        /** Имя категории операции; пусто, если счёта нет. */
        @JvmField val category: String
    )

    /**
     * @param walletId     локальный `_id` кошелька выписки
     * @param rows         строки-расходы, ещё не добавленные на телефон
     * @param importRowIds `row_id` всех строк импорта: их операции — «из этого импорта»
     * @return `row_id` → похожая операция; строк без кандидата в ответе нет
     */
    @JvmStatic
    fun find(
        db: SQLiteDatabase, walletId: Long, rows: List<StatementImport.Row>,
        importRowIds: Collection<String>
    ): Map<String, Match> {
        val own = HashSet<String>()
        for (id in importRowIds) own.add(plain(id))

        val sorted = ArrayList(rows)
        sorted.sortWith { a, b -> a.date.toString().compareTo(b.date.toString()) }

        val out = HashMap<String, Match>()
        val used = HashSet<Long>()
        for (row in sorted) {
            val day = parse(row.date)
            val amount = row.amountValue()
            if (day == null || amount <= 0) continue
            var best: Match? = null
            var bestDiff = Long.MAX_VALUE
            val c = db.rawQuery(
                "SELECT o._id, substr(o.date, 1, 10), o.amount, o.uuid, a.name FROM operation o" +
                    " LEFT JOIN account a ON a._id = o.acc_to_id" +
                    " WHERE o.acc_fr_id = ? AND o.deleted_at IS NULL AND abs(o.amount - ?) < 0.005" +
                    " AND substr(o.date, 1, 10) BETWEEN ? AND ? ORDER BY o._id",
                arrayOf(
                    walletId.toString(), amount.toString(),
                    day.minusDays(1).toString(), day.plusDays(1).toString()
                )
            )
            try {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (used.contains(id) || (!c.isNull(3) && own.contains(plain(c.getString(3))))) continue
                    val opDay = parse(c.getString(1)) ?: continue
                    val diff = Math.abs(ChronoUnit.DAYS.between(day, opDay))
                    if (diff < bestDiff) {
                        bestDiff = diff
                        best = Match(id, c.getString(1), c.getDouble(2), if (c.isNull(4)) "" else c.getString(4))
                    }
                }
            } finally {
                c.close()
            }
            if (best != null) {
                used.add(best.operationId)
                out[row.rowId!!] = best
            }
        }
        return out
    }

    private fun plain(uuid: String): String = uuid.lowercase(Locale.ROOT).replace("-", "")

    private fun parse(date: String?): LocalDate? {
        if (date == null || date.length < 10) return null
        return try {
            LocalDate.parse(date.substring(0, 10))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
