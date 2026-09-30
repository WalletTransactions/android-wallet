package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import android.icu.text.DecimalFormat;
import com.example.wallettransactions.AdapterUtils;
import android.widget.Toast;

import com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment;
import com.example.wallettransactions.Adapters.CurrencyPairRateAdapter;
import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.Views.RateChartView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CurrencyPairRateListActivity extends AppCompatActivity {

    public static final String ARG_ITEM_SYMBOL = "symbol";

    private static final Map<String, String> CBR_CODES = new HashMap<>();
    static {
        CBR_CODES.put("USD", "R01235");
        CBR_CODES.put("EUR", "R01239");
        CBR_CODES.put("GBP", "R01035");
        CBR_CODES.put("CHF", "R01775");
        CBR_CODES.put("JPY", "R01820");
        CBR_CODES.put("CNY", "R01375");
        CBR_CODES.put("KZT", "R01335");
        CBR_CODES.put("GEL", "R01207");
        CBR_CODES.put("PLN", "R01565");
        CBR_CODES.put("AZN", "R01215");
        CBR_CODES.put("AMD", "R01060");
        CBR_CODES.put("BYN", "R01090");
        CBR_CODES.put("UAH", "R01720");
        CBR_CODES.put("CZK", "R01235"); // placeholder fallback
    }

    private static final String[] RATE_SOURCES = {"Yahoo Finance", "Frankfurter (ECB)", "ЦБ РФ"};
    private static final int SOURCE_YAHOO = 0;
    private static final int SOURCE_FRANKFURTER = 1;
    private static final int SOURCE_CBR = 2;

    private String mCurrencyPairSymbol;
    private int currencyPairId;
    private ChooseDatePeriodFragment fragmentDatePeriod;
    private CurrencyPairRateAdapter adapter;
    private List<Pair<Date, Double>> items = new ArrayList<>();
    private SQLiteDatabase db;
    private RateChartView rateChart;

    // Download-rates dialog state — the sheet's buttons are wired via android:onClick
    // (dialog_download_rates.xml), so its handlers (onPickSource/onCancelDownload/
    // onRunDownload) need to reach these instead of closing over local variables.
    private AlertDialog downloadDialog;
    private View downloadDlgView;
    private int downloadSourceIndex;
    private final Calendar downloadDateFrom = Calendar.getInstance();
    private final Calendar downloadDateTo = Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_pair_rate_list);

        mCurrencyPairSymbol = getIntent().getStringExtra(ARG_ITEM_SYMBOL);
        db = new WalletDbHelper(this).getReadableDatabase();

        setupToolbarTitle();

        CurrencyPair pair0 = Singleton.instance.getCurrencyPairList(this)
                .getItemBySymbol(mCurrencyPairSymbol);
        int pairPrecision = pair0 != null ? pair0.getPrecision() : 5;
        currencyPairId = pair0 != null ? pair0.getId() : -1;

        ListView lst = findViewById(R.id.listView);
        adapter = new CurrencyPairRateAdapter(this, items, pairPrecision);
        adapter.setCurrencyPairId(currencyPairId);
        adapter.setOnRateClickListener((rateDate, rateValue) -> {
            RateEditDialog dialog = new RateEditDialog(CurrencyPairRateListActivity.this, currencyPairId, rateDate, rateValue,
                    new RateEditDialog.OnRateSavedListener() {
                @Override
                public void onRateSaved() {
                    // Refresh the chart's cached dataset too, not just the list, otherwise
                    // it keeps drawing the pre-edit values until the Activity is reopened.
                    reloadAll();
                }

                @Override
                public void onRateDeleted() {
                    reloadAll();
                }
            });
            dialog.show();
        });
        lst.setAdapter(adapter);

        rateChart = findViewById(R.id.rateChart);
        rateChart.setOnRangeChangedListener(new RateChartView.OnRangeChangedListener() {
            @Override
            public void onRangeChanged(Date from, Date to) {
                // Dragging the handles inside the chart narrows what the list below shows,
                // but must not move the date filter above — that stays under the user's
                // explicit control only.
                loadRatesForRange(from, to);
            }
        });

        fragmentDatePeriod = (ChooseDatePeriodFragment) getSupportFragmentManager()
                .findFragmentById(R.id.fragmentDatePeriod);

        Date lastDate = WalletDbLoader.LoadLastRateDate(db, mCurrencyPairSymbol);
        Calendar cal = Calendar.getInstance();
        cal.setTime(lastDate);
        cal.add(Calendar.MONTH, -1);
        Date startDate = cal.getTime();

        fragmentDatePeriod.setDateTo(lastDate);
        fragmentDatePeriod.setDateFrom(startDate);

        fragmentDatePeriod.setOnDatePeriodChangedListener(
                new ChooseDatePeriodFragment.OnDatePeriodChangedListener() {
                    @Override
                    public void onDateChanged(ChooseDatePeriodFragment parent, Date from, Date to) {
                        reloadChartData(from, to);
                        loadFilteredRates();
                    }
                });

        reloadChartData(startDate, lastDate);
        loadFilteredRates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null && db.isOpen()) db.close();
    }

    public void btnUp(View view) {
        finish();
    }

    public void btnDownloadRates(View view) {
        showDownloadDialog();
    }

    public void onAddRateClick(View view) {
        // Same sheet as editing an existing rate (dialog_edit_rate.xml already designed for
        // both — dialogTitle/btnDelete just adapt to the mode), instead of a separate dialog.
        RateEditDialog dialog = RateEditDialog.newRate(this, currencyPairId,
                new RateEditDialog.OnRateSavedListener() {
                    @Override
                    public void onRateSaved() {
                        reloadAll();
                    }

                    @Override
                    public void onRateDeleted() {
                        reloadAll();
                    }
                });
        dialog.show();
    }

    private void showDownloadDialog() {
        CurrencyPair pair = Singleton.instance.getCurrencyPairList(this)
                .getItemBySymbol(mCurrencyPairSymbol);

        downloadDlgView = LayoutInflater.from(this).inflate(R.layout.dialog_download_rates, null);
        TextView sourceValue = downloadDlgView.findViewById(R.id.sourceValue);
        EditText inputFrom = downloadDlgView.findViewById(R.id.inputFrom);
        EditText inputTo   = downloadDlgView.findViewById(R.id.inputTo);

        // Let the user pick the source, pre-selecting a sensible default for this pair
        // (CBR is only meaningful for RUB pairs; otherwise Yahoo/Frankfurter cover it).
        downloadSourceIndex = defaultSourceIndex(pair);
        sourceValue.setText(RATE_SOURCES[downloadSourceIndex]);

        // Pre-populate dates from current filter
        downloadDateFrom.setTime(fragmentDatePeriod.getDateFrom());
        downloadDateTo.setTime(fragmentDatePeriod.getDateTo());

        SimpleDateFormat displayFmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        inputFrom.setText(displayFmt.format(downloadDateFrom.getTime()));
        inputTo.setText(displayFmt.format(downloadDateTo.getTime()));
        // Tap-to-pick, not a keyboard field — same trick as ChooseDateFragment.
        inputFrom.setFocusable(false);
        inputTo.setFocusable(false);

        inputFrom.setOnClickListener(v -> CalendarPicker.show(this, downloadDateFrom, (y, m, d) -> {
            downloadDateFrom.set(y, m, d);
            inputFrom.setText(displayFmt.format(downloadDateFrom.getTime()));
        }));

        inputTo.setOnClickListener(v -> CalendarPicker.show(this, downloadDateTo, (y, m, d) -> {
            downloadDateTo.set(y, m, d);
            inputTo.setText(displayFmt.format(downloadDateTo.getTime()));
        }));

        downloadDialog = new AlertDialog.Builder(this)
                .setView(downloadDlgView)
                .create();
        downloadDialog.show();
    }

    /** rowSource tap target (dialog_download_rates.xml android:onClick). */
    public void onPickSource(View view) {
        new AlertDialog.Builder(this)
                .setTitle("Источник")
                .setItems(RATE_SOURCES, (d, which) -> {
                    downloadSourceIndex = which;
                    ((TextView) downloadDlgView.findViewById(R.id.sourceValue)).setText(RATE_SOURCES[which]);
                })
                .show();
    }

    /** btnCancel tap target (dialog_download_rates.xml android:onClick). */
    public void onCancelDownload(View view) {
        if (downloadDialog != null) downloadDialog.dismiss();
    }

    /** btnDownload tap target (dialog_download_rates.xml android:onClick). */
    public void onRunDownload(View view) {
        CurrencyPair pair = Singleton.instance.getCurrencyPairList(this)
                .getItemBySymbol(mCurrencyPairSymbol);

        RadioGroup radioGroupFreq = downloadDlgView.findViewById(R.id.radioGroupFreq);
        int checkedId = radioGroupFreq.getCheckedRadioButtonId();
        final int freqMode = checkedId == R.id.radioMonday ? 1
                           : checkedId == R.id.radioMonth ? 2 : 0; // 0=daily,1=weekly,2=monthly

        Date dateFrom = downloadDateFrom.getTime();
        Date dateTo = downloadDateTo.getTime();
        final int sourceIndex = downloadSourceIndex;

        if (downloadDialog != null) downloadDialog.dismiss();
        Toast.makeText(this, "Загрузка…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                Map<String, Double> rates = downloadRates(pair, dateFrom, dateTo, sourceIndex);
                if (rates.isEmpty()) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Нет данных за указанный период", Toast.LENGTH_LONG).show());
                    return;
                }

                Map<String, Double> filtered = filterByFrequency(rates, freqMode);

                SQLiteDatabase writable = new WalletDbHelper(CurrencyPairRateListActivity.this)
                        .getWritableDatabase();

                int count = 0;
                try {
                    for (Map.Entry<String, Double> e : filtered.entrySet()) {
                        WalletDbCRUD.InsertOrReplaceRate(writable, mCurrencyPairSymbol,
                                e.getKey(), e.getValue());
                        count++;
                    }
                } finally {
                    writable.close();
                }

                final int inserted = count;
                runOnUiThread(() -> {
                    Toast.makeText(this, "Готово: загружено " + inserted + " записей", Toast.LENGTH_SHORT).show();
                    reloadAll();
                });

            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Filters a {yyyy-MM-dd → rate} map by frequency.
     * mode 0 = daily (no filter), 1 = weekly (keep Mondays), 2 = monthly (keep 1st of month).
     * For weekly/monthly, if the exact anchor day has no rate, the closest available day is used.
     */
    private Map<String, Double> filterByFrequency(Map<String, Double> all, int mode) {
        if (mode == 0) return all;

        // Sort dates
        List<String> sorted = new ArrayList<>(all.keySet());
        java.util.Collections.sort(sorted);

        Map<String, Double> result = new java.util.LinkedHashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        // Build a list of anchor dates within the range
        if (sorted.isEmpty()) return result;
        try {
            Calendar cur = Calendar.getInstance();
            cur.setTime(fmt.parse(sorted.get(0)));
            Calendar end = Calendar.getInstance();
            end.setTime(fmt.parse(sorted.get(sorted.size() - 1)));

            if (mode == 1) {
                // Advance to first Monday on or after start
                while (cur.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cur.add(Calendar.DAY_OF_MONTH, 1);
            } else {
                // Advance to 1st of the month containing the first date
                cur.set(Calendar.DAY_OF_MONTH, 1);
                if (cur.before(Calendar.getInstance()) && fmt.format(cur.getTime()).compareTo(sorted.get(0)) < 0)
                    cur.add(Calendar.MONTH, 1);
                cur.set(Calendar.DAY_OF_MONTH, 1);
            }

            while (!cur.after(end)) {
                String anchor = fmt.format(cur.getTime());
                // Find closest available date (anchor itself or next available)
                String best = null;
                for (String d : sorted) {
                    if (d.compareTo(anchor) >= 0) { best = d; break; }
                }
                if (best != null && !result.containsKey(best)) {
                    result.put(best, all.get(best));
                }
                if (mode == 1) cur.add(Calendar.WEEK_OF_YEAR, 1);
                else cur.add(Calendar.MONTH, 1);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Pre-selects a sensible default in the source Spinner; the user can still change it. */
    private int defaultSourceIndex(CurrencyPair pair) {
        if (pair == null) return SOURCE_YAHOO;
        String base = pair.getBaseCurrency().getIso();
        String quote = pair.getQuoteCurrency().getIso();
        boolean isRub = "RUB".equals(base) || "RUB".equals(quote);
        if (isRub) {
            String foreign = "RUB".equals(base) ? quote : base;
            return CBR_CODES.containsKey(foreign) ? SOURCE_CBR : SOURCE_YAHOO;
        }
        return SOURCE_YAHOO;
    }

    /** Returns map {yyyy-MM-dd → rate}, downloaded from the user-selected source. */
    private Map<String, Double> downloadRates(CurrencyPair pair, Date dateFrom, Date dateTo, int sourceIndex)
            throws Exception {
        if (pair == null) throw new Exception("Валютная пара не найдена");

        String base = pair.getBaseCurrency().getIso();
        String quote = pair.getQuoteCurrency().getIso();

        switch (sourceIndex) {
            case SOURCE_CBR:
                if (!"RUB".equals(base) && !"RUB".equals(quote))
                    throw new Exception("ЦБ РФ котирует только пары с RUB");
                return downloadFromCBR(base, quote, dateFrom, dateTo);
            case SOURCE_FRANKFURTER:
                return downloadFromFrankfurter(base, quote, dateFrom, dateTo);
            case SOURCE_YAHOO:
            default:
                return downloadFromYahoo(base, quote, dateFrom, dateTo);
        }
    }

    private Map<String, Double> downloadFromFrankfurter(String base, String quote,
            Date dateFrom, Date dateTo) throws Exception {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String urlStr = "https://api.frankfurter.app/"
                + fmt.format(dateFrom) + ".." + fmt.format(dateTo)
                + "?from=" + base + "&to=" + quote;

        String json = fetchString(urlStr, "UTF-8");
        JSONObject root = new JSONObject(json);
        JSONObject ratesObj = root.getJSONObject("rates");

        Map<String, Double> result = new HashMap<>();
        Iterator<String> keys = ratesObj.keys();
        while (keys.hasNext()) {
            String date = keys.next();
            JSONObject dayRates = ratesObj.getJSONObject(date);
            if (dayRates.has(quote)) {
                result.put(date, dayRates.getDouble(quote));
            }
        }
        return result;
    }

    private Map<String, Double> downloadFromCBR(String base, String quote,
            Date dateFrom, Date dateTo) throws Exception {
        // CBR provides foreign/RUB rates — figure out which side is foreign
        boolean rubIsQuote = "RUB".equals(quote);
        String foreign = rubIsQuote ? base : quote;

        String cbrCode = CBR_CODES.get(foreign);
        if (cbrCode == null)
            throw new Exception("Валюта " + foreign + " не поддерживается ЦБ РФ");

        SimpleDateFormat cbrFmt = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        SimpleDateFormat dbFmt  = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        String urlStr = "https://www.cbr.ru/scripts/XML_dynamic.asp"
                + "?date_req1=" + cbrFmt.format(dateFrom)
                + "&date_req2=" + cbrFmt.format(dateTo)
                + "&VAL_NM_RQ=" + cbrCode;

        String xml = fetchString(urlStr, "windows-1251");

        Map<String, Double> result = new HashMap<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new java.io.StringReader(xml));

        String currentDate = null;
        double nominal = 1;
        double value = 0;
        boolean inRecord = false;
        boolean inNominal = false;
        boolean inValue = false;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("Record".equals(tag)) {
                    // Date in DD.MM.YYYY format
                    String raw = parser.getAttributeValue(null, "Date");
                    if (raw != null && raw.length() == 10) {
                        currentDate = raw.substring(6) + "-" + raw.substring(3, 5) + "-" + raw.substring(0, 2);
                    }
                    inRecord = true;
                    nominal = 1; value = 0;
                } else if (inRecord && "Nominal".equals(tag)) {
                    inNominal = true;
                } else if (inRecord && "Value".equals(tag)) {
                    inValue = true;
                }
            } else if (eventType == XmlPullParser.TEXT) {
                String text = parser.getText().trim().replace(',', '.');
                if (inNominal) nominal = Double.parseDouble(text);
                else if (inValue) value  = Double.parseDouble(text);
            } else if (eventType == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("Record".equals(tag) && currentDate != null) {
                    double rate = value / nominal;
                    // If RUB is the base (rate = RUB→foreign), invert
                    if (!rubIsQuote) rate = 1.0 / rate;
                    result.put(currentDate, rate);
                    inRecord = false;
                } else if ("Nominal".equals(tag)) inNominal = false;
                else if ("Value".equals(tag)) inValue = false;
            }
            eventType = parser.next();
        }
        return result;
    }

    /**
     * Downloads rates from Yahoo Finance's (unofficial) chart API. Covers far more currencies
     * than Frankfurter/CBR — including exotics not quoted by either — via the "{BASE}{QUOTE}=X"
     * forex symbol convention. Falls back to the inverse cross (quote as base) and inverts the
     * rate if the direct symbol doesn't exist for this pair.
     */
    private Map<String, Double> downloadFromYahoo(String base, String quote, Date dateFrom, Date dateTo)
            throws Exception {
        long period1 = dateFrom.getTime() / 1000L;
        long period2 = dateTo.getTime() / 1000L + 86400L; // make the "to" day inclusive

        Map<String, Double> result = fetchYahooSymbol(base + quote + "=X", period1, period2, false);
        if (result.isEmpty())
            result = fetchYahooSymbol(quote + base + "=X", period1, period2, true);
        if (result.isEmpty())
            throw new Exception("Yahoo Finance не располагает котировками " + base + "/" + quote);
        return result;
    }

    /** Returns map {yyyy-MM-dd → rate}, or empty if the symbol doesn't exist on Yahoo. */
    private Map<String, Double> fetchYahooSymbol(String symbol, long period1, long period2, boolean invert)
            throws Exception {
        Map<String, Double> result = new HashMap<>();

        String urlStr = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            return result; // symbol not found for this cross — caller tries the inverse
        }

        String json;
        try (InputStream is = conn.getInputStream();
             InputStreamReader reader = new InputStreamReader(is, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            json = sb.toString();
        } finally {
            conn.disconnect();
        }

        JSONArray results = new JSONObject(json).getJSONObject("chart").getJSONArray("result");
        if (results.length() == 0) return result;

        JSONObject r0 = results.getJSONObject(0);
        JSONArray timestamps = r0.getJSONArray("timestamp");
        JSONArray closes = r0.getJSONObject("indicators").getJSONArray("quote")
                .getJSONObject(0).getJSONArray("close");

        SimpleDateFormat dbFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dbFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        for (int i = 0; i < timestamps.length(); i++) {
            if (closes.isNull(i)) continue;
            double close = closes.getDouble(i);
            Date d = new Date(timestamps.getLong(i) * 1000L);
            result.put(dbFmt.format(d), invert ? 1.0 / close : close);
        }
        return result;
    }

    private String fetchString(String urlStr, String encoding) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "WalletTransactions/1.0");
        InputStream is = conn.getInputStream();
        InputStreamReader reader = new InputStreamReader(is, encoding);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    /**
     * Refreshes both the chart and the list after the data changed underneath us (add,
     * edit, delete, or an external-source download) — scoped to the current date filter,
     * same as {@link #reloadChartData} / {@link #loadFilteredRates}.
     */
    private void reloadAll() {
        reloadChartData(fragmentDatePeriod.getDateFrom(), fragmentDatePeriod.getDateTo());
        loadFilteredRates();
    }

    /**
     * Reloads the chart's dataset scoped to [from, to] — the chart only ever plots the date
     * range selected in the filter above, never all-time history — and resets its drag
     * handles to span that whole range (any narrower selection made by dragging is reset).
     */
    private void reloadChartData(Date from, Date to) {
        List<Pair<Date, Double>> ranged =
                WalletDbLoader.LoadCurrencyPairRateList(db, mCurrencyPairSymbol, from, to);
        rateChart.setData(ranged);
        rateChart.setSelection(from, to);
    }

    private void loadFilteredRates() {
        loadRatesForRange(fragmentDatePeriod.getDateFrom(), fragmentDatePeriod.getDateTo());
    }

    /** Loads just the list (not the chart) for an arbitrary [from, to] — used when the user
     * drags the chart's handles to narrow the list without touching the date filter above. */
    private void loadRatesForRange(Date from, Date to) {
        List<Pair<Date, Double>> loaded =
                WalletDbLoader.LoadCurrencyPairRateList(db, mCurrencyPairSymbol, from, to);
        items.clear();
        items.addAll(loaded);
        adapter.notifyDataSetChanged();
        updateRateCard();
    }

    /**
     * Шапка карточки: последний курс из показанного набора, его изменение
     * к предыдущему и границы периода с мин/макс под графиком.
     * Считается по {@link #items}, поэтому следует и за фильтром дат,
     * и за сужением выделения на графике.
     */
    private void updateRateCard() {
        TextView rateValue = findViewById(R.id.rateValue);
        TextView rateDelta = findViewById(R.id.rateDelta);
        TextView chartFrom = findViewById(R.id.chartFrom);
        TextView chartTo = findViewById(R.id.chartTo);
        TextView chartMinMax = findViewById(R.id.chartMinMax);

        if (items.isEmpty()) {
            rateValue.setText("—");
            rateDelta.setText("");
            chartFrom.setText("");
            chartTo.setText("");
            chartMinMax.setText("нет курсов за период");
            return;
        }

        DecimalFormat format = AdapterUtils.getFormat(adapter.getPrecision());

        // items идут от новых к старым: [0] — последний курс, последний элемент — самый ранний.
        Pair<Date, Double> latest = items.get(0);
        // Изменение за весь показанный период — то же, что рисовал сам график,
        // поэтому подпись внутри него убрана: одна величина в одном месте.
        Double periodStart = items.size() > 1 ? items.get(items.size() - 1).second : null;

        rateValue.setText(format.format(latest.second));
        rateDelta.setText(CurrencyPairRateAdapter.formatDelta(periodStart, latest.second));
        rateDelta.setTextColor(ContextCompat.getColor(this,
                CurrencyPairRateAdapter.deltaColorRes(periodStart, latest.second)));

        double min = latest.second;
        double max = latest.second;
        for (Pair<Date, Double> rate : items) {
            min = Math.min(min, rate.second);
            max = Math.max(max, rate.second);
        }

        Date oldest = items.get(items.size() - 1).first;
        chartFrom.setText(String.format("%1$td.%1$tm.%1$ty", oldest));
        chartTo.setText(String.format("%1$td.%1$tm.%1$ty", latest.first));
        chartMinMax.setText("мин " + format.format(min) + " · макс " + format.format(max));
    }

    private void setupToolbarTitle() {
        FrameLayout titleFrame = findViewById(R.id.toolbarTitleFrame);

        CurrencyPair pair = Singleton.instance.getCurrencyPairList(this)
                .getItemBySymbol(mCurrencyPairSymbol);

        if (pair == null) {
            TextView fallback = new TextView(this);
            fallback.setText(mCurrencyPairSymbol);
            fallback.setTextSize(20);
            fallback.setTypeface(fallback.getTypeface(), android.graphics.Typeface.BOLD);
            titleFrame.addView(fallback);
            return;
        }

        View titleView = LayoutInflater.from(this)
                .inflate(R.layout.toolbar_currency_pair_title, titleFrame, false);
        ((ImageView) titleView.findViewById(R.id.imgBaseCurrency))
                .setImageBitmap(pair.getBaseCurrency().getIcon().getBitmap());
        ((ImageView) titleView.findViewById(R.id.imgQuoteCurrency))
                .setImageBitmap(pair.getQuoteCurrency().getIcon().getBitmap());
        ((TextView) titleView.findViewById(R.id.tvPairSymbol))
                .setText(mCurrencyPairSymbol);

        titleFrame.addView(titleView);
    }
}
