package com.example.wallettransactions.DataItems;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Параметры фильтра «Найти подобные» (openspec similar-search) для открытой операции:
 * категория, кошелёк, непустой комментарий и вид операции. Чистая логика без экрана —
 * экран только отдаёт сюда то, что видит пользователь, и кладёт результат в
 * {@link com.example.wallettransactions.PeriodFilterState#presetFilter}.
 */
public final class SimilarFilter {

    private final List<Integer> accountIds;
    private final DataFilter.OperationKind kind;
    private final String searchText;

    private SimilarFilter(List<Integer> accountIds, DataFilter.OperationKind kind, String searchText) {
        this.accountIds = accountIds;
        this.kind = kind;
        this.searchText = searchText;
    }

    /**
     * @param from    счёт «откуда»; null — сторона ещё не выбрана, её просто нет в фильтре
     * @param to      счёт «куда»
     * @param comment текст комментария как на экране; пробелы по краям не считаются
     */
    public static SimilarFilter of(AccountItem from, AccountItem to, String comment) {
        List<Integer> ids = new ArrayList<>();
        // Категории — первыми: порядок id в фильтре совпадает с порядком выбора на форме.
        addIfCategory(ids, from);
        addIfCategory(ids, to);
        addIfWallet(ids, from);
        addIfWallet(ids, to);

        String text = comment == null ? "" : comment.trim();
        return new SimilarFilter(ids, kindOf(from, to), text);
    }

    /**
     * Переоценка и прочие системные проводки ни доход, ни расход — вида нет ({@code all}).
     * Расход — с кошелька на категорию, доход — наоборот, перевод — между кошельками;
     * ровно те же условия, что у {@link DataFilter.OperationKind}.
     */
    private static DataFilter.OperationKind kindOf(AccountItem from, AccountItem to) {
        if (from == null || to == null) return DataFilter.OperationKind.all;
        if (from.isSystemRevaluation() || to.isSystemRevaluation()) return DataFilter.OperationKind.all;

        boolean fromWallet = from.isOperatingAccount();
        boolean toWallet = to.isOperatingAccount();
        if (fromWallet && toWallet) return DataFilter.OperationKind.transfer;
        if (fromWallet) return DataFilter.OperationKind.expense;
        if (toWallet) return DataFilter.OperationKind.income;
        return DataFilter.OperationKind.all;
    }

    private static void addIfCategory(List<Integer> ids, AccountItem item) {
        if (item != null && !item.isOperatingAccount() && !ids.contains(item.getId())) ids.add(item.getId());
    }

    private static void addIfWallet(List<Integer> ids, AccountItem item) {
        if (item != null && item.isOperatingAccount() && !ids.contains(item.getId())) ids.add(item.getId());
    }

    public List<Integer> getAccountIds() {
        return accountIds;
    }

    public DataFilter.OperationKind getKind() {
        return kind;
    }

    /** Пустая строка — комментария нет, в фильтр текст не идёт. */
    public String getSearchText() {
        return searchText;
    }
}
