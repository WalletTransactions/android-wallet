package com.example.wallettransactions.ai

import org.json.JSONObject
import java.util.Collections

/**
 * Импорт выписки wallet-ai (`Import`/`ImportDetail` в wallet-ai/openapi.yaml;
 * openspec add-ai-statement-import). Строки есть только в `ready`/`done`.
 * Незнакомые статусы, группы и поля разбор не ломают: группу, которую приложение
 * не знает, экран показывает как «не для записи» ([StatementRowState]).
 */
class StatementImport(
    @JvmField val id: String?,
    @JvmField val status: String?,
    @JvmField val failure: String?,
    @JvmField val walletUuid: String?,
    @JvmField val sourceName: String?,
    @JvmField val progressDone: Int,
    @JvmField val progressTotal: Int,
    /** LLM недоступна — разбор на паузе с этого момента; null — не на паузе. */
    @JvmField val pausedSince: String?,
    @JvmField val rows: List<Row>
) {

    /** Строка исходника выписки (`lines[]` у строки импорта). */
    class Line internal constructor(
        @JvmField val n: Int,
        @JvmField val text: String?
    )

    /** Строка импорта (`ImportRow`). */
    class Row(
        @JvmField val rowId: String?,
        @JvmField val group: String?,
        @JvmField val lines: List<Line>,
        /** `ГГГГ-ММ-ДД`. */
        @JvmField val date: String?,
        /** `1290.00` — положительная. */
        @JvmField val amount: String?,
        /** `expense`, `income`, `transfer`. */
        @JvmField val dir: String?,
        @JvmField val description: String?,
        @JvmField val categoryUuid: String?,
        /** Вариант модели ниже порога уверенности — имя категории. */
        @JvmField val suggestion: String?,
        /** `amount_not_in_text`, `date_not_in_text`, `currency_mismatch` или null. */
        @JvmField val check: String?,
        @JvmField val committed: Boolean
    ) {

        fun isExpense(): Boolean = "expense" == dir

        /** Сумма числом; 0 — не разобралась. */
        fun amountValue(): Double =
            try {
                if (amount == null) 0.0 else amount.replace(',', '.').toDouble()
            } catch (e: NumberFormatException) {
                0.0
            }

        /** Текст строк выписки, из которых взята строка, — по строке на номер. */
        fun sourceText(): String {
            val sb = StringBuilder()
            for (l in lines) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(l.text)
            }
            return sb.toString()
        }

        companion object {
            @JvmStatic
            internal fun fromJson(j: JSONObject): Row {
                val lines = ArrayList<Line>()
                val arr = j.optJSONArray("lines")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val l = arr.optJSONObject(i)
                        if (l != null) lines.add(Line(l.optInt("n", 0), str(l, "text")))
                    }
                }
                return Row(
                    str(j, "row_id"), str(j, "group"), Collections.unmodifiableList(lines), str(j, "date"),
                    str(j, "amount"), str(j, "dir"), str(j, "description"), str(j, "category_uuid"),
                    str(j, "suggestion"), str(j, "check"), j.optBoolean("committed", false)
                )
            }
        }
    }

    /** Разбор ещё идёт: в очереди, извлечение строк или подбор категорий. */
    fun isActive(): Boolean = QUEUED == status || EXTRACTING == status || CLASSIFYING == status

    /** Строки готовы к проверке: `ready` или `done`. */
    fun hasRows(): Boolean = READY == status || DONE == status

    fun row(rowId: String?): Row? {
        for (r in rows) if (r.rowId == rowId) return r
        return null
    }

    companion object {
        const val QUEUED = "queued"
        const val EXTRACTING = "extracting"
        const val CLASSIFYING = "classifying"
        const val READY = "ready"
        const val DONE = "done"
        const val FAILED = "failed"

        @JvmStatic
        fun fromJson(j: JSONObject): StatementImport {
            val id = str(j, "import_id")
            val progress = j.optJSONObject("progress")
            val rows = ArrayList<Row>()
            val arr = j.optJSONArray("rows")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i)
                    if (r != null) rows.add(Row.fromJson(r))
                }
            }
            return StatementImport(
                id, str(j, "status"), str(j, "failure"), str(j, "wallet_uuid"),
                str(j, "source_name"), progress?.optInt("done", 0) ?: 0,
                progress?.optInt("total", 0) ?: 0, str(j, "paused_since"),
                Collections.unmodifiableList(rows)
            )
        }

        /** `GET /api/ai/imports`: новые первыми, без строк. */
        @JvmStatic
        fun listFromJson(j: JSONObject): List<StatementImport> {
            val out = ArrayList<StatementImport>()
            val arr = j.optJSONArray("imports") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) out.add(fromJson(o))
            }
            return out
        }

        /** Строка или null: `optString` отдаёт для JSON null текст «null». */
        private fun str(j: JSONObject, key: String): String? =
            if (j.isNull(key)) null else j.optString(key, null)
    }
}
