package com.example.wallettransactions;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

/**
 * Три чипа «Расход» / «Доход» / «Оба» из {@code view_category_kind} (openspec
 * category-kind-income-expense): «Новая категория» и карточка категории. Хранит выбранный вид,
 * красит чипы через {@link Chips} и сообщает об изменении; сам в базу не пишет.
 */
public final class CategoryKindChips {

    private final Activity activity;
    private final View group;
    private final TextView expense;
    private final TextView income;
    private final TextView both;
    private final Runnable onChanged;
    private AccountItem.CategoryKind selected;

    /**
     * @param initial   выбранный вид при открытии экрана;
     * @param onChanged зовётся после смены вида пользователем (карточка пересчитывает «Сохранить»),
     *                  {@code null} — не нужно.
     */
    public CategoryKindChips(Activity activity, AccountItem.CategoryKind initial, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.group = activity.findViewById(R.id.categoryKindGroup);
        this.expense = activity.findViewById(R.id.categoryKindExpense);
        this.income = activity.findViewById(R.id.categoryKindIncome);
        this.both = activity.findViewById(R.id.categoryKindBoth);
        this.selected = initial == null ? AccountItem.CategoryKind.BOTH : initial;
        expense.setOnClickListener(v -> choose(AccountItem.CategoryKind.EXPENSE));
        income.setOnClickListener(v -> choose(AccountItem.CategoryKind.INCOME));
        both.setOnClickListener(v -> choose(AccountItem.CategoryKind.BOTH));
        paint();
    }

    public AccountItem.CategoryKind getSelected() {
        return selected;
    }

    /** Блок виден только у обычной категории; у кошелька и системной категории его нет. */
    public void setVisible(boolean visible) {
        group.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void choose(AccountItem.CategoryKind kind) {
        if (kind == selected) return;
        selected = kind;
        paint();
        if (onChanged != null) onChanged.run();
    }

    private void paint() {
        Chips.paint(activity, expense, selected == AccountItem.CategoryKind.EXPENSE);
        Chips.paint(activity, income, selected == AccountItem.CategoryKind.INCOME);
        Chips.paint(activity, both, selected == AccountItem.CategoryKind.BOTH);
    }
}
