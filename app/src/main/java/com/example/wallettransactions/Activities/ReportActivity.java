package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.AdapterView;
import android.widget.ListView;

import android.widget.Toast;
import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Adapters.ReportByAccountsAdapter;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.Data.WalletDbLoader;

import java.util.Collections;

/**
 * Экран «Отчёты» — доходы и расходы по категориям за период.
 *
 * Период, фильтр, валюта эквивалента и карточка состояния денег — общие для всех
 * табов, ими занимается {@link BaseTabActivity}; свои плитки «Доход/Расход» здесь
 * больше не нужны: те же суммы показывает общая карточка.
 */
public class ReportActivity extends BaseTabActivity {

    private ListView lstExpanse;
    private ListView lstIncome;

    private AccountList getAccounts() {
        return Singleton.instance.getAccounts(this);
    }

    private IconList getIconList() {
        return Singleton.instance.getIconList(this);
    }

    private CurrencyList getCurrencyList() {
        return Singleton.instance.getCurrencyList(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        lstExpanse = findViewById(R.id.listExpanseView);
        lstIncome = findViewById(R.id.listIncomeView);

        lstIncome.setOnItemClickListener(this::treeViewClick);
        lstExpanse.setOnItemClickListener(this::treeViewClick);

        initTabScreen(R.id.navReport, getString(R.string.tab_reports));
        reloadSharedAndTabData();
    }

    /** Тап по категории — её операции на «Операциях»: фильтр становится общим. */
    public void treeViewClick(AdapterView<?> parent, View itemClicked, int position, long id) {
        try {
            AccountItem item = (AccountItem) parent.getItemAtPosition(position);
            state().setAccountIds(this, Collections.singletonList(item.getId()));

            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Общий фильтр шапки сужает обе таблицы — те же операции, что видны в ленте «Операций». */
    @Override
    protected void reloadTabData()
    {
        ReportByAccount reportIncome = WalletDbLoader.GenerateIncomeReportByAccount(getDb(),
                getDateFrom(), getDateTo(), getAccounts(), getCurrencyList(), getCurrencyEq(), getDataFilter());

        ReportByAccountsAdapter incomeAdapter = new ReportByAccountsAdapter(this, reportIncome,
                getIconList(), getCurrencyEq(), R.color.colorprofit);
        lstIncome.setAdapter(incomeAdapter);

        ReportByAccount reportExpanse = WalletDbLoader.GenerateExpanseReportByAccount(getDb(),
                getDateFrom(), getDateTo(), getAccounts(), getCurrencyList(), getCurrencyEq(), getDataFilter());

        ReportByAccountsAdapter expanseAdapter = new ReportByAccountsAdapter(this, reportExpanse,
                getIconList(), getCurrencyEq(), R.color.colorexpanse);
        lstExpanse.setAdapter(expanseAdapter);

        AdapterUtils.setListViewHeightBasedOnChildren(lstIncome);
        AdapterUtils.setListViewHeightBasedOnChildren(lstExpanse);

        // Пустая секция оставляла бы висеть подпись и полоску карточки.
        setSectionVisible(R.id.captionIncomes, R.id.cardIncomes, incomeAdapter.getCount() > 0);
        setSectionVisible(R.id.captionExpanses, R.id.cardExpanses, expanseAdapter.getCount() > 0);
    }

    private void setSectionVisible(int captionId, int cardId, boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        findViewById(captionId).setVisibility(visibility);
        findViewById(cardId).setVisibility(visibility);
    }

}
