package com.example.wallettransactions.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Группировка экрана «Долги» (openspec debts, спека «Экран „Долги“», design D10): займы по
 * контрагентам, контрагенты — по модулю остатка в эквиваленте, архивные займы скрыты за
 * переключателем «Показывать архивные» (openspec wallets-filter-and-debt-autoarchive, D1: «Закрытые»
 * заменены архивом; нулевой заём без архива виден), направление «Мне должны» / «Я должен» — по
 * знаку остатка (D7), поиск по имени контрагента (D6), просрочка — срок раньше сегодня при
 * ненулевом остатке. Без БД и Android — для юнит-тестов.
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
        /** Остаток пересчитан по курсу — валюта займа не валюта эквивалента. */
        public final boolean converted;
        /** Флаг архива кошелька займа — влияет только на показ в списке. */
        public final boolean archived;

        public Loan(int accountId, String name, String counterpartyUuid, double balance, Double balanceEq,
                    String currencySymbol, int precision, String dueDate, String comment) {
            this(accountId, name, counterpartyUuid, balance, balanceEq, currencySymbol, precision, dueDate,
                    comment, false);
        }

        public Loan(int accountId, String name, String counterpartyUuid, double balance, Double balanceEq,
                    String currencySymbol, int precision, String dueDate, String comment, boolean converted) {
            this(accountId, name, counterpartyUuid, balance, balanceEq, currencySymbol, precision, dueDate,
                    comment, converted, false);
        }

        public Loan(int accountId, String name, String counterpartyUuid, double balance, Double balanceEq,
                    String currencySymbol, int precision, String dueDate, String comment, boolean converted,
                    boolean archived) {
            this.archived = archived;
            this.accountId = accountId;
            this.converted = converted;
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
        /** Хоть один заём пересчитан по курсу — сумма приблизительная («≈»). */
        public boolean approx;

        Group(String counterpartyUuid, String name) {
            this.counterpartyUuid = counterpartyUuid;
            this.name = name;
        }
    }

    /**
     * Займы одного контрагента для его карточки (openspec counterparty-card): те же группировка и
     * итог, что у экрана «Долги», но архивные займы остаются в списке (с пометкой) и входят в итог.
     *
     * @return группа контрагента или null, если у него нет займов
     */
    public static Group groupFor(List<Loan> loans, Map<String, String> names, String counterpartyUuid) {
        for (Group g : group(loans, names, true)) {
            if (g.counterpartyUuid.equals(counterpartyUuid)) return g;
        }
        return null;
    }

    /** Фильтр направления: по знаку остатка займа; нулевой остаток не проходит ни в одно из двух. */
    public enum Direction { ALL, OWED_TO_ME, I_OWE }

    /** Без поиска и направления — только переключатель архива. */
    public static List<Group> group(List<Loan> loans, Map<String, String> names, boolean showArchived) {
        return group(loans, names, showArchived, Direction.ALL, "");
    }

    /**
     * Условия складываются через «И»; контрагент без подходящих займов в результат не попадает.
     *
     * @param names uuid контрагента → имя; займ неизвестного контрагента идёт под его uuid
     * @param showArchived переключатель «Показывать архивные»
     * @param query строка поиска по имени контрагента ({@link AccountNames#matchesSearch})
     */
    public static List<Group> group(List<Loan> loans, Map<String, String> names, boolean showArchived,
                                    Direction direction, String query) {
        Map<String, Group> byCp = new LinkedHashMap<>();
        for (Loan loan : loans) {
            if (!showArchived && loan.archived) continue;
            if (direction == Direction.OWED_TO_ME && (loan.isClosed() || loan.balance < 0)) continue;
            if (direction == Direction.I_OWE && (loan.isClosed() || loan.balance > 0)) continue;
            Group g = byCp.get(loan.counterpartyUuid);
            if (g == null) {
                String name = names.get(loan.counterpartyUuid);
                String shown = name != null ? name : loan.counterpartyUuid;
                if (!AccountNames.matchesSearch(shown, query)) continue;
                g = new Group(loan.counterpartyUuid, shown);
                byCp.put(loan.counterpartyUuid, g);
            }
            g.loans.add(loan);
            if (loan.balanceEq != null) g.totalEq += loan.balanceEq;
            // Нет курса — итог неполный («~»); пересчёт по курсу — приблизительный («≈»); заём в
            // валюте эквивалента не требует ни того, ни другого.
            if (loan.isClosed()) continue;
            if (loan.balanceEq == null) g.incomplete = true;
            else if (loan.converted) g.approx = true;
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
