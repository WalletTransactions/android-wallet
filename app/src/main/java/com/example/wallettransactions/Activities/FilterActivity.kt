package com.example.wallettransactions.Activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Экран «Фильтр» операций. Открывается общей кнопкой шапки — см.
 * [BaseTabActivity.btnChangeFilter], — и одинаково работает со всех табов.
 *
 * Период здесь не показывается: он уже есть в шапке самих «Операций»
 * ([com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment]),
 * дублировать его тут незачем.
 *
 * Контракт в обе стороны — [MainActivity.cFilterAccountList] (ID через запятую):
 * на входе уже выбранные счета (чтобы экран открывался в текущем состоянии фильтра, а не
 * пустым), на выходе — новые. Применяет их [BaseTabActivity.onActivityResult],
 * записывая в общее состояние [com.example.wallettransactions.PeriodFilterState].
 *
 * Вид операции, текст поиска и границы сумм пишутся сразу в [PeriodFilterState] —
 * он один на процесс, поэтому передавать их через extra не нужно (как и период).
 */
class FilterActivity : AppCompatActivity() {

    companion object {
        private const val requestPickCategories = 1
        private const val requestPickWallets = 2

        private fun setVisible(view: View, visible: Boolean) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
        }

        private fun setOptionalText(field: EditText, value: Double?) {
            field.setText(if (value == null) "" else formatCompact(value))
        }

        /** Без лишних нулей после запятой: 100.0 → «100», 100.5 → «100.5». */
        private fun formatCompact(value: Double): String {
            return AdapterUtils.plainNumber(value)
        }

        /** null — поле пустое или не число (запятую тоже принимаем как разделитель). */
        private fun parseOptionalDouble(field: EditText): Double? {
            val text = field.text.toString().trim().replace(",", ".")
            if (text.isEmpty()) return null
            return try {
                java.lang.Double.parseDouble(text)
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun joinIds(items: List<AccountItem>): String {
            val ids = StringBuilder()
            for (item in items) {
                if (ids.isNotEmpty()) ids.append(",")
                ids.append(item.id)
            }
            return ids.toString()
        }
    }

    private val selectedCategories = ArrayList<AccountItem>()
    private val selectedWallets = ArrayList<AccountItem>()

    private lateinit var categorySummary: TextView
    private lateinit var walletSummary: TextView

    private lateinit var inputSearchText: EditText
    private lateinit var chipAll: TextView
    private lateinit var chipExpense: ViewGroup
    private lateinit var chipIncome: ViewGroup
    private lateinit var chipTransfer: ViewGroup
    private var selectedKind: DataFilter.OperationKind = DataFilter.OperationKind.all

    private lateinit var inputAmountMin: EditText
    private lateinit var inputAmountMax: EditText
    private lateinit var inputEqMin: EditText
    private lateinit var inputEqMax: EditText

    /** «Показать N» — N считает [updateApplyButtonCount] по ещё не применённому выбору. */
    private lateinit var btnApply: TextView

    /** Крестики очистки по одному параметру (similar-search) — видны, только пока параметр задан. */
    private var clearSearch: View? = null
    private lateinit var clearKind: View
    private lateinit var clearAmountMin: View
    private lateinit var clearAmountMax: View
    private lateinit var clearEqMin: View
    private lateinit var clearEqMax: View
    private lateinit var clearCategories: View
    private lateinit var clearWallets: View
    private lateinit var chevronCategories: View
    private lateinit var chevronWallets: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)

        categorySummary = findViewById(R.id.categorySummary)
        walletSummary = findViewById(R.id.walletSummary)
        btnApply = findViewById(R.id.btnApply)

        setupSearchText()
        setupKind()
        setupAmountRanges()
        restoreSelection(intent.getStringExtra(MainActivity.cFilterAccountList))
        setupClearButtons()

