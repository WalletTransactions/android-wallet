package com.example.wallettransactions.DataItems.CurrencyConversion

/**
 * Какие валюты предлагать в пикерах валюты эквивалента (openspec currency-ui-tweaks,
 * spec currency-directory «Пикеры валюты эквивалента учитывают флаг», design D5).
 * Одно правило для шапки корневых экранов и для «Настроек»; без Activity — ради
 * юнит-теста. Кошелёк в валюте не нужен: что предлагать, решает только флаг
 * (equivalent-picker-by-flag — раньше шапка брала лишь валюты живых кошельков).
 * <p>
 * Текущую валюту сравниваем по id, а не по ссылке: после перечитывания списка
 * (синк, форма валюты) у неё уже другой экземпляр {@link Currency}.
 */
object EquivalentCurrencies {

    /**
     * Валюты с флагом «Показывать в эквиваленте» плюс текущая (даже со снятым флагом),
     * в исходном порядке; текущая, которой среди кандидатов нет, — в конце. Если флаг
     * снят у всех кандидатов — все кандидаты, как до появления флага.
     */
    @JvmStatic
    fun filter(candidates: List<Currency>, current: Currency?): List<Currency> {
        val result = ArrayList<Currency>()
        if (!anyFlagged(candidates)) {
            result.addAll(candidates)
        } else {
            for (c in candidates) {
                if (c.isShowInEquivalent || sameId(c, current)) result.add(c)
            }
        }
        if (current != null && !containsId(result, current)) result.add(current)
        return result
    }

    /**
     * Флаг «В эквиваленте» у новой валюты (equivalent-picker-by-flag): выключен, кроме
     * самой первой валюты справочника — иначе пикеры эквивалента остались бы пустыми.
     */
    @JvmStatic
    fun defaultForNew(directory: List<Currency>?): Boolean {
        return directory == null || directory.isEmpty()
    }

    private fun anyFlagged(currencies: List<Currency>): Boolean {
        for (c in currencies) if (c.isShowInEquivalent) return true
        return false
    }

    private fun containsId(currencies: List<Currency>, needle: Currency): Boolean {
        for (c in currencies) if (sameId(c, needle)) return true
        return false
    }

    private fun sameId(a: Currency?, b: Currency?): Boolean {
        return a != null && b != null && a.id == b.id
    }
}
