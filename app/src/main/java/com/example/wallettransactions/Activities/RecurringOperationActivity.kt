package com.example.wallettransactions.Activities

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.Gravity
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
import com.example.wallettransactions.Data.RecurringOperationsDb
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Operations.RecurringOperation
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.RecurringWorker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale

/**
 * Правило повторяющейся операции — новое или правка ([cRecurringId]).
 *
 * Тип операции и счета — по той же схеме, что [TransactionActivity]: верхняя строка
 * — «другая сторона» (статья расхода / дохода, для перевода «Откуда»), нижняя — кошелёк.
 * Валютный перевод (кошельки в разных валютах) и комиссия сюда не входят: правило
 * заводит ровно одну проводку, а у валютного перевода их две и сумма во второй валюте
 * зависит от курса на дату — такой перевод по правилу не создать.
 */
class RecurringOperationActivity : AppCompatActivity() {

    companion object {
        const val cRecurringId = "RecurringOperationActivity.Id"

        private const val AccountSelectTop = 0
        private const val AccountSelectBottom = 1

        private val INTERVALS = intArrayOf(1, 2, 3, 4)

        private val DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
        private val TIME_UI = DateTimeFormatter.ofPattern("HH:mm")

        @JvmStatic
        fun buildIntent(ctx: Context, recurringId: Int): Intent {
            val intent = Intent(ctx, RecurringOperationActivity::class.java)
            intent.putExtra(cRecurringId, recurringId)
            return intent
        }

        private fun formatAmount(value: Double, precision: Int): String {
            return String.format(Locale.US, "%.${precision}f", value)
        }
    }

    private enum class Kind { expense, income, transfer }

    private var mKind = Kind.expense
    private val mTopByKind = EnumMap<Kind, AccountItem?>(Kind::class.java)

    private lateinit var mRule: RecurringOperation
    private var mIsEdit = false

    private var mAccFrom: AccountItem? = null
    private var mAccTo: AccountItem? = null

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
        setContentView(R.layout.activity_recurring_operation)
        assignComponents()

        val id = intent.getIntExtra(cRecurringId, 0)
        val loaded: RecurringOperation? = if (id > 0) RecurringOperationsDb.load(db(), id) else null
        mIsEdit = loaded != null

        if (loaded == null) {
            val rule = RecurringOperation()
            rule.startDate = LocalDate.now().atTime(12, 0)
            rule.frequency = RecurringOperation.Frequency.MONTHLY
            mRule = rule
        } else {
            mRule = loaded
        }

        findViewById<TextView>(R.id.screenTitle).setText(
                if (mIsEdit) R.string.recurring_title_edit else R.string.recurring_title_new)
        findViewById<View>(R.id.btnDelete).visibility = if (mIsEdit) View.VISIBLE else View.GONE

        mAccFrom = accountById(mRule.accFromId)
        mAccTo = accountById(mRule.accToId)
        mKind = deriveKind(mAccFrom, mAccTo)

        if (mIsEdit) {
            mEditAmount.setText(formatAmount(mRule.amount, currencyPrecision()))
            mEditComment.setText(mRule.comment)
        }

        for (i in 0 until mWeekDays.childCount) {
            mWeekDays.getChildAt(i).setOnClickListener { onDayClick(it) }
        }

