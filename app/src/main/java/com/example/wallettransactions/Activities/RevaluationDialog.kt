package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.SyncRunner

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Диалог «Переоценка» актива (openspec capital-assets, design п. 8; по образцу
 * [RateEditDialog]). Δ = новая оценка − баланс актива по всем его проводкам до конца
 * выбранного дня; запись — [WalletDbCRUD.WriteRevaluation] (одна проводка через
 * системную «Переоценку», на конец дня, комментарий «Оценка: …»). После записи —
 * `markDataChanged()` и синк в фоне, как «Записать» на экране «ИИ».
 */
class RevaluationDialog(private val context: Context,
                        private val asset: AccountOperatingItem,
                        private val listener: OnSaved?) {

    fun interface OnSaved {
        fun onRevaluationSaved()
    }

    companion object {
        private val DISPLAY_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        private fun parse(text: String): Double? {
            val s = text.trim().replace(',', '.').replace(" ", "")
            if (s.isEmpty() || s == "-") return null
            return try {
                java.lang.Double.parseDouble(s)
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    private val pickedDate: Calendar = Calendar.getInstance()
    private var dialog: AlertDialog? = null

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_revaluation, null)
        val edtDate = view.findViewById<EditText>(R.id.inputDate)
        val edtValue = view.findViewById<EditText>(R.id.inputValue)
        val tvDelta = view.findViewById<TextView>(R.id.textCurrentDelta)

        drawDate(edtDate)
        val refresh = Runnable { tvDelta.text = deltaLine(edtValue.text.toString()) }
        edtDate.setOnClickListener {
            CalendarPicker.show(context, pickedDate) { year, month, day ->
                pickedDate.set(year, month, day)
                drawDate(edtDate)
                refresh.run()
            }
        }
        edtValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh.run() }
        })
        refresh.run()

        val dlg = AlertDialog.Builder(context).setView(view).create()
        dialog = dlg
        view.findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        view.findViewById<android.view.View>(R.id.btnSave).setOnClickListener { save(edtValue) }
        dlg.show()
    }

    private fun drawDate(field: EditText) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.time))
    }

    private fun dayStr(): String = DateUtils.DateToDateStr(pickedDate.time)

    private fun currentBalance(): Double =
            WalletDbLoader.LoadAccountBalanceAt(db(), asset.id, dayStr() + " 23:59")

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(context)

    private fun precision(): Int {
        val c = asset.currency
        return if (c != null) c.precision else 2
    }

    private fun money(value: Double): String {
        val c = asset.currency
        return AdapterUtils.getFormat(precision()).format(value) + " " + (if (c != null) c.symbol else "")
    }

    /** «Сейчас: 10 000 000 ₽ · Разница: +2 000 000 ₽». */
    fun deltaLine(input: String): String {
        val current = currentBalance()
        val value = parse(input)
        if (value == null) return context.getString(R.string.revaluation_current, money(current))
        val delta = round(value - current)
        val sign = if (delta > 0) "+" else if (delta < 0) "−" else ""
        return context.getString(R.string.revaluation_current_delta, money(current), sign + money(Math.abs(delta)))
    }

    private fun save(edtValue: EditText) {
        val value = parse(edtValue.text.toString())
        if (value == null) {
            edtValue.error = context.getString(R.string.revaluation_enter_value)
            return
        }
        if (value < 0) {
            edtValue.error = context.getString(R.string.revaluation_negative)
            return
        }
        if (BigDecimal.valueOf(value).stripTrailingZeros().scale() > precision()) {
            edtValue.error = context.getString(R.string.revaluation_too_precise, precision())
            return
        }
        val result = WalletDbCRUD.WriteRevaluation(db(), asset.id, dayStr(),
                value, precision(), context.getString(R.string.revaluation_comment, money(value)))
        when (result) {
            WalletDbCRUD.RevaluationResult.unchanged -> {
                Toast.makeText(context, R.string.revaluation_unchanged, Toast.LENGTH_SHORT).show()
                return
            }
            WalletDbCRUD.RevaluationResult.negative -> {
                edtValue.error = context.getString(R.string.revaluation_negative)
                return
            }
            WalletDbCRUD.RevaluationResult.failed -> {
                Toast.makeText(context, R.string.revaluation_failed, Toast.LENGTH_LONG).show()
                return
            }
            else -> {}
        }
        Singleton.instance.reloadAccounts(context)
        PeriodFilterState.get(context).markDataChanged()
        val app = context.applicationContext
        Thread { SyncRunner.runOnce(app) }.start()
        dialog?.dismiss()
        listener?.onRevaluationSaved()
    }

    private fun round(v: Double): Double =
            BigDecimal.valueOf(v).setScale(precision(), RoundingMode.HALF_UP).toDouble()
}
