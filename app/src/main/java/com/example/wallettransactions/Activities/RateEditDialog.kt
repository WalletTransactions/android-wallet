package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * "Добавить/Редактировать курс" — one sheet (dialog_edit_rate.xml) for both, per its own
 * design comment: dialogTitle and btnDelete's visibility just adapt to the mode.
 */
class RateEditDialog private constructor(
        private val context: Context,
        private val currencyPairId: Int,
        private val rateDate: Date,
        private val rateValue: Double,
        private val isNew: Boolean,
        private val listener: OnRateSavedListener) {

    private var dialog: AlertDialog? = null

    /** Выбранная дата целиком — не разбираем её обратно из текста поля. */
    private val pickedDate: Calendar = Calendar.getInstance()

    interface OnRateSavedListener {
        fun onRateSaved()
        fun onRateDeleted()
    }

    /** Edit an existing rate. */
    constructor(context: Context, currencyPairId: Int, rateDate: Date, rateValue: Double,
                listener: OnRateSavedListener)
            : this(context, currencyPairId, rateDate, rateValue, false, listener)

    private constructor(context: Context, currencyPairId: Int, listener: OnRateSavedListener)
            : this(context, currencyPairId, Date(), 0.0, true, listener)

    companion object {
        private val DISPLAY_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        /** Add a new rate — date defaults to today, value starts blank. */
        @JvmStatic
        fun newRate(context: Context, currencyPairId: Int, listener: OnRateSavedListener): RateEditDialog {
            return RateEditDialog(context, currencyPairId, listener)
        }
    }

    fun show() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_edit_rate, null)

        val edtDate = view.findViewById<EditText>(R.id.inputDate)
        val edtValue = view.findViewById<EditText>(R.id.inputRate)
        val btnDelete = view.findViewById<View>(R.id.btnDelete)

        view.findViewById<TextView>(R.id.dialogTitle)
                .setText(if (isNew) R.string.rate_dialog_add else R.string.rate_dialog_edit)

        pickedDate.time = rateDate
        drawDate(edtDate)
        edtDate.setOnClickListener {
            CalendarPicker.show(context, pickedDate) { year, month, day ->
                pickedDate.set(year, month, day)
                drawDate(edtDate)
            }
        }

        if (isNew) {
            edtValue.setText("")
            btnDelete.visibility = View.GONE
        } else {
            edtValue.setText(String.format("%.5f", rateValue))
            btnDelete.visibility = View.VISIBLE
        }

        val builder = AlertDialog.Builder(context)
        builder.setView(view)
        val dlg = builder.create()
        dialog = dlg

        btnDelete.setOnClickListener { deleteRate() }
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener { saveRate(edtValue) }

        dlg.show()
    }

    private fun drawDate(field: EditText) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.time))
    }

    private fun saveRate(edtValue: EditText) {
        val valueStr = edtValue.text.toString().trim().replace(',', '.')

        if (valueStr.isEmpty()) {
            Toast.makeText(context, R.string.rate_error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Дата приходит из календаря, а не из текста поля — разбирать нечего.
            val newDate = pickedDate.time
            val newValue = java.lang.Double.parseDouble(valueStr)

            val db: SQLiteDatabase = WalletDbHelper(context).writableDatabase
            try {
                if (isNew) {
                    // v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — CONFLICT_REPLACE,
                    // а для SQLite это "удалить старую строку целиком и вставить новую" —
                    // при случайном совпадении дат уже существующий uuid терялся бы и в
                    // потоке синка появлялся бы дубль вместо одного обновления. Тот же
                    // приём, что теперь и в WalletDbCRUD.InsertOrReplaceRate: сперва пробуем
                    // обновить (uuid сохраняется, dirty форсит триггер), и только если
                    // строки ещё нет — вставляем с новым uuid.
                    val dateStr = DateUtils.DateToDateStr(newDate)
                    val updateValues = ContentValues()
                    updateValues.put("rate", newValue)
                    // deleted_at IS NULL — реальный баг, найденный на живом
                    // устройстве (2026-09-11, WalletFillData.InsertCurrencyPairRate,
                    // тот же класс здесь): без этого условия matcher "оживлял" мягко
                    // удалённую строку (rate обновлялся, deleted_at оставался
                    // проставленным) — на сервере та же строка тоже мягко удалена и
                    // потому не покрыта частичным индексом uq_currency_pair_rate_ledger
                    // (WHERE deleted_at IS NULL), ON CONFLICT по нему не находил
                    // "конфликта" и падал на currency_pair_rate_pkey голым INSERT'ом
                    // с уже занятым uuid ("duplicate_key"). С фильтром мёртвая строка
                    // для матчинга не существует — INSERT ниже заведёт новую с
                    // свежим uuid, старая тень остаётся как есть.
                    val updated = db.update("currency_pair_rate", updateValues,
                            "currency_pair_id = ? AND date = ? AND deleted_at IS NULL",
                            arrayOf(currencyPairId.toString(), dateStr))

                    val rowId: Long
                    if (updated > 0) {
                        rowId = 1 // строка уже была — обновили, а не вставили
                    } else {
                        val values = ContentValues()
                        values.put("currency_pair_id", currencyPairId)
                        values.put("date", dateStr)
                        values.put("rate", newValue)
                        values.put("uuid", UUID.randomUUID().toString())
                        rowId = db.insert("currency_pair_rate", null, values)
                    }

                    if (rowId >= 0) {
                        listener.onRateSaved()
                        dialog?.dismiss()
                        Toast.makeText(context, R.string.rate_added, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.rate_error_add, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val values = ContentValues()
                    values.put("date", DateUtils.DateToDateStr(newDate))
                    values.put("rate", newValue)

                    // Match on the date part only: rates are stored as plain 'yyyy-MM-dd', so
                    // comparing against a full 'yyyy-MM-dd HH:mm' string never matched any row
                    // and the edit silently no-op'd.
                    val rows = db.update("currency_pair_rate", values,
                            "currency_pair_id = ? AND substr(date, 1, 10) = ?",
                            arrayOf(currencyPairId.toString(), DateUtils.DateToDateStr(rateDate)))

                    if (rows > 0) {
                        listener.onRateSaved()
                        dialog?.dismiss()
                        Toast.makeText(context, R.string.rate_updated, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.rate_not_found, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.error_with_reason, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRate() {
        AlertDialog.Builder(context)
                .setTitle(R.string.rate_delete_question)
                .setMessage(DateUtils.DateToStr(rateDate) + " - " + String.format("%.5f", rateValue))
                .setPositiveButton(R.string.action_delete) { _, _ -> doDeleteRate() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /**
     * Мягкое удаление (см. javadoc WalletDbCRUD.DeleteOperation, тот же довод) —
     * `deleted_at`, dirty=1 форсит триггер, доедет до сервера/других устройств
     * следующим синком.
     */
    private fun doDeleteRate() {
        try {
            val db = WalletDbHelper(context).writableDatabase
            val values = ContentValues()
            values.put("deleted_at", System.currentTimeMillis())
            val rows = db.update("currency_pair_rate", values,
                    "currency_pair_id = ? AND substr(date, 1, 10) = ?",
                    arrayOf(currencyPairId.toString(), DateUtils.DateToDateStr(rateDate)))
            db.close()

            if (rows > 0) {
                listener.onRateDeleted()
                dialog?.dismiss()
                Toast.makeText(context, R.string.rate_deleted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.rate_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.rate_error_delete, e.message), Toast.LENGTH_SHORT).show()
        }
    }
}
