package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.icu.text.DecimalFormat
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Adapters.CurrencyPairRateAdapter
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Views.RateChartView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CurrencyPairRateListActivity : AppCompatActivity() {

    /** Источники курсов по индексу SOURCE_*; имена собственные, переводится только «ЦБ РФ». */
    private fun rateSources(): Array<String> {
        return arrayOf("Yahoo Finance", "Frankfurter (ECB)", getString(R.string.rates_source_cbr))
    }

    private lateinit var mCurrencyPairSymbol: String
    private var currencyPairId = 0
    private var currentPair: CurrencyPair? = null
    private lateinit var fragmentDatePeriod: ChooseDatePeriodFragment
    private lateinit var adapter: CurrencyPairRateAdapter
    private val items: MutableList<Pair<Date, Double>> = ArrayList()
    private lateinit var db: SQLiteDatabase
    private lateinit var rateChart: RateChartView

    // Download-rates dialog state — the sheet's buttons are wired via android:onClick
    // (dialog_download_rates.xml), so its handlers (onPickSource/onCancelDownload/
    // onRunDownload) need to reach these instead of closing over local variables.
    private var downloadDialog: AlertDialog? = null
    private var downloadDlgView: View? = null
    private var downloadSourceIndex = 0
    private val downloadDateFrom: Calendar = Calendar.getInstance()
    private val downloadDateTo: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_pair_rate_list)

        mCurrencyPairSymbol = intent.getStringExtra(ARG_ITEM_SYMBOL)!!
        db = WalletDbHelper(this).readableDatabase

        setupToolbarTitle()

        val pair0 = Singleton.instance.getCurrencyPairList(this)
            .getItemBySymbol(mCurrencyPairSymbol)
        val pairPrecision = if (pair0 != null) pair0.precision else 5
        currencyPairId = if (pair0 != null) pair0.id else -1
        currentPair = pair0
        findViewById<View>(R.id.btnDeletePair).visibility =
            if (pair0 != null && !pair0.isShared) View.VISIBLE else View.GONE

        val lst = findViewById<ListView>(R.id.listView)
        adapter = CurrencyPairRateAdapter(this, items, pairPrecision)
        adapter.setCurrencyPairId(currencyPairId)
        adapter.setOnRateClickListener { rateDate, rateValue ->
            val dialog = RateEditDialog(
                this@CurrencyPairRateListActivity, currencyPairId, rateDate, rateValue,
                object : RateEditDialog.OnRateSavedListener {
                    override fun onRateSaved() {
                        // Refresh the chart's cached dataset too, not just the list, otherwise
                        // it keeps drawing the pre-edit values until the Activity is reopened.
                        reloadAll()
                    }

                    override fun onRateDeleted() {
                        reloadAll()
                    }
                })
            dialog.show()
        }
        lst.adapter = adapter

        rateChart = findViewById(R.id.rateChart)
        rateChart.setOnRangeChangedListener { from, to ->
            // Dragging the handles inside the chart narrows what the list below shows,
            // but must not move the date filter above — that stays under the user's
            // explicit control only.
            loadRatesForRange(from, to)
        }

        fragmentDatePeriod = supportFragmentManager
            .findFragmentById(R.id.fragmentDatePeriod) as ChooseDatePeriodFragment

        val lastDate = WalletDbLoader.LoadLastRateDate(db, mCurrencyPairSymbol)
        val cal = Calendar.getInstance()
        cal.time = lastDate
        cal.add(Calendar.MONTH, -1)
        val startDate = cal.time

        fragmentDatePeriod.setDateTo(lastDate)
        fragmentDatePeriod.setDateFrom(startDate)

        fragmentDatePeriod.setOnDatePeriodChangedListener { _, from, to ->
            reloadChartData(from, to)
            loadFilteredRates()
        }

        reloadChartData(startDate, lastDate)
        loadFilteredRates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::db.isInitialized && db.isOpen) db.close()
    }

    fun btnUp(view: View) {
        finish()
    }

    fun btnDownloadRates(view: View) {
        showDownloadDialog()
    }

    /** Кнопка «Удалить пару» в тулбаре (видна только у своей пары). После удаления экран закрывается. */
    fun onDeletePair(view: View) {
        val pair = currentPair
        if (pair == null || pair.isShared) return
        CurrencyPairListActivity.confirmDeletePair(this, pair) { finish() }
    }

    fun onAddRateClick(view: View) {
        // Same sheet as editing an existing rate (dialog_edit_rate.xml already designed for
        // both — dialogTitle/btnDelete just adapt to the mode), instead of a separate dialog.
        val dialog = RateEditDialog.newRate(
            this, currencyPairId,
            object : RateEditDialog.OnRateSavedListener {
                override fun onRateSaved() {
                    reloadAll()
                }

                override fun onRateDeleted() {
                    reloadAll()
                }
            })
        dialog.show()
    }

    private fun showDownloadDialog() {
        val pair = Singleton.instance.getCurrencyPairList(this)
            .getItemBySymbol(mCurrencyPairSymbol)

        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_download_rates, null)
        downloadDlgView = dlgView
        val sourceValue = dlgView.findViewById<TextView>(R.id.sourceValue)
        val inputFrom = dlgView.findViewById<EditText>(R.id.inputFrom)
        val inputTo = dlgView.findViewById<EditText>(R.id.inputTo)

        // Let the user pick the source, pre-selecting a sensible default for this pair
        // (CBR is only meaningful for RUB pairs; otherwise Yahoo/Frankfurter cover it).
        downloadSourceIndex = defaultSourceIndex(pair)
        sourceValue.text = rateSources()[downloadSourceIndex]

        // Pre-populate dates from current filter
        downloadDateFrom.time = fragmentDatePeriod.getDateFrom()
        downloadDateTo.time = fragmentDatePeriod.getDateTo()

        val displayFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        inputFrom.setText(displayFmt.format(downloadDateFrom.time))
        inputTo.setText(displayFmt.format(downloadDateTo.time))
        // Tap-to-pick, not a keyboard field — same trick as ChooseDateFragment.
        inputFrom.isFocusable = false
        inputTo.isFocusable = false

        inputFrom.setOnClickListener {
            CalendarPicker.show(this, downloadDateFrom) { y, m, d ->
                downloadDateFrom.set(y, m, d)
                inputFrom.setText(displayFmt.format(downloadDateFrom.time))
            }
        }

        inputTo.setOnClickListener {
            CalendarPicker.show(this, downloadDateTo) { y, m, d ->
                downloadDateTo.set(y, m, d)
                inputTo.setText(displayFmt.format(downloadDateTo.time))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dlgView)
            .create()
        downloadDialog = dialog
        dialog.show()
    }

    /** rowSource tap target (dialog_download_rates.xml android:onClick). */
    fun onPickSource(view: View) {
        AlertDialog.Builder(this)
            .setTitle(R.string.rates_source_title)
            .setItems(rateSources()) { _, which ->
                downloadSourceIndex = which
                downloadDlgView!!.findViewById<TextView>(R.id.sourceValue).text = rateSources()[which]
            }
            .show()
    }

    /** btnCancel tap target (dialog_download_rates.xml android:onClick). */
    fun onCancelDownload(view: View) {
        downloadDialog?.dismiss()
    }

    /** btnDownload tap target (dialog_download_rates.xml android:onClick). */
    fun onRunDownload(view: View) {
        val pair = Singleton.instance.getCurrencyPairList(this)
            .getItemBySymbol(mCurrencyPairSymbol)

        val radioGroupFreq = downloadDlgView!!.findViewById<RadioGroup>(R.id.radioGroupFreq)
        val checkedId = radioGroupFreq.checkedRadioButtonId
        val freqMode = if (checkedId == R.id.radioMonday) 1
        else if (checkedId == R.id.radioMonth) 2 else 0 // 0=daily,1=weekly,2=monthly

        val dateFrom = downloadDateFrom.time
        val dateTo = downloadDateTo.time
        val sourceIndex = downloadSourceIndex

        downloadDialog?.dismiss()
        Toast.makeText(this, R.string.rates_loading, Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val rates = downloadRates(pair, dateFrom, dateTo, sourceIndex)
                if (rates.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.rates_no_data, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val filtered = filterByFrequency(rates, freqMode)

                val writable = WalletDbHelper(this@CurrencyPairRateListActivity)
                    .writableDatabase

                var count = 0
                try {
                    for ((key, value) in filtered) {
                        WalletDbCRUD.InsertOrReplaceRate(writable, mCurrencyPairSymbol, key, value)
                        count++
                    }
                } finally {
                    writable.close()
                }

                val inserted = count
                runOnUiThread {
                    Toast.makeText(
                        this,
                        resources.getQuantityString(R.plurals.rates_loaded, inserted, inserted),
                        Toast.LENGTH_SHORT
                    ).show()
                    reloadAll()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_with_reason, ex.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Filters a {yyyy-MM-dd → rate} map by frequency.
     * mode 0 = daily (no filter), 1 = weekly (keep Mondays), 2 = monthly (keep 1st of month).
     * For weekly/monthly, if the exact anchor day has no rate, the closest available day is used.
     */
    private fun filterByFrequency(all: Map<String, Double>, mode: Int): Map<String, Double> {
        if (mode == 0) return all

        // Sort dates
        val sorted = ArrayList(all.keys)
        sorted.sort()

        val result = LinkedHashMap<String, Double>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        // Build a list of anchor dates within the range
        if (sorted.isEmpty()) return result
        try {
            val cur = Calendar.getInstance()
            cur.time = fmt.parse(sorted[0])!!
            val end = Calendar.getInstance()
            end.time = fmt.parse(sorted[sorted.size - 1])!!

            if (mode == 1) {
                // Advance to first Monday on or after start
                while (cur.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cur.add(Calendar.DAY_OF_MONTH, 1)
            } else {
                // Advance to 1st of the month containing the first date
                cur.set(Calendar.DAY_OF_MONTH, 1)
                if (cur.before(Calendar.getInstance()) && fmt.format(cur.time).compareTo(sorted[0]) < 0)
                    cur.add(Calendar.MONTH, 1)
                cur.set(Calendar.DAY_OF_MONTH, 1)
            }

            while (!cur.after(end)) {
                val anchor = fmt.format(cur.time)
                // Find closest available date (anchor itself or next available)
                var best: String? = null
                for (d in sorted) {
                    if (d.compareTo(anchor) >= 0) {
                        best = d
                        break
                    }
                }
                if (best != null && !result.containsKey(best)) {
                    result[best] = all[best]!!
                }
                if (mode == 1) cur.add(Calendar.WEEK_OF_YEAR, 1)
                else cur.add(Calendar.MONTH, 1)
            }
        } catch (ignored: Exception) {
        }
        return result
    }

    /** Pre-selects a sensible default in the source Spinner; the user can still change it. */
    private fun defaultSourceIndex(pair: CurrencyPair?): Int {
        if (pair == null) return SOURCE_YAHOO
        val base = pair.baseCurrency!!.iso
        val quote = pair.quoteCurrency!!.iso
        val isRub = "RUB" == base || "RUB" == quote
        if (isRub) {
            val foreign = if ("RUB" == base) quote else base
            return if (CBR_CODES.containsKey(foreign)) SOURCE_CBR else SOURCE_YAHOO
        }
        return SOURCE_YAHOO
    }

    /** Returns map {yyyy-MM-dd → rate}, downloaded from the user-selected source. */
    @Throws(Exception::class)
    private fun downloadRates(pair: CurrencyPair?, dateFrom: Date, dateTo: Date, sourceIndex: Int): Map<String, Double> {
        if (pair == null) throw Exception(getString(R.string.rates_error_no_pair))

        val base = pair.baseCurrency!!.iso
        val quote = pair.quoteCurrency!!.iso

        return when (sourceIndex) {
            SOURCE_CBR -> {
                if ("RUB" != base && "RUB" != quote)
                    throw Exception(getString(R.string.rates_error_cbr_rub_only))
                downloadFromCBR(base, quote, dateFrom, dateTo)
            }
            SOURCE_FRANKFURTER -> downloadFromFrankfurter(base, quote, dateFrom, dateTo)
            else -> downloadFromYahoo(base, quote, dateFrom, dateTo)
        }
    }

    @Throws(Exception::class)
    private fun downloadFromFrankfurter(base: String, quote: String, dateFrom: Date, dateTo: Date): Map<String, Double> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val urlStr = ("https://api.frankfurter.app/"
                + fmt.format(dateFrom) + ".." + fmt.format(dateTo)
                + "?from=" + base + "&to=" + quote)

        val json = fetchString(urlStr, "UTF-8")
        val root = JSONObject(json)
        val ratesObj = root.getJSONObject("rates")

        val result = HashMap<String, Double>()
        val keys = ratesObj.keys()
        while (keys.hasNext()) {
            val date = keys.next()
            val dayRates = ratesObj.getJSONObject(date)
            if (dayRates.has(quote)) {
                result[date] = dayRates.getDouble(quote)
            }
        }
        return result
    }

    @Throws(Exception::class)
    private fun downloadFromCBR(base: String, quote: String, dateFrom: Date, dateTo: Date): Map<String, Double> {
        // CBR provides foreign/RUB rates — figure out which side is foreign
        val rubIsQuote = "RUB" == quote
        val foreign = if (rubIsQuote) base else quote

        val cbrCode = CBR_CODES[foreign]
            ?: throw Exception(getString(R.string.rates_error_cbr_unsupported, foreign))

        val cbrFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)

        val urlStr = ("https://www.cbr.ru/scripts/XML_dynamic.asp"
                + "?date_req1=" + cbrFmt.format(dateFrom)
                + "&date_req2=" + cbrFmt.format(dateTo)
                + "&VAL_NM_RQ=" + cbrCode)

        val xml = fetchString(urlStr, "windows-1251")

        val result = HashMap<String, Double>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(java.io.StringReader(xml))

        var currentDate: String? = null
        var nominal = 1.0
        var value = 0.0
        var inRecord = false
        var inNominal = false
        var inValue = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name
                if ("Record" == tag) {
                    // Date in DD.MM.YYYY format
                    val raw = parser.getAttributeValue(null, "Date")
                    if (raw != null && raw.length == 10) {
                        currentDate = raw.substring(6) + "-" + raw.substring(3, 5) + "-" + raw.substring(0, 2)
                    }
                    inRecord = true
                    nominal = 1.0
                    value = 0.0
                } else if (inRecord && "Nominal" == tag) {
                    inNominal = true
                } else if (inRecord && "Value" == tag) {
                    inValue = true
                }
            } else if (eventType == XmlPullParser.TEXT) {
                val text = parser.text.trim().replace(',', '.')
                if (inNominal) nominal = text.toDouble()
                else if (inValue) value = text.toDouble()
            } else if (eventType == XmlPullParser.END_TAG) {
                val tag = parser.name
                if ("Record" == tag && currentDate != null) {
                    var rate = value / nominal
                    // If RUB is the base (rate = RUB→foreign), invert
                    if (!rubIsQuote) rate = 1.0 / rate
                    result[currentDate] = rate
                    inRecord = false
                } else if ("Nominal" == tag) inNominal = false
                else if ("Value" == tag) inValue = false
            }
            eventType = parser.next()
        }
        return result
    }

    /**
     * Downloads rates from Yahoo Finance's (unofficial) chart API. Covers far more currencies
     * than Frankfurter/CBR — including exotics not quoted by either — via the "{BASE}{QUOTE}=X"
     * forex symbol convention. Falls back to the inverse cross (quote as base) and inverts the
     * rate if the direct symbol doesn't exist for this pair.
     */
    @Throws(Exception::class)
    private fun downloadFromYahoo(base: String, quote: String, dateFrom: Date, dateTo: Date): Map<String, Double> {
        val period1 = dateFrom.time / 1000L
        val period2 = dateTo.time / 1000L + 86400L // make the "to" day inclusive

        var result = fetchYahooSymbol("$base$quote=X", period1, period2, false)
        if (result.isEmpty())
            result = fetchYahooSymbol("$quote$base=X", period1, period2, true)
        if (result.isEmpty())
            throw Exception(getString(R.string.rates_error_yahoo_none, "$base/$quote"))
        return result
    }

    /** Returns map {yyyy-MM-dd → rate}, or empty if the symbol doesn't exist on Yahoo. */
    @Throws(Exception::class)
    private fun fetchYahooSymbol(symbol: String, period1: Long, period2: Long, invert: Boolean): Map<String, Double> {
        val result = HashMap<String, Double>()

        val urlStr = ("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            return result // symbol not found for this cross — caller tries the inverse
        }

        val json: String
        try {
            conn.inputStream.use { `is` ->
                InputStreamReader(`is`, "UTF-8").use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) sb.append(buf, 0, n)
                    json = sb.toString()
                }
            }
        } finally {
            conn.disconnect()
        }

        val results: JSONArray = JSONObject(json).getJSONObject("chart").getJSONArray("result")
        if (results.length() == 0) return result

        val r0 = results.getJSONObject(0)
        val timestamps = r0.getJSONArray("timestamp")
        val closes = r0.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")

        val dbFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dbFmt.timeZone = java.util.TimeZone.getTimeZone("UTC")

        for (i in 0 until timestamps.length()) {
            if (closes.isNull(i)) continue
            val close = closes.getDouble(i)
            val d = Date(timestamps.getLong(i) * 1000L)
            result[dbFmt.format(d)] = if (invert) 1.0 / close else close
        }
        return result
    }

    @Throws(Exception::class)
    private fun fetchString(urlStr: String, encoding: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "WalletTransactions/1.0")
        val `is`: InputStream = conn.inputStream
        val reader = InputStreamReader(`is`, encoding)
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var n: Int
        while (reader.read(buf).also { n = it } != -1) sb.append(buf, 0, n)
        reader.close()
        conn.disconnect()
        return sb.toString()
    }

    /**
     * Refreshes both the chart and the list after the data changed underneath us (add,
     * edit, delete, or an external-source download) — scoped to the current date filter,
     * same as [reloadChartData] / [loadFilteredRates].
     */
    private fun reloadAll() {
        reloadChartData(fragmentDatePeriod.getDateFrom(), fragmentDatePeriod.getDateTo())
        loadFilteredRates()
    }

    /**
     * Reloads the chart's dataset scoped to [from, to] — the chart only ever plots the date
     * range selected in the filter above, never all-time history — and resets its drag
     * handles to span that whole range (any narrower selection made by dragging is reset).
     */
    private fun reloadChartData(from: Date, to: Date) {
        val ranged = WalletDbLoader.LoadCurrencyPairRateList(db, mCurrencyPairSymbol, from, to)
        rateChart.setData(ranged)
        rateChart.setSelection(from, to)
    }

    private fun loadFilteredRates() {
        loadRatesForRange(fragmentDatePeriod.getDateFrom(), fragmentDatePeriod.getDateTo())
    }

    /** Loads just the list (not the chart) for an arbitrary [from, to] — used when the user
     * drags the chart's handles to narrow the list without touching the date filter above. */
    private fun loadRatesForRange(from: Date, to: Date) {
        val loaded = WalletDbLoader.LoadCurrencyPairRateList(db, mCurrencyPairSymbol, from, to)
        items.clear()
        items.addAll(loaded)
        adapter.notifyDataSetChanged()
        updateRateCard()
    }

    /**
     * Шапка карточки: последний курс из показанного набора, его изменение
     * к предыдущему и границы периода с мин/макс под графиком.
     * Считается по [items], поэтому следует и за фильтром дат,
     * и за сужением выделения на графике.
     */
    private fun updateRateCard() {
        val rateValue = findViewById<TextView>(R.id.rateValue)
        val rateDelta = findViewById<TextView>(R.id.rateDelta)
        val chartFrom = findViewById<TextView>(R.id.chartFrom)
        val chartTo = findViewById<TextView>(R.id.chartTo)
        val chartMinMax = findViewById<TextView>(R.id.chartMinMax)

        if (items.isEmpty()) {
            rateValue.text = "—"
            rateDelta.text = ""
            chartFrom.text = ""
            chartTo.text = ""
            chartMinMax.setText(R.string.rates_chart_empty)
            return
        }

        val format: DecimalFormat = AdapterUtils.getFormat(adapter.getPrecision())

        // items идут от новых к старым: [0] — последний курс, последний элемент — самый ранний.
        val latest = items[0]
        // Изменение за весь показанный период — то же, что рисовал сам график,
        // поэтому подпись внутри него убрана: одна величина в одном месте.
        val periodStart: Double? = if (items.size > 1) items[items.size - 1].second else null

        rateValue.text = format.format(latest.second)
        rateDelta.text = CurrencyPairRateAdapter.formatDelta(periodStart, latest.second)
        rateDelta.setTextColor(
            ContextCompat.getColor(
                this,
                CurrencyPairRateAdapter.deltaColorRes(periodStart, latest.second)
            )
        )

        var min: Double = latest.second
        var max: Double = latest.second
        for (rate in items) {
            min = Math.min(min, rate.second)
            max = Math.max(max, rate.second)
        }

        val oldest = items[items.size - 1].first
        chartFrom.text = String.format("%1\$td.%1\$tm.%1\$ty", oldest)
        chartTo.text = String.format("%1\$td.%1\$tm.%1\$ty", latest.first)
        chartMinMax.text = getString(R.string.rates_chart_min_max, format.format(min), format.format(max))
    }

    private fun setupToolbarTitle() {
        val titleFrame = findViewById<FrameLayout>(R.id.toolbarTitleFrame)

        val pair = Singleton.instance.getCurrencyPairList(this)
            .getItemBySymbol(mCurrencyPairSymbol)

        if (pair == null) {
            val fallback = TextView(this)
            fallback.text = mCurrencyPairSymbol
            fallback.textSize = 20f
            fallback.setTypeface(fallback.typeface, Typeface.BOLD)
            titleFrame.addView(fallback)
            return
        }

        val titleView = LayoutInflater.from(this)
            .inflate(R.layout.toolbar_currency_pair_title, titleFrame, false)
        titleView.findViewById<ImageView>(R.id.imgBaseCurrency)
            .setImageBitmap(pair.baseCurrency!!.icon!!.bitmap)
        titleView.findViewById<ImageView>(R.id.imgQuoteCurrency)
            .setImageBitmap(pair.quoteCurrency!!.icon!!.bitmap)
        titleView.findViewById<TextView>(R.id.tvPairSymbol).text = mCurrencyPairSymbol

        titleFrame.addView(titleView)
    }

    companion object {
        const val ARG_ITEM_SYMBOL = "symbol"

        private val CBR_CODES: Map<String, String> = hashMapOf(
            "USD" to "R01235",
            "EUR" to "R01239",
            "GBP" to "R01035",
            "CHF" to "R01775",
            "JPY" to "R01820",
            "CNY" to "R01375",
            "KZT" to "R01335",
            "GEL" to "R01207",
            "PLN" to "R01565",
            "AZN" to "R01215",
            "AMD" to "R01060",
            "BYN" to "R01090",
            "UAH" to "R01720",
            "CZK" to "R01235" // placeholder fallback
        )

        private const val SOURCE_YAHOO = 0
        private const val SOURCE_FRANKFURTER = 1
        private const val SOURCE_CBR = 2
    }
}
