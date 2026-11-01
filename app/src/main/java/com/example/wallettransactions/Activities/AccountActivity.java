package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Дашборд «Кошельки» — таб нижней панели: общая шапка всех корневых экранов
 * ({@link BaseTabActivity} — период, фильтр, карточка остатков) плюс список
 * остатков по кошелькам ({@code activity_account.xml}).
 *
 * Выбор счёта для операции жил здесь же вторым режимом и открывался списком-диалогом;
 * теперь это отдельный экран — {@link AccountPickerActivity}. Кнопки «+» в шапке нет:
 * счета заводятся только в справочнике «Кошельки и категории» (тот же
 * AccountPickerActivity, режим справочника; openspec currency-ui-tweaks, design D10).
 */
public class AccountActivity extends BaseTabActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_account);
        initTabScreen(R.id.navWallets, "Кошельки");
        reloadSharedAndTabData();
    }

    @Override
    protected void reloadTabData() {
        AccountList accounts = Singleton.instance.getAccounts(this);
        List<WalletRow> walletRows = buildWalletRows();

        ListView listWallets = findViewById(R.id.listWallets);
        listWallets.setAdapter(new WalletBalanceAdapter(this, walletRows, getCurrencyEq(), accounts));

        // Строка кошелька — свёрнутый итог, за которым стоят операции: тап разворачивает
        // его в ленту «Операций» по этому кошельку. Так же ведёт себя строка категории в
        // «Отчётах» (ReportActivity.treeViewClick) — фильтр общий, поэтому он же виден в
        // шапке и снимается «Сбросить». Правка кошелька живёт в справочнике «Ещё».
        listWallets.setOnItemClickListener((parent, view, position, id) -> {
            state().setAccountIds(this, Collections.singletonList(walletRows.get(position).accountId));

            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
    }

    /**
     * One row per wallet with open/close balance (native + equivalent) and the net change
     * over the period — per wallet_balance_item_layout.xml, fattest wallet first.
     *
     * A wallet untouched during the period is listed too: the report is about where the
     * money stands, and a full wallet nobody touched this month still holds its money —
     * hiding it would leave the balances not adding up to the card above. Archived wallets
     * are listed for the same reason: the archive flag only keeps them out of pickers, it
     * never takes them out of the arithmetic ({@code AccountItem.getIsArchived()}).
     */
    private List<WalletRow> buildWalletRows() {
        SQLiteDatabase db = getDb();
        Currency currencyEq = getCurrencyEq();

        List<WalletDbLoader.WalletBalanceRow> openRows = WalletDbLoader.LoadBalancesPerWallet(
                db, getDateFrom(), getDateFrom(), currencyEq);
        List<WalletDbLoader.WalletBalanceRow> closeRows = WalletDbLoader.LoadBalancesPerWallet(
                db, getDateTo(), getDateTo(), currencyEq);

        Map<Integer, WalletDbLoader.WalletBalanceRow> openByAccount = new HashMap<>();
        for (WalletDbLoader.WalletBalanceRow r : openRows) openByAccount.put(r.accountId, r);

        List<WalletRow> result = new ArrayList<>();
        for (WalletDbLoader.WalletBalanceRow close : closeRows) {
            WalletDbLoader.WalletBalanceRow open = openByAccount.get(close.accountId);

            // Missing from the "open" snapshot means no activity existed before dateFrom at
            // all — a real, exactly-zero balance, not a missing-rate gap.
            double openBal    = open != null ? open.balance : 0;
            double openEq     = open != null && open.hasEq ? open.balanceEq : 0;
            boolean openHasEq = open == null || open.hasEq;

            boolean netEqComputable = close.hasEq && openHasEq;
            double netEq     = netEqComputable ? close.balanceEq - openEq : 0;
            double netNative = close.balance - openBal;

            result.add(new WalletRow(close.accountId, close.name, close.currencySymbol,
                    openBal, openEq, openHasEq, open != null && open.approx,
                    close.balance, close.balanceEq, close.hasEq, close.approx,
                    netEq, netEqComputable, netNative));
        }

        // Fattest wallet at the end of the period first. Wallets whose currency has no rate to
        // currencyEq at all are not comparable with the rest, so they sink below every wallet
        // that is, and are ranked among themselves by their own currency.
        applyFilter(result);

        result.sort(Comparator
                .<WalletRow>comparingInt(r -> r.closeHasEq ? 1 : 0).reversed()
                .thenComparing((a, b) -> Double.compare(
                        b.closeHasEq ? b.closeEq : b.closeBal,
                        a.closeHasEq ? a.closeEq : a.closeBal)));

        return result;
    }

    /**
     * Общий фильтр шапки отбирает строки, но не меняет цифры в них: остаток — это остаток,
     * «сумма операций с комментарием „кофе“ с начала времён» остатком не была бы.
     *
     * <ul>
     *   <li>в фильтре выбраны кошельки — остаются только они;</li>
     *   <li>задано что-то ещё (категории, вид операции, текст, суммы) — остаются кошельки,
     *   у которых за период есть хоть одна операция, прошедшая фильтр, т.е. те, чьи
     *   операции видны в ленте «Операций» с тем же фильтром.</li>
     * </ul>
     */
    private void applyFilter(List<WalletRow> rows) {
        if (!state().hasFilter()) return;

        AccountList accounts = Singleton.instance.getAccounts(this);
        Set<Integer> selectedWallets = new HashSet<>();
        boolean categorySelected = false;
        for (Integer id : state().getAccountIds()) {
            AccountItem item = accounts.getAccountById(id);
            if (item == null) continue;
            if (item.isOperatingAccount()) selectedWallets.add(id);
            else categorySelected = true;
        }

        if (!selectedWallets.isEmpty()) rows.removeIf(r -> !selectedWallets.contains(r.accountId));

        boolean operationCriteria = categorySelected
                || state().getOperationKind() != DataFilter.OperationKind.all
                || !state().getSearchText().isEmpty()
                || state().getAmountMin() != null || state().getAmountMax() != null
                || state().getEqMin() != null || state().getEqMax() != null;
        if (!operationCriteria) return;

        Set<Integer> withOperations = WalletDbLoader.LoadWalletIdsWithOperations(
                getDb(), getDateFrom(), getDateTo(), getCurrencyEq(), getDataFilter());
        rows.removeIf(r -> !withOperations.contains(r.accountId));
    }

    /** One combined open+close+net row for the wallets list (see buildWalletRows). */
    private static class WalletRow {
        final int accountId;
        final String name;
        final String currencySymbol;
        final double openBal, openEq;
        final boolean openHasEq, openApprox;
        final double closeBal, closeEq;
        final boolean closeHasEq, closeApprox;
        final double netEq;
        final boolean netEqComputable;
        final double netNative;

        WalletRow(int accountId, String name, String currencySymbol,
                  double openBal, double openEq, boolean openHasEq, boolean openApprox,
                  double closeBal, double closeEq, boolean closeHasEq, boolean closeApprox,
                  double netEq, boolean netEqComputable, double netNative) {
            this.accountId = accountId;
            this.name = name;
            this.currencySymbol = currencySymbol;
            this.openBal = openBal;
            this.openEq = openEq;
            this.openHasEq = openHasEq;
            this.openApprox = openApprox;
            this.closeBal = closeBal;
            this.closeEq = closeEq;
            this.closeHasEq = closeHasEq;
            this.closeApprox = closeApprox;
            this.netEq = netEq;
            this.netEqComputable = netEqComputable;
            this.netNative = netNative;
        }
    }

    // ── Wallet balance row adapter (wallet_balance_item_layout.xml — open/close/net) ────────

    private static class WalletBalanceAdapter extends ArrayAdapter<WalletRow> {

        private final Currency mCurrencyEq;
        private final AccountList mAccounts;

        WalletBalanceAdapter(android.content.Context ctx, List<WalletRow> rows,
                             Currency currencyEq, AccountList accounts) {
            super(ctx, R.layout.wallet_balance_item_layout, rows);
            mCurrencyEq = currencyEq;
            mAccounts = accounts;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.wallet_balance_item_layout, parent, false);

            WalletRow row = getItem(position);

            ImageView imgIcon = convertView.findViewById(R.id.walletIcon);
            ImageView imgFlag = convertView.findViewById(R.id.walletFlag);
            TextView tvName = convertView.findViewById(R.id.walletName);
            TextView tvNet = convertView.findViewById(R.id.walletNet);
            TextView tvOpenNative = convertView.findViewById(R.id.walletOpenNative);
            TextView tvOpenEq = convertView.findViewById(R.id.walletOpenEq);
            TextView tvCloseNative = convertView.findViewById(R.id.walletCloseNative);
            TextView tvCloseEq = convertView.findViewById(R.id.walletCloseEq);

            AccountItem acc = mAccounts.getAccountById(row.accountId);
            if (acc != null && acc.getIcon() != null) {
                imgIcon.setImageBitmap(acc.getIcon().getBitmap());
            } else {
                imgIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
            }
            if (acc instanceof AccountOperatingItem && ((AccountOperatingItem) acc).getCurrency() != null) {
                imgFlag.setImageBitmap(((AccountOperatingItem) acc).getCurrency().getIcon().getBitmap());
                imgFlag.setVisibility(View.VISIBLE);
            } else {
                imgFlag.setVisibility(View.GONE);
            }

            tvName.setText(row.name);

            if (row.netEqComputable) {
                setNetText(tvNet, row.netEq, mCurrencyEq.getPrecision(), mCurrencyEq.getSymbol(), "");
            } else {
                // No rate to currencyEq exists for this wallet's currency at all — fall back
                // to its own currency so the change is still shown, just not comparable.
                setNetText(tvNet, row.netNative, 2, row.currencySymbol, "~");
            }

            tvOpenNative.setText(AdapterUtils.getFormat(2).format(row.openBal) + " " + row.currencySymbol);
            tvOpenEq.setText(row.openHasEq
                    ? (row.openApprox ? "≈ " : "") + AdapterUtils.getFormat(mCurrencyEq.getPrecision()).format(row.openEq) + " " + mCurrencyEq.getSymbol()
                    : "~");

            // Остаток на конец — главная цифра строки, поэтому стоит рядом с названием
            // кошелька и без подписи. Эквивалент идёт следом мелким, но только когда
            // кошелёк не в валюте эквивалента: иначе это была бы та же цифра дважды.
            tvCloseNative.setText(AdapterUtils.getFormat(2).format(row.closeBal) + " " + row.currencySymbol);
            if (!row.closeApprox) {
                tvCloseEq.setVisibility(View.GONE);
            } else {
                tvCloseEq.setVisibility(View.VISIBLE);
                tvCloseEq.setText(row.closeHasEq
                        ? "≈ " + AdapterUtils.getFormat(mCurrencyEq.getPrecision()).format(row.closeEq) + " " + mCurrencyEq.getSymbol()
                        : "~");
            }

            return convertView;
        }

        /**
         * Приход за период. Ноль — обычное дело: кошелёк могли просто не трогать, — и он
         * не «плюс», поэтому идёт без знака и нейтральным цветом.
         */
        private void setNetText(TextView view, double net, int precision, String symbol, String prefix) {
            boolean isZero = Math.round(net * 100) == 0;
            String sign = isZero ? "" : net > 0 ? "+" : "-";

            view.setText(prefix + sign + AdapterUtils.getFormat(precision).format(Math.abs(net))
                    + " " + symbol);
            view.setTextColor(getContext().getResources().getColor(
                    isZero ? R.color.colortransfer : net > 0 ? R.color.colorprofit : R.color.colorexpanse));
        }
    }
}
