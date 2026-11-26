package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Блок «Комиссия за перевод» (view_commission_block.xml) — общий для окна операции
 * ([TransactionActivity]) и формы шаблона ([OperationTemplateActivity]), openspec
 * operation-templates, design D8: переключатель, счёт списания комиссии, размер, сторона
 * списания и (только в шаблоне) режим «Фикс» / «% от суммы».
 *
 * Держит состояние блока и его отрисовку; выбор счёта и клавиатуру ведёт активность-хозяин.
 */
class CommissionBlock(private val activity: Activity) {

    val rowToggle: View = activity.findViewById(R.id.rowCommissionToggle)
    val switchView: FrameLayout = activity.findViewById(R.id.switchCommission)
    val details: View = activity.findViewById(R.id.groupCommissionDetails)
    val imgAccount: ImageView = activity.findViewById(R.id.imgCommissionAccount)
    val editAccount: EditText = activity.findViewById(R.id.editCommissionAccount)
    val editAmount: EditText = activity.findViewById(R.id.editCommissionAmount)
    val tvCurrency: TextView = activity.findViewById(R.id.tvCurrencySymbolCommission)
    val radioFromSource: RadioButton = activity.findViewById(R.id.radioCommissionFromSource)
    val radioFromDest: RadioButton = activity.findViewById(R.id.radioCommissionFromDest)
    private val rowMode: View = activity.findViewById(R.id.rowCommissionMode)
    private val modeFixed: TextView = activity.findViewById(R.id.commissionModeFixed)
    private val modePercent: TextView = activity.findViewById(R.id.commissionModePercent)

    var enabled = false
        private set

    var account: AccountItem? = null
        private set

    /** «% от суммы» вместо «Фикс» — только в форме шаблона ([showModes]). */
    var percent = false
        private set

    /** Точность валюты кошелька, с которого списывается комиссия. */
    var precision = 2
        private set

    val fromDest: Boolean get() = radioFromDest.isChecked

    init {
        editAccount.isFocusable = false
    }

    /** Показать выбор «Фикс» / «% от суммы» (форма шаблона). */
    fun showModes() {
        rowMode.visibility = View.VISIBLE
        paintModes()
    }

    /** Блок есть только у перевода; на других видах сворачивается, набранное не стирается. */
    fun setAvailable(isTransfer: Boolean) {
        rowToggle.visibility = if (isTransfer) View.VISIBLE else View.GONE
        details.visibility = if (isTransfer && enabled) View.VISIBLE else View.GONE
    }

    /**
     * Включить/выключить комиссию. При первом включении подставляется категория по
     * умолчанию — системная «Комиссия за перевод».
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        applySwitchState(switchView, on)
        details.visibility = if (on) View.VISIBLE else View.GONE
        if (on && account == null) bindAccount(defaultAccount(activity))
    }

    fun toggle() = setEnabled(!enabled)

    fun bindAccount(item: AccountItem?) {
        account = item
        if (item != null) {
            imgAccount.setImageBitmap(item.icon!!.bitmap)
            editAccount.setText(item.displayName)
        } else {
            imgAccount.setImageBitmap(Singleton.instance.getIconList(activity).unknownIcon!!.bitmap)
            editAccount.setText("")
        }
    }

    fun setFromDest(dest: Boolean) {
        radioFromDest.isChecked = dest
        radioFromSource.isChecked = !dest
    }

    fun setPercent(on: Boolean) {
        percent = on
        paintModes()
    }

    /** Кошелёк, с которого списывается комиссия: отправитель или получатель перевода. */
    fun side(from: AccountItem?, to: AccountItem?): AccountOperatingItem? =
        (if (fromDest) to else from) as? AccountOperatingItem

    /**
     * Символ рядом с размером: валюта кошелька списания или «%». @return точность для
     * фильтра ввода или null, если кошелька ещё нет.
     */
    fun updateCurrency(from: AccountItem?, to: AccountItem?): Int? {
        val side = side(from, to)
        if (percent) {
            tvCurrency.text = "%"
        } else {
            tvCurrency.text = side?.currency?.symbol ?: ""
        }
        if (side == null) return null
        precision = side.currency!!.precision
        return if (percent) PERCENT_PRECISION else precision
    }

    private fun paintModes() {
        paintChip(modeFixed, !percent)
        paintChip(modePercent, percent)
    }

    private fun paintChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(activity,
                if (active) R.color.colorSurface else R.color.colorTextSecondary))
    }

    companion object {
        /** Знаков после запятой у процента комиссии. */
        const val PERCENT_PRECISION = 3

        /**
         * Категория комиссии по умолчанию. В старых базах её может не быть — начальный
         * справочник накатывался до того, как комиссия появилась, — поэтому заводим на месте
         * и обновляем справочник в памяти, иначе строка осталась бы без имени и иконки.
         */
        @JvmStatic
        fun defaultAccount(ctx: Context): AccountItem? {
            val accounts = Singleton.instance.getAccounts(ctx)
            val existing = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER_COMMISSION)
            if (existing != null) return existing

            val id = WalletDbCRUD.GetOrCreateSystemCategory(Singleton.instance.getWritableDatabase(ctx),
                    AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                    AccountItem.SYSTEM_COMMISSION_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
            if (id <= 0) return null

            WalletDbLoader.LoadAccounts(Singleton.instance.getReadableDatabase(ctx), accounts,
                    Singleton.instance.getCurrencyList(ctx),
                    Singleton.instance.getIconList(ctx), null)
            return accounts.getAccountById(id)
        }

        /** Переключатель-«пилюля» (как в SettingsActivity): двигает бегунок и красит фон. */
        @JvmStatic
        fun applySwitchState(switchView: FrameLayout, on: Boolean) {
            switchView.tag = on
            switchView.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off)
            val thumb = switchView.getChildAt(0) ?: return
            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (on) Gravity.END else Gravity.START
            thumb.layoutParams = lp
        }
    }
}
