package com.example.wallettransactions.Activities

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat

import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.DataItems.Operations.TemplateLayout
import com.example.wallettransactions.DataItems.Operations.TemplateSchedule
import com.example.wallettransactions.DataItems.Operations.TemplateStep
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.RecurringWorker

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale

/**
 * Форма шаблона операций — новый, правка ([cTemplateId]) или заготовка из окна операции
 * («Сохранить как шаблон», экстры cPrefill*), openspec operation-templates.
 *
 * Вид и счета — по той же схеме, что [TransactionActivity]: верхняя строка — «другая
 * сторона» (статья расхода / дохода, для перевода «Откуда»), нижняя — кошелёк. У перевода
 * — блок комиссии ([CommissionBlock]) с режимом «Фикс» / «% от суммы». Форма собирает из
 * полей шаги шаблона: основной («остаток») и необязательную комиссию к нему (design D4).
 *
 * Ниже — «Повторять»: «Не повторять» (ручной шаблон) или частота с полями расписания —
 * тогда шаблон становится повторяющейся операцией (recurring-operations).
 */
class OperationTemplateActivity : AppCompatActivity() {

    companion object {
        const val cTemplateId = "OperationTemplateActivity.Id"

        const val cPrefillKind = "OperationTemplateActivity.Kind"
        const val cPrefillFrom = "OperationTemplateActivity.From"
        const val cPrefillTo = "OperationTemplateActivity.To"
        const val cPrefillComment = "OperationTemplateActivity.Comment"
        const val cPrefillTotal = "OperationTemplateActivity.Total"
        const val cPrefillCommissionAccount = "OperationTemplateActivity.CommissionAccount"
        const val cPrefillCommissionAmount = "OperationTemplateActivity.CommissionAmount"
        const val cPrefillCommissionFromDest = "OperationTemplateActivity.CommissionFromDest"

        private const val AccountSelectTop = 0
        private const val AccountSelectBottom = 1
        private const val AccountSelectCommission = 2

        /** Пункт «Не повторять» в выпадающем списке частоты. */
        private const val FREQUENCY_NONE = -1

        private val INTERVALS = intArrayOf(1, 2, 3, 4)

        private val DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
        private val TIME_UI = DateTimeFormatter.ofPattern("HH:mm")

        @JvmStatic
        fun buildIntent(ctx: Context, templateId: Int): Intent {
            val intent = Intent(ctx, OperationTemplateActivity::class.java)
            intent.putExtra(cTemplateId, templateId)
            return intent
        }

        /**
         * «Сохранить как шаблон» из окна операции: вид («expense» / «income» / «transfer»),
         * счета, комментарий и общая сумма; у перевода с комиссией — её счёт, размер
         * («Фикс») и сторона списания. Ничего не пишет до «Сохранить» в форме.
         */
        @JvmStatic
        fun buildPrefillIntent(
            ctx: Context, kind: String, fromId: Int, toId: Int, comment: String, total: String?,
            commissionAccountId: Int, commissionAmount: String?, commissionFromDest: Boolean
        ): Intent {
            val intent = Intent(ctx, OperationTemplateActivity::class.java)
            intent.putExtra(cPrefillKind, kind)
            intent.putExtra(cPrefillFrom, fromId)
            intent.putExtra(cPrefillTo, toId)
            intent.putExtra(cPrefillComment, comment)
            intent.putExtra(cPrefillTotal, total)
            intent.putExtra(cPrefillCommissionAccount, commissionAccountId)
            intent.putExtra(cPrefillCommissionAmount, commissionAmount)
            intent.putExtra(cPrefillCommissionFromDest, commissionFromDest)
            return intent
        }

        private fun parse(text: String): BigDecimal? = OperationTemplateRunActivity.parse(text)
    }

    private enum class Kind { expense, income, transfer }

    private var mKind = Kind.expense
    private val mTopByKind = EnumMap<Kind, AccountItem?>(Kind::class.java)

