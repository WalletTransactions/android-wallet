package com.example.wallettransactions.DataItems;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

import java.util.ArrayList;

public class DataFilter {

    /**
     * Вид операции. Доход — с категории на кошелёк, расход — с кошелька на категорию;
     * ровно те же условия, по которым считают свои суммы отчёты «Доход»/«Расход»
     * (см. WalletDbLoader.GenerateIncomeReportByAccount). Перевод — операция между двумя
     * кошельками, ни в один из этих отчётов не попадает.
     */
    public enum OperationKind {
        all(""),
        income(" AND acc_to_is_operating = 1 AND acc_from_is_operating = 0\n"),
        expense(" AND acc_to_is_operating = 0 AND acc_from_is_operating = 1\n"),
        transfer(" AND acc_to_is_operating = 1 AND acc_from_is_operating = 1\n");

        private final String sql;

        OperationKind(String sql) {
            this.sql = sql;
        }

        /** Условие для запросов по {@code v_operation_eq}; для {@link #all} — пустая строка. */
        public String getSQLFilter() {
            return sql;
        }
    }

    private ArrayList<AccountItem> accountList = new ArrayList<>();
    private OperationKind operationKind = OperationKind.all;

    /** Поиск по комментарию и названию счетов — см. {@link #getSQLFilter}. Пустая строка — фильтра нет. */
    private String searchText = "";

    /** Границы суммы в валюте кошелька ({@code amount}); null — граница не задана. */
    private Double amountMin;
    private Double amountMax;

    /** Границы суммы в валюте эквивалента ({@code amount_eq}); null — граница не задана. */
    private Double eqMin;
    private Double eqMax;

    public void clearAccount()
    {
        accountList.clear();

    }

    public void addAccount(AccountItem item){
        accountList.add(item);
    }

    public ArrayList<String> getAccounts() {
        ArrayList<String> res = new ArrayList<>();

        for (AccountItem itm: accountList)
            res.add(String.valueOf(itm.getId()));

        return res;
    }

    public OperationKind getOperationKind() {
        return operationKind;
    }

    public void setOperationKind(OperationKind kind) {
        operationKind = kind != null ? kind : OperationKind.all;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String text) {
        searchText = text != null ? text : "";
    }

    public Double getAmountMin() {
        return amountMin;
    }

    public Double getAmountMax() {
        return amountMax;
    }

    public void setAmountRange(Double min, Double max) {
        amountMin = min;
        amountMax = max;
    }

    public Double getEqMin() {
        return eqMin;
    }

    public Double getEqMax() {
        return eqMax;
    }

    public void setEqRange(Double min, Double max) {
        eqMin = min;
        eqMax = max;
    }

    /**
     * Тот же критерий, что и {@link OperationKind#getSQLFilter()}, но для уже загруженной
     * операции: после правки одной операции лента обновляется без перезапроса базы.
     */
    public boolean matchesKind(AccountItem accFrom, AccountItem accTo) {
        switch (operationKind) {
            case income:   return accTo.isOperatingAccount() && !accFrom.isOperatingAccount();
            case expense:  return accFrom.isOperatingAccount() && !accTo.isOperatingAccount();
            case transfer: return accFrom.isOperatingAccount() && accTo.isOperatingAccount();
            default:       return true;
        }
    }

    /**
     * Собирает условие {@code WHERE} для запроса по {@code v_operation_eq}. {@code fields} —
     * колонки счетов (from/to), по остальным критериям (текст, тип, суммы) имена колонок
     * фиксированы: они совпадают в этом представлении всегда.
     */
    public String getSQLFilter(String[] fields){

        String accRes="";
        String accountsSQL = String.join(",",  getAccounts());

        if (!accountsSQL.isEmpty()) {
            for (String field : fields) {
                if (!accRes.isEmpty())
                    accRes = accRes + " OR ";

                accRes = accRes + "(" + field + " in (" + accountsSQL + "))\n";
            }
            accRes = " AND (" +accRes+")\n";
        }

        StringBuilder sql = new StringBuilder(accRes);
        sql.append(operationKind.getSQLFilter());

        if (!searchText.trim().isEmpty()) {
            String pattern = "%" + escapeLike(searchText.trim()) + "%";
            sql.append(" AND (comment LIKE '").append(pattern).append("' ESCAPE '\\'")
                    .append(" OR acc_fr_name LIKE '").append(pattern).append("' ESCAPE '\\'")
                    .append(" OR acc_to_name LIKE '").append(pattern).append("' ESCAPE '\\')\n");
        }

        if (amountMin != null) sql.append(" AND amount >= ").append(amountMin).append("\n");
        if (amountMax != null) sql.append(" AND amount <= ").append(amountMax).append("\n");
        if (eqMin != null) sql.append(" AND amount_eq >= ").append(eqMin).append("\n");
        if (eqMax != null) sql.append(" AND amount_eq <= ").append(eqMax).append("\n");

        return sql.toString();
    }

    /** Экранирует кавычку (SQL-строка) и служебные для LIKE символы % и _ (ESCAPE '\'). */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .replace("'", "''");
    }
}
