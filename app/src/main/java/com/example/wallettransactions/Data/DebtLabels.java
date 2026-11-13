package com.example.wallettransactions.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Подпись перевода с займом в ленте (openspec debts, спека «Подпись операции с долговым
 * кошельком», design D6): «Дал в долг», «Взял в долг», «Вернул долг», «Мне вернули».
 * Вычисляется по остатку займа ДО проводки, одним проходом в хронологическом порядке, и
 * в базе не хранится — правка задним числом пересчитывает её при следующей загрузке.
 *
 * <p>Правило (остаток: «+» мне должны, «−» я должен):
 * <ul>
 *   <li>на заём при остатке ≥ 0 — «Дал в долг», при &lt; 0 — «Вернул долг»;</li>
 *   <li>с займа при остатке &gt; 0 — «Мне вернули», при ≤ 0 — «Взял в долг».</li>
 * </ul>
 * Нулевой остаток с займа — «Взял в долг», а не «Мне вернули»: первый заём «взял» начинается
 * именно с нуля.
 */
public final class DebtLabels {
    private DebtLabels() {}

    public enum Label { GAVE, TOOK, REPAID, RETURNED }

    /** Проводка, у которой ровно одна сторона — заём {@link #loanId}. */
    public static final class Posting {
        final int operationId;
        final String date;
        final int loanId;
        /** «+» — деньги пришли на заём, «−» — ушли с него; в валюте займа. */
        final double delta;

        public Posting(int operationId, String date, int loanId, double delta) {
            this.operationId = operationId;
            this.date = date;
            this.loanId = loanId;
            this.delta = delta;
        }
    }

    public static Label of(double balanceBefore, double delta) {
        if (delta > 0) return balanceBefore >= 0 ? Label.GAVE : Label.REPAID;
        return balanceBefore > 0 ? Label.RETURNED : Label.TOOK;
    }

    /** id операции → подпись. Порядок — по дате, при равной — по id (порядку ввода). */
    public static Map<Integer, Label> compute(List<Posting> postings) {
        List<Posting> sorted = new ArrayList<>(postings);
        Collections.sort(sorted, (a, b) -> {
            int c = a.date.compareTo(b.date);
            return c != 0 ? c : Integer.compare(a.operationId, b.operationId);
        });
        Map<Integer, Double> balance = new HashMap<>();
        Map<Integer, Label> out = new HashMap<>();
        for (Posting p : sorted) {
            double before = balance.getOrDefault(p.loanId, 0.0);
            out.put(p.operationId, of(before, p.delta));
            balance.put(p.loanId, before + p.delta);
        }
        return out;
    }
}
