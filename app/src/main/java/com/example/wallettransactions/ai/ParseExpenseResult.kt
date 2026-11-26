package com.example.wallettransactions.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Результат задания `parse_expense` — `ParseExpenseResult` из
 * wallet-ai/openapi.yaml (там же — что значит каждое поле). Только разбор
 * JSON, без решений: что показать в карточке, решают [DraftReconciler]
 * и экран «ИИ».
 */
class ParseExpenseResult private constructor(
    @JvmField val outcome: String,
    @JvmField val promptVersion: String,
    @JvmField val reasons: List<String>,
    @JvmField val notes: List<String>,
    /** `null`, если не [DRAFT]. */
    @JvmField val expense: Expense?,
    /** `null`, если не [DRAFT]. */
    @JvmField val resolution: Resolution?,
    /** Исходный JSON — чтобы пережить пересоздание экрана (`onSaveInstanceState`). */
    @JvmField val json: String
) {

    fun isDraft(): Boolean = DRAFT == outcome

    /** Что модель поняла из текста — строки как в ответе. */
    class Expense internal constructor(
        @JvmField val amount: String,
        @JvmField val currencyHint: String,
        @JvmField val category: String,
        @JvmField val wallet: String,
        /** `YYYY-MM-DD HH:MM:SS`. */
        @JvmField val datetime: String,
        @JvmField val comment: String
    ) {
        companion object {
            @JvmStatic
            internal fun fromJson(json: JSONObject): Expense =
                Expense(
                    json.optString("amount", ""), json.optString("currency_hint", ""),
                    json.optString("category", ""), json.optString("wallet", ""),
                    json.optString("datetime", ""), json.optString("comment", "")
                )
        }
    }

    /** Счёт из справочника сервера: `Named` или `Wallet` в openapi. */
    class Ref(
        @JvmField val uuid: String,
        @JvmField val name: String,
        /** Только у кошелька, иначе пусто. */
        @JvmField val isoCode: String,
        @JvmField val symbol: String,
        @JvmField val precision: Int
    ) {
        companion object {
            @JvmStatic
            internal fun fromJson(json: JSONObject?): Ref? {
                if (json == null) return null
                return Ref(
                    json.optString("uuid", ""), json.optString("name", ""),
                    json.optString("iso_code", ""), json.optString("symbol", ""), json.optInt("precision", 2)
                )
            }
        }
    }

    /** Решение resolver по одному полю (кошелёк или категория). */
    class Choice(
        @JvmField val kind: String,
        @JvmField val why: String,
        @JvmField val found: Ref?,
        /** Только у кошелька при `currency_mismatch`. */
        @JvmField val force: Ref?,
        @JvmField val candidates: List<Ref>
    ) {
        companion object {
            const val FOUND = "found"
            const val ASK = "ask"
            const val REJECT = "reject"

            @JvmStatic
            internal fun fromJson(json: JSONObject?): Choice {
                if (json == null) return Choice(ASK, "missing", null, null, emptyList())
                val candidates = ArrayList<Ref>()
                val arr = json.optJSONArray("candidates")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val ref = Ref.fromJson(arr.optJSONObject(i))
                        if (ref != null) candidates.add(ref)
                    }
                }
                return Choice(
                    json.optString("kind", ASK), json.optString("why", ""),
                    Ref.fromJson(json.optJSONObject("found")), Ref.fromJson(json.optJSONObject("force")),
                    candidates
                )
            }
        }
    }

    class Resolution(
        @JvmField val wallet: Choice,
        @JvmField val category: Choice
    ) {
        companion object {
            @JvmStatic
            internal fun fromJson(json: JSONObject): Resolution =
                Resolution(
                    Choice.fromJson(json.optJSONObject("wallet")),
                    Choice.fromJson(json.optJSONObject("category"))
                )
        }
    }

    companion object {
        const val DRAFT = "draft"
        const val NOT_EXPENSE = "not_expense"
        const val REJECTED = "rejected"

        @JvmStatic
        fun fromJson(json: JSONObject): ParseExpenseResult {
            val e = json.optJSONObject("expense")
            val r = json.optJSONObject("resolution")
            return ParseExpenseResult(
                json.optString("outcome", ""),
                json.optString("prompt_version", ""),
                strings(json.optJSONArray("reasons")),
                strings(json.optJSONArray("notes")),
                if (e == null) null else Expense.fromJson(e),
                if (r == null) null else Resolution.fromJson(r),
                json.toString()
            )
        }

        private fun strings(arr: JSONArray?): List<String> {
            val out = ArrayList<String>()
            if (arr == null) return out
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return out
        }
    }
}
