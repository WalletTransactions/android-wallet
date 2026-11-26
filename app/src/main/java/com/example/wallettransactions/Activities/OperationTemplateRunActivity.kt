package com.example.wallettransactions.Activities

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.DataItems.Operations.TemplateLayout
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Запуск шаблона» (openspec operation-templates, design D3): общая сумма, дата и время,
 * проводки по шагам с суммой каждой. Суммы считает [TemplateLayout.expand] — та же
 * раскладка, что у генератора; правка одной проводки пересчитывает «остаток»
 * ([TemplateLayout.edit]), правка «остатка» меняет общую сумму. «Сохранить» —
 * [OperationTemplatesDb.apply]: все проводки одной транзакцией. Расписание шаблона
 * ручной запуск не трогает.
 */
class OperationTemplateRunActivity : AppCompatActivity() {

    companion object {
        const val cTemplateId = "OperationTemplateRunActivity.Id"

        private val DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
        private val TIME_UI = DateTimeFormatter.ofPattern("HH:mm")

        @JvmStatic
        fun buildIntent(ctx: Context, templateId: Int): Intent =
            Intent(ctx, OperationTemplateRunActivity::class.java).putExtra(cTemplateId, templateId)

        /** Число из поля: пусто или мусор — null. */
        @JvmStatic
        internal fun parse(text: String): BigDecimal? {
            val s = text.replace(',', '.').trim()
            if (s.isEmpty()) return null
            return try {
                BigDecimal(s)
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    private lateinit var mTemplate: OperationTemplate
    private var mPrecision = 2
    private var mSymbol = ""
    private var mRestIndex = 0
    private var mAt: LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0)

    /** Суммы проводок по шагам; null — раскладки нет (сумма не введена или ошибочна). */
    private var mAmounts: List<BigDecimal>? = null
    private var mTotal: BigDecimal? = null
    /** Поля меняет сам экран — слушатели не должны принимать это за правку пользователя. */
    private var mUpdating = false

    private lateinit var mEditTotal: EditText
    private lateinit var mTvError: TextView
    private lateinit var mBtnSave: TextView
    private lateinit var mTvDate: TextView
    private lateinit var mTvTime: TextView
    private val mRowAmounts = ArrayList<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operation_template_run)

        val db = Singleton.instance.getWritableDatabase(this)
        val loaded = OperationTemplatesDb.load(db, intent.getIntExtra(cTemplateId, 0))
        if (loaded == null || loaded.mainStep() == null) {
            finish()
            return
        }
        mTemplate = loaded
        mRestIndex = mTemplate.steps.indexOf(mTemplate.mainStep())
        mPrecision = OperationTemplatesDb.precisionOf(db, mTemplate)

        val accounts = Singleton.instance.getAccounts(this)
        val wallet = mTemplate.steps.flatMap { listOf(it.accFromId, it.accToId) }
                .firstNotNullOfOrNull { accounts.getAccountById(it) as? AccountOperatingItem }
        mSymbol = wallet?.currency?.symbol ?: ""

        findViewById<TextView>(R.id.screenTitle).setText(R.string.template_run_title)
        findViewById<TextView>(R.id.tvTemplateName).text = mTemplate.name
        findViewById<TextView>(R.id.tvTotalCurrency).text = mSymbol
        mEditTotal = findViewById(R.id.editTotal)
        mTvError = findViewById(R.id.tvLayoutError)
        mBtnSave = findViewById(R.id.btnSaveRun)
        mTvDate = findViewById(R.id.tvRunDate)
        mTvTime = findViewById(R.id.tvRunTime)

        val container = findViewById<ViewGroup>(R.id.postings)
        val inflater = LayoutInflater.from(this)
        for ((i, s) in mTemplate.steps.withIndex()) {
            val row = inflater.inflate(R.layout.operation_template_run_row, container, false)
            val from = accounts.getAccountById(s.accFromId)?.displayName ?: "—"
            val to = accounts.getAccountById(s.accToId)?.displayName ?: "—"
            row.findViewById<TextView>(R.id.runAccounts).text = "$from → $to"
            row.findViewById<TextView>(R.id.runCurrency).text = mSymbol
            val edit = row.findViewById<EditText>(R.id.runAmount)
            edit.addTextChangedListener(watcher { if (!mUpdating) onPostingEdited(i) })
            mRowAmounts.add(edit)
            container.addView(row)
        }

        val total = mTemplate.totalAmount
        if (total != null) mEditTotal.setText(format(total))
        mEditTotal.addTextChangedListener(watcher { if (!mUpdating) onTotalEdited() })

        renderDate()
        onTotalEdited()
    }

