package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Лист «Списать остаток?» при архивации кошелька (openspec archive-wallet-writeoff; по образцу
 * [RevaluationDialog]). Сам ничего не пишет: решение отдаёт [Listener], а экран
 * «Кошелёк» сохраняет правки и проводку одной транзакцией. Категорию выбирает
 * [AccountPickerActivity] — его результат приходит в `onActivityResult` экрана,
 * тот передаёт его сюда через [setCategory].
 *
 * @param message готовая строка «На кошельке «…» осталось …»
 */
class ArchiveWriteOffDialog(
    private val context: Context,
    private val message: String,
    private val listener: Listener
) {

    interface Listener {
        /** «Списать и архивировать»: категория выбрана, `day` — `yyyy-MM-dd`. */
        fun onWriteOff(categoryId: Int, day: String)

        /** «Архивировать без списания». */
        fun onArchiveWithoutWriteOff()

        /** Тап по полю категории: открыть выбор, `currentId` — 0, если не выбрана. */
        fun onPickCategory(currentId: Int)
    }

    private val pickedDate: Calendar = Calendar.getInstance()
    private var dialog: AlertDialog? = null
    private var edtCategory: EditText? = null
    private var categoryId = 0

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_archive_writeoff, null)
        view.findViewById<TextView>(R.id.textMessage).text = message
        val category = view.findViewById<EditText>(R.id.inputCategory)
        edtCategory = category
        val edtDate = view.findViewById<EditText>(R.id.inputDate)

        drawDate(edtDate)
        edtDate.setOnClickListener {
            CalendarPicker.show(context, pickedDate) { year, month, day ->
                pickedDate.set(year, month, day)
                drawDate(edtDate)
            }
        }
        category.setOnClickListener { listener.onPickCategory(categoryId) }

        val created = AlertDialog.Builder(context).setView(view).create()
        dialog = created
        view.findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { created.dismiss() }
        view.findViewById<android.view.View>(R.id.btnSkip).setOnClickListener { listener.onArchiveWithoutWriteOff() }
        view.findViewById<android.view.View>(R.id.btnWriteOff).setOnClickListener {
            if (categoryId <= 0) {
                category.error = context.getString(R.string.archive_writeoff_category_hint)
                return@setOnClickListener
            }
            listener.onWriteOff(categoryId, DateUtils.DateToDateStr(pickedDate.time))
        }
        created.show()
    }

    /** Выбор из [AccountPickerActivity]. */
    fun setCategory(category: AccountItem?) {
        val field = edtCategory
        if (category == null || field == null) return
        categoryId = category.id
        field.setText(category.displayName)
        field.error = null
    }

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun dismiss() {
        if (isShowing) dialog?.dismiss()
    }

    private fun drawDate(field: EditText) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.time))
    }

    companion object {
        /**
         * Показывать ли лист (design D4, D5): кошелёк, не актив ни до, ни после сохранения,
         * архив ставится сейчас, остаток (уже округлённый до точности валюты) не 0.
         */
        @JvmStatic
        fun isOffered(
            isWallet: Boolean, wasArchived: Boolean, archiveNow: Boolean,
            assetBefore: Boolean, assetAfter: Boolean, roundedBalance: Double
        ): Boolean {
            return isWallet && !wasArchived && archiveNow && !assetBefore && !assetAfter && roundedBalance != 0.0
        }

        private val DISPLAY_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }
}
