package com.example.wallettransactions.Activities

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.example.wallettransactions.Data.AccountNames
import com.example.wallettransactions.R

/**
 * Диалог выбора из списка с полем «Поиск по названию» (openspec wallets-filter-and-debt-autoarchive,
 * спека debts «Поиск по имени контрагента»): подстрока, без регистра и различия «ё»/«е»
 * ([AccountNames.matchesSearch]); ничего не нашлось — «Ничего не найдено». Закреплённые
 * строки (например, «Новый контрагент») поиском не скрываются.
 */
internal object SearchableListDialog {

    fun interface OnPick {
        /** @param index индекс в списке `items`, а не в отфильтрованном */
        fun onPick(index: Int)
    }

    /**
     * @param pinned строки сверху, всегда видимые; номер в [OnPick] — `-1 - позиция`
     *               (−1 — первая закреплённая)
     */
    @JvmStatic
    fun show(activity: Activity, title: String, pinned: List<String>,
             items: List<String>, onPick: OnPick) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val search = EditText(activity)
        search.setHint(R.string.search_by_name_hint)
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(search)

        val listView = ListView(activity)
        box.addView(listView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (320 * density).toInt()))

        val empty = TextView(activity)
        empty.setText(R.string.search_nothing_found)
        empty.setPadding(0, pad / 2, 0, pad / 2)
        empty.visibility = View.GONE
        box.addView(empty)

        // Строки списка: закреплённые, затем подходящие под поиск; код — индекс в исходных списках.
        val codes = ArrayList<Int>()
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1,
                ArrayList<String>())
        listView.adapter = adapter

        val refill = Runnable {
            adapter.clear()
            codes.clear()
            for (i in pinned.indices) {
                adapter.add(pinned[i])
                codes.add(-1 - i)
            }
            val q = search.text.toString()
            var shown = 0
            for (i in items.indices) {
                if (!AccountNames.matchesSearch(items[i], q)) continue
                adapter.add(items[i])
                codes.add(i)
                shown++
            }
            adapter.notifyDataSetChanged()
            empty.visibility = if (shown == 0 && items.isNotEmpty()) View.VISIBLE else View.GONE
        }
        refill.run()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refill.run() }
        })

        val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPick.onPick(codes[position])
        }
        dialog.show()
    }
}
