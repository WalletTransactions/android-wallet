package com.example.wallettransactions.Activities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.wallettransactions.Data.AccountNames
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Экран «Контрагенты» (openspec debts, спека «Контрагент», «Связь контрагента с контактом
 * телефона»; design D7, D10): создание и выбор из контактов системным
 * `ACTION_PICK` — без разрешения `READ_CONTACTS`: система даёт доступ к одному
 * выбранному контакту. Привязка к контакту (lookup-key) — локальная, на другом устройстве
 * её нет.
 */
class CounterpartiesActivity : AppCompatActivity() {

    private var pickMode = false
    private var selectedUuid: String? = null

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_counterparties)
        pickMode = intent.getBooleanExtra(EXTRA_PICK, false)
        selectedUuid = intent.getStringExtra(EXTRA_SELECTED_UUID)
        list = findViewById(R.id.counterpartiesList)
        val search = findViewById<EditText>(R.id.counterpartiesSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString()
                render()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из карточки контрагента (правка, архив, удаление) — список перечитываем.
        render()
    }

    /** Строка поиска (спека debts «Поиск по имени контрагента»); живёт на экране. */
    private var query = ""

    fun btnUp(view: View) {
        finish()
    }

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    private fun render() {
        list.removeAllViews()
        val everyone = WalletDbLoader.LoadCounterparties(db())
        val all = ArrayList<CounterpartyItem>()
        for (c in everyone) {
            // В выборе архивные не предлагаются (спека debts «Архивный контрагент»), кроме уже выбранного.
            if (pickMode && c.isArchived && c.uuid != selectedUuid) continue
            if (AccountNames.matchesSearch(c.name, query)) all.add(c)
        }
        val archived = ArrayList<CounterpartyItem>()
        for (c in all) {
            if (c.isArchived) archived.add(c)
            else list.addView(row(c))
        }
        if (archived.isNotEmpty()) {
            val header = text(getString(R.string.counterparties_archived), 12, R.color.colorTextSecondary, true)
            header.isAllCaps = true
            header.setPadding(0, dp(18), 0, dp(4))
            list.addView(header)
            for (c in archived) list.addView(row(c))
        }
        val empty = findViewById<TextView>(R.id.counterpartiesEmpty)
        // Пусто из-за поиска — «Ничего не найдено», пусто вообще — «Контрагентов пока нет».
        empty.setText(if (everyone.isEmpty()) R.string.counterparties_empty else R.string.search_nothing_found)
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(c: CounterpartyItem): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        row.setBackgroundResource(ripple.resourceId)
        val selected = pickMode && c.uuid == selectedUuid
        row.addView(
            text(
                c.name, 16,
                if (c.isArchived) R.color.colorTextSecondary
                else if (selected) R.color.colorPrimaryDark else R.color.colorTextPrimary,
                true
            )
        )
        val loans = WalletDbCRUD.CountLiveDebts(db(), c.uuid)
        var sub: String? = if (loans > 0) getString(R.string.counterparty_loans, loans) else null
        if (c.comment.isNotEmpty()) sub = if (sub == null) c.comment else sub + " · " + c.comment
        if (sub != null) row.addView(text(sub, 12, R.color.colorTextSecondary, false))
        if (pickMode) {
            // Тап — выбрать и вернуться; карточка (правка, архив, контакт) — долгим тапом.
            row.setOnClickListener { deliver(c.uuid, c.name) }
            row.setOnLongClickListener { openCard(c); true }
        } else {
            row.setOnClickListener { openCard(c) }
        }
        return row
    }

    private fun openCard(c: CounterpartyItem) {
        startActivity(
            Intent(this, CounterpartyCardActivity::class.java)
                .putExtra(CounterpartyCardActivity.ARG_COUNTERPARTY_ID, c.id)
        )
    }

    private fun deliver(uuid: String?, name: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_UUID, uuid).putExtra(RESULT_NAME, name))
        finish()
    }

    fun onAddCounterparty(view: View?) {
        askName("") { name -> create(name, null) }
    }

    /** Системный выбор контакта — без разрешения; нет приложения контактов — ввод текстом. */
    fun onPickContact(view: View) {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                REQUEST_PICK_CONTACT
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show()
            onAddCounterparty(view)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Отмена выбора — ничего не делаем (спека «Связь контрагента с контактом»).
        if (requestCode != REQUEST_PICK_CONTACT || resultCode != RESULT_OK || data == null || data.data == null) return
        var name: String? = null
        var lookup: String? = null
        try {
            contentResolver.query(
                data.data!!,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY),
                null, null, null
            ).use { c ->
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0)
                    lookup = c.getString(1)
                }
            }
        } catch (e: SecurityException) {
            name = null
        }
        val picked = name
        if (picked == null || picked.trim().isEmpty()) {
            onAddCounterparty(null)
            return
        }
        val lookupKey = lookup
        askName(picked) { n -> create(n, lookupKey) }
    }

    private fun create(name: String, lookupKey: String?) {
        val db = db()
        val existing = WalletDbCRUD.GetCounterpartyIdByName(db, name)
        val id = if (existing > 0) existing else WalletDbCRUD.CreateCounterparty(db, name, "")
        if (existing > 0) {
            Toast.makeText(this, getString(R.string.debt_counterparty_exists, name), Toast.LENGTH_LONG).show()
        }
        if (id > 0 && lookupKey != null) {
            WalletDbCRUD.SetCounterpartyContact(db, WalletDbCRUD.CounterpartyUuid(db, id), lookupKey)
        }
        // В режиме выбора только что созданный (или найденный по имени) контрагент и есть выбор.
        if (pickMode && id > 0) {
            changed()
            deliver(WalletDbCRUD.CounterpartyUuid(db, id), name)
            return
        }
        changed()
    }

    private fun askName(initial: String, callback: (String) -> Unit) {
        val input = EditText(this)
        input.setHint(R.string.debt_counterparty_name_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.setText(initial)
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(10), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.debt_counterparty_name_hint)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = WalletDbCRUD.NormalizeCounterpartyName(input.text.toString()) ?: ""
                if (name.isNotEmpty()) callback(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changed() {
        PeriodFilterState.get(this).markDataChanged()
        render()
    }

    private fun text(s: String, sp: Int, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        t.setTextColor(ContextCompat.getColor(this, color))
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_CONTACT = 420

        /** Режим выбора (карточка займа): тап по строке возвращает контрагента, а не открывает карточку. */
        const val EXTRA_PICK = "CounterpartiesActivity.pick"

        /** uuid уже выбранного контрагента: подсвечивается и остаётся в списке, даже если архивный. */
        const val EXTRA_SELECTED_UUID = "CounterpartiesActivity.selectedUuid"
        const val RESULT_UUID = "CounterpartiesActivity.resultUuid"
        const val RESULT_NAME = "CounterpartiesActivity.resultName"

        @JvmStatic
        fun buildPickIntent(context: Context, selectedUuid: String?): Intent {
            return Intent(context, CounterpartiesActivity::class.java)
                .putExtra(EXTRA_PICK, true)
                .putExtra(EXTRA_SELECTED_UUID, selectedUuid)
        }
    }
}
