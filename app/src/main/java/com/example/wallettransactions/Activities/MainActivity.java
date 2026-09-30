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

        setContentView(R.layout.activity_transaction_list);
        assignComponents();
        initTabScreen(R.id.navOps, "Операции");

        if (com.example.wallettransactions.WalletApplication.hadCrash(this)) {
            showCrashRecoveryDialog();
        } else {
            reloadSharedAndTabData();
        }
    }

    private void showCrashRecoveryDialog() {
        String log = com.example.wallettransactions.WalletApplication.getLastCrashLog(this);
        new AlertDialog.Builder(this)
                .setTitle("Приложение аварийно завершилось")
                .setMessage("В базе данных могут быть некорректные записи.\nВыберите действие:")
                .setCancelable(false)
                .setPositiveButton("Авторемонт", (d, w) -> {
                    com.example.wallettransactions.WalletApplication.clearCrashFlag(this);
                    SQLiteDatabase db = getWritableDatabase();
                    java.util.List<Integer> removed =
                            com.example.wallettransactions.Data.WalletDbCRUD.repairDatabase(db);
                    String msg = removed.isEmpty()
                            ? "Повреждённых записей не найдено"
                            : "Удалено " + removed.size() + " некорректных транзакций: " + removed;
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    reloadSharedAndTabData();
                })
                .setNeutralButton("Отчёт об ошибке", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash_log", log));
                    Toast.makeText(this, "Лог скопирован в буфер обмена", Toast.LENGTH_SHORT).show();
                    showCrashRecoveryDialog(); // re-show main dialog
                })
                .setNegativeButton("Игнорировать", (d, w) -> {
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
        AdapterUtils.setListViewHeightBasedOnChildren(listView);
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
        AdapterUtils.setListViewHeightBasedOnChildren(listView);
    }

    @Override
    public void notifyDataSetChanged() {
        TranAdapter.notifyDataSetChanged();
    }

    public void btnUp(View view) {
        ((ScrollView)findViewById(R.id.scrollable)).smoothScrollTo(0,0);
    }

    // ── Группировка ленты ─────────────────────────────────────────────────────

    /** Раньше это было подменю Toolbar; шапка теперь плоская, поэтому — попап у кнопки. */
    public void onPickGrouping(View view) {
        PopupMenu popup = new PopupMenu(this, view);

        for (TransactionArrayAdapter.GroupByTime groupBy : TransactionArrayAdapter.GroupByTime.values())
            popup.getMenu().add(0, groupBy.ordinal(), groupBy.ordinal(), groupingTitle(groupBy));

        popup.setOnMenuItemClickListener(item -> {
            setGrouping(TransactionArrayAdapter.GroupByTime.values()[item.getItemId()]);
            AdapterUtils.setListViewHeightBasedOnChildren(listView);
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

    private static String groupingTitle(TransactionArrayAdapter.GroupByTime groupBy) {
        switch (groupBy) {
            case week:  return "По неделям";
            case month: return "По месяцам";
            default:    return "По дате";
        }
    }
}
