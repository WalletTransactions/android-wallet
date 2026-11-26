package com.example.wallettransactions.Activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ListView
import android.widget.Toast

import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Adapters.ReportByAccountsAdapter
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Data.WalletDbLoader

/**
 * Экран «Отчёты» — доходы и расходы по категориям за период.
 *
 * Период, фильтр, валюта эквивалента и карточка состояния денег — общие для всех
 * табов, ими занимается [BaseTabActivity]; свои плитки «Доход/Расход» здесь
 * больше не нужны: те же суммы показывает общая карточка.
 */
class ReportActivity : BaseTabActivity() {

    private lateinit var lstExpanse: ListView
    private lateinit var lstIncome: ListView

    private fun getAccounts(): AccountList = Singleton.instance.getAccounts(this)

    private fun getIconList(): IconList = Singleton.instance.getIconList(this)

    private fun getCurrencyList(): CurrencyList = Singleton.instance.getCurrencyList(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        lstExpanse = findViewById(R.id.listExpanseView)
        lstIncome = findViewById(R.id.listIncomeView)

        lstIncome.setOnItemClickListener(::treeViewClick)
        lstExpanse.setOnItemClickListener(::treeViewClick)

        initTabScreen(R.id.navReport, getString(R.string.tab_reports))
        reloadSharedAndTabData()
    }

    /** Тап по категории — её операции на «Операциях»: фильтр становится общим. */
    fun treeViewClick(parent: AdapterView<*>, itemClicked: View?, position: Int, id: Long) {
        try {
            val item = parent.getItemAtPosition(position) as AccountItem
            state().setAccountIds(this, listOf(item.id))

            startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Общий фильтр шапки сужает обе таблицы — те же операции, что видны в ленте «Операций». */
    override fun reloadTabData() {
        val reportIncome = WalletDbLoader.GenerateIncomeReportByAccount(db,
                dateFrom, dateTo, getAccounts(), getCurrencyList(), currencyEq, dataFilter)

        val incomeAdapter = ReportByAccountsAdapter(this, reportIncome,
                getIconList(), currencyEq, R.color.colorprofit)
        lstIncome.adapter = incomeAdapter

        val reportExpanse = WalletDbLoader.GenerateExpanseReportByAccount(db,
                dateFrom, dateTo, getAccounts(), getCurrencyList(), currencyEq, dataFilter)

        val expanseAdapter = ReportByAccountsAdapter(this, reportExpanse,
                getIconList(), currencyEq, R.color.colorexpanse)
        lstExpanse.adapter = expanseAdapter

        AdapterUtils.setListViewHeightBasedOnChildren(lstIncome)
        AdapterUtils.setListViewHeightBasedOnChildren(lstExpanse)

        // Пустая секция оставляла бы висеть подпись и полоску карточки.
        setSectionVisible(R.id.captionIncomes, R.id.cardIncomes, incomeAdapter.count > 0)
        setSectionVisible(R.id.captionExpanses, R.id.cardExpanses, expanseAdapter.count > 0)
    }

    private fun setSectionVisible(captionId: Int, cardId: Int, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(captionId).visibility = visibility
        findViewById<View>(cardId).visibility = visibility
    }

}
