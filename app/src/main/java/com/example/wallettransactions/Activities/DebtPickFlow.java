package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.database.sqlite.SQLiteDatabase;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.DebtDirection;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * «Дать в долг» / «Взять в долг» / «Вернуть» из выбора статьи (openspec debts, design D6):
 * контрагент → заём (существующий кошелёк или «Новый заём») → кошелёк займа и направление
 * перевода. Сумму, дату и комментарий вводят на обычной форме операции — она получает
 * готовую пару «кошелёк ↔ заём» и тип «Перевод», так что разновалютный заём идёт тем же
 * путём через «Transfer», что и любой перевод.
 *
 * <p>Новые контрагент и заём пишутся в базу сразу, при выборе: форма операции берёт счёт
 * из кэша {@code Singleton}. Пустой заём, если операцию так и не сохранили, остаётся
 * закрытым займом с нулевым остатком — его видно в «Закрытых».
 */
final class DebtPickFlow {

    interface Result {
        /** @param direction {@link DebtDirection#WALLET_TO_LOAN} или {@link DebtDirection#LOAN_TO_WALLET} */
        void onLoanPicked(int loanAccountId, int direction);
    }

    private final Activity activity;
    private final DebtDirection.Action action;
    /** Кошелёк пользователя из формы — валюта «Нового займа» по умолчанию; может быть null. */
    private final AccountOperatingItem userWallet;
    private final Result result;

    private DebtPickFlow(Activity activity, DebtDirection.Action action, AccountOperatingItem userWallet, Result result) {
        this.activity = activity;
        this.action = action;
        this.userWallet = userWallet;
        this.result = result;
    }

    static void start(Activity activity, DebtDirection.Action action, AccountOperatingItem userWallet, Result result) {
        new DebtPickFlow(activity, action, userWallet, result).pickCounterparty();
    }

    // ── шаг 1: контрагент ────────────────────────────────────────────────────