        // Слушатели — после того, как поля заполнены исходным состоянием: иначе
        // восстановление сохранённого текста само по себе гоняло бы лишние запросы.
        attachCountWatchers(inputSearchText, inputAmountMin, inputAmountMax, inputEqMin, inputEqMax)
        updateApplyButtonCount()
    }

    // ── Кнопка «Показать N» ───────────────────────────────────────────────────

    private fun attachCountWatchers(vararg fields: EditText) {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateApplyButtonCount() }
        }
        for (field in fields) field.addTextChangedListener(watcher)
    }

    /**
     * Период и валюта эквивалента берутся из общего состояния как есть — здесь их не
     * меняют (см. класс-javadoc), а остальное — из ещё не применённого выбора на экране,
     * поэтому строится временный [DataFilter], а не `state().getDataFilter()`.
     */
    private fun updateApplyButtonCount() {
        val state = PeriodFilterState.get(this)
        val count = WalletDbLoader.CountOperations(Singleton.instance.getReadableDatabase(this),
                state.getDateFrom()!!, state.getDateTo()!!, state.getCurrencyEq(this), buildPreviewFilter())
        btnApply.text = getString(R.string.filter_apply_count, count)
        refreshClearButtons()
    }

    // ── Крестики очистки по одному параметру ──────────────────────────────────

    private fun setupClearButtons() {
        val clearSearch = findViewById<View>(R.id.btnClearSearch)
        this.clearSearch = clearSearch
        clearKind = findViewById(R.id.btnClearKind)
        clearAmountMin = findViewById(R.id.btnClearAmountMin)
        clearAmountMax = findViewById(R.id.btnClearAmountMax)
        clearEqMin = findViewById(R.id.btnClearEqMin)
        clearEqMax = findViewById(R.id.btnClearEqMax)
        clearCategories = findViewById(R.id.btnClearCategories)
        clearWallets = findViewById(R.id.btnClearWallets)
        chevronCategories = findViewById(R.id.chevronCategories)
        chevronWallets = findViewById(R.id.chevronWallets)

        clearSearch.setOnClickListener { inputSearchText.setText("") }
        clearAmountMin.setOnClickListener { inputAmountMin.setText("") }
        clearAmountMax.setOnClickListener { inputAmountMax.setText("") }
        clearEqMin.setOnClickListener { inputEqMin.setText("") }
        clearEqMax.setOnClickListener { inputEqMax.setText("") }
        clearKind.setOnClickListener { pickKind(DataFilter.OperationKind.all) }
        clearCategories.setOnClickListener {
            selectedCategories.clear()
            updateSummary(selectedCategories, categorySummary)
            updateApplyButtonCount()
        }
        clearWallets.setOnClickListener {
            selectedWallets.clear()
            updateSummary(selectedWallets, walletSummary)
            updateApplyButtonCount()
        }
    }

    /**
     * Крестик виден только у заданного параметра; в строках счетов он занимает место
     * шеврона — выбор уже сделан, и открыть пикер можно тапом по самой строке.
     */
    private fun refreshClearButtons() {
        val clearSearch = clearSearch ?: return

        setVisible(clearSearch, inputSearchText.text.isNotEmpty())
        setVisible(clearKind, selectedKind != DataFilter.OperationKind.all)
        setVisible(clearAmountMin, inputAmountMin.text.isNotEmpty())
        setVisible(clearAmountMax, inputAmountMax.text.isNotEmpty())
        setVisible(clearEqMin, inputEqMin.text.isNotEmpty())
        setVisible(clearEqMax, inputEqMax.text.isNotEmpty())

        setVisible(clearCategories, selectedCategories.isNotEmpty())
        setVisible(chevronCategories, selectedCategories.isEmpty())
        setVisible(clearWallets, selectedWallets.isNotEmpty())
        setVisible(chevronWallets, selectedWallets.isEmpty())
    }

    private fun buildPreviewFilter(): DataFilter {
        val preview = DataFilter()
        for (item in selectedCategories) preview.addAccount(item)
        for (item in selectedWallets) preview.addAccount(item)
        preview.setOperationKind(selectedKind)
        preview.setSearchText(inputSearchText.text.toString())
        preview.setAmountRange(parseOptionalDouble(inputAmountMin), parseOptionalDouble(inputAmountMax))
        preview.setEqRange(parseOptionalDouble(inputEqMin), parseOptionalDouble(inputEqMax))
        return preview
    }

    // ── Поиск по тексту ───────────────────────────────────────────────────────

    private fun setupSearchText() {
        inputSearchText = findViewById(R.id.inputSearchText)
        inputSearchText.setText(PeriodFilterState.get(this).getSearchText())
    }

    // ── Тип операции ──────────────────────────────────────────────────────────

    private fun setupKind() {
        chipAll = findViewById(R.id.chipAll)
        chipExpense = findViewById(R.id.chipExpense)
        chipIncome = findViewById(R.id.chipIncome)
        chipTransfer = findViewById(R.id.chipTransfer)

        selectedKind = PeriodFilterState.get(this).getOperationKind()
        paintKind()

        chipAll.setOnClickListener { pickKind(DataFilter.OperationKind.all) }
        chipExpense.setOnClickListener { pickKind(DataFilter.OperationKind.expense) }
        chipIncome.setOnClickListener { pickKind(DataFilter.OperationKind.income) }
        chipTransfer.setOnClickListener { pickKind(DataFilter.OperationKind.transfer) }
    }

    private fun pickKind(kind: DataFilter.OperationKind) {
        selectedKind = kind
        paintKind()
        updateApplyButtonCount()
    }

    /** Красит все четыре чипа под текущий выбор — как чипы типа операции на экране операции. */
    private fun paintKind() {
        paintPlainChip(chipAll, selectedKind == DataFilter.OperationKind.all)
        paintIconChip(chipExpense, selectedKind == DataFilter.OperationKind.expense)
        paintIconChip(chipIncome, selectedKind == DataFilter.OperationKind.income)
        paintIconChip(chipTransfer, selectedKind == DataFilter.OperationKind.transfer)
    }

    private fun paintPlainChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(this,
                if (active) R.color.colorSurface else R.color.colorTextSecondary))
    }

    /** Чип-контейнер из иконки (childAt 0) и подписи (childAt 1) — см. FilterChipIcon. */
    private fun paintIconChip(chip: ViewGroup, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)

        val color = ContextCompat.getColor(this,
                if (active) R.color.colorSurface else R.color.colorTextSecondary)
        (chip.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (chip.getChildAt(1) as TextView).setTextColor(color)
    }

    // ── Суммы ─────────────────────────────────────────────────────────────────

    private fun setupAmountRanges() {
        inputAmountMin = findViewById(R.id.inputAmountMin)
        inputAmountMax = findViewById(R.id.inputAmountMax)
        inputEqMin = findViewById(R.id.inputEqMin)
        inputEqMax = findViewById(R.id.inputEqMax)

        val state = PeriodFilterState.get(this)
        setOptionalText(inputAmountMin, state.getAmountMin())
        setOptionalText(inputAmountMax, state.getAmountMax())
        setOptionalText(inputEqMin, state.getEqMin())
        setOptionalText(inputEqMax, state.getEqMax())

        // Валюта эквивалента общая для всех табов и выбирается в шапке — тут не рубли
        // жёстко, а то, что выбрано сейчас (см. BaseTabActivity.onPickCurrencyEq).
        val labelEqRange = findViewById<TextView>(R.id.labelEqRange)
        val currencyEq = state.getCurrencyEq(this)
        labelEqRange.text = if (currencyEq != null)
            getString(R.string.filter_eq_range_label_currency, currencyEq.iso)
        else
            getString(R.string.filter_eq_range_label)
    }

    // ── Счета ─────────────────────────────────────────────────────────────────

    /** Пришедшие ID раскладываются на кошельки и счета затрат — на экране это разные строки. */
    private fun restoreSelection(csvIds: String?) {
        val accounts = Singleton.instance.getAccounts(this)

        for (id in PeriodFilterState.parseIds(csvIds)) {
            val item = accounts.getAccountById(id) ?: continue

            (if (item.isOperatingAccount) selectedWallets else selectedCategories).add(item)
        }

        updateSummary(selectedCategories, categorySummary)
        updateSummary(selectedWallets, walletSummary)
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onPickCategories(view: View) {
        openPicker(getString(R.string.filter_picker_categories), MultiPickerActivity.cSourceExpanseAccounts,
                selectedCategories, requestPickCategories)
    }

    fun onPickWallets(view: View) {
        openPicker(getString(R.string.filter_picker_wallets), MultiPickerActivity.cSourceOperationAccounts,
                selectedWallets, requestPickWallets)
    }

    private fun openPicker(title: String, sourceType: Int, selected: List<AccountItem>, requestCode: Int) {
        val intent = Intent(this, MultiPickerActivity::class.java)
        intent.putExtra(MultiPickerActivity.cTitle, title)
        intent.putExtra(MultiPickerActivity.cSourceType, sourceType)
        intent.putExtra(MultiPickerActivity.cSelectedIds, joinIds(selected))

        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        val ids = MultiPickerActivity.parseIds(
                data.getStringExtra(MultiPickerActivity.cSelectedIds))

        val accounts = Singleton.instance.getAccounts(this)
        val target = if (requestCode == requestPickCategories)
            selectedCategories else selectedWallets

        target.clear()
        for (id in ids) {
            val item = accounts.getAccountById(id)
            if (item != null) target.add(item)
        }

        updateSummary(target, if (requestCode == requestPickCategories) categorySummary else walletSummary)
        updateApplyButtonCount()
    }

    private fun updateSummary(selected: List<AccountItem>, summary: TextView) {
        summary.text = if (selected.isEmpty()) getString(R.string.filter_all)
        else resources.getQuantityString(R.plurals.filter_selected_count, selected.size, selected.size)
    }

    fun onResetFilter(view: View) {
        selectedCategories.clear()
        selectedWallets.clear()
        categorySummary.setText(R.string.filter_all)
        walletSummary.setText(R.string.filter_all)

        selectedKind = DataFilter.OperationKind.all
        paintKind()

        inputSearchText.setText("")
        inputAmountMin.setText("")
        inputAmountMax.setText("")
        inputEqMin.setText("")
        inputEqMax.setText("")

        // Каждый setText выше уже дёргает watcher, но явный вызов не даёт положиться
        // на порядок срабатываний — последним всегда считаем по полностью сброшенному виду.
        updateApplyButtonCount()
    }

    fun onApplyFilter(view: View) {
        val state = PeriodFilterState.get(this)
        state.setOperationKind(this, selectedKind)
        state.setSearchText(this, inputSearchText.text.toString())
        state.setAmountRange(this, parseOptionalDouble(inputAmountMin), parseOptionalDouble(inputAmountMax))
        state.setEqRange(this, parseOptionalDouble(inputEqMin), parseOptionalDouble(inputEqMax))

        val allSelected = ArrayList<AccountItem>()
        allSelected.addAll(selectedCategories)
        allSelected.addAll(selectedWallets)

        val answerIntent = Intent()
        if (allSelected.isNotEmpty()) {
            val ids = StringBuilder()
            for (item in allSelected) {
                if (ids.isNotEmpty()) ids.append(",")
                ids.append(item.id)
            }
            answerIntent.putExtra(MainActivity.cFilterAccountList, ids.toString())
        }

        setResult(RESULT_OK, answerIntent)
        finish()
    }
}
