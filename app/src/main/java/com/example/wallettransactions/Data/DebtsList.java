package com.example.wallettransactions.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Группировка экрана «Долги» (openspec debts, спека «Экран „Долги“», design D10): займы по
 * контрагентам, контрагенты — по модулю остатка в эквиваленте, закрытые (нулевой остаток)
 * скрыты без переключателя, просрочка — срок раньше сегодня при ненулевом остатке. Без БД
 * и Android — для юнит-тестов.
 */
public final class DebtsList {
    private DebtsList() {}

    /** Один заём — один кошелёк. */
    public static final class Loan {
        public final int accountId;
        public final String name;
        public final String counterpartyUuid;
        /** Остаток в валюте займа: «+» мне должны, «−» я должен. */
        public final double balance;
        /** Остаток в эквиваленте; null — нет курса. */
        public final Double balanceEq;
        public final String currencySymbol;
        public final int precision;
        /** {@code yyyy-MM-dd} или null. */
        public final String dueDate;
        public final String comment;

        public Loan(int accountId, String name, String counterpartyUuid, double balance, Double balanceEq,
                    String currencySymbol, int precision, String dueDate, String comment) {
            this.accountId = accountId;
            this.name = name;
            this.counterpartyUuid = counterpartyUuid;
            this.balance = balance;
            this.balanceEq = balanceEq;
            this.currencySymbol = currencySymbol;
            this.precision = precision;
            this.dueDate = dueDate;
            this.comment = comment == null ? "" : comment;
        }

        /** Нулевой остаток с точностью валюты займа. */
        public boolean isClosed() {
            return Math.abs(balance) < Math.pow(10, -precision) / 2;
        }

        /** Спека «Просрочка»: срок раньше сегодня, остаток не нулевой. */
        public boolean isOverdue(String today) {
            return dueDate != null && !isClosed() && dueDate.compareTo(today) < 0;
        }
    }

    /** Контрагент со своими займами. */
    public static final class Group {
        public final String counterpartyUuid;
        public final String name;
        public final List<Loan> loans = new ArrayList<>();
        /** Сумма остатков в эквиваленте по займам с курсом. */
        public double totalEq;
        /** Хоть у одного займа нет курса — {@link #totalEq} неполная. */
        public boolean incomplete;

        Group(String counterpartyUuid, String name) {
            this.counterpartyUuid = counterpartyUuid;
            this.name = name;
        }
    }

    /**
     * @param names uuid контрагента → имя; займ неизвестного контрагента идёт под его uuid
     * @param showClosed переключатель «Закрытые»
     */
    public static List<Group> group(List<Loan> loans, Map<String, String> names, boolean showClosed) {
        Map<String, Group> byCp = new LinkedHashMap<>();
        for (Loan loan : loans) {
            if (!showClosed && loan.isClosed()) continue;
            Group g = byCp.get(loan.counterpartyUuid);
            if (g == null) {
                String name = names.get(loan.counterpartyUuid);
                g = new Group(loan.counterpartyUuid, name != null ? name : loan.counterpartyUuid);
                byCp.put(loan.counterpartyUuid, g);
            }
            g.loans.add(loan);
            if (loan.balanceEq != null) g.totalEq += loan.balanceEq;
            else if (!loan.isClosed()) g.incomplete = true;
        }
        List<Group> out = new ArrayList<>(byCp.values());
        for (Group g : out) {
            Collections.sort(g.loans, (a, b) -> Double.compare(abs(b.balanceEq, b.balance), abs(a.balanceEq, a.balance)));
        }
        Collections.sort(out, (a, b) -> {
            int c = Double.compare(Math.abs(b.totalEq), Math.abs(a.totalEq));
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private static double abs(Double eq, double balance) {
        return Math.abs(eq != null ? eq : balance);
    }
}
