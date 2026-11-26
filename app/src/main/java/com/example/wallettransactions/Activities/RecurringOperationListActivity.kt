package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import com.example.wallettransactions.Data.RecurringOperationsDb
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Operations.RecurringOperation
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.RecurringWorker

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Ещё» → «Повторяющиеся операции»: список правил (`recurring_operation`).
 * Тап — правка ([RecurringOperationActivity]), долгое нажатие — удаление,
 * переключатель справа — включить/выключить повтор без захода в правило.
 */
class RecurringOperationListActivity : AppCompatActivity() {

    companion object {
        private const val requestCodeEdit = 1

        private val DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
    }

    private val mItems = ArrayList<RecurringOperation>()
    private lateinit var mAdapter: RecurringAdapter
    private lateinit var mEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_operation_list)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.recurring_list_title)

        val list = findViewById<ListView>(R.id.listRecurring)
        mEmpty = findViewById(R.id.emptyRecurring)
        mAdapter = RecurringAdapter(this, mItems)
        list.adapter = mAdapter

        reload()
    }

    /** Строка-«+» под списком (activity_recurring_operation_list.xml). */
    fun onAddRecurring(view: View) {
        @Suppress("DEPRECATION")
        startActivityForResult(RecurringOperationActivity.buildIntent(this, 0), requestCodeEdit)
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
        mItems.clear()
        mItems.addAll(RecurringOperationsDb.loadAll(db()))
        mAdapter.notifyDataSetChanged()
        mEmpty.visibility = if (mItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEdit(r: RecurringOperation) {
        @Suppress("DEPRECATION")
        startActivityForResult(RecurringOperationActivity.buildIntent(this, r.id), requestCodeEdit)
    }

    private fun confirmDelete(r: RecurringOperation) {
        AlertDialog.Builder(this)
                .setTitle(R.string.recurring_delete_question)
                .setMessage(R.string.recurring_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    RecurringOperationsDb.delete(db(), r.id)
                    RecurringWorker.kick(this)
                    reload()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /**
     * Включение — пауза снимается без досоздания пропущенного за время паузы
     * (см. [RecurringOperationsDb.setActive]); операции, наступающие с этого
     * момента, заведёт [RecurringWorker] (или открытие таба, что раньше).
     */
    private fun toggleActive(r: RecurringOperation) {
        RecurringOperationsDb.setActive(db(), r, !r.isActive)
        RecurringWorker.kick(this)
        mAdapter.notifyDataSetChanged()
    }

    // ── Адаптер ───────────────────────────────────────────────────────────────

    private inner class RecurringAdapter(ctx: Context, items: List<RecurringOperation>)
        : ArrayAdapter<RecurringOperation>(ctx, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.recurring_operation_item_layout, parent, false)

            val r = getItem(position)!!
            val accounts = Singleton.instance.getAccounts(context)
            val from: AccountItem? = accounts.getAccountById(r.accFromId)
            val to: AccountItem? = accounts.getAccountById(r.accToId)

            val fromWallet = from != null && from.isOperatingAccount
            val toWallet = to != null && to.isOperatingAccount
            // «Другая сторона» — как на экране операции: статья у расхода, источник у дохода/перевода.
            val top = if (fromWallet && !toWallet) to else from

            val icon = v.findViewById<ImageView>(R.id.recIcon)
            if (top != null) icon.setImageBitmap(top.icon!!.bitmap)
            else icon.setImageBitmap(Singleton.instance.getIconList(context).unknownIcon!!.bitmap)

            val comment: String? = r.comment
            val title = if (comment != null && !comment.trim().isEmpty())
                comment.trim()
            else
                (if (top != null) top.displayName else context.getString(R.string.recurring_account_deleted))
            v.findViewById<TextView>(R.id.recTitle).text = title
            v.findViewById<TextView>(R.id.recAccounts).text = nameOf(from) + " → " + nameOf(to)

            val c = context
            val schedule = StringBuilder(r.describeSchedule(c))
            val endDate = r.endDate
            if (endDate != null) schedule.append(c.getString(R.string.recurring_until, endDate.format(DATE_UI)))
            if (r.isActive) {
                val next = r.nextOccurrence(LocalDateTime.now())
                schedule.append('\n').append(if (next != null)
                    c.getString(R.string.recurring_list_next, next.format(DATE_UI))
                else
                    c.getString(R.string.recurring_list_finished))
            } else {
                schedule.append('\n').append(c.getString(R.string.recurring_list_off))
            }
            v.findViewById<TextView>(R.id.recSchedule).text = schedule

            val amount = v.findViewById<TextView>(R.id.recAmount)
            val wallet: AccountOperatingItem? = if (fromWallet) from as AccountOperatingItem
                    else if (toWallet) to as AccountOperatingItem else null
            val precision = if (wallet != null) wallet.currency!!.precision else 2
            val sign = if (fromWallet && !toWallet) "−" else (if (!fromWallet && toWallet) "+" else "")
            amount.text = sign + String.format(Locale.US, "%,." + precision + "f", r.amount) +
                    (if (wallet != null) " " + wallet.currency!!.symbol else "")
            val color = if (fromWallet && !toWallet) R.color.colorexpanse
                    else (if (!fromWallet && toWallet) R.color.colorprofit else R.color.colortransfer)
            amount.setTextColor(ContextCompat.getColor(context, color))

            val sw = v.findViewById<FrameLayout>(R.id.recSwitch)
            applySwitchState(sw, r.isActive)
            sw.setOnClickListener { toggleActive(r) }

            val content = v.findViewById<View>(R.id.recContent)
            content.alpha = if (r.isActive) 1f else 0.5f
            content.setOnClickListener { openEdit(r) }
            content.setOnLongClickListener {
                confirmDelete(r)
                true
            }
            return v
        }

        private fun nameOf(item: AccountItem?): String {
            return if (item != null) item.displayName else "—"
        }

        private fun applySwitchState(switchView: FrameLayout, on: Boolean) {
            switchView.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off)
            val thumb = switchView.getChildAt(0) ?: return
            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (on) Gravity.END else Gravity.START
            thumb.layoutParams = lp
        }
    }
}
