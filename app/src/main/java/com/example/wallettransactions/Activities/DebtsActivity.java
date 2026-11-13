package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.DebtsList;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Экран «Долги» (openspec debts, спека «Экран „Долги“», design D10): итоги «Мне должны» /
 * «Я должен» в валюте эквивалента с признаком неполноты, контрагенты по модулю остатка,
 * под каждым — займы с остатком в своей валюте, сроком и пометкой «просрочен». Займы с
 * нулевым остатком — за переключателем «Показывать закрытые». Тап по займу — карточка
 * кошелька, где меняются контрагент, срок и комментарий.
 *
 * <p>Остатки — на сегодня по всем проводкам ({@code LoadBalancesPerWallet} с завтрашней
 * датой), а не на конец выбранного периода: долг — состояние «сейчас».
 */
public class DebtsActivity extends AppCompatActivity {

    private LinearLayout list;
    private CheckBox showClosed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debts);
        list = findViewById(R.id.debtsList);
        showClosed = findViewById(R.id.debtsShowClosed);
        showClosed.setOnCheckedChangeListener((b, c) -> render());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись с формы операции («Вернуть»), карточки займа или после синка —
        // перечитать всегда: форма операции ревизию данных не поднимает, а запросы
        // экрана лёгкие (остатки займов и список контрагентов).
        render();
    }

    public void btnUp(View view) {
        finish();
    }

    public void onOpenCounterparties(View view) {
        startActivity(new Intent(this, CounterpartiesActivity.class));
    }

    private void render() {
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
        Currency eq = PeriodFilterState.get(this).getCurrencyEq(this);
        Date asOf = tomorrow();

        // Итоги — тем же расчётом, что строки карточки периода.
        TextView owed = findViewById(R.id.debtsOwedToMe);
        TextView owe = findViewById(R.id.debtsIOwe);
        if (eq != null) {
            WalletDbLoader.DebtsResult totals = WalletDbLoader.GenerateDebtsToDate(db, asOf, eq);
            // Ноль — без знака и цвета: «−0,00 €» у «Я должен» читалось бы как долг.
            AdapterUtils.setAmountText(owed, totals.owedToMe, eq, totals.owedToMe.getTotalAmountEq() > 0
                    ? AdapterUtils.Operation.deposit : AdapterUtils.Operation.transfer);
            AdapterUtils.setAmountText(owe, totals.iOwe, eq, totals.iOwe.getTotalAmountEq() > 0
                    ? AdapterUtils.Operation.expanse : AdapterUtils.Operation.transfer);
        }

        Map<Integer, WalletDbLoader.WalletBalanceRow> balances = new HashMap<>();
        if (eq != null) {
            for (WalletDbLoader.WalletBalanceRow r : WalletDbLoader.LoadBalancesPerWallet(db, asOf, asOf, eq)) {
                balances.put(r.accountId, r);
            }
        }
        Map<Integer, AccountOperatingItem> wallets = new HashMap<>();
        List<DebtsList.Loan> loans = new ArrayList<>();
        for (AccountItem a : Singleton.instance.getAccounts(this).getAllAccounts()) {
            if (!(a instanceof AccountOperatingItem) || !((AccountOperatingItem) a).isDebt()) continue;
            AccountOperatingItem w = (AccountOperatingItem) a;
            wallets.put(w.getId(), w);
            WalletDbLoader.WalletBalanceRow row = balances.get(w.getId());
            Currency c = w.getCurrency();
            loans.add(new DebtsList.Loan(w.getId(), w.getDisplayName(), w.getDebt().counterpartyUuid,
                    row != null ? row.balance : 0,
                    row == null ? Double.valueOf(0) : row.hasEq ? Double.valueOf(row.balanceEq) : null,
                    c != null ? c.getSymbol() : "", c != null ? c.getPrecision() : 2,
                    w.getDebt().dueDate, w.getDebt().comment,
                    c != null && eq != null && c.getId() != eq.getId()));
        }
        Map<String, String> names = new HashMap<>();
        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) names.put(c.uuid, c.name);

        List<DebtsList.Group> groups = DebtsList.group(loans, names, showClosed.isChecked());
        list.removeAllViews();
        findViewById(R.id.debtsEmpty).setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        for (DebtsList.Group g : groups) {
            list.addView(groupHeader(g, eq));
            for (DebtsList.Loan loan : g.loans) list.addView(loanRow(loan, wallets.get(loan.accountId), today));
        }
    }

    private View groupHeader(DebtsList.Group g, Currency eq) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(4));
        TextView name = text(g.name, 16, R.color.colorTextPrimary, true);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView total = text("", 15, R.color.colorTextPrimary, true);
        AdapterUtils.setAmountText(total, g.totalEq, eq, g.approx, g.incomplete);
        row.addView(total);
        return row;
    }

    private View loanRow(DebtsList.Loan loan, AccountOperatingItem wallet, String today) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), 0, dp(8));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(loan.name, 14, R.color.colorTextPrimary, false);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView amount = text("", 14, R.color.colorTextPrimary, true);
        AdapterUtils.setAmountText(amount, loan.balance, wallet != null ? wallet.getCurrency() : null, false);
        line.addView(amount);
        row.addView(line);

        String note = loan.isClosed() ? getString(R.string.debts_closed)
                : loan.isOverdue(today) ? getString(R.string.debts_overdue, loan.dueDate)
                : loan.dueDate != null ? getString(R.string.debts_due, loan.dueDate) : null;
        if (!loan.comment.isEmpty()) note = note == null ? loan.comment : note + " · " + loan.comment;
        if (note != null) {
            TextView sub = text(note, 12, loan.isOverdue(today) ? R.color.colorexpanse : R.color.colorTextSecondary,
                    loan.isOverdue(today));
            row.addView(sub);
        }

        row.setOnClickListener(v -> showLoanActions(loan, wallet));
        return row;
    }

    // ── действия с займом (спека «Карточка займа», «Прощение долга») ────────────

    private static final int REQUEST_FORGIVE_CATEGORY = 410;

    /** Заём, который прощают, пока открыт диалог; null — диалога нет. */
    private DebtsList.Loan forgiveLoan;
    private AccountOperatingItem forgiveWallet;
    private int forgiveCategoryId;
    private String forgiveDay;
    private androidx.appcompat.app.AlertDialog forgiveDialog;
    private android.widget.EditText forgiveAmount;
    private TextView forgiveCategoryView;
    private TextView forgiveDateView;

    private void showLoanActions(DebtsList.Loan loan, AccountOperatingItem wallet) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!loan.isClosed() && wallet != null) {
            items.add(getString(R.string.debt_action_return));
            actions.add(() -> startReturn(loan));
            items.add(getString(R.string.debt_forgive));
            actions.add(() -> showForgive(loan, wallet));
        }
        items.add(getString(R.string.debt_action_card));
        actions.add(() -> {
            Intent edit = new Intent(this, AccountItemActivity.class);
            edit.putExtra(AccountItemActivity.ARG_ACCOUNT_ID, loan.accountId);
            startActivity(edit);
        });
        items.add(getString(R.string.debt_action_operations));
        actions.add(() -> {
            PeriodFilterState.get(this).presetFilter(this,
                    java.util.Collections.singletonList(loan.accountId), null, null);
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(loan.name)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /**
     * «Вернуть» (спека «Направление перевода по действию»): форма операции — перевод, заём на
     * нужной стороне по знаку остатка, сумма — весь остаток; кошелёк выбирает пользователь.
     */
    private void startReturn(DebtsList.Loan loan) {
        Intent op = new Intent(this, TransactionActivity.class);
        op.putExtra(TransactionActivity.cKind, "transfer"); // not UI: kind key
        // Дата обязательна: форма разбирает её без проверки на null (как у «+» на табах).
        op.putExtra(TransactionActivity.cDate, com.example.wallettransactions.DateUtils.DateToStr(new Date()));
        if (loan.balance > 0) op.putExtra(TransactionActivity.cFrom, loan.accountId);
        else op.putExtra(TransactionActivity.cTo, loan.accountId);
        op.putExtra(TransactionActivity.cAmount, Math.abs(loan.balance));
        startActivity(op);
    }

    private void showForgive(DebtsList.Loan loan, AccountOperatingItem wallet) {
        forgiveLoan = loan;
        forgiveWallet = wallet;
        forgiveDay = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        android.database.sqlite.SQLiteDatabase wdb = Singleton.instance.getWritableDatabase(this);
        forgiveCategoryId = com.example.wallettransactions.Data.WalletDbCRUD.GetOrCreateDebtWriteOffCategory(
                wdb, getString(R.string.debt_writeoff_category));
        reloadAccounts(wdb);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        Currency c = wallet.getCurrency();
        TextView amountLabel = text(getString(R.string.debt_forgive_amount, c != null ? c.getSymbol() : ""),
                12, R.color.colorTextSecondary, false);
        box.addView(amountLabel);
        forgiveAmount = new android.widget.EditText(this);
        forgiveAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        forgiveAmount.setText(AdapterUtils.plainNumber(Math.abs(loan.balance)));
        box.addView(forgiveAmount);
        forgiveCategoryView = text("", 15, R.color.colorPrimary, true);
        forgiveCategoryView.setPadding(0, dp(12), 0, dp(12));
        forgiveCategoryView.setOnClickListener(v -> {
            Intent pick = AccountPickerActivity.buildIntent(this, getString(R.string.debt_forgive),
                    AccountPickerActivity.cSelectedTypeChargeAccounts, forgiveCategoryId);
            pick.putExtra(AccountPickerActivity.cHideSystem, true);
            startActivityForResult(pick, REQUEST_FORGIVE_CATEGORY);
        });
        box.addView(forgiveCategoryView);
        forgiveDateView = text("", 15, R.color.colorPrimary, true);
        forgiveDateView.setPadding(0, dp(4), 0, dp(12));
        forgiveDateView.setOnClickListener(v -> com.example.wallettransactions.CalendarPicker.show(this,
                (Date) null, (y, m, d) -> {
                    forgiveDay = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                    paintForgive();
                }));
        box.addView(forgiveDateView);
        paintForgive();

        String cp = loan.name;
        forgiveDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(loan.balance > 0 ? getString(R.string.debt_forgive_title_me, cp)
                        : getString(R.string.debt_forgive_title_them, cp))
                .setView(box)
                .setPositiveButton(R.string.debt_forgive, (d, w) -> writeForgive())
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> forgiveDialog = null)
                .show();
    }

    private void paintForgive() {
        AccountItem cat = Singleton.instance.getAccounts(this).getAccountById(forgiveCategoryId);
        forgiveCategoryView.setText(getString(R.string.debt_forgive_category, cat != null ? cat.getDisplayName() : "—"));
        forgiveDateView.setText(getString(R.string.debt_forgive_date, forgiveDay));
    }

    private void writeForgive() {
        double amount;
        try {
            amount = Double.parseDouble(forgiveAmount.getText().toString().replace(',', '.').trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        android.database.sqlite.SQLiteDatabase wdb = Singleton.instance.getWritableDatabase(this);
        Currency c = forgiveWallet.getCurrency();
        com.example.wallettransactions.Data.WalletDbCRUD.WriteOffResult r =
                com.example.wallettransactions.Data.WalletDbCRUD.ForgiveDebt(wdb, forgiveLoan.accountId,
                        forgiveCategoryId, forgiveDay, amount, c != null ? c.getPrecision() : 2,
                        getString(R.string.debt_forgive_comment));
        boolean ok = r == com.example.wallettransactions.Data.WalletDbCRUD.WriteOffResult.written;
        android.widget.Toast.makeText(this, ok ? R.string.debt_forgive_done : R.string.debt_forgive_failed,
                android.widget.Toast.LENGTH_SHORT).show();
        if (ok) {
            PeriodFilterState.get(this).markDataChanged();
            render();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FORGIVE_CATEGORY || resultCode != RESULT_OK || data == null) return;
        int id = data.getIntExtra(AccountPickerActivity.cAccountId, 0);
        if (id > 0) forgiveCategoryId = id;
        if (forgiveDialog != null) paintForgive();
    }

    private void reloadAccounts(android.database.sqlite.SQLiteDatabase db) {
        WalletDbLoader.LoadAccounts(db, Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this), Singleton.instance.getIconList(this), null);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static Date tomorrow() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        return c.getTime();
    }
}
