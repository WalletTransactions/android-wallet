package com.example.wallettransactions.Activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import android.view.View;

import android.widget.*;
import com.example.wallettransactions.*;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.DataItems.Operations.ListChanged;
import com.example.wallettransactions.DataItems.Operations.OperationItem;
import com.example.wallettransactions.DataItems.Operations.OperationList;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.Adapters.TransactionArrayAdapter;
import com.example.wallettransactions.Data.WalletDbLoader;

import java.util.ArrayList;

/**
 * Экран «Операции» — лента операций за период. Период, фильтр, валюта эквивалента и
 * карточка состояния денег — общие для всех табов, ими занимается
 * {@link BaseTabActivity}; здесь остаётся только сама лента.
 */
public class MainActivity extends BaseTabActivity implements ListChanged {

    static final private int requestCodeTransactionEdit = 1;

    /** ID счетов через запятую — контракт обмена с {@link FilterActivity}. */
    public static final String cFilterAccountList =  "Transaction.AccountList";

    /** Тап по напоминанию (reminder.ReminderWorker): сразу открыть новую операцию. */
    public static final String EXTRA_ADD_OPERATION = "MainActivity.addOperation";

    public IconList getIconList(){
        return Singleton.instance.getIconList(this);
    }
    public AccountList getAccounts() { return Singleton.instance.getAccounts(this); }

    public CurrencyList getCurrencyList() {
        return Singleton.instance.getCurrencyList(this);
    }

    public CurrencyPairList getCurrencyPairList(){
        return Singleton.instance.getCurrencyPairList(this);
    }

    private SQLiteDatabase getWritableDatabase(){
        return Singleton.instance.getWritableDatabase(this);
    }

    static public OperationList transactions = new OperationList();

    TransactionArrayAdapter TranAdapter ;

    ListView listView;
    TextView btnGroupBy;

