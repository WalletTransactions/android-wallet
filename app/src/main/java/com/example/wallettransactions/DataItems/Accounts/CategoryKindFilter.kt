package com.example.wallettransactions.DataItems.Accounts

/**
 * Фильтр категорий по виду операции (openspec category-kind-income-expense, спека
 * operation-entry «Выбор счёта для строки»): «Статья расхода» предлагает категории вида
 * «Расход» и «Оба», «Статья дохода» — «Доход» и «Оба».
 * <p>
 * Чистая функция без Android: решение принимается по значениям, чтобы его можно было
 * проверить JUnit-тестом без устройства.
 */
object CategoryKindFilter {

    /** Значение extra пикера «вид операции не задан» — фильтр не применяется. */
    const val NO_FILTER: Int = -1

    /**
     * Показывать ли категорию в выборе статьи.
     *
     * @param categoryKind вид категории (колонка {@code category_kind});
     * @param operationKind вид операции: {@link AccountItem.CategoryKind#EXPENSE} для
     *                      «Статьи расхода», {@link AccountItem.CategoryKind#INCOME} для
     *                      «Статьи дохода»; {@code null} или {@link AccountItem.CategoryKind#BOTH} —
     *                      фильтр не применяется;
     * @param keepAnyway   категория уже выбрана в этой операции или предложена явно (кандидат
     *                      ИИ/импорта выписки): остаётся в списке, даже если её вид не подходит —
     *                      иначе правка старой операции или подсказка показали бы пустое место.
     */
    @JvmStatic
    fun isVisible(
        categoryKind: AccountItem.CategoryKind?,
        operationKind: AccountItem.CategoryKind?,
        keepAnyway: Boolean
    ): Boolean {
        if (operationKind == null || operationKind == AccountItem.CategoryKind.BOTH) return true
        if (keepAnyway) return true
        if (categoryKind == null || categoryKind == AccountItem.CategoryKind.BOTH) return true
        return categoryKind == operationKind
    }

    /**
     * Вид операции из extra пикера: {@code 0} расход, {@code 1} доход; {@link #NO_FILTER}
     * и любое другое значение — {@code null} (фильтра нет).
     */
    @JvmStatic
    fun operationKindFromExtra(extra: Int): AccountItem.CategoryKind? {
        if (extra == AccountItem.CategoryKind.EXPENSE.value) return AccountItem.CategoryKind.EXPENSE
        if (extra == AccountItem.CategoryKind.INCOME.value) return AccountItem.CategoryKind.INCOME
        return null
    }
}