    private void pickCounterparty() {
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        List<CounterpartyItem> list = new ArrayList<>();
        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
            if (c.isArchived) continue; // спека «Архивный контрагент»: не в выборе при вводе
            if (action == DebtDirection.Action.RETURN && openLoans(db, c.uuid).isEmpty()) continue;
            list.add(c);
        }
        boolean canCreate = action != DebtDirection.Action.RETURN;
        if (list.isEmpty() && !canCreate) {
            Toast.makeText(activity, R.string.debt_return_nothing, Toast.LENGTH_LONG).show();
            return;
        }
        int offset = canCreate ? 1 : 0;
        String[] items = new String[list.size() + offset];
        if (canCreate) items[0] = activity.getString(R.string.debt_new_counterparty);
        for (int i = 0; i < list.size(); i++) items[i + offset] = list.get(i).name;
        new AlertDialog.Builder(activity)
                .setTitle(titleRes())
                .setItems(items, (d, which) -> {
                    if (canCreate && which == 0) askNewCounterparty();
                    else pickLoan(list.get(which - offset));
                })
                .show();
    }

    private void askNewCounterparty() {
        EditText input = new EditText(activity);
        input.setHint(R.string.debt_counterparty_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.debt_new_counterparty_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = WalletDbCRUD.NormalizeCounterpartyName(input.getText().toString());
                    if (name.isEmpty()) return;
                    SQLiteDatabase db = new WalletDbHelper(activity).getWritableDatabase();
                    int id = WalletDbCRUD.GetCounterpartyIdByName(db, name);
                    if (id > 0) {
                        // Спека «Дубль имени»: второго не заводим — берём существующего.
                        Toast.makeText(activity, activity.getString(R.string.debt_counterparty_exists, name),
                                Toast.LENGTH_LONG).show();
                    } else {
                        id = WalletDbCRUD.CreateCounterparty(db, name, "");
                    }
                    for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
                        if (c.id == id) { pickLoan(c); return; }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── шаг 2: заём ──────────────────────────────────────────────────────────

    private void pickLoan(CounterpartyItem counterparty) {
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        List<AccountOperatingItem> loans = action == DebtDirection.Action.RETURN
                ? openLoans(db, counterparty.uuid) : liveLoans(counterparty.uuid);
        boolean canCreate = action != DebtDirection.Action.RETURN;
        Currency newCurrency = newLoanCurrency(loans);
        int offset = canCreate && newCurrency != null ? 1 : 0;

        String[] items = new String[loans.size() + offset];
        if (offset == 1) items[0] = activity.getString(R.string.debt_new_loan, newCurrency.getIso());
        for (int i = 0; i < loans.size(); i++) {
            AccountOperatingItem loan = loans.get(i);
            items[i + offset] = activity.getString(R.string.debt_loan_row, loan.getDisplayName(),
                    money(WalletDbLoader.LoadAccountBalance(db, loan.getId()), loan.getCurrency()));
        }
        new AlertDialog.Builder(activity)
                .setTitle(counterparty.name)
                .setItems(items, (d, which) -> {
                    if (offset == 1 && which == 0) createLoan(counterparty, newCurrency);
                    else finish(loans.get(which - offset));
                })
                .show();
    }

    private void createLoan(CounterpartyItem counterparty, Currency currency) {
        SQLiteDatabase db = new WalletDbHelper(activity).getWritableDatabase();
        int loanId;
        db.beginTransaction();
        try {
            loanId = WalletDbCRUD.CreateDebtWallet(db, counterparty.id, currency.getId(), "", null);
            if (loanId > 0) db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (loanId <= 0) {
            Toast.makeText(activity, R.string.debt_loan_failed, Toast.LENGTH_LONG).show();
            return;
        }
        reloadAccounts(db);
        AccountItem loan = Singleton.instance.getAccounts(activity).getAccountById(loanId);
        if (loan instanceof AccountOperatingItem) finish((AccountOperatingItem) loan);
    }

    private void finish(AccountOperatingItem loan) {
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        int direction = DebtDirection.of(action, WalletDbLoader.LoadAccountBalance(db, loan.getId()));
        if (direction == DebtDirection.NONE) {
            Toast.makeText(activity, R.string.debt_return_nothing, Toast.LENGTH_LONG).show();
            return;
        }
        result.onLoanPicked(loan.getId(), direction);
    }

    // ── данные ───────────────────────────────────────────────────────────────

    /** Живые неархивные займы контрагента из кэша счетов. */
    private List<AccountOperatingItem> liveLoans(String counterpartyUuid) {
        List<AccountOperatingItem> out = new ArrayList<>();
        for (AccountItem a : Singleton.instance.getAccounts(activity).getAllAccounts()) {
            if (!(a instanceof AccountOperatingItem)) continue;
            AccountOperatingItem w = (AccountOperatingItem) a;
            if (w.isDebt() && counterpartyUuid.equals(w.getDebt().counterpartyUuid)
                    && !Boolean.TRUE.equals(w.getIsArchived())) out.add(w);
        }
        return out;
    }

    /** Займы с ненулевым остатком — для «Вернуть». */
    private List<AccountOperatingItem> openLoans(SQLiteDatabase db, String counterpartyUuid) {
        List<AccountOperatingItem> out = new ArrayList<>();
        for (AccountOperatingItem w : liveLoans(counterpartyUuid)) {
            double b = WalletDbLoader.LoadAccountBalance(db, w.getId());
            if (Math.abs(b) >= Math.pow(10, -w.getCurrency().getPrecision()) / 2) out.add(w);
        }
        return out;
    }

    /** Валюта «Нового займа»: кошелька в форме, иначе эквивалента (design D6). */
    private Currency newLoanCurrency(List<AccountOperatingItem> loans) {
        if (userWallet != null && userWallet.getCurrency() != null) return userWallet.getCurrency();
        Currency eq = PeriodFilterState.get(activity).getCurrencyEq(activity);
        if (eq != null) return eq;
        return loans.isEmpty() ? null : loans.get(0).getCurrency();
    }

    private void reloadAccounts(SQLiteDatabase db) {
        WalletDbLoader.LoadAccounts(db, Singleton.instance.getAccounts(activity),
                Singleton.instance.getCurrencyList(activity), Singleton.instance.getIconList(activity), null);
        PeriodFilterState.get(activity).markDataChanged();
    }

    private int titleRes() {
        switch (action) {
            case GIVE: return R.string.debt_action_give;
            case TAKE: return R.string.debt_action_take;
            default: return R.string.debt_action_return;
        }
    }

    static String money(double amount, Currency c) {
        int precision = c != null ? c.getPrecision() : 2;
        return AdapterUtils.getFormat(precision).format(amount) + (c != null ? " " + c.getSymbol() : "");
    }
}
