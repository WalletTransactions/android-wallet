package com.example.wallettransactions.DataItems

import com.example.wallettransactions.DataItems.Accounts.AccountItem

class DataFilter {

    /**
     * Вид операции. Доход — с категории на кошелёк, расход — с кошелька на категорию;
     * ровно те же условия, по которым считают свои суммы отчёты «Доход»/«Расход»
     * (см. WalletDbLoader.GenerateIncomeReportByAccount). Перевод — операция между двумя
     * кошельками, ни в один из этих отчётов не попадает. Переоценка капитального актива
     * ({@code is_revaluation = 1}, capital-assets) — ни доход, ни расход.
     */
    enum class OperationKind(private val sql: String) {
        all(""),
        income(" AND acc_to_is_operating = 1 AND acc_from_is_operating = 0 AND is_revaluation = 0\n"),
        expense(" AND acc_to_is_operating = 0 AND acc_from_is_operating = 1 AND is_revaluation = 0\n"),
        transfer(" AND acc_to_is_operating = 1 AND acc_from_is_operating = 1\n");

        /** Условие для запросов по {@code v_operation_eq}; для {@link #all} — пустая строка. */
        fun getSQLFilter(): String {
            return sql
        }
    }

    private val accountList = ArrayList<AccountItem>()

    var operationKind: OperationKind = OperationKind.all
        private set

    /** Поиск по комментарию и названию счетов — см. {@link #getSQLFilter}. Пустая строка — фильтра нет. */
    var searchText: String = ""
        private set

    /** Границы суммы в валюте кошелька ({@code amount}); null — граница не задана. */
    var amountMin: Double? = null
        private set
    var amountMax: Double? = null
        private set

    /** Границы суммы в валюте эквивалента ({@code amount_eq}); null — граница не задана. */
    var eqMin: Double? = null
        private set
    var eqMax: Double? = null
        private set

    fun clearAccount() {
        accountList.clear()
    }

    fun addAccount(item: AccountItem) {
        accountList.add(item)
    }

    val accounts: ArrayList<String>
        get() {
            val res = ArrayList<String>()

            for (itm in accountList)
                res.add(itm.id.toString())

            return res
        }

    fun setOperationKind(kind: OperationKind?) {
        operationKind = kind ?: OperationKind.all
    }

    fun setSearchText(text: String?) {
        searchText = text ?: ""
    }

    fun setAmountRange(min: Double?, max: Double?) {
        amountMin = min
        amountMax = max
    }

    fun setEqRange(min: Double?, max: Double?) {
        eqMin = min
        eqMax = max
    }

    /**
     * Тот же критерий, что и {@link OperationKind#getSQLFilter()}, но для уже загруженной
     * операции: после правки одной операции лента обновляется без перезапроса базы.
     */
    fun matchesKind(accFrom: AccountItem, accTo: AccountItem): Boolean {
        val revaluation = accFrom.isSystemRevaluation || accTo.isSystemRevaluation
        return when (operationKind) {
            OperationKind.income -> accTo.isOperatingAccount && !accFrom.isOperatingAccount && !revaluation
            OperationKind.expense -> accFrom.isOperatingAccount && !accTo.isOperatingAccount && !revaluation
            OperationKind.transfer -> accFrom.isOperatingAccount && accTo.isOperatingAccount
            else -> true
        }
    }

    /**
     * Собирает условие {@code WHERE} для запроса по {@code v_operation_eq}. {@code fields} —
     * колонки счетов (from/to), по остальным критериям (текст, тип, суммы) имена колонок
     * фиксированы: они совпадают в этом представлении всегда.
     */
    fun getSQLFilter(fields: Array<String>): String {

        val sql = StringBuilder()
        // Две группы — кошельки и категории: внутри группы «ИЛИ», между группами «И»
        // (operations-filter). Пустая группа ничего не ограничивает.
        sql.append(accountGroupSql(fields, true))
        sql.append(accountGroupSql(fields, false))
        sql.append(operationKind.getSQLFilter())

        if (searchText.trim().isNotEmpty()) {
            val pattern = "%" + escapeLike(searchText.trim()) + "%"
            sql.append(" AND (comment LIKE '").append(pattern).append("' ESCAPE '\\'")
                .append(" OR acc_fr_name LIKE '").append(pattern).append("' ESCAPE '\\'")
                .append(" OR acc_to_name LIKE '").append(pattern).append("' ESCAPE '\\')\n")
        }

        if (amountMin != null) sql.append(" AND amount >= ").append(amountMin).append("\n")
        if (amountMax != null) sql.append(" AND amount <= ").append(amountMax).append("\n")
        if (eqMin != null) sql.append(" AND amount_eq >= ").append(eqMin).append("\n")
        if (eqMax != null) sql.append(" AND amount_eq <= ").append(eqMax).append("\n")

        return sql.toString()
    }

    /**
     * Условие одной группы счетов: операция проходит, если хотя бы одна её сторона
     * (любая из {@code fields}) входит в группу. Пустая группа — пустая строка.
     */
    private fun accountGroupSql(fields: Array<String>, wallets: Boolean): String {
        val ids = ArrayList<String>()
        for (item in accountList) {
            if (item.isOperatingAccount == wallets) ids.add(item.id.toString())
        }
        if (ids.isEmpty()) return ""

        val idList = java.lang.String.join(",", ids)
        val group = StringBuilder()
        for (field in fields) {
            if (group.length > 0) group.append(" OR ")
            group.append("(").append(field).append(" in (").append(idList).append("))\n")
        }
        return " AND ($group)\n"
    }

    companion object {
        /** Экранирует кавычку (SQL-строка) и служебные для LIKE символы % и _ (ESCAPE '\'). */
        private fun escapeLike(text: String): String {
            return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .replace("'", "''")
        }
    }
}
