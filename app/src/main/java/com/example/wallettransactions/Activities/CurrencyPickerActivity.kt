package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Adapters.CurrencyPickerAdapter
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * «Список валют» — общий однократный выбор одной валюты (activity_currency_picker.xml):
 * тап по строке сразу возвращает результат. Используется by dialog_new_pair.xml's
 * Базовая/Котируемая валюта. С [ARG_BROWSE] (кнопка «Валюты» на «Курсы валют») —
 * режим просмотра: тап открывает форму валюты и ничего не возвращает. The currency source is the same [com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList] the
 * rest of the app already uses (MainActivity's switcher, SettingsActivity, filter chips),
 * per the design's own "не дублируйте список по экранам" note — no separate
 * currencies.json is loaded here.
 */
class CurrencyPickerActivity : AppCompatActivity() {

    private var adapter: CurrencyPickerAdapter? = null
    private lateinit var searchInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_picker)

        val title = intent.getStringExtra(ARG_TITLE)
        if (title != null) findViewById<TextView>(R.id.pickerTitle).text = title
        val currentIso = intent.getStringExtra(ARG_CURRENT_ISO)
        val browse = intent.getBooleanExtra(ARG_BROWSE, false)

        val adapter = CurrencyPickerAdapter(this, loadCurrencies(), currentIso)
        this.adapter = adapter
        adapter.setOnEditCurrencyListener { openForm(it) }

        val listCurrencies = findViewById<ListView>(R.id.listCurrencies)
        listCurrencies.adapter = adapter
        listCurrencies.setOnItemClickListener { _, _, position, _ ->
            val chosen = adapter.getItem(position)
            if (browse) {
                openForm(chosen)
                return@setOnItemClickListener
            }
            val result = Intent()
            result.putExtra(RESULT_ISO, chosen.iso)
            setResult(RESULT_OK, result)
            finish()
        }

        searchInput = findViewById(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onCreateCurrency(view: View) {
        startActivityForResult(CurrencyFormActivity.buildIntent(this, -1), REQUEST_EDIT_CURRENCY)
    }

    private fun openForm(currency: Currency) {
        startActivityForResult(CurrencyFormActivity.buildIntent(this, currency.id), REQUEST_EDIT_CURRENCY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_CURRENCY && resultCode == RESULT_OK) refresh()
    }

    /**
     * Синк перечитывает валюты в синглтоне (Singleton.reloadCurrencyList) — правки с
     * веба (design D9) видны при возврате на экран, а не только после перезапуска.
     */
    override fun onResume() {
        super.onResume()
        if (adapter != null) refresh()
    }

    /** Перечитать из синглтона, не сбрасывая набранный поиск. */
    private fun refresh() {
        val adapter = adapter ?: return
        adapter.replaceAll(loadCurrencies())
        val q = searchInput.text
        if (q.isNotEmpty()) adapter.filter.filter(q)
    }

    private fun loadCurrencies(): List<Currency> {
        return ArrayList(Singleton.instance.getCurrencyList(this))
    }

    companion object {
        const val ARG_TITLE = "CurrencyPicker.Title"
        const val ARG_CURRENT_ISO = "CurrencyPicker.CurrentIso"

        /** Режим просмотра (кнопка «Валюты» на «Курсы валют», design D4): тап открывает форму, ничего не возвращает. */
        const val ARG_BROWSE = "CurrencyPicker.Browse"
        const val RESULT_ISO = "CurrencyPicker.ResultIso"

        private const val REQUEST_EDIT_CURRENCY = 1

        @JvmStatic
        fun buildIntent(ctx: Context, title: String?, currentIso: String?): Intent {
            val intent = Intent(ctx, CurrencyPickerActivity::class.java)
            intent.putExtra(ARG_TITLE, title)
            if (currentIso != null) intent.putExtra(ARG_CURRENT_ISO, currentIso)
            return intent
        }

        /** Список «Валюты» в режиме просмотра. */
        @JvmStatic
        fun buildBrowseIntent(ctx: Context): Intent {
            val intent = buildIntent(ctx, ctx.getString(R.string.currency_list_title), null)
            intent.putExtra(ARG_BROWSE, true)
            return intent
        }
    }
}
