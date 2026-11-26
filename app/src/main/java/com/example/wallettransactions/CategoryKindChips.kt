package com.example.wallettransactions

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.example.wallettransactions.DataItems.Accounts.AccountItem

/**
 * Три чипа «Расход» / «Доход» / «Оба» из `view_category_kind` (openspec
 * category-kind-income-expense): «Новая категория» и карточка категории. Хранит выбранный вид,
 * красит чипы через [Chips] и сообщает об изменении; сам в базу не пишет.
 *
 * @param initial   выбранный вид при открытии экрана;
 * @param onChanged зовётся после смены вида пользователем (карточка пересчитывает «Сохранить»),
 *                  `null` — не нужно.
 */
class CategoryKindChips(
    private val activity: Activity,
    initial: AccountItem.CategoryKind?,
    private val onChanged: Runnable?
) {
    private val group: View = activity.findViewById(R.id.categoryKindGroup)
    private val expense: TextView = activity.findViewById(R.id.categoryKindExpense)
    private val income: TextView = activity.findViewById(R.id.categoryKindIncome)
    private val both: TextView = activity.findViewById(R.id.categoryKindBoth)
    var selected: AccountItem.CategoryKind = initial ?: AccountItem.CategoryKind.BOTH
        private set

    init {
        expense.setOnClickListener { choose(AccountItem.CategoryKind.EXPENSE) }
        income.setOnClickListener { choose(AccountItem.CategoryKind.INCOME) }
        both.setOnClickListener { choose(AccountItem.CategoryKind.BOTH) }
        paint()
    }

    /** Блок виден только у обычной категории; у кошелька и системной категории его нет. */
    fun setVisible(visible: Boolean) {
        group.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun choose(kind: AccountItem.CategoryKind) {
        if (kind == selected) return
        selected = kind
        paint()
        onChanged?.run()
    }

    private fun paint() {
        Chips.paint(activity, expense, selected == AccountItem.CategoryKind.EXPENSE)
        Chips.paint(activity, income, selected == AccountItem.CategoryKind.INCOME)
        Chips.paint(activity, both, selected == AccountItem.CategoryKind.BOTH)
    }
}
