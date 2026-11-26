package com.example.wallettransactions.Activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.DebtsList
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Карточка контрагента (openspec counterparty-card, спека debts «Карточка контрагента»): иконка,
 * имя, комментарий, контакт телефона, итог по займам и список займов. Тап по займу открывает
 * карточку этого долга-кошелька ([AccountItemActivity]), где операции займа доступны
 * кнопкой-фильтром. Всё локальное: без сети карточка работает так же.
 *
 * Имя и комментарий сохраняются при уходе с экрана ([onPause]); дубль имени — отказ и
 * прежнее имя. Контакт привязывается системным `ACTION_PICK` без разрешения
 * `READ_CONTACTS`, поэтому имя контакта не читается — карточка показывает только факт привязки.
 */
class CounterpartyCardActivity : AppCompatActivity() {

    private var counterpartyId = 0
    private var uuid: String? = null
    private var archived = false
    private var savedName: String = ""
    private var savedComment: String = ""

    private lateinit var iconView: ImageView
    private lateinit var nameEdit: EditText
    private lateinit var commentEdit: EditText
    private lateinit var contactStatus: TextView
    private lateinit var btnBind: Button
    private lateinit var btnOpen: Button
    private lateinit var btnUnbind: Button
    private lateinit var totalView: TextView
    private lateinit var loansBox: LinearLayout
    private lateinit var btnArchive: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_counterparty_card)
        findViewById<TextView>(R.id.screenTitle).setText(R.string.counterparty_card_title)

        iconView = findViewById(R.id.counterpartyIcon)
        nameEdit = findViewById(R.id.counterpartyName)
        commentEdit = findViewById(R.id.counterpartyComment)
        contactStatus = findViewById(R.id.counterpartyContactStatus)
        btnBind = findViewById(R.id.btnContactBind)
        btnOpen = findViewById(R.id.btnContactOpen)
        btnUnbind = findViewById(R.id.btnContactUnbind)
        totalView = findViewById(R.id.counterpartyTotal)
        loansBox = findViewById(R.id.counterpartyLoans)
        btnArchive = findViewById(R.id.btnCounterpartyArchive)

        counterpartyId = intent.getIntExtra(ARG_COUNTERPARTY_ID, 0)
        val item = load()
        if (item == null) { // контрагента удалили (например, синком), пока список был открыт
            finish()
            return
        }
        uuid = item.uuid
        nameEdit.setText(item.name)
        commentEdit.setText(item.comment)
        savedName = item.name
        savedComment = item.comment
    }

    override fun onResume() {
        super.onResume()
        if (uuid == null) return
        // Вернулись из карточки займа или выбора контакта: займы, контакт и иконку перечитываем,
        // поля имени и комментария не трогаем — пользователь мог не закончить правку.
        val item = load()
        if (item == null) {
            finish()
            return
        }
        archived = item.isArchived
        renderIcon(item)
        renderContact()
        renderArchiveButton()
        renderLoans()
    }

    override fun onPause() {
        super.onPause()
        saveEdits()
    }

    fun btnUp(view: View) {
        finish()
    }

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    private fun load(): CounterpartyItem? {
        for (c in WalletDbLoader.LoadCounterparties(db())) {
            if (c.id == counterpartyId) return c
        }
        return null
    }

    private fun changed() {
        PeriodFilterState.get(this).markDataChanged()
    }

    // ── имя и комментарий ─────────────────────────────────────────────────────

    /** Имя с проверкой дубля (прежнее остаётся) и комментарий; вызывается при уходе с экрана. */
    private fun saveEdits() {
        if (uuid == null) return
        val db = db()
        val name = WalletDbCRUD.NormalizeCounterpartyName(nameEdit.text.toString()) ?: ""
        if (name.isNotEmpty() && name != savedName) {
            if (WalletDbCRUD.RenameCounterparty(db, counterpartyId, name)) {
                savedName = name
                changed()
            } else {
                Toast.makeText(this, getString(R.string.counterparty_name_taken, name), Toast.LENGTH_LONG).show()
                nameEdit.setText(savedName)
            }
        } else if (name.isEmpty()) {
            nameEdit.setText(savedName)
        }
        val comment = commentEdit.text.toString().trim()
        if (comment != savedComment) {
            WalletDbCRUD.UpdateCounterpartyComment(db, counterpartyId, comment)
            savedComment = comment
            changed()
        }
    }

    // ── иконка ────────────────────────────────────────────────────────────────

    fun pickIconClick(view: View) {
        val item = load()
        var current = if (item != null) WalletDbCRUD.IconIdByUuid(db(), item.iconUuid) else -1
        if (current <= 0) current = Singleton.instance.getIconList(this).unknownIcon!!.id
        startActivityForResult(
            IconPickerActivity.buildIntent(
                this,
                getString(R.string.icon_title_counterparty), Icon.IconType.CATEGORY, current
            ), REQUEST_PICK_ICON
        )
    }

    private fun renderIcon(item: CounterpartyItem) {
        val iconId = WalletDbCRUD.IconIdByUuid(db(), item.iconUuid)
        val icon = Singleton.instance.getIconList(this).getIconByIdOrUnknown(if (iconId > 0) iconId else null)
        iconView.setImageBitmap(icon?.bitmap)
    }

    // ── контакт телефона ──────────────────────────────────────────────────────

    private fun renderContact() {
        val linked = WalletDbCRUD.GetCounterpartyContact(db(), uuid!!) != null
        contactStatus.setText(if (linked) R.string.counterparty_contact_linked else R.string.counterparty_contact_none)
        btnBind.setText(if (linked) R.string.counterparty_contact_change else R.string.counterparty_contact_bind)
        btnOpen.visibility = if (linked) View.VISIBLE else View.GONE
        btnUnbind.visibility = if (linked) View.VISIBLE else View.GONE
        // Одна кнопка «Привязать» занимает весь ряд — без зазора справа, как у остальных.
        val bindParams = btnBind.layoutParams as LinearLayout.LayoutParams
        bindParams.marginEnd = if (linked) dp(8) else 0
        btnBind.layoutParams = bindParams
    }

    /** «Привязать» / «Сменить»: системный выбор контакта, без разрешения на чтение контактов. */
    fun onContactBind(view: View) {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                REQUEST_PICK_CONTACT
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show()
        }
    }

    fun onContactOpen(view: View) {
        val lookup = WalletDbCRUD.GetCounterpartyContact(db(), uuid!!) ?: return
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show()
        }
    }

    fun onContactUnbind(view: View) {
        WalletDbCRUD.ClearCounterpartyContact(db(), uuid!!)
        renderContact()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Отмена выбора — ничего не меняем (спека «Связь контрагента с контактом»).
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQUEST_PICK_ICON) {
            val iconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, -1)
            if (iconId > 0 && WalletDbCRUD.SetCounterpartyIcon(db(), counterpartyId, iconId)) changed()
        } else if (requestCode == REQUEST_PICK_CONTACT && data.data != null) {
            var lookup: String? = null
            try {
                contentResolver.query(
                    data.data!!,
                    arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null
                ).use { c ->
                    if (c != null && c.moveToFirst()) lookup = c.getString(0)
                }
            } catch (e: SecurityException) {
                lookup = null
            }
            val key = lookup
            if (key != null && key.isNotEmpty()) WalletDbCRUD.SetCounterpartyContact(db(), uuid!!, key)
        }
        // onResume перерисует иконку и контакт.
    }

    // ── займы ─────────────────────────────────────────────────────────────────

    private fun renderLoans() {
        val db = db()
        val eq = PeriodFilterState.get(this).getCurrencyEq(this)
        val wallets = HashMap<Int, AccountOperatingItem>()
        val loans = DebtsActivity.loadLoans(this, db, eq, DebtsActivity.asOfTomorrow(), wallets)
        val group = DebtsList.groupFor(loans, DebtsActivity.counterpartyNames(db), uuid)

        loansBox.removeAllViews()
        if (group == null) {
            totalView.visibility = View.GONE
            loansBox.addView(text(getString(R.string.counterparty_no_loans), 14, R.color.colorTextSecondary, false))
            return
        }
        if (eq != null) {
            totalView.visibility = View.VISIBLE
            AdapterUtils.setAmountText(totalView, group.totalEq, eq, group.approx, group.incomplete)
        } else {
            totalView.visibility = View.GONE
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        for (loan in group.loans) loansBox.addView(loanRow(loan, wallets[loan.accountId], today))
    }

    private fun loanRow(loan: DebtsList.Loan, wallet: AccountOperatingItem?, today: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        row.setBackgroundResource(ripple.resourceId)

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        val name = text(loan.name, 14, if (loan.archived) R.color.colorTextSecondary else R.color.colorTextPrimary, false)
        line.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val amount = text("", 14, R.color.colorTextPrimary, true)
        AdapterUtils.setAmountText(amount, loan.balance, wallet?.currency, false)
        line.addView(amount)
        row.addView(line)

        var note: String? = if (loan.isClosed()) getString(R.string.debts_closed)
        else if (loan.isOverdue(today)) getString(R.string.debts_overdue, loan.dueDate)
        else if (loan.dueDate != null) getString(R.string.debts_due, loan.dueDate) else null
        if (loan.archived) {
            val archivedMark = getString(R.string.counterparty_loan_archived)
            note = if (note == null) archivedMark else "$note · $archivedMark"
        }
        if (loan.comment.isNotEmpty()) note = if (note == null) loan.comment else note + " · " + loan.comment
        if (note != null) {
            row.addView(
                text(
                    note, 12, if (loan.isOverdue(today)) R.color.colorexpanse else R.color.colorTextSecondary,
                    loan.isOverdue(today)
                )
            )
        }

        // Заём — это долг-кошелёк: открываем его карточку, а операции — её кнопкой-фильтром.
        row.setOnClickListener {
            startActivity(
                Intent(this, AccountItemActivity::class.java)
                    .putExtra(AccountItemActivity.ARG_ACCOUNT_ID, loan.accountId)
            )
        }
        return row
    }

    // ── архив и удаление ──────────────────────────────────────────────────────

    private fun renderArchiveButton() {
        btnArchive.setText(if (archived) R.string.counterparty_unarchive else R.string.counterparty_archive)
    }

    fun onToggleArchive(view: View) {
        archived = !archived
        WalletDbCRUD.ArchiveCounterparty(db(), counterpartyId, archived)
        changed()
        renderArchiveButton()
    }

    fun onDelete(view: View) {
        // Спека «Удаление контрагента с долгом»: не удаляем, объясняем почему, карточка остаётся.
        val result = WalletDbCRUD.DeleteCounterparty(db(), counterpartyId)
        if (result == WalletDbCRUD.DeleteCounterpartyResult.hasDebts) {
            Toast.makeText(this, getString(R.string.counterparty_delete_has_debts, savedName), Toast.LENGTH_LONG).show()
            return
        }
        changed()
        uuid = null // onPause не должен писать правки в удалённого контрагента
        finish()
    }

    private fun text(s: String?, sp: Int, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        t.setTextColor(ContextCompat.getColor(this, color))
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_COUNTERPARTY_ID = "CounterpartyCardActivity.counterparty_id"

        private const val REQUEST_PICK_ICON = 421
        private const val REQUEST_PICK_CONTACT = 422
    }
}
