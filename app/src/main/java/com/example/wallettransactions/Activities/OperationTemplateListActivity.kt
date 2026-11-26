package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.RecurringWorker

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Ещё» → «Шаблоны операций» (openspec operation-templates): все шаблоны, в том числе
 * повторяющиеся (шаблон с расписанием). Тап — запуск ([OperationTemplateRunActivity]),
 * долгое нажатие — «Изменить» / «Удалить», переключатель справа (только у шаблона с
 * расписанием) — включить/выключить повтор без захода в форму.
 */
class OperationTemplateListActivity : AppCompatActivity() {

    companion object {
        private const val requestCodeEdit = 1

        private val DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))

        /** Поиск по названию как по тексту, без учёта регистра (спека «Список шаблонов»). */
        @JvmStatic
        internal fun matches(t: OperationTemplate, query: String): Boolean {
            val q = query.trim()
            return q.isEmpty() || t.name.contains(q, ignoreCase = true)
        }
    }

    private val mAll = ArrayList<OperationTemplate>()
    private val mItems = ArrayList<OperationTemplate>()
    private lateinit var mAdapter: TemplateAdapter
    private lateinit var mEmpty: TextView
    private lateinit var mSearch: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operation_template_list)

        val list = findViewById<ListView>(R.id.listTemplates)
        mEmpty = findViewById(R.id.templatesEmpty)
        mSearch = findViewById(R.id.templatesSearch)
        mAdapter = TemplateAdapter(this, mItems)
        list.adapter = mAdapter

        mSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
    }

    override fun onResume() {
        super.onResume()
        // Запуск и форма могли поменять «Следующая» и состав списка.
        reload()
    }

    /** «+ Добавить шаблон» на нижней панели. */
    fun onAddTemplate(view: View) {
        @Suppress("DEPRECATION")
        startActivityForResult(OperationTemplateActivity.buildIntent(this, 0), requestCodeEdit)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCodeEdit) reload()
    }

    fun btnUp(view: View) {
        finish()
    }

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    private fun reload() {
        mAll.clear()
        mAll.addAll(OperationTemplatesDb.loadAll(db()).sortedBy { it.name.lowercase() })
        applyFilter()
    }

    private fun applyFilter() {
        val query = mSearch.text.toString()
        mItems.clear()
        mItems.addAll(mAll.filter { matches(it, query) })
        mAdapter.notifyDataSetChanged()
        mEmpty.setText(if (mAll.isEmpty()) R.string.template_list_empty else R.string.search_nothing_found)
        mEmpty.visibility = if (mItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openRun(t: OperationTemplate) {
        startActivity(OperationTemplateRunActivity.buildIntent(this, t.id))
    }

    private fun openEdit(t: OperationTemplate) {
        @Suppress("DEPRECATION")
        startActivityForResult(OperationTemplateActivity.buildIntent(this, t.id), requestCodeEdit)
    }

    private fun showActions(t: OperationTemplate) {
        AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(arrayOf(getString(R.string.template_action_edit), getString(R.string.action_delete))) { _, which ->
                    if (which == 0) openEdit(t) else confirmDelete(t)
                }
                .show()
    }

    private fun confirmDelete(t: OperationTemplate) {
        AlertDialog.Builder(this)
                .setTitle(R.string.template_delete_question)
                .setMessage(R.string.template_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    OperationTemplatesDb.delete(db(), t.id)
                    RecurringWorker.kick(this)
                    reload()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /**
     * Включение — пауза снимается без досоздания пропущенного за время паузы
     * (см. [OperationTemplatesDb.setActive]); наступающие с этого момента повторы
     * заведёт [RecurringWorker] (или открытие таба, что раньше).
     */
    private fun toggleActive(t: OperationTemplate) {
        val s = t.schedule ?: return
        OperationTemplatesDb.setActive(db(), t, !s.isActive)
        RecurringWorker.kick(this)
        mAdapter.notifyDataSetChanged()
    }

    // ── Адаптер ───────────────────────────────────────────────────────────────

    private inner class TemplateAdapter(ctx: Context, items: List<OperationTemplate>)
        : ArrayAdapter<OperationTemplate>(ctx, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.operation_template_item_layout, parent, false)

            val t = getItem(position)!!
            val c = context
            val accounts = Singleton.instance.getAccounts(c)
            val main = t.mainStep()
            val from: AccountItem? = if (main != null) accounts.getAccountById(main.accFromId) else null
            val to: AccountItem? = if (main != null) accounts.getAccountById(main.accToId) else null

            val fromWallet = from != null && from.isOperatingAccount
            val toWallet = to != null && to.isOperatingAccount
            // «Другая сторона» — как на экране операции: статья у расхода, источник у дохода/перевода.
            val top = if (fromWallet && !toWallet) to else from

            val icon = v.findViewById<ImageView>(R.id.tplIcon)
            if (top != null) icon.setImageBitmap(top.icon!!.bitmap)
            else icon.setImageBitmap(Singleton.instance.getIconList(c).unknownIcon!!.bitmap)

            v.findViewById<TextView>(R.id.tplTitle).text = t.name
            v.findViewById<TextView>(R.id.tplAccounts).text = nameOf(from) + " → " + nameOf(to)

            val schedule = t.schedule
            val second = StringBuilder()
            if (schedule == null) {
                second.append(c.getString(R.string.template_manual))
            } else {
                second.append(schedule.describeSchedule(c))
                val endDate = schedule.endDate
                if (endDate != null) second.append(c.getString(R.string.recurring_until, endDate.format(DATE_UI)))
                if (schedule.isActive) {
                    val next = schedule.nextOccurrence(LocalDateTime.now())
                    second.append('\n').append(if (next != null)
                        c.getString(R.string.recurring_list_next, next.format(DATE_UI))
                    else
                        c.getString(R.string.recurring_list_finished))
                } else {
                    second.append('\n').append(c.getString(R.string.recurring_list_off))
                }
            }
            v.findViewById<TextView>(R.id.tplSchedule).text = second

            val amount = v.findViewById<TextView>(R.id.tplAmount)
            val total = t.totalAmount
            if (total == null) {
                amount.visibility = View.GONE
            } else {
                amount.visibility = View.VISIBLE
                val wallet: AccountOperatingItem? = if (fromWallet) from as AccountOperatingItem
                        else if (toWallet) to as AccountOperatingItem else null
                val precision = if (wallet != null) wallet.currency!!.precision else 2
                val sign = if (fromWallet && !toWallet) "−" else (if (!fromWallet && toWallet) "+" else "")
                amount.text = sign + String.format(Locale.US, "%,." + precision + "f", total.toDouble()) +
                        (if (wallet != null) " " + wallet.currency!!.symbol else "")
                val color = if (fromWallet && !toWallet) R.color.colorexpanse
                        else (if (!fromWallet && toWallet) R.color.colorprofit else R.color.colortransfer)
                amount.setTextColor(ContextCompat.getColor(c, color))
            }

            val sw = v.findViewById<FrameLayout>(R.id.tplSwitch)
            if (schedule == null) {
                sw.visibility = View.GONE
                sw.setOnClickListener(null)
            } else {
                sw.visibility = View.VISIBLE
                CommissionBlock.applySwitchState(sw, schedule.isActive)
                sw.setOnClickListener { toggleActive(t) }
            }

            val content = v.findViewById<View>(R.id.tplContent)
            content.alpha = if (schedule != null && !schedule.isActive) 0.5f else 1f
            content.setOnClickListener { openRun(t) }
            content.setOnLongClickListener {
                showActions(t)
                true
            }
            return v
        }

        private fun nameOf(item: AccountItem?): String {
            return if (item != null) item.displayName else "—"
        }
    }
}