    private void assignComponents()
    {
        listView = findViewById(R.id.listView);
        btnGroupBy = findViewById(R.id.btnGroupBy);

        TranAdapter = new TransactionArrayAdapter(this, transactions, getCurrencyEq(), TransactionArrayAdapter.GroupByTime.day);
        setGrouping(TransactionArrayAdapter.GroupByTime.day);
        listView.setAdapter(TranAdapter);
        transactions.onListChanged.add(this);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View itemClicked, int position,
                                    long id) {
                try {
                    OperationItem item = (OperationItem) parent.getItemAtPosition(position);

                    Intent intent = new Intent(MainActivity.this, TransactionActivity.class);

                    intent.putExtra(TransactionActivity.cTranId, item.IdTran);
                    intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(item.Date));
                    intent.putExtra(TransactionActivity.cFrom, item.AccFrom.getId());
                    intent.putExtra(TransactionActivity.cTo, item.AccTo.getId());
                    intent.putExtra(TransactionActivity.cComment, item.Comment);
                    intent.putExtra(TransactionActivity.cAmount, item.Amount);
                    intent.putExtra(TransactionActivity.cIsEdit, true);

                    startActivityForResult(intent, requestCodeTransactionEdit);
                }
                catch (Exception e)
                {
                    Toast.makeText(MainActivity.this, "Error during the creating operation" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Первый запуск: база создаётся в фоне, лента откроется после (fix-first-start-anr).
        if (com.example.wallettransactions.Data.DbInit.redirectIfNeeded(this)) return;

        setContentView(R.layout.activity_transaction_list);
        assignComponents();
        initTabScreen(R.id.navOps, getString(R.string.tab_operations));

        if (com.example.wallettransactions.WalletApplication.hadCrash(this)) {
            showCrashRecoveryDialog();
        } else {
            reloadSharedAndTabData();
            // Не при восстановлении после поворота: экран операции уже открывался.
            if (savedInstanceState == null) openAddOperationIfAsked(getIntent());
        }
    }

    /** Лента уже в стеке — напоминание приходит сюда, а не в onCreate (SINGLE_TOP). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!com.example.wallettransactions.WalletApplication.hadCrash(this)) openAddOperationIfAsked(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showConnectionQuestion();
    }

    /**
     * Вопрос «Операции без входа», пока подключение к кошельку его ждёт (openspec
     * new-user-default-set, spec ledger-connection). Не поверх диалога аварийного завершения.
     */
    private void showConnectionQuestion() {
        if (com.example.wallettransactions.WalletApplication.hadCrash(this)) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) LedgerConnectionDialog.showIfAsked(this);
        });
    }

    private void openAddOperationIfAsked(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_ADD_OPERATION, false)) return;
        // Снять флаг: иначе пересоздание активности открыло бы экран операции ещё раз.
        intent.removeExtra(EXTRA_ADD_OPERATION);
        btnOperationAdd(null);
    }

    private void showCrashRecoveryDialog() {
        String log = com.example.wallettransactions.WalletApplication.getLastCrashLog(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_message)
                .setCancelable(false)
                .setPositiveButton(R.string.crash_repair, (d, w) -> {
                    com.example.wallettransactions.WalletApplication.clearCrashFlag(this);
                    SQLiteDatabase db = getWritableDatabase();
                    java.util.List<Integer> removed =
                            com.example.wallettransactions.Data.WalletDbCRUD.repairDatabase(db);
                    String msg = removed.isEmpty()
                            ? getString(R.string.crash_repair_nothing)
                            : getResources().getQuantityString(R.plurals.crash_repair_removed,
                                    removed.size(), removed.size(), removed.toString());
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    reloadSharedAndTabData();
                })
                .setNeutralButton(R.string.crash_report, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash_log", log));
                    Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show();
                    showCrashRecoveryDialog(); // re-show main dialog
                })
                .setNegativeButton(R.string.crash_ignore, (d, w) -> {
                    com.example.wallettransactions.WalletApplication.clearCrashFlag(this);
                    reloadSharedAndTabData();
                })
                .show();
    }

    @Override
    protected void reloadTabData()
    {
        WalletDbLoader.LoadOperations(getDb(), transactions, getAccounts(), getDateFrom(), getDateTo(),
                getCurrencyEq(), getDataFilter());

        TranAdapter.setCurrencyEq(getCurrencyEq());
        TranAdapter.notifyDataSetChanged();
        // Сводка лежит в AppBarLayout и уезжает вверх вместе с прокруткой ленты. Пустую ленту
        // прокрутить нельзя — свёрнутую шапку (с датами периода) было бы не вернуть.
        if (transactions.isEmpty()) {
            ((com.google.android.material.appbar.AppBarLayout) findViewById(R.id.summaryBar))
                    .setExpanded(true, false);
        }
        // Данные поменял проход синка — он мог закончиться «ждёт решения».
        showConnectionQuestion();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == requestCodeTransactionEdit && resultCode == RESULT_OK && data != null) {
            int tranId = data.getIntExtra(TransactionActivity.cTranId, -1);
            if (tranId >= 0) {
                updateSingleTransaction(tranId);
            } else {
                reloadSharedAndTabData();
            }
        }
    }

    private void updateSingleTransaction(int tranId) {
        SQLiteDatabase db = getDb();
        OperationItem updated = WalletDbLoader.LoadSingleOperation(db, tranId, getAccounts(), getCurrencyEq());
        // Иначе строка потеряет вторую сумму валютного перевода и комиссию.
        if (updated != null) WalletDbLoader.AttachLinksTo(db, updated, getAccounts(), getCurrencyEq());

        int index = -1;
        for (int i = 0; i < transactions.size(); i++) {
            if (transactions.get(i).IdTran == tranId) {
                index = i;
                break;
            }
        }

        if (updated != null) {
            boolean inRange = !updated.Date.before(getDateFrom()) && !updated.Date.after(getDateTo());
            ArrayList<String> filterAccounts = getDataFilter().getAccounts();
            boolean passesAccounts = filterAccounts.isEmpty()
                    || filterAccounts.contains(String.valueOf(updated.AccFrom.getId()))
                    || filterAccounts.contains(String.valueOf(updated.AccTo.getId()));
            // Правка могла сделать из дохода перевод — тогда при фильтре «только доходы»
            // операции в ленте больше не место.
            boolean passesFilter = passesAccounts
                    && getDataFilter().matchesKind(updated.AccFrom, updated.AccTo);

            if (inRange && passesFilter) {
                if (index >= 0) {
                    transactions.set(index, updated);
                } else {
                    transactions.add(updated);
                    // bubble into correct position (list is sorted by date DESC)
                    for (int i = transactions.size() - 1; i > 0; i--) {
                        if (transactions.get(i).Date.after(transactions.get(i - 1).Date)) {
                            OperationItem tmp = transactions.get(i);
                            transactions.set(i, transactions.get(i - 1));
                            transactions.set(i - 1, tmp);
                        } else {
                            break;
                        }
                    }
                }
            } else if (index >= 0) {
                transactions.remove(index);
            }
        } else if (index >= 0) {
            transactions.remove(index);
        }

        transactions.notifyObservers();

        refreshSummaryCard();

        TranAdapter.setCurrencyEq(getCurrencyEq());
        TranAdapter.notifyDataSetChanged();
    }

    @Override
    public void notifyDataSetChanged() {
        TranAdapter.notifyDataSetChanged();
    }

    public void btnUp(View view) {
        ((com.google.android.material.appbar.AppBarLayout) findViewById(R.id.summaryBar)).setExpanded(true, true);
        listView.smoothScrollBy(0, 0); // гасит инерцию: иначе она перебивает setSelection
        listView.setSelection(0);
    }

    // ── Группировка ленты ─────────────────────────────────────────────────────

    /** Раньше это было подменю Toolbar; шапка теперь плоская, поэтому — попап у кнопки. */
    public void onPickGrouping(View view) {
        PopupMenu popup = new PopupMenu(this, view);

        for (TransactionArrayAdapter.GroupByTime groupBy : TransactionArrayAdapter.GroupByTime.values())
            popup.getMenu().add(0, groupBy.ordinal(), groupBy.ordinal(), groupingTitle(groupBy));

        popup.setOnMenuItemClickListener(item -> {
            setGrouping(TransactionArrayAdapter.GroupByTime.values()[item.getItemId()]);
            TranAdapter.notifyDataSetChanged();
            return true;
        });

        popup.show();
    }

    private void setGrouping(TransactionArrayAdapter.GroupByTime groupBy) {
        switch (groupBy) {
            case week:  TranAdapter.setGroupByWewek(); break;
            case month: TranAdapter.setGroupByMonth(); break;
            default:    TranAdapter.setGroupByDay();   break;
        }
        btnGroupBy.setText(groupingTitle(groupBy));
    }

    private String groupingTitle(TransactionArrayAdapter.GroupByTime groupBy) {
        switch (groupBy) {
            case week:  return getString(R.string.group_by_week);
            case month: return getString(R.string.group_by_month);
            default:    return getString(R.string.group_by_date);
        }
    }
}
