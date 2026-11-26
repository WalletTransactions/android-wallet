package com.example.wallettransactions.Activities

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Adapters.CurrencyPairListAdapter
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import java.util.Locale

/**
 * Экран «Курсы валют» — список валютных пар. Тап по паре открывает
 * [CurrencyPairRateListActivity] (история курса пары).
 *
 * Фильтр один — поиск по валюте в шапке, он же всегда на виду. Раньше здесь была
 * сворачиваемая панель с чипами валют: два тапа до фильтра и ещё один список поверх
 * списка пар, хотя набрать «EUR» и так быстрее.
 */
class CurrencyPairListActivity : AppCompatActivity() {

    private var db: SQLiteDatabase? = null
    private var adapter: CurrencyPairListAdapter? = null

    // "Новая валютная пара" sheet state — its rows open CurrencyPickerActivity for a result,
    // so the still-open dialog's view and the picked ISOs need to be reachable from
    // onActivityResult, not just closed-over locals in showAddPairDialog().
    private var newPairDlgView: View? = null
    private var newPairDialog: AlertDialog? = null
    private var selectedBaseIso: String? = null
    private var selectedQuoteIso: String? = null

    /** Строка поиска в верхнем регистре; пустая — показываем все пары. */
    private var filterQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currencypair_list)

        db = WalletDbHelper(this).readableDatabase

        val listPairs = findViewById<ListView>(R.id.listPairs)
        val adapter = CurrencyPairListAdapter(this, ArrayList(), db)
        this.adapter = adapter
        listPairs.adapter = adapter
        listPairs.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            val intent = Intent(this, CurrencyPairRateListActivity::class.java)
            intent.putExtra(CurrencyPairRateListActivity.ARG_ITEM_SYMBOL, item.symbol)
            startActivity(intent)
        }
        listPairs.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemLongClickListener false
            if (item.isShared) {
                Toast.makeText(this, R.string.pair_shared_cannot_delete, Toast.LENGTH_SHORT).show()
            } else {
                confirmDeletePair(this, item) { applyFilter() }
            }
            true
        }

        val currencyFilterSearch = findViewById<EditText>(R.id.currencyFilterSearch)
        currencyFilterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s.toString().trim().uppercase(Locale.ROOT)
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        applyFilter()
    }

    override fun onDestroy() {
        super.onDestroy()
        val db = db
        if (db != null && db.isOpen) db.close()
    }

    override fun onResume() {
        super.onResume()
        // Rows show each pair's latest rate/date/% change (see CurrencyPairListAdapter) — refresh
        // on every return to this screen, since a rate can be added/edited/deleted/downloaded on
        // CurrencyPairRateListActivity and we're not notified of that via onActivityResult (it's
        // opened with a plain startActivity).
        if (adapter != null) applyFilter()
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onAddPair(view: View) {
        showAddPairDialog()
    }

    /** Кнопка «Маршруты» в тулбаре — маршруты пересчёта валют (currency-conversion-routes). */
    fun onOpenRoutes(view: View) {
        startActivity(Intent(this, CurrencyRouteListActivity::class.java))
    }

    /** Кнопка «Валюты» в тулбаре — список валют в режиме просмотра. */
    fun onOpenCurrencies(view: View) {
        startActivity(CurrencyPickerActivity.buildBrowseIntent(this))
    }

    /**
     * Пара проходит, если запрос пуст или совпадает с символом пары либо с ISO или
     * названием любой из двух её валют: «EUR», «eurusd» и «евро» одинаково находят пару.
     */
    private fun matchesFilter(cp: CurrencyPair): Boolean {
        if (filterQuery.isEmpty()) return true

        return contains(cp.symbol)
                || matchesCurrency(cp.baseCurrency)
                || matchesCurrency(cp.quoteCurrency)
    }

    private fun matchesCurrency(currency: Currency?): Boolean {
        return currency != null && (contains(currency.iso) || contains(currency.name))
    }

    private fun contains(value: String?): Boolean {
        return value != null && value.uppercase(Locale.ROOT).contains(filterQuery)
    }

    private fun applyFilter() {
        val all = Singleton.instance.getCurrencyPairList(this)
        val filtered = ArrayList<CurrencyPair>()
        for (i in 0 until all.size()) {
            val cp = all.getItem(i)
            if (matchesFilter(cp)) filtered.add(cp)
        }
        adapter!!.setItems(filtered)
    }

    private fun showAddPairDialog() {
        selectedBaseIso = null
        selectedQuoteIso = null

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_pair, null)
        newPairDlgView = view
        view.findViewById<TextView>(R.id.baseValue).setText(R.string.pair_choose)
        view.findViewById<TextView>(R.id.quoteValue).setText(R.string.pair_choose)

        val dialog = AlertDialog.Builder(this).setView(view).create()
        newPairDialog = dialog
        dialog.show()
    }

    /** rowBase tap target (dialog_new_pair.xml android:onClick). */
    fun onPickBase(view: View) {
        startActivityForResult(
            CurrencyPickerActivity.buildIntent(this, getString(R.string.pair_base_currency), selectedBaseIso),
            REQUEST_PICK_BASE
        )
    }

    /** rowQuote tap target (dialog_new_pair.xml android:onClick). */
    fun onPickQuote(view: View) {
        startActivityForResult(
            CurrencyPickerActivity.buildIntent(this, getString(R.string.pair_quote_currency), selectedQuoteIso),
            REQUEST_PICK_QUOTE
        )
    }

    /** btnCancel tap target (dialog_new_pair.xml android:onClick). */
    fun onCancelNewPair(view: View) {
        newPairDialog?.dismiss()
    }

    /** btnCreate tap target (dialog_new_pair.xml android:onClick). */
    fun onCreatePair(view: View) {
        val baseIso = selectedBaseIso
        val quoteIso = selectedQuoteIso
        if (baseIso == null || quoteIso == null) {
            Toast.makeText(this, R.string.pair_error_choose_both, Toast.LENGTH_SHORT).show()
            return
        }
        if (baseIso == quoteIso) {
            Toast.makeText(this, R.string.pair_error_same, Toast.LENGTH_SHORT).show()
            return
        }

        var precision = 5
        try {
            precision = newPairDlgView!!.findViewById<EditText>(R.id.inputPrecision)
                .text.toString().trim().toInt()
        } catch (ignored: NumberFormatException) {
        }

        val writable = WalletDbHelper(this).writableDatabase
        val newId = WalletDbCRUD.InsertCurrencyPair(writable, baseIso, quoteIso, precision)
        writable.close()
        if ((newId ?: -1L) < 0) {
            Toast.makeText(this, R.string.pair_error_add, Toast.LENGTH_SHORT).show()
            return
        }

        newPairDialog?.dismiss()
        // Reload pair list into singleton and refresh adapter (respecting the
        // currently active currency filters, if any)
        Singleton.instance.getCurrencyPairList(this).clear()
        Singleton.instance.getCurrencyPairList(this)
        applyFilter()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val dlgView = newPairDlgView
        if (resultCode != RESULT_OK || data == null || dlgView == null) return

        val iso = data.getStringExtra(CurrencyPickerActivity.RESULT_ISO) ?: return

        if (requestCode == REQUEST_PICK_BASE) {
            selectedBaseIso = iso
            dlgView.findViewById<TextView>(R.id.baseValue).text = iso
        } else if (requestCode == REQUEST_PICK_QUOTE) {
            selectedQuoteIso = iso
            dlgView.findViewById<TextView>(R.id.quoteValue).text = iso
        }
    }

    companion object {
        private const val REQUEST_PICK_BASE = 300
        private const val REQUEST_PICK_QUOTE = 301

        /**
         * Подтверждение и удаление своей пары вместе с курсами (spec currency-pairs) — общее
         * для долгого нажатия здесь и кнопки в тулбаре CurrencyPairRateListActivity. Пишет
         * только локально; на сервер пара и курсы уйдут tombstone'ами со следующим push.
         */
        @JvmStatic
        internal fun confirmDeletePair(activity: Activity, pair: CurrencyPair, onDeleted: Runnable) {
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.pair_delete_question, pair.symbol))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    val rates = liveRateCount(activity, pair.id)
                    if (rates == 0L) {
                        deletePair(activity, pair, onDeleted)
                    } else {
                        confirmWithRateCount(activity, pair, rates, onDeleted)
                    }
                }
                .show()
        }

        /**
         * Второй шаг для пары с историей: курсы вводились руками и восстановить их нечем,
         * поэтому перед удалением переспрашиваем, называя, сколько курсов уйдёт. Пара без
         * курсов сюда не попадает (spec currency-pairs).
         */
        private fun confirmWithRateCount(
            activity: Activity, pair: CurrencyPair, rates: Long,
            onDeleted: Runnable
        ) {
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.pair_delete_with_rates, pair.symbol, rates))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete) { _, _ -> deletePair(activity, pair, onDeleted) }
                .show()
        }

        private fun liveRateCount(activity: Activity, pairId: Int): Long {
            return DatabaseUtils.queryNumEntries(
                Singleton.instance.getReadableDatabase(activity),
                WalletContract.CurrencyPairRateEntry.TABLE_NAME,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND "
                        + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf(Integer.toString(pairId))
            )
        }

        private fun deletePair(activity: Activity, pair: CurrencyPair, onDeleted: Runnable) {
            val writable = WalletDbHelper(activity).writableDatabase
            val deleted = WalletDbCRUD.DeleteCurrencyPair(writable, pair.id)
            writable.close()
            if (deleted == 0) {
                Toast.makeText(activity, R.string.pair_shared_cannot_delete, Toast.LENGTH_SHORT).show()
                return
            }
            Singleton.instance.reloadCurrencyPairList(activity)
            onDeleted.run()
        }
    }
}