    private fun watcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }

    fun btnUp(view: View) {
        finish()
    }

    private fun format(v: BigDecimal): String = v.setScale(mPrecision, RoundingMode.HALF_UP).toPlainString()

    // ── Раскладка ─────────────────────────────────────────────────────────────

    /** Общая сумма изменилась — суммы проводок раскладываются заново по режимам шаблона. */
    private fun onTotalEdited() {
        val total = parse(mEditTotal.text.toString())
        mTotal = total
        if (total == null || total.signum() <= 0) {
            mAmounts = null
            showAmounts(null)
            render(layoutError = false)
            return
        }
        when (val layout = TemplateLayout.expand(mTemplate.steps, total, mPrecision)) {
            is TemplateLayout.Result.Ok -> {
                mAmounts = layout.amounts
                showAmounts(layout.amounts)
                render(layoutError = false)
            }
            else -> {
                mAmounts = null
                showAmounts(null)
                render(layoutError = true)
            }
        }
    }

    /**
     * Поправили сумму одной проводки: «остаток» пересчитывается так, чтобы общая сумма
     * сохранилась; поправили сам «остаток» — меняется общая сумма. Режимы шаблона не меняются.
     */
    private fun onPostingEdited(index: Int) {
        val amounts = mAmounts ?: return
        val total = mTotal ?: return
        val value = parse(mRowAmounts[index].text.toString()) ?: BigDecimal.ZERO
        val (newAmounts, newTotal) = TemplateLayout.edit(amounts, mRestIndex, total, index, value)
        mAmounts = newAmounts
        mTotal = newTotal
        mUpdating = true
        try {
            if (index == mRestIndex) mEditTotal.setText(format(newTotal))
            else mRowAmounts[mRestIndex].setText(format(newAmounts[mRestIndex]))
        } finally {
            mUpdating = false
        }
        render(layoutError = newAmounts.any { it.signum() <= 0 })
    }

    private fun showAmounts(amounts: List<BigDecimal>?) {
        mUpdating = true
        try {
            for ((i, edit) in mRowAmounts.withIndex()) {
                edit.setText(if (amounts == null) "" else format(amounts[i]))
            }
        } finally {
            mUpdating = false
        }
    }

    private fun render(layoutError: Boolean) {
        mTvError.visibility = if (layoutError) View.VISIBLE else View.GONE
        val amounts = mAmounts
        val total = mTotal
        val enabled = !layoutError && amounts != null && total != null && total.signum() > 0 &&
                amounts.all { it.signum() > 0 }
        mBtnSave.isEnabled = enabled
        mBtnSave.alpha = if (enabled) 1f else 0.4f
    }

    /** Для тестов: доступна ли «Сохранить». */
    internal fun isSaveEnabled(): Boolean = mBtnSave.isEnabled

    /** Для тестов: текущие суммы проводок. */
    internal fun currentAmounts(): List<BigDecimal>? = mAmounts

    // ── Дата и время ──────────────────────────────────────────────────────────

    private fun renderDate() {
        mTvDate.text = mAt.format(DATE_UI)
        mTvTime.text = mAt.format(TIME_UI)
    }

    fun onPickDate(view: View) {
        val cal = java.util.Calendar.getInstance()
        cal.set(mAt.year, mAt.monthValue - 1, mAt.dayOfMonth)
        CalendarPicker.show(this, cal) { year, month, day ->
            mAt = LocalDate.of(year, month + 1, day).atTime(mAt.toLocalTime())
            renderDate()
        }
    }

    fun onPickTime(view: View) {
        TimePickerDialog(this, { _, hour, minute ->
            mAt = mAt.toLocalDate().atTime(hour, minute)
            renderDate()
        }, mAt.hour, mAt.minute, true).show()
    }

    // ── Сохранение ────────────────────────────────────────────────────────────

    fun onSaveClick(view: View) {
        if (!mBtnSave.isEnabled) return
        val amounts = mAmounts ?: return
        val ids = OperationTemplatesDb.apply(Singleton.instance.getWritableDatabase(this), mTemplate, amounts, mAt)
        if (ids == null) {
            Toast.makeText(this, R.string.recurring_error_save, Toast.LENGTH_SHORT).show()
            return
        }
        PeriodFilterState.get(this).markDataChanged()
        Toast.makeText(this, getString(R.string.recurring_created, ids.size), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
