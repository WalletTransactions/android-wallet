package com.example.wallettransactions.Activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import android.view.View

import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.WalletApplication
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.DataItems.Operations.ListChanged
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.DataItems.Operations.OperationList
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.Adapters.TransactionArrayAdapter
import com.example.wallettransactions.Data.DbInit
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.google.android.material.appbar.AppBarLayout

/**
 * Экран «Операции» — лента операций за период. Период, фильтр, валюта эквивалента и
 * карточка состояния денег — общие для всех табов, ими занимается
 * [BaseTabActivity]; здесь остаётся только сама лента.
 */
class MainActivity : BaseTabActivity(), ListChanged {

    companion object {
        private const val requestCodeTransactionEdit = 1

        /** ID счетов через запятую — контракт обмена с [FilterActivity]. */
        const val cFilterAccountList = "Transaction.AccountList"

        /** Тап по напоминанию (reminder.ReminderWorker): сразу открыть новую операцию. */
        const val EXTRA_ADD_OPERATION = "MainActivity.addOperation"

        @JvmField
        var transactions = OperationList()
    }

    fun getIconList(): IconList = Singleton.instance.getIconList(this)
    fun getAccounts(): AccountList = Singleton.instance.getAccounts(this)

    fun getCurrencyList(): CurrencyList = Singleton.instance.getCurrencyList(this)

    fun getCurrencyPairList(): CurrencyPairList = Singleton.instance.getCurrencyPairList(this)

    private fun getWritableDatabase(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    private lateinit var TranAdapter: TransactionArrayAdapter

    private lateinit var listView: ListView
    private lateinit var btnGroupBy: TextView

    private fun assignComponents() {
        listView = findViewById(R.id.listView)
        btnGroupBy = findViewById(R.id.btnGroupBy)

        TranAdapter = TransactionArrayAdapter(this, transactions, currencyEq, TransactionArrayAdapter.GroupByTime.day)
        setGrouping(TransactionArrayAdapter.GroupByTime.day)
        listView.adapter = TranAdapter
        transactions.onListChanged.add(this)

        listView.setOnItemClickListener { parent, _, position, _ ->
            try {
                val item = parent.getItemAtPosition(position) as OperationItem

                val intent = Intent(this@MainActivity, TransactionActivity::class.java)

                intent.putExtra(TransactionActivity.cTranId, item.IdTran)
                intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(item.Date))
                intent.putExtra(TransactionActivity.cFrom, item.AccFrom.id)
                intent.putExtra(TransactionActivity.cTo, item.AccTo.id)
                intent.putExtra(TransactionActivity.cComment, item.Comment)
                intent.putExtra(TransactionActivity.cAmount, item.Amount)
                intent.putExtra(TransactionActivity.cIsEdit, true)

                @Suppress("DEPRECATION")
                startActivityForResult(intent, requestCodeTransactionEdit)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error during the creating operation" + e.message, Toast.LENGTH_SHORT).show()
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Первый запуск: база создаётся в фоне, лента откроется после (fix-first-start-anr).
        if (DbInit.redirectIfNeeded(this)) return

        setContentView(R.layout.activity_transaction_list)
        assignComponents()
        initTabScreen(R.id.navOps, getString(R.string.tab_operations))

        if (WalletApplication.hadCrash(this)) {
            showCrashRecoveryDialog()
        } else {
            reloadSharedAndTabData()
            // Не при восстановлении после поворота: экран операции уже открывался.
            if (savedInstanceState == null) openAddOperationIfAsked(intent)
        }
    }

    /** Лента уже в стеке — напоминание приходит сюда, а не в onCreate (SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!WalletApplication.hadCrash(this)) openAddOperationIfAsked(intent)
    }

    override fun onResume() {
        super.onResume()
        showConnectionQuestion()
    }

    /**
     * Вопрос «Операции без входа», пока подключение к кошельку его ждёт (openspec
     * new-user-default-set, spec ledger-connection). Не поверх диалога аварийного завершения.
     */
    private fun showConnectionQuestion() {
        if (WalletApplication.hadCrash(this)) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) LedgerConnectionDialog.showIfAsked(this)
        }
    }

