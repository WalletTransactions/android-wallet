package com.example.wallettransactions.DataItems

import com.example.wallettransactions.DataItems.Accounts.AccountItem

/**
 * Параметры фильтра «Найти подобные» (openspec similar-search) для открытой операции:
 * категория, кошелёк, непустой комментарий и вид операции. Чистая логика без экрана —
 * экран только отдаёт сюда то, что видит пользователь, и кладёт результат в
 * {@link com.example.wallettransactions.PeriodFilterState#presetFilter}.
 */
class SimilarFilter private constructor(
    val accountIds: List<Int>,
    val kind: DataFilter.OperationKind,
    /** Пустая строка — комментария нет, в фильтр текст не идёт. */
    val searchText: String
) {

    companion object {
        /**
         * @param from    счёт «откуда»; null — сторона ещё не выбрана, её просто нет в фильтре
         * @param to      счёт «куда»
         * @param comment текст комментария как на экране; пробелы по краям не считаются
         */
        @JvmStatic
        fun of(from: AccountItem?, to: AccountItem?, comment: String?): SimilarFilter {
            val ids = ArrayList<Int>()
            // Категории — первыми: порядок id в фильтре совпадает с порядком выбора на форме.
            addIfCategory(ids, from)
            addIfCategory(ids, to)
            addIfWallet(ids, from)
            addIfWallet(ids, to)

            val text = if (comment == null) "" else comment.trim()
            return SimilarFilter(ids, kindOf(from, to), text)
        }

        /**
         * Переоценка и прочие системные проводки ни доход, ни расход — вида нет ({@code all}).
         * Расход — с кошелька на категорию, доход — наоборот, перевод — между кошельками;
         * ровно те же условия, что у {@link DataFilter.OperationKind}.
         */
        private fun kindOf(from: AccountItem?, to: AccountItem?): DataFilter.OperationKind {
            if (from == null || to == null) return DataFilter.OperationKind.all
            if (from.isSystemRevaluation || to.isSystemRevaluation) return DataFilter.OperationKind.all

            val fromWallet = from.isOperatingAccount
            val toWallet = to.isOperatingAccount
            if (fromWallet && toWallet) return DataFilter.OperationKind.transfer
            if (fromWallet) return DataFilter.OperationKind.expense
            if (toWallet) return DataFilter.OperationKind.income
            return DataFilter.OperationKind.all
        }

        private fun addIfCategory(ids: MutableList<Int>, item: AccountItem?) {
            if (item != null && !item.isOperatingAccount && !ids.contains(item.id)) ids.add(item.id)
        }

        private fun addIfWallet(ids: MutableList<Int>, item: AccountItem?) {
            if (item != null && item.isOperatingAccount && !ids.contains(item.id)) ids.add(item.id)
        }
    }
}
