package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.R
import java.util.Calendar
import java.util.Locale

/**
 * Поля признака займа (`view_debt_fields.xml`, openspec debts): «Заём», контрагент,
 * срок возврата, комментарий. Нужна, чтобы уже заведённые кошельки-люди («Кулигин · EUR»)
 * стали займами со всей своей историей — запись `WalletDbCRUD.SetAccountDebt` /
 * `ClearAccountDebt`. С признаком актива взаимоисключающий: пока отмечено одно,
 * флажок другого скрыт (спека, «Кошелёк уже актив»).
 *
 * Новый контрагент в базу не пишется до «Сохранить»: экран можно закрыть без записи.
 * Его имя держится в [newCounterpartyName], создаёт его [save].
 */
internal class DebtFields(
    private val activity: Activity,
    private val original: AccountOperatingItem.DebtInfo?,
    private val walletName: String?,
    private val onChange: Runnable?
) {

    private val checkIsDebt: CheckBox = activity.findViewById(R.id.checkIsDebt)
    private val details: View = activity.findViewById(R.id.debtDetails)
    private val counterpartyView: TextView = activity.findViewById(R.id.debtCounterparty)
    private val dueView: TextView = activity.findViewById(R.id.debtDueDate)
    private val commentView: EditText = activity.findViewById(R.id.debtComment)
    private val assetFields: View? = activity.findViewById(R.id.assetFields)
    private val checkIsAsset: CheckBox? = activity.findViewById(R.id.checkIsAsset)

    private var counterpartyUuid: String? = null
    private var counterpartyName: String? = null

    /** Не пусто — выбран «Новый контрагент», в базе его ещё нет. */
    private var newCounterpartyName: String? = null
    private var dueDate: String? = null

    init {
        if (original != null) {
            checkIsDebt.isChecked = true
            counterpartyUuid = original.counterpartyUuid
            counterpartyName = nameOf(original.counterpartyUuid)
            dueDate = original.dueDate
            commentView.setText(original.comment)
        }
        refresh()

        checkIsDebt.setOnCheckedChangeListener { _, checked ->
            if (checked && counterpartyUuid == null && newCounterpartyName == null) suggestCounterparty()
            refresh()
            changed()
        }
        counterpartyView.setOnClickListener { pickCounterparty() }
        dueView.setOnClickListener { pickDueDate() }
        commentView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                changed()
            }
        })
    }

    fun isDebt(): Boolean = checkIsDebt.isChecked

    /** Отличается ли выбор от исходного признака займа. */
    fun differs(): Boolean {
        if (original == null) return isDebt()
        if (!isDebt()) return true
        return newCounterpartyName != null
                || counterpartyUuid != original.counterpartyUuid
                || dueDate != original.dueDate
                || comment() != original.comment
    }

    /** Можно ли сохранить: заём без контрагента не пишется. */
    fun validate(): Boolean {
        if (!isDebt() || counterpartyUuid != null || newCounterpartyName != null) return true
        Toast.makeText(activity, R.string.debt_error_no_counterparty, Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * Запись признака — в транзакции вызывающего. Новый контрагент создаётся здесь же;
     * если имя тем временем занял другой (синк), берётся существующий.
     *
     * @return false — не записалось (контрагент не создан или кошелёк не подходит)
     */
    fun save(db: SQLiteDatabase, accountId: Int): Boolean {
        if (!differs()) return true
        if (!isDebt()) {
            WalletDbCRUD.ClearAccountDebt(db, accountId)
            return true
        }
        var uuid = counterpartyUuid
        val newName = newCounterpartyName
        if (newName != null) {
            var id = WalletDbCRUD.GetCounterpartyIdByName(db, newName)
            if (id <= 0) id = WalletDbCRUD.CreateCounterparty(db, newName, "")
            if (id <= 0) return false
            uuid = WalletDbCRUD.CounterpartyUuid(db, id)
        }
        return uuid != null && WalletDbCRUD.SetAccountDebt(db, accountId, uuid, comment(), dueDate) == true
    }

    // ── выбор ──────────────────────────────────────────────────────────────

    /**
     * При первом включении — контрагент по имени кошелька («Кулигин · EUR» → «Кулигин»):
     * есть такой — он, нет — новый с этим именем (в базу — только по «Сохранить»).
     */
    private fun suggestCounterparty() {
        val suggested = WalletDbCRUD.SuggestCounterpartyName(walletName)
        if (suggested.isEmpty()) return
        val db = WalletDbHelper(activity).readableDatabase
        val id = WalletDbCRUD.GetCounterpartyIdByName(db, suggested)
        if (id > 0) {
            for (c in WalletDbLoader.LoadCounterparties(db)) {
                if (c.id == id) {
                    selectExisting(c)
                    return
                }
            }
        }
        newCounterpartyName = suggested
        counterpartyName = suggested
    }

    private fun pickCounterparty() {
        activity.startActivityForResult(
            CounterpartiesActivity.buildPickIntent(activity, counterpartyUuid), REQUEST_PICK_COUNTERPARTY
        )
    }

    /**
     * Результат выбора на экране «Контрагенты».
     *
     * @return true — результат этого поля (хосту дальше разбирать нечего)
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK_COUNTERPARTY) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true // отмена — выбор прежний
        val uuid = data.getStringExtra(CounterpartiesActivity.RESULT_UUID)
        val db = WalletDbHelper(activity).readableDatabase
        for (c in WalletDbLoader.LoadCounterparties(db)) {
            if (c.uuid == uuid) {
                selectExisting(c)
                refresh()
                changed()
                break
            }
        }
        return true
    }

    private fun pickDueDate() {
        val start = Calendar.getInstance()
        val due = dueDate
        if (due != null) {
            val p = due.split("-")
            start.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        }
        if (due != null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.debt_due_label)
                .setItems(
                    arrayOf(
                        activity.getString(R.string.debt_due_label),
                        activity.getString(R.string.debt_due_clear)
                    )
                ) { _, which ->
                    if (which == 1) {
                        dueDate = null
                        refresh()
                        changed()
                    } else showCalendar(start)
                }
                .show()
        } else {
            showCalendar(start)
        }
    }

    private fun showCalendar(start: Calendar) {
        CalendarPicker.show(activity, start) { y, m, d ->
            dueDate = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)
            refresh()
            changed()
        }
    }

    // ── вид ─────────────────────────────────────────────────────────────────

    /** Перерисовать; зовёт и экран при смене флажка актива (его слушает AssetFields). */
    fun refresh() {
        val debt = checkIsDebt.isChecked
        details.visibility = if (debt) View.VISIBLE else View.GONE
        // Актив и заём — взаимоисключающие: пока отмечено одно, другого не предлагаем.
        assetFields?.visibility = if (debt) View.GONE else View.VISIBLE
        val asset = checkIsAsset != null && checkIsAsset.isChecked
        checkIsDebt.visibility = if (asset) View.GONE else View.VISIBLE
        counterpartyView.text = counterpartyName ?: activity.getString(R.string.debt_counterparty_none)
        dueView.text = dueDate ?: activity.getString(R.string.debt_due_none)
    }

    private fun selectExisting(c: CounterpartyItem) {
        counterpartyUuid = c.uuid
        counterpartyName = c.name
        newCounterpartyName = null
    }

    private fun nameOf(uuid: String?): String? {
        val db = WalletDbHelper(activity).readableDatabase
        for (c in WalletDbLoader.LoadCounterparties(db)) {
            if (c.uuid == uuid) return c.name
        }
        return null
    }

    private fun comment(): String = commentView.text.toString().trim()

    private fun changed() {
        onChange?.run()
    }

    companion object {
        /** Код запроса экрана «Контрагенты»; хост отдаёт сюда onActivityResult (см. [onActivityResult]). */
        const val REQUEST_PICK_COUNTERPARTY = 61
    }
}