    private fun openAddOperationIfAsked(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_ADD_OPERATION, false)) return
        // Снять флаг: иначе пересоздание активности открыло бы экран операции ещё раз.
        intent.removeExtra(EXTRA_ADD_OPERATION)
        // Параметр view в btnOperationAdd не используется — отдаём любое существующее представление.
        btnOperationAdd(listView)
    }

    private fun showCrashRecoveryDialog() {
        val log = WalletApplication.getLastCrashLog(this)
        AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_message)
                .setCancelable(false)
                .setPositiveButton(R.string.crash_repair) { _, _ ->
                    WalletApplication.clearCrashFlag(this)
                    val db = getWritableDatabase()
                    val removed: List<Int> = WalletDbCRUD.repairDatabase(db)
                    val msg = if (removed.isEmpty())
                        getString(R.string.crash_repair_nothing)
                    else
                        resources.getQuantityString(R.plurals.crash_repair_removed,
                                removed.size, removed.size, removed.toString())
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    reloadSharedAndTabData()
                }
                .setNeutralButton(R.string.crash_report) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash_log", log))
                    Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
                    showCrashRecoveryDialog() // re-show main dialog
                }
                .setNegativeButton(R.string.crash_ignore) { _, _ ->
                    WalletApplication.clearCrashFlag(this)
                    reloadSharedAndTabData()
                }
                .show()
    }

    override fun reloadTabData() {
        WalletDbLoader.LoadOperations(db, transactions, getAccounts(), dateFrom, dateTo,
                currencyEq, dataFilter)

        TranAdapter.setCurrencyEq(currencyEq)
        TranAdapter.notifyDataSetChanged()
        // Сводка лежит в AppBarLayout и уезжает вверх вместе с прокруткой ленты. Пустую ленту
        // прокрутить нельзя — свёрнутую шапку (с датами периода) было бы не вернуть.
        if (transactions.isEmpty()) {
            findViewById<AppBarLayout>(R.id.summaryBar).setExpanded(true, false)
        }
        // Данные поменял проход синка — он мог закончиться «ждёт решения».
        showConnectionQuestion()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestCodeTransactionEdit && resultCode == RESULT_OK && data != null) {
            val tranId = data.getIntExtra(TransactionActivity.cTranId, -1)
            if (tranId >= 0) {
                updateSingleTransaction(tranId)
            } else {
                reloadSharedAndTabData()
            }
        }
    }

    private fun updateSingleTransaction(tranId: Int) {
        val db = db
        val updated: OperationItem? = WalletDbLoader.LoadSingleOperation(db, tranId, getAccounts(), currencyEq)
        // Иначе строка потеряет вторую сумму валютного перевода и комиссию.
        if (updated != null) WalletDbLoader.AttachLinksTo(db, updated, getAccounts(), currencyEq)

        var index = -1
        for (i in 0 until transactions.size) {
            if (transactions[i].IdTran == tranId) {
                index = i
                break
            }
        }

        if (updated != null) {
            val inRange = !updated.Date.before(dateFrom) && !updated.Date.after(dateTo)
            val filterAccounts = dataFilter.accounts
            val passesAccounts = filterAccounts.isEmpty()
                    || filterAccounts.contains(updated.AccFrom.id.toString())
                    || filterAccounts.contains(updated.AccTo.id.toString())
            // Правка могла сделать из дохода перевод — тогда при фильтре «только доходы»
            // операции в ленте больше не место.
            val passesFilter = passesAccounts
                    && dataFilter.matchesKind(updated.AccFrom, updated.AccTo)

            if (inRange && passesFilter) {
                if (index >= 0) {
                    transactions[index] = updated
                } else {
                    transactions.add(updated)
                    // bubble into correct position (list is sorted by date DESC)
                    for (i in transactions.size - 1 downTo 1) {
                        if (transactions[i].Date.after(transactions[i - 1].Date)) {
                            val tmp = transactions[i]
                            transactions[i] = transactions[i - 1]
                            transactions[i - 1] = tmp
                        } else {
                            break
                        }
                    }
                }
            } else if (index >= 0) {
                transactions.removeAt(index)
            }
        } else if (index >= 0) {
            transactions.removeAt(index)
        }

        transactions.notifyObservers()

        refreshSummaryCard()

        TranAdapter.setCurrencyEq(currencyEq)
        TranAdapter.notifyDataSetChanged()
    }

    override fun notifyDataSetChanged() {
        TranAdapter.notifyDataSetChanged()
    }

    fun btnUp(view: View) {
        findViewById<AppBarLayout>(R.id.summaryBar).setExpanded(true, true)
        listView.smoothScrollBy(0, 0) // гасит инерцию: иначе она перебивает setSelection
        listView.setSelection(0)
    }

    // ── Группировка ленты ─────────────────────────────────────────────────────

    /** Раньше это было подменю Toolbar; шапка теперь плоская, поэтому — попап у кнопки. */
    fun onPickGrouping(view: View) {
        val popup = PopupMenu(this, view)

        for (groupBy in TransactionArrayAdapter.GroupByTime.values())
            popup.menu.add(0, groupBy.ordinal, groupBy.ordinal, groupingTitle(groupBy))

        popup.setOnMenuItemClickListener { item ->
            setGrouping(TransactionArrayAdapter.GroupByTime.values()[item.itemId])
            TranAdapter.notifyDataSetChanged()
            true
        }

        popup.show()
    }

    private fun setGrouping(groupBy: TransactionArrayAdapter.GroupByTime) {
        when (groupBy) {
            TransactionArrayAdapter.GroupByTime.week -> TranAdapter.setGroupByWewek()
            TransactionArrayAdapter.GroupByTime.month -> TranAdapter.setGroupByMonth()
            else -> TranAdapter.setGroupByDay()
        }
        btnGroupBy.text = groupingTitle(groupBy)
    }

    private fun groupingTitle(groupBy: TransactionArrayAdapter.GroupByTime): String {
        return when (groupBy) {
            TransactionArrayAdapter.GroupByTime.week -> getString(R.string.group_by_week)
            TransactionArrayAdapter.GroupByTime.month -> getString(R.string.group_by_month)
            else -> getString(R.string.group_by_date)
        }
    }
}
