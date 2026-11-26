package com.example.wallettransactions.Adapters

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.icu.text.DecimalFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.IFilterIsLike
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.R

class AccountsAdapter @JvmOverloads constructor(
    private val ctx: Context,
    private var accounts: ArrayList<AccountItem>,
    private val fIconList: IconList?,
    private val currencyEq: Currency? = null
) : ArrayAdapter<AccountItem>(ctx, R.layout.account_item_layout, accounts) {

    private val grayColorMatrix: ColorMatrix = ColorMatrix().apply { setSaturation(0f) }
    private var filter: Filter? = null

    private val decimalFormat = DecimalFormat("#,##0.00")

    fun setAccounts(accounts: ArrayList<AccountItem>) {
        this.accounts = accounts
    }

    override fun getFilter(): Filter {
        var f = filter
        if (f == null) {
            f = AccountFilter<AccountItem>(accounts)
            filter = f
        }
        return f
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val listItem = convertView
            ?: LayoutInflater.from(ctx).inflate(R.layout.account_item_layout, parent, false)

        val tvName: TextView = listItem.findViewById(R.id.textName)
        val tvDescr: TextView = listItem.findViewById(R.id.textDescription)
        val textBalance: TextView = listItem.findViewById(R.id.textBalance)
        val textCurrencySymbol: TextView = listItem.findViewById(R.id.textCurrencySymbol)
        val textEquivalent: TextView = listItem.findViewById(R.id.textEquivalent)
        val img: ImageView = listItem.findViewById(R.id.imageView)
        val imgCur: ImageView = listItem.findViewById(R.id.imageCurencyView)

        val account = accounts[position]

        if (account.isOperatingAccount) {
            val accountOp = account as AccountOperatingItem

            imgCur.visibility = View.VISIBLE
            textBalance.visibility = View.VISIBLE
            textCurrencySymbol.visibility = View.VISIBLE
            textBalance.text = AdapterUtils
                .getFormat(accountOp.currency!!.precision)
                .format(accountOp.CurrentBalance)
            textCurrencySymbol.text = accountOp.currency!!.symbol
            imgCur.setImageBitmap(accountOp.currency!!.icon!!.bitmap)

            // Show equivalent if available
            if (currencyEq != null) {
                textEquivalent.visibility = View.VISIBLE
                val db = WalletDbHelper(ctx).readableDatabase
                try {
                    val equivalent = accountOp.getBalanceEq(currencyEq, db)
                    if (equivalent == null) {
                        textEquivalent.text = "~"
                    } else {
                        textEquivalent.text =
                            (if (accountOp.isConverted(currencyEq)) "≈ " else "") +
                                    AdapterUtils.getFormat(currencyEq.precision).format(equivalent) +
                                    " " + currencyEq.symbol
                    }
                } finally {
                    db.close()
                }
            } else {
                textEquivalent.visibility = View.GONE
            }
        } else {
            imgCur.visibility = View.GONE
            textBalance.visibility = View.GONE
            textCurrencySymbol.visibility = View.GONE
            textEquivalent.visibility = View.GONE
        }

        tvName.text = account.displayName
        tvDescr.text = account.comment
        img.setImageBitmap(account.icon!!.bitmap)

        if (account.isArchived)
            img.colorFilter = ColorMatrixColorFilter(grayColorMatrix)
        else
            img.colorFilter = null

        return listItem
    }

    private inner class AccountFilter<T : IFilterIsLike>(accounts: ArrayList<T>) : Filter() {
        private val sourceObjects: ArrayList<T> = ArrayList()

        init {
            synchronized(this) {
                sourceObjects.addAll(accounts)
            }
        }

        override fun performFiltering(chars: CharSequence): FilterResults {
            val filterSeq = chars.toString().lowercase()
            val result = FilterResults()
            if (filterSeq.isNotEmpty()) {
                val filter = ArrayList<T>()

                for (obj in sourceObjects) {
                    // the filtering itself:
                    if (obj.isLike(filterSeq))
                        filter.add(obj)
                }
                result.count = filter.size
                result.values = filter
            } else {
                // add all objects
                synchronized(this) {
                    result.values = sourceObjects
                    result.count = sourceObjects.size
                }
            }
            return result
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            // NOTE: this function is *always* called from the UI thread.
            val filtered = results.values as ArrayList<T>
            notifyDataSetChanged()
            clear()
            for (i in 0 until filtered.size)
                add(filtered[i] as AccountItem)
            notifyDataSetInvalidated()
        }
    }
}