        renderAll()
    }

    private fun assignComponents() {
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
    }

    private fun renderSchedule() {
        mTvFrequency.setText(mRule.frequency.title)

        val weekly = mRule.frequency == RecurringOperation.Frequency.WEEKLY
        mGroupWeekly.visibility = if (weekly) View.VISIBLE else View.GONE
        if (weekly) {
            for (i in 0 until mWeekDays.childCount) {
                val chip = mWeekDays.getChildAt(i) as TextView
                val day = DayOfWeek.of(Integer.parseInt(chip.tag.toString()))
                paintDayChip(chip, mRule.hasDay(day))
            }
            mTvInterval.text = intervalLabel(mRule.interval)
        }

        mTvStartDate.text = mRule.startDate!!.format(DATE_UI)
        mTvStartTime.text = mRule.startDate!!.format(TIME_UI)

        val endDate = mRule.endDate
        val forever = endDate == null
        applySwitchState(mSwitchForever, forever)
        mDividerEndDate.visibility = if (forever) View.GONE else View.VISIBLE
        mRowEndDate.visibility = if (forever) View.GONE else View.VISIBLE
        if (endDate != null) mTvEndDate.text = endDate.format(DATE_UI)

        applySwitchState(mSwitchActive, mRule.isActive)

        renderNextOccurrence()
    }

    private fun renderNextOccurrence() {
        if (!mRule.isActive) {
            mTvNextOccurrence.setText(R.string.recurring_off)
            return
        }
        val next = mRule.nextOccurrence(LocalDateTime.now())
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
        val picked: Kind
        val id = view.id
        picked = if (id == R.id.kindIncome) Kind.income
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

        val intoFrom = (requestCode == AccountSelectTop) == topIsFrom()
        if (intoFrom) mAccFrom = picked
        else mAccTo = picked
        renderKind()
    }

    // ── Расписание ────────────────────────────────────────────────────────────

    /** Выпадающий список частоты повтора. */
    fun onPickFrequency(view: View) {
        val popup = PopupMenu(this, view)
        for (f in RecurringOperation.Frequency.values()) {
            popup.menu.add(0, f.value, f.ordinal, f.title)
        }
        popup.setOnMenuItemClickListener { item ->
            mRule.frequency = RecurringOperation.Frequency.fromValue(item.itemId)
            if (mRule.frequency == RecurringOperation.Frequency.WEEKLY) {
                // Первый заход в «еженедельно» — отмечаем день недели даты начала, чтобы
                // правило сразу было осмысленным.
                if (mRule.weekDays == 0) mRule.weekDays = RecurringOperation.dayBit(mRule.startDate!!.dayOfWeek)
            } else {
                // Шаг «каждые N» — только у еженедельного, см. RecurringOperationEntry.COLUMN_INTERVAL.
                mRule.interval = 1
            }
            renderSchedule()
            true
        }
        popup.show()
    }

    fun onPickInterval(view: View) {
        val popup = PopupMenu(this, view)
        for (i in INTERVALS.indices) {
            popup.menu.add(0, INTERVALS[i], i, intervalLabel(INTERVALS[i]))
        }
        popup.setOnMenuItemClickListener { item ->
            mRule.interval = item.itemId
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
        val day = DayOfWeek.of(Integer.parseInt(view.tag.toString()))
        mRule.weekDays = mRule.weekDays xor RecurringOperation.dayBit(day)
        renderSchedule()
    }

    fun onPickStartDate(view: View) {
        val cal = java.util.Calendar.getInstance()
        cal.set(mRule.startDate!!.year, mRule.startDate!!.monthValue - 1, mRule.startDate!!.dayOfMonth)
        CalendarPicker.show(this, cal) { year, month, day ->
            mRule.startDate = LocalDate.of(year, month + 1, day).atTime(mRule.startDate!!.toLocalTime())
            renderSchedule()
        }
    }

    fun onPickStartTime(view: View) {
        val t = mRule.startDate!!.toLocalTime()
        TimePickerDialog(this, { _, hour, minute ->
            mRule.startDate = mRule.startDate!!.toLocalDate().atTime(hour, minute)
            renderSchedule()
        }, t.hour, t.minute, true).show()
    }

    fun onToggleForever(view: View) {
        if (mRule.endDate == null) {
            // «Всегда» сняли — предлагаем окончание через год, дату сразу можно поменять.
            mRule.endDate = mRule.startDate!!.toLocalDate().plusYears(1)
            renderSchedule()
            onPickEndDate(view)
        } else {
            mRule.endDate = null
            renderSchedule()
        }
    }

    fun onPickEndDate(view: View) {
        val end = mRule.endDate ?: LocalDate.now()
        val cal = java.util.Calendar.getInstance()
        cal.set(end.year, end.monthValue - 1, end.dayOfMonth)
        CalendarPicker.show(this, cal) { year, month, day ->
            mRule.endDate = LocalDate.of(year, month + 1, day)
            renderSchedule()
        }
    }

    fun onToggleActive(view: View) {
        mRule.isActive = !mRule.isActive
        renderSchedule()
    }

    private fun applySwitchState(switchView: FrameLayout, on: Boolean) {
        switchView.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off)
        val thumb = switchView.getChildAt(0) ?: return
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (on) Gravity.END else Gravity.START
        thumb.layoutParams = lp
    }

    // ── Сохранение и удаление ─────────────────────────────────────────────────

    fun onSaveClick(view: View) {
        val accFrom = mAccFrom
        val accTo = mAccTo
        if (accFrom == null) {
            Toast.makeText(this, R.string.operation_error_no_source, Toast.LENGTH_SHORT).show()
            return
        }
        if (accTo == null) {
            Toast.makeText(this, R.string.operation_error_no_target, Toast.LENGTH_SHORT).show()
            return
        }
        if (accFrom.id == accTo.id) {
            Toast.makeText(this, R.string.recurring_error_same_accounts, Toast.LENGTH_SHORT).show()
            return
        }
        val from = asWallet(accFrom)
        val to = asWallet(accTo)
        if (from == null && to == null) {
            Toast.makeText(this, R.string.recurring_error_no_wallet, Toast.LENGTH_LONG).show()
            return
        }
        if (from != null && to != null && from.currency!!.id != to.currency!!.id) {
            Toast.makeText(this, R.string.recurring_error_cross_currency, Toast.LENGTH_LONG).show()
            return
        }

        val amount: Double = try {
            java.lang.Double.parseDouble(mEditAmount.text.toString().replace(',', '.').trim())
        } catch (e: NumberFormatException) {
            0.0
        }
        if (amount <= 0) {
            Toast.makeText(this, R.string.recurring_error_amount, Toast.LENGTH_SHORT).show()
            return
        }
        if (mRule.frequency == RecurringOperation.Frequency.WEEKLY && mRule.weekDays == 0) {
            Toast.makeText(this, R.string.recurring_error_no_days, Toast.LENGTH_SHORT).show()
            return
        }
        val endDate = mRule.endDate
        if (endDate != null && endDate.isBefore(mRule.startDate!!.toLocalDate())) {
            Toast.makeText(this, R.string.recurring_error_end_before_start, Toast.LENGTH_SHORT).show()
            return
        }

        mRule.accFromId = accFrom.id
        mRule.accToId = accTo.id
        mRule.amount = amount
        mRule.comment = mEditComment.text.toString()

        // Дата начала в прошлом (новое правило, перенос даты, включение после паузы):
        // наступившие, но ещё не созданные повторы можно создать сразу или пропустить —
        // решает пользователь, молча досоздавать их нельзя.
        val missed: List<LocalDateTime> = if (mRule.isActive)
            mRule.occurrencesBetween(mRule.lastDate, LocalDateTime.now())
        else
            emptyList()
        if (missed.isNotEmpty()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.recurring_missed_title)
                    .setMessage(resources.getQuantityString(R.plurals.recurring_missed_message,
                            missed.size, missed.size, missed[0].format(DATE_UI)))
                    .setPositiveButton(R.string.recurring_missed_create) { _, _ -> persist() }
                    .setNeutralButton(R.string.recurring_missed_future_only) { _, _ ->
                        mRule.lastDate = missed[missed.size - 1]
                        persist()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            return
        }
        persist()
    }

    private fun persist() {
        val db = db()
        val result = if (mIsEdit) RecurringOperationsDb.update(db, mRule).toLong()
        else RecurringOperationsDb.insert(db, mRule).toLong()
        if (result <= 0) {
            Toast.makeText(this, R.string.recurring_error_save, Toast.LENGTH_SHORT).show()
            return
        }

        val created = RecurringOperationsDb.generateDue(db, LocalDateTime.now())
        RecurringWorker.kick(this)
        if (created > 0) {
            PeriodFilterState.get(this).markDataChanged()
            Toast.makeText(this, getString(R.string.recurring_created, created), Toast.LENGTH_SHORT).show()
        }
        setResult(RESULT_OK)
        finish()
    }

    fun onDeleteClick(view: View) {
        AlertDialog.Builder(this)
                .setTitle(R.string.recurring_delete_question)
                .setMessage(R.string.recurring_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    RecurringOperationsDb.delete(db(), mRule.id)
                    RecurringWorker.kick(this)
                    setResult(RESULT_OK)
                    finish()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }
}
