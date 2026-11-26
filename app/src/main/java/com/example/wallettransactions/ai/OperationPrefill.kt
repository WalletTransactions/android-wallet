package com.example.wallettransactions.ai

import android.database.sqlite.SQLiteDatabase

/**
 * Поля окна «Новая операция» из черновика wallet-ai (spec ai-expense-entry,
 * «Карточка черновика», «Вопрос resolver — выбор в карточке»). Счета — только
 * живые на телефоне (через [DraftReconciler]): выбранный resolver кошелёк
 * или категория, которых на телефоне нет, остаются пустыми и попадают в
 * [walletProblem]/[categoryProblem] — тогда экран «ИИ» сначала
 * показывает блок ошибки, а не окно.
 */
class OperationPrefill private constructor(
    @JvmField val amount: Double,
    /** `yyyy-MM-dd HH:mm` — формат окна операции (`DateUtils`); null — «сейчас». */
    @JvmField val date: String?,
    @JvmField val comment: String,
    /** Локальный `_id`; 0 — не выбран. */
    @JvmField val walletId: Int,
    @JvmField val categoryId: Int,
    /** Кандидаты `ask` и `force` — первыми в пикерах, по порядку resolver. */
    @JvmField val priorityWalletIds: IntArray,
    @JvmField val priorityCategoryIds: IntArray,
    @JvmField val hint: String,
    @JvmField val walletProblem: DraftReconciler.Local?,
    @JvmField val categoryProblem: DraftReconciler.Local?
) {

    /** Есть пропавший с телефона счёт — окно сразу не открывается. */
    fun hasProblem(): Boolean = walletProblem != null || categoryProblem != null

    companion object {
        @JvmStatic
        fun build(db: SQLiteDatabase, draft: ParseExpenseResult, sourceText: String?): OperationPrefill {
            val e = draft.expense!!
            val w = DraftReconciler.reconcile(db, draft.resolution!!.wallet)
            val c = DraftReconciler.reconcile(db, draft.resolution.category)

            val walletId = if (w.selected != null && isWallet(db, w.selected.id, true)) w.selected.id.toInt() else 0
            val categoryId = if (c.selected != null && isWallet(db, c.selected.id, false)) c.selected.id.toInt() else 0

            val walletPriority = ArrayList(w.candidates)
            if (w.force != null) walletPriority.add(w.force)

            return OperationPrefill(
                parseAmount(e.amount), toWindowDate(e.datetime), e.comment,
                walletId, categoryId, ids(db, walletPriority, true), ids(db, c.candidates, false),
                AiTexts.operationHint(sourceText, draft), w.problem, c.problem
            )
        }

        /** `YYYY-MM-DD HH:MM:SS` → `YYYY-MM-DD HH:MM`; пусто — null («сейчас»). */
        @JvmStatic
        internal fun toWindowDate(datetime: String?): String? {
            if (datetime == null || datetime.length < 16) return null
            return datetime.substring(0, 16)
        }

        @JvmStatic
        internal fun parseAmount(s: String?): Double =
            try {
                if (s == null || s.isEmpty()) 0.0 else s.replace(',', '.').toDouble()
            } catch (ex: NumberFormatException) {
                0.0
            }

        private fun ids(db: SQLiteDatabase, locals: List<DraftReconciler.Local>, wallets: Boolean): IntArray {
            val out = ArrayList<Int>()
            for (l in locals) {
                val id = l.id.toInt()
                if (!out.contains(id) && isWallet(db, l.id, wallets)) out.add(id)
            }
            return IntArray(out.size) { out[it] }
        }

        /** Кошелёк ли это (`account.kind = 1`) — resolver мог прислать не тот вид счёта. */
        private fun isWallet(db: SQLiteDatabase, id: Long, wallet: Boolean): Boolean {
            val cur = db.rawQuery("SELECT kind FROM account WHERE _id=? LIMIT 1", arrayOf(id.toString()))
            try {
                return cur.moveToFirst() && (cur.getInt(0) == 1) == wallet
            } finally {
                cur.close()
            }
        }
    }
}
