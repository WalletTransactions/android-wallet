package com.example.wallettransactions.Adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.R
import java.util.Locale

/** Rows for CurrencyPickerActivity's list (currency_picker_item_layout.xml) — filters by
 * ISO code or name, marks the currently-selected currency (if any) with the colour of its code. */
class CurrencyPickerAdapter(
    private val context: Context,
    initial: List<Currency>,
    private val selectedIso: String?
) : BaseAdapter(), Filterable {

    private val all: MutableList<Currency> = ArrayList(initial)

    /** Тап по ✎ в строке — правка валюты, отдельно от выбора строки. */
    fun interface OnEditCurrencyListener {
        fun onEditCurrency(currency: Currency)
    }

    private var shown: List<Currency> = ArrayList(all)
    private var editListener: OnEditCurrencyListener? = null

    fun setOnEditCurrencyListener(listener: OnEditCurrencyListener?) {
        this.editListener = listener
    }

    /** Список валют живёт в синглтоне и мог измениться в форме — перечитываем. */
    fun replaceAll(currencies: List<Currency>) {
        all.clear()
        all.addAll(currencies)
        shown = ArrayList(all)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = shown.size

    override fun getItem(position: Int): Currency = shown[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.currency_picker_item_layout, parent, false)

        val c = getItem(position)
        val code = view.findViewById<TextView>(R.id.currencyCode)
        code.text = c.iso
        // Текущий выбор — цветом кода: отдельного маркера (радио-кружка) в строке нет, как и в выборе счёта.
        code.setTextColor(
            ContextCompat.getColor(
                context,
                if (c.iso == selectedIso) R.color.colorPrimaryDark else R.color.colorTextPrimary
            )
        )
        view.findViewById<TextView>(R.id.currencyName).text = c.name
        view.findViewById<TextView>(R.id.currencySymbol).text = c.symbol

        val tags = view.findViewById<TextView>(R.id.currencyTags)
        val tagText = tagsOf(context, c)
        tags.text = tagText
        tags.visibility = if (tagText.isEmpty()) View.GONE else View.VISIBLE

        val icon = view.findViewById<ImageView>(R.id.currencyIcon)
        if (c.icon != null) icon.setImageBitmap(c.icon!!.bitmap)
        else icon.setImageDrawable(null)

        val edit = view.findViewById<View>(R.id.btnEditCurrency)
        edit.setOnClickListener {
            editListener?.onEditCurrency(c)
        }

        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = if (constraint == null) "" else constraint.toString().trim().uppercase(Locale.ROOT)
                val filtered: MutableList<Currency> = ArrayList()
                for (c in all) {
                    if (q.isEmpty()
                        || c.iso.uppercase(Locale.ROOT).contains(q)
                        || (c.name != null && c.name.uppercase(Locale.ROOT).contains(q))
                    ) {
                        filtered.add(c)
                    }
                }
                val results = FilterResults()
                results.values = filtered
                results.count = filtered.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                shown = results.values as List<Currency>
                notifyDataSetChanged()
            }
        }
    }

    companion object {
        /** Подписи строки (spec currency-directory «Пометка скрытой валюты в списке»). */
        @JvmStatic
        fun tagsOf(context: Context, c: Currency): String {
            val sb = StringBuilder()
            if (c.isShared) sb.append(context.getString(R.string.currency_tag_shared))
            if (!c.isShowInEquivalent) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(context.getString(R.string.currency_tag_not_in_eq))
            }
            return sb.toString()
        }
    }
}
