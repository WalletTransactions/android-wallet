package com.example.wallettransactions.Data

/**
 * Подпись перевода с займом в ленте (openspec debts, спека «Подпись операции с долговым
 * кошельком», design D6): «Дал в долг», «Взял в долг», «Вернул долг», «Мне вернули».
 * Вычисляется по остатку займа ДО проводки, одним проходом в хронологическом порядке, и
 * в базе не хранится — правка задним числом пересчитывает её при следующей загрузке.
 *
 * Правило (остаток: «+» мне должны, «−» я должен):
 *  - на заём при остатке ≥ 0 — «Дал в долг», при &lt; 0 — «Вернул долг»;
 *  - с займа при остатке &gt; 0 — «Мне вернули», при ≤ 0 — «Взял в долг».
 *
 * Нулевой остаток с займа — «Взял в долг», а не «Мне вернули»: первый заём «взял» начинается
 * именно с нуля.
 */
object DebtLabels {
    enum class Label { GAVE, TOOK, REPAID, RETURNED }

    /**
     * Проводка, у которой ровно одна сторона — заём [loanId].
     * [delta]: «+» — деньги пришли на заём, «−» — ушли с него; в валюте займа.
     */
    class Posting(
        @JvmField val operationId: Int,
        @JvmField val date: String,
        @JvmField val loanId: Int,
        @JvmField val delta: Double
    )

    @JvmStatic
    fun of(balanceBefore: Double, delta: Double): Label {
        if (delta > 0) return if (balanceBefore >= 0) Label.GAVE else Label.REPAID
        return if (balanceBefore > 0) Label.RETURNED else Label.TOOK
    }

    /** id операции → подпись. Порядок — по дате, при равной — по id (порядку ввода). */
    @JvmStatic
    fun compute(postings: List<Posting>): Map<Int, Label> {
        val sorted = postings.sortedWith { a, b ->
            val c = a.date.compareTo(b.date)
            if (c != 0) c else a.operationId.compareTo(b.operationId)
        }
        val balance = HashMap<Int, Double>()
        val out = HashMap<Int, Label>()
        for (p in sorted) {
            val before = balance[p.loanId] ?: 0.0
            out[p.operationId] = of(before, p.delta)
            balance[p.loanId] = before + p.delta
        }
        return out
    }
}