    private lateinit var mTemplate: OperationTemplate
    private var mIsEdit = false
    /** Расписание в работе; null — «Не повторять». */
    private var mSchedule: TemplateSchedule? = null

    private var mAccFrom: AccountItem? = null
    private var mAccTo: AccountItem? = null

    private lateinit var mCommission: CommissionBlock

    private lateinit var mEditName: EditText
    private lateinit var mKindExpense: ViewGroup
    private lateinit var mKindIncome: ViewGroup
    private lateinit var mKindTransfer: ViewGroup
    private lateinit var mLabelSlotTop: TextView
    private lateinit var mLabelSlotBottom: TextView
    private lateinit var mImgSlotTop: ImageView
    private lateinit var mImgSlotBottom: ImageView
    private lateinit var mTvSlotTop: TextView
    private lateinit var mTvSlotBottom: TextView
    private lateinit var mEditAmount: EditText
    private lateinit var mTvCurrencySymbol: TextView
    private lateinit var mEditComment: EditText
    private lateinit var mTvFrequency: TextView
    private lateinit var mGroupSchedule: View
    private lateinit var mGroupWeekly: View
    private lateinit var mWeekDays: ViewGroup
    private lateinit var mTvInterval: TextView
    private lateinit var mTvStartDate: TextView
    private lateinit var mTvStartTime: TextView
    private lateinit var mSwitchForever: FrameLayout
    private lateinit var mDividerEndDate: View
    private lateinit var mRowEndDate: View
    private lateinit var mTvEndDate: TextView
    private lateinit var mSwitchActive: FrameLayout
    private lateinit var mTvNextOccurrence: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operation_template)
        assignComponents()

        val id = intent.getIntExtra(cTemplateId, 0)
        val loaded: OperationTemplate? = if (id > 0) OperationTemplatesDb.load(db(), id) else null
        mIsEdit = loaded != null
        mTemplate = loaded ?: OperationTemplate()
        mSchedule = mTemplate.schedule?.copy()

        findViewById<TextView>(R.id.screenTitle).setText(
                if (mIsEdit) R.string.template_title_edit else R.string.template_title_new)
        findViewById<View>(R.id.btnDelete).visibility = if (mIsEdit) View.VISIBLE else View.GONE

        if (mIsEdit) bindTemplate() else bindPrefill()

        for (i in 0 until mWeekDays.childCount) {
            mWeekDays.getChildAt(i).setOnClickListener { onDayClick(it) }
        }

        renderAll()
    }

    /** Поля формы из шагов существующего шаблона: основной шаг и комиссия к нему. */
    private fun bindTemplate() {
        val main = mTemplate.mainStep()
        val commission = mTemplate.steps.firstOrNull { it.isCommission }
        mEditName.setText(mTemplate.name)
        mAccFrom = accountById(main?.accFromId ?: 0)
        mAccTo = accountById(main?.accToId ?: 0)
        mKind = deriveKind(mAccFrom, mAccTo)
        val total = mTemplate.totalAmount
        if (total != null) mEditAmount.setText(total.setScale(currencyPrecision(), java.math.RoundingMode.HALF_UP).toPlainString())
        // У перенесённого правила комментарий хранится в шаге; форма показывает его как комментарий шаблона.
        mEditComment.setText(if (mTemplate.comment.isNotBlank()) mTemplate.comment else main?.comment ?: "")

        if (commission != null && main != null) {
            mCommission.bindAccount(accountById(commission.accToId))
            mCommission.setEnabled(true)
            mCommission.setFromDest(commission.accFromId == main.accToId && commission.accFromId != main.accFromId)
            mCommission.setPercent(commission.amountMode == TemplateStep.AmountMode.PERCENT)
            mCommission.editAmount.setText(commission.amountValue?.stripTrailingZeros()?.toPlainString() ?: "")
        }
    }

    /** Новый шаблон: пустая форма или заготовка из окна операции. */
    private fun bindPrefill() {
        val kind = intent.getStringExtra(cPrefillKind)
        mAccFrom = accountById(intent.getIntExtra(cPrefillFrom, 0))
        mAccTo = accountById(intent.getIntExtra(cPrefillTo, 0))
        mKind = when (kind) {
            "income" -> Kind.income
            "transfer" -> Kind.transfer
            "expense" -> Kind.expense
            else -> deriveKind(mAccFrom, mAccTo)
        }
        mEditComment.setText(intent.getStringExtra(cPrefillComment) ?: "")
        mEditAmount.setText(intent.getStringExtra(cPrefillTotal) ?: "")
        val commissionAmount = intent.getStringExtra(cPrefillCommissionAmount)
        if (mKind == Kind.transfer && !commissionAmount.isNullOrEmpty()) {
            val acc = accountById(intent.getIntExtra(cPrefillCommissionAccount, 0))
            if (acc != null) mCommission.bindAccount(acc)
            mCommission.setEnabled(true)
            mCommission.setFromDest(intent.getBooleanExtra(cPrefillCommissionFromDest, false))
            mCommission.setPercent(false)
            mCommission.editAmount.setText(commissionAmount)
        }
    }

    private fun assignComponents() {
        mEditName = findViewById(R.id.editName)
        mKindExpense = findViewById(R.id.kindExpense)
        mKindIncome = findViewById(R.id.kindIncome)
        mKindTransfer = findViewById(R.id.kindTransfer)
        mLabelSlotTop = findViewById(R.id.labelSlotTop)
        mLabelSlotBottom = findViewById(R.id.labelSlotBottom)
        mImgSlotTop = findViewById(R.id.imgSlotTop)
        mImgSlotBottom = findViewById(R.id.imgSlotBottom)
        mTvSlotTop = findViewById(R.id.tvSlotTop)
        mTvSlotBottom = findViewById(R.id.tvSlotBottom)
        mEditAmount = findViewById(R.id.editAmount)
        mTvCurrencySymbol = findViewById(R.id.tvCurrencySymbol)
        mEditComment = findViewById(R.id.editComment)
        mTvFrequency = findViewById(R.id.tvFrequency)
        mGroupSchedule = findViewById(R.id.groupSchedule)
        mGroupWeekly = findViewById(R.id.groupWeekly)
        mWeekDays = findViewById(R.id.weekDays)
        mTvInterval = findViewById(R.id.tvInterval)
        mTvStartDate = findViewById(R.id.tvStartDate)
        mTvStartTime = findViewById(R.id.tvStartTime)
        mSwitchForever = findViewById(R.id.switchForever)
        mDividerEndDate = findViewById(R.id.dividerEndDate)
        mRowEndDate = findViewById(R.id.rowEndDate)
        mTvEndDate = findViewById(R.id.tvEndDate)
        mSwitchActive = findViewById(R.id.switchActive)
        mTvNextOccurrence = findViewById(R.id.tvNextOccurrence)
        mCommission = CommissionBlock(this)
        mCommission.showModes()
    }

    private fun db(): SQLiteDatabase = Singleton.instance.getWritableDatabase(this)

    fun btnUp(view: View) {
        finish()
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private fun renderAll() {
        renderKind()
        renderSchedule()
    }

    private fun renderKind() {
        paintChip(mKindExpense, mKind == Kind.expense)
        paintChip(mKindIncome, mKind == Kind.income)
        paintChip(mKindTransfer, mKind == Kind.transfer)

        when (mKind) {
            Kind.income -> {
                mLabelSlotTop.setText(R.string.operation_slot_income_category)
                mLabelSlotBottom.setText(R.string.operation_slot_wallet)
            }
            Kind.transfer -> {
                mLabelSlotTop.setText(R.string.operation_slot_from)
                mLabelSlotBottom.setText(R.string.operation_slot_to)
            }
            else -> {
                mLabelSlotTop.setText(R.string.operation_slot_expense_category)
                mLabelSlotBottom.setText(R.string.operation_slot_wallet)
            }
        }

        bindSlot(mImgSlotTop, mTvSlotTop, getTop())
        bindSlot(mImgSlotBottom, mTvSlotBottom, getBottom())

        var wallet = asWallet(mAccFrom)
        if (wallet == null) wallet = asWallet(mAccTo)
        mTvCurrencySymbol.text = if (wallet != null) wallet.currency!!.symbol else ""

        mCommission.setAvailable(mKind == Kind.transfer)
        mCommission.updateCurrency(mAccFrom, mAccTo)
    }

    private fun renderSchedule() {
        val schedule = mSchedule
        mGroupSchedule.visibility = if (schedule != null) View.VISIBLE else View.GONE
        if (schedule == null) {
            mTvFrequency.setText(R.string.template_repeat_none)
            return
        }
        mTvFrequency.setText(schedule.frequency.title)

        val weekly = schedule.frequency == TemplateSchedule.Frequency.WEEKLY
        mGroupWeekly.visibility = if (weekly) View.VISIBLE else View.GONE
        if (weekly) {
            for (i in 0 until mWeekDays.childCount) {
                val chip = mWeekDays.getChildAt(i) as TextView
                val day = DayOfWeek.of(Integer.parseInt(chip.tag.toString()))
                paintDayChip(chip, schedule.hasDay(day))
            }
            mTvInterval.text = intervalLabel(schedule.interval)
        }

        mTvStartDate.text = schedule.startDate!!.format(DATE_UI)
        mTvStartTime.text = schedule.startDate!!.format(TIME_UI)

        val endDate = schedule.endDate
        val forever = endDate == null
        CommissionBlock.applySwitchState(mSwitchForever, forever)
        mDividerEndDate.visibility = if (forever) View.GONE else View.VISIBLE
        mRowEndDate.visibility = if (forever) View.GONE else View.VISIBLE
        if (endDate != null) mTvEndDate.text = endDate.format(DATE_UI)

        CommissionBlock.applySwitchState(mSwitchActive, schedule.isActive)

        renderNextOccurrence(schedule)
    }

    private fun renderNextOccurrence(schedule: TemplateSchedule) {
        if (!schedule.isActive) {
            mTvNextOccurrence.setText(R.string.recurring_off)
            return
        }
        val next = schedule.nextOccurrence(LocalDateTime.now())
        mTvNextOccurrence.text = if (next != null)
            getString(R.string.recurring_next, next.format(DATE_UI), next.format(TIME_UI))
        else
            getString(R.string.recurring_ended)
    }

    private fun paintChip(chip: ViewGroup, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        val color = ContextCompat.getColor(this, if (active) R.color.colorSurface else R.color.colorTextSecondary)
        (chip.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (chip.getChildAt(1) as TextView).setTextColor(color)
    }

    private fun paintDayChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(this, if (active) R.color.colorSurface else R.color.colorTextSecondary))
    }

    private fun bindSlot(img: ImageView, text: TextView, item: AccountItem?) {
        if (item != null) {
            img.setImageBitmap(item.icon!!.bitmap)
            text.text = item.displayName
            text.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary))
        } else {
            img.setImageBitmap(Singleton.instance.getIconList(this).unknownIcon!!.bitmap)
            text.setText(R.string.recurring_choose)
            text.setTextColor(ContextCompat.getColor(this, R.color.colorTextHint))
        }
    }

    // ── Тип операции и счета ──────────────────────────────────────────────────

    private fun topIsFrom(): Boolean = mKind != Kind.expense

    private fun getTop(): AccountItem? = if (topIsFrom()) mAccFrom else mAccTo

    private fun getBottom(): AccountItem? = if (topIsFrom()) mAccTo else mAccFrom

    private fun setSlots(top: AccountItem?, bottom: AccountItem?) {
        if (topIsFrom()) {
            mAccFrom = top
            mAccTo = bottom
        } else {
            mAccFrom = bottom
            mAccTo = top
        }
    }

    private fun deriveKind(from: AccountItem?, to: AccountItem?): Kind {
        val fromWallet = from != null && from.isOperatingAccount
        val toWallet = to != null && to.isOperatingAccount
        if (fromWallet && toWallet) return Kind.transfer
        if (!fromWallet && toWallet) return Kind.income
        return Kind.expense
    }

    private fun accountById(id: Int): AccountItem? {
        return if (id > 0) Singleton.instance.getAccounts(this).getAccountById(id) else null
    }

    private fun asWallet(item: AccountItem?): AccountOperatingItem? {
        return item as? AccountOperatingItem
    }

    private fun currencyPrecision(): Int {
        var wallet = asWallet(mAccFrom)
        if (wallet == null) wallet = asWallet(mAccTo)
        return if (wallet != null) wallet.currency!!.precision else 2
    }

    fun onKindClick(view: View) {
        val id = view.id
        val picked = if (id == R.id.kindIncome) Kind.income
        else if (id == R.id.kindTransfer) Kind.transfer
        else Kind.expense
        if (picked == mKind) return

        // Как на экране операции: кошелёк снизу переезжает, верхняя строка у каждого типа своя.
        mTopByKind[mKind] = getTop()
        val wallet = getBottom()
        mKind = picked

        var top = mTopByKind[mKind]
        if (mKind == Kind.transfer && top != null && wallet != null && top.id == wallet.id) {
            top = null
        }
        setSlots(top, wallet)
        renderKind()
    }

    fun onPickTop(view: View) {
        val type = if (mKind == Kind.transfer)
            AccountPickerActivity.cSelectedTypeOperationAccounts
        else
            AccountPickerActivity.cSelectedTypeChargeAccounts
        val current = getTop()
        val pick = AccountPickerActivity.buildIntent(this, mLabelSlotTop.text.toString(),
                type, if (current == null) 0 else current.id, getBottom())
        // Вид категории (category-kind): расход — «Расход» и «Оба», доход — «Доход» и «Оба».
        if (mKind != Kind.transfer) {
            pick.putExtra(AccountPickerActivity.cCategoryKind, if (mKind == Kind.income) 1 else 0)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, AccountSelectTop)
    }

    fun onPickBottom(view: View) {
        val current = getBottom()
        @Suppress("DEPRECATION")
        startActivityForResult(AccountPickerActivity.buildIntent(this, mLabelSlotBottom.text.toString(),
                AccountPickerActivity.cSelectedTypeOperationAccounts,
                if (current == null) 0 else current.id, getTop()), AccountSelectBottom)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        val accountId = data.getIntExtra(AccountPickerActivity.cAccountId, -1)
        if (accountId <= 0) return
        val picked = accountById(accountId)

        if (requestCode == AccountSelectCommission) {
            mCommission.bindAccount(picked)
            return
        }
        val intoFrom = (requestCode == AccountSelectTop) == topIsFrom()
        if (intoFrom) mAccFrom = picked
        else mAccTo = picked
        renderKind()
    }

    // ── Комиссия (обработчики view_commission_block.xml) ──────────────────────

    fun onToggleCommission(view: View) {
        mCommission.toggle()
        mCommission.updateCurrency(mAccFrom, mAccTo)
    }

    fun onPickCommissionAccount(view: View) {
        val side = mCommission.side(mAccFrom, mAccTo)
        val current = mCommission.account
        @Suppress("DEPRECATION")
        startActivityForResult(AccountPickerActivity.buildIntent(this,
                getString(R.string.operation_commission_account),
                AccountPickerActivity.cSelectedTypeChargeAccounts,
                if (current == null) 0 else current.id, side), AccountSelectCommission)
    }

    fun onCommissionSideChanged(view: View) {
        mCommission.updateCurrency(mAccFrom, mAccTo)
    }

    fun onCommissionModeClick(view: View) {
        mCommission.setPercent(view.id == R.id.commissionModePercent)
        mCommission.updateCurrency(mAccFrom, mAccTo)
    }

    // ── Расписание ────────────────────────────────────────────────────────────

    /** Выпадающий список «Повторять»: «Не повторять» и частоты. */
    fun onPickFrequency(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, FREQUENCY_NONE, 0, R.string.template_repeat_none)
        for (f in TemplateSchedule.Frequency.values()) {
            popup.menu.add(0, f.value, f.ordinal + 1, f.title)
        }
        popup.setOnMenuItemClickListener { item ->
            onFrequencyPicked(item.itemId)
            true
        }
        popup.show()
    }

    /** Выбор в «Повторять»; [FREQUENCY_NONE] — убрать расписание, шаги не трогаются. */
    internal fun onFrequencyPicked(value: Int) {
        if (value == FREQUENCY_NONE) {
            mSchedule = null
            renderSchedule()
            return
        }
        var schedule = mSchedule
        if (schedule == null) {
            // Расписание появилось только что: «сегодня 12:00», «Всегда» и «Повтор включён».
            schedule = TemplateSchedule()
            schedule.startDate = LocalDate.now().atTime(12, 0)
            schedule.isActive = true
            mSchedule = schedule
        }
        schedule.frequency = TemplateSchedule.Frequency.fromValue(value)
        if (schedule.frequency == TemplateSchedule.Frequency.WEEKLY) {
            // Первый заход в «еженедельно» — отмечаем день недели даты начала.
            if (schedule.weekDays == 0) schedule.weekDays = TemplateSchedule.dayBit(schedule.startDate!!.dayOfWeek)
        } else {
            // Шаг «каждые N» — только у еженедельного.
            schedule.interval = 1
        }
        renderSchedule()
    }

    fun onPickInterval(view: View) {
        val popup = PopupMenu(this, view)
        for (i in INTERVALS.indices) {
            popup.menu.add(0, INTERVALS[i], i, intervalLabel(INTERVALS[i]))
        }
        popup.setOnMenuItemClickListener { item ->
            mSchedule?.interval = item.itemId
            renderSchedule()
            true
        }
        popup.show()
    }

    private fun intervalLabel(weeks: Int): String {
        return if (weeks == 1) getString(R.string.recurring_every_week)
        else resources.getQuantityString(R.plurals.recurring_every_weeks, weeks, weeks)
    }

    private fun onDayClick(view: View) {
        val schedule = mSchedule ?: return
        val day = DayOfWeek.of(Integer.parseInt(view.tag.toString()))
        schedule.weekDays = schedule.weekDays xor TemplateSchedule.dayBit(day)
        renderSchedule()
    }

    fun onPickStartDate(view: View) {
        val schedule = mSchedule ?: return
        val start = schedule.startDate!!
        val cal = java.util.Calendar.getInstance()
        cal.set(start.year, start.monthValue - 1, start.dayOfMonth)
        CalendarPicker.show(this, cal) { year, month, day ->
            schedule.startDate = LocalDate.of(year, month + 1, day).atTime(schedule.startDate!!.toLocalTime())
            renderSchedule()
        }
    }

    fun onPickStartTime(view: View) {
        val schedule = mSchedule ?: return
        val t = schedule.startDate!!.toLocalTime()
        TimePickerDialog(this, { _, hour, minute ->
            schedule.startDate = schedule.startDate!!.toLocalDate().atTime(hour, minute)
            renderSchedule()
        }, t.hour, t.minute, true).show()
    }

    fun onToggleForever(view: View) {
        val schedule = mSchedule ?: return
        if (schedule.endDate == null) {
            // «Всегда» сняли — предлагаем окончание через год, дату сразу можно поменять.
            schedule.endDate = schedule.startDate!!.toLocalDate().plusYears(1)
            renderSchedule()
            onPickEndDate(view)
        } else {
            schedule.endDate = null
            renderSchedule()
        }
    }

    fun onPickEndDate(view: View) {
        val schedule = mSchedule ?: return
        val end = schedule.endDate ?: LocalDate.now()
        val cal = java.util.Calendar.getInstance()
        cal.set(end.year, end.monthValue - 1, end.dayOfMonth)
        CalendarPicker.show(this, cal) { year, month, day ->
            schedule.endDate = LocalDate.of(year, month + 1, day)
            renderSchedule()
        }
    }

    fun onToggleActive(view: View) {
        val schedule = mSchedule ?: return
        schedule.isActive = !schedule.isActive
        renderSchedule()
    }

    // ── Сохранение и удаление ─────────────────────────────────────────────────

    private fun toast(res: Int, long: Boolean = false) {
        Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun onSaveClick(view: View) {
        val name = mEditName.text.toString().trim()
        if (name.isEmpty()) {
            toast(R.string.template_error_no_name)
            return
        }
        val accFrom = mAccFrom
        val accTo = mAccTo
        if (accFrom == null) {
            toast(R.string.operation_error_no_source)
            return
        }
        if (accTo == null) {
            toast(R.string.operation_error_no_target)
            return
        }
        if (accFrom.id == accTo.id) {
            toast(R.string.recurring_error_same_accounts)
            return
        }
        if (asWallet(accFrom) == null && asWallet(accTo) == null) {
            toast(R.string.recurring_error_no_wallet, long = true)
            return
        }

        val schedule = mSchedule
        val totalText = mEditAmount.text.toString()
        val total = parse(totalText)
        if ((schedule != null || totalText.isNotBlank()) && (total == null || total.signum() <= 0)) {
            toast(R.string.recurring_error_amount)
            return
        }

        val withCommission = mKind == Kind.transfer && mCommission.enabled
        var commissionValue: BigDecimal? = null
        if (withCommission) {
            commissionValue = parse(mCommission.editAmount.text.toString())
            if (commissionValue == null || commissionValue.signum() <= 0) {
                toast(R.string.template_error_commission_amount)
                return
            }
            if (mCommission.percent && commissionValue > BigDecimal(100)) {
                toast(R.string.template_error_percent)
                return
            }
        }

        val draft = buildTemplate(name, total, accFrom, accTo, commissionValue)
        val accounts = Singleton.instance.getAccounts(this)
        when (draft.validate { id -> (accounts.getAccountById(id) as? AccountOperatingItem)?.currency?.id }) {
            null -> {}
            OperationTemplate.Problem.MIXED_CURRENCY -> {
                toast(R.string.template_error_currency, long = true)
                return
            }
            OperationTemplate.Problem.NO_WALLET -> {
                toast(R.string.recurring_error_no_wallet, long = true)
                return
            }
            else -> {
                toast(R.string.recurring_error_save)
                return
            }
        }

        if (schedule != null) {
            if (schedule.frequency == TemplateSchedule.Frequency.WEEKLY && schedule.weekDays == 0) {
                toast(R.string.recurring_error_no_days)
                return
            }
            val endDate = schedule.endDate
            if (endDate != null && endDate.isBefore(schedule.startDate!!.toLocalDate())) {
                toast(R.string.recurring_error_end_before_start)
                return
            }
        }
        // Комиссия больше общей суммы — такой шаблон не запустить и по расписанию.
        if (total != null && TemplateLayout.expand(draft.steps, total, currencyPrecision()) is TemplateLayout.Result.Overflow) {
            toast(R.string.template_error_overflow, long = true)
            return
        }

        mTemplate.name = draft.name
        mTemplate.comment = draft.comment
        mTemplate.totalAmount = draft.totalAmount
        mTemplate.steps.clear()
        mTemplate.steps.addAll(draft.steps)
        mTemplate.schedule = schedule

        // Дата начала в прошлом (новый шаблон, перенос даты, расписание добавили):
        // наступившие, но ещё не созданные повторы можно создать сразу или пропустить —
        // решает пользователь, молча досоздавать их нельзя.
        val missed: List<LocalDateTime> = if (schedule != null && schedule.isActive)
            schedule.occurrencesBetween(schedule.lastDate, LocalDateTime.now())
        else
            emptyList()
        if (missed.isNotEmpty()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.recurring_missed_title)
                    .setMessage(resources.getQuantityString(R.plurals.recurring_missed_message,
                            missed.size, missed.size, missed[0].format(DATE_UI)))
                    .setPositiveButton(R.string.recurring_missed_create) { _, _ -> persist() }
                    .setNeutralButton(R.string.recurring_missed_future_only) { _, _ ->
                        schedule!!.lastDate = missed[missed.size - 1]
                        persist()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            return
        }
        persist()
    }

    /**
     * Шаги из полей формы (design D4): основной («остаток») и, у перевода с комиссией,
     * комиссия к нему — с кошелька отправителя или получателя на категорию комиссии.
     * Существующие шаги сохраняют свои id/uuid — [OperationTemplatesDb.update] сопоставляет их по номеру.
     */
    private fun buildTemplate(name: String, total: BigDecimal?, accFrom: AccountItem, accTo: AccountItem,
                              commissionValue: BigDecimal?): OperationTemplate {
        val t = OperationTemplate()
        t.name = name
        t.comment = mEditComment.text.toString().trim()
        t.totalAmount = total

        val main = (mTemplate.stepByNum(1)?.copy()) ?: TemplateStep()
        main.num = 1
        main.accFromId = accFrom.id
        main.accToId = accTo.id
        main.amountMode = TemplateStep.AmountMode.REST
        main.amountValue = null
        main.comment = ""
        main.commissionOfNum = null
        t.steps.add(main)

        if (commissionValue != null) {
            val commissionAcc = mCommission.account ?: CommissionBlock.defaultAccount(this)
            val side = mCommission.side(accFrom, accTo)
            if (commissionAcc != null && side != null) {
                val c = (mTemplate.stepByNum(2)?.copy()) ?: TemplateStep()
                c.num = 2
                c.accFromId = side.id
                c.accToId = commissionAcc.id
                c.amountMode = if (mCommission.percent) TemplateStep.AmountMode.PERCENT else TemplateStep.AmountMode.FIXED
                c.amountValue = commissionValue
                c.comment = ""
                c.commissionOfNum = 1
                t.steps.add(c)
            }
        }
        return t
    }

    private fun persist() {
        val db = db()
        val result = if (mIsEdit) OperationTemplatesDb.update(db, mTemplate).toLong()
        else OperationTemplatesDb.insert(db, mTemplate)
        if (result <= 0) {
            toast(R.string.recurring_error_save)
            return
        }

        if (mTemplate.isScheduled) {
            val created = OperationTemplatesDb.generateDue(db, LocalDateTime.now())
            if (created > 0) {
                PeriodFilterState.get(this).markDataChanged()
                Toast.makeText(this, getString(R.string.recurring_created, created), Toast.LENGTH_SHORT).show()
            }
        }
        RecurringWorker.kick(this)
        setResult(RESULT_OK)
        finish()
    }

    fun onDeleteClick(view: View) {
        AlertDialog.Builder(this)
                .setTitle(R.string.template_delete_question)
                .setMessage(R.string.template_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    OperationTemplatesDb.delete(db(), mTemplate.id)
                    RecurringWorker.kick(this)
                    setResult(RESULT_OK)
                    finish()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }
}
