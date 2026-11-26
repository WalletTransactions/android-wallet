package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Множественный выбор счетов (activity_multi_picker.xml, прототип 2a — блок isPick).
 *
 * Заменяет системный AlertDialog с галочками, который стоял в «Фильтре».
 * Контракт: на вход — какой набор счетов показывать ([cSourceType]) и уже
 * отмеченные id через запятую ([cSelectedIds]); на выход — отмеченные id
 * в том же виде, тем же ключом.
 *
 * Сверху — поиск по названию (contains, без учёта регистра), как в
 * [AccountPickerActivity]: фильтрует только видимый список, отметки при
 * этом не теряются даже у строк, ушедших из выдачи.
 */
class MultiPickerActivity : AppCompatActivity() {

    companion object {
        const val cTitle = "MultiPickerActivity.Title"
        const val cSourceType = "MultiPickerActivity.SourceType"
        const val cSelectedIds = "MultiPickerActivity.SelectedIds"

        /** Статьи затрат — всё, что не кошелёк. */
        const val cSourceExpanseAccounts = 1
        /** Кошельки. */
        const val cSourceOperationAccounts = 2

        @JvmStatic
        fun parseIds(csv: String?): MutableSet<Int> {
            val result = HashSet<Int>()
            if (csv == null || csv.isEmpty()) return result

            for (part in csv.split(",")) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                try {
                    result.add(Integer.parseInt(trimmed))
                } catch (ignored: NumberFormatException) {
                    // чужой id в extra — просто пропускаем, экран не должен падать
                }
            }
            return result
        }

        /**
         * Действие «Готово» экранной клавиатуры или Enter физической: та присылает
         * `IME_NULL` с [KeyEvent] — и на нажатие, и на отпускание, поэтому
         * берём только `ACTION_DOWN`, иначе второй вызов пришёл бы в уже закрытый экран.
         */
        @JvmStatic
        fun isDoneKey(actionId: Int, event: KeyEvent?): Boolean {
            if (actionId == EditorInfo.IME_ACTION_DONE) return true
            return event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
                    && event.action == KeyEvent.ACTION_DOWN
        }
    }

    private val mAllItems = ArrayList<AccountItem>()
    private val mVisibleItems = ArrayList<AccountItem>()
    private val selected = HashSet<Int>()

    private lateinit var pickerCount: TextView
    private lateinit var btnToggleAll: TextView
    private lateinit var mSearchInput: EditText
    private lateinit var mEmptyState: View
    private lateinit var adapter: PickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_picker)

        pickerCount = findViewById(R.id.pickerCount)
        btnToggleAll = findViewById(R.id.btnToggleAll)
        mSearchInput = findViewById(R.id.searchInput)
        mEmptyState = findViewById(R.id.emptyState)

        val title = intent.getStringExtra(cTitle)
        if (title != null) findViewById<TextView>(R.id.pickerTitle).text = title

        mAllItems.addAll(loadItems(intent.getIntExtra(cSourceType, cSourceOperationAccounts)))
        selected.addAll(parseIds(intent.getStringExtra(cSelectedIds)))

        adapter = PickerAdapter(this, mVisibleItems)
        val list = findViewById<ListView>(R.id.listItems)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = mVisibleItems[position]
            if (!selected.remove(item.id)) selected.add(item.id)
            adapter.notifyDataSetChanged()
            updateHeader()
        }

        mSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        // Enter на клавиатуре = «Готово» (spec account-screens): возвращает весь выбор,
        // включая скрытые поиском отмеченные счета — onDone идёт по mAllItems.
        mSearchInput.setOnEditorActionListener { v, actionId, event ->
            if (isDoneKey(actionId, event)) {
                onDone(v)
                return@setOnEditorActionListener true
            }
            // Отпускание физического Enter: действие уже выполнено на нажатии — только поглощаем.
            event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
        }

        applyFilter()
        AppSettings.applyPickerKeyboard(this, mSearchInput)
    }

    /** Именно contains, а не регулярка: символ вроде скобки в поиске не должен ронять экран. */
    private fun applyFilter() {
        val query = mSearchInput.text.toString().trim().lowercase()

        mVisibleItems.clear()
        for (item in mAllItems) {
            if (query.isEmpty() || item.displayName.lowercase().contains(query))
                mVisibleItems.add(item)
        }

        adapter.notifyDataSetChanged()
        mEmptyState.visibility = if (mVisibleItems.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    private fun loadItems(sourceType: Int): List<AccountItem> {
        val accounts = Singleton.instance.getAccounts(this)
        return if (sourceType == cSourceExpanseAccounts)
            ArrayList(accounts.expanseAccounts)
        else
            ArrayList(accounts.operationAccounts)
    }

    private fun updateHeader() {
        val allSelected = selected.size == mAllItems.size && mAllItems.isNotEmpty()

        pickerCount.text = if (selected.isEmpty())
            getString(R.string.filter_all)
        else
            getString(R.string.picker_selected_of, selected.size, mAllItems.size)

        btnToggleAll.setText(if (allSelected) R.string.picker_unselect_all else R.string.picker_select_all)
    }

    fun onToggleAll(view: View) {
        if (selected.size == mAllItems.size) {
            selected.clear()
        } else {
            for (item in mAllItems) selected.add(item.id)
        }
        adapter.notifyDataSetChanged()
        updateHeader()
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onDone(view: View) {
        val ids = StringBuilder()
        // Идём по mAllItems, а не по set — порядок остаётся тем же, что на экране.
        for (item in mAllItems) {
            if (!selected.contains(item.id)) continue
            if (ids.isNotEmpty()) ids.append(",")
            ids.append(item.id)
        }

        val result = Intent()
        result.putExtra(cSelectedIds, ids.toString())
        setResult(RESULT_OK, result)
        finish()
    }

    private inner class PickerAdapter(context: Context, items: List<AccountItem>)
        : ArrayAdapter<AccountItem>(context, R.layout.multi_picker_item_layout, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.multi_picker_item_layout, parent, false)

            val item = getItem(position)!!
            val isChecked = selected.contains(item.id)

            row.findViewById<ImageView>(R.id.itemIcon).setImageBitmap(item.icon!!.bitmap)
            row.findViewById<TextView>(R.id.itemLabel).text = item.displayName

            val check = row.findViewById<TextView>(R.id.itemCheck)
            check.setBackgroundResource(if (isChecked) R.drawable.bg_check_on else R.drawable.bg_check_off)
            check.text = if (isChecked) "✓" else ""

            return row
        }
    }
}
