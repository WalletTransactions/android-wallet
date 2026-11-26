package com.example.wallettransactions.Activities

import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

import java.util.Calendar
import java.util.Date
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.Locale

import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Data.DebtAutoArchive
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader


/**
 * Экран операции (activity_transaction.xml, прототип 2a — блок isAdd).
 *
 * В базе операция хранится двойной записью: списание `From` и зачисление
 * `To`. На экране вместо двух безымянных счетов — чип типа операции и две
 * строки, где верхняя (slotTop) — «другая сторона», нижняя (slotBottom) — кошелёк:
 *
 *  * Расход  — slotTop = To (статья расхода), slotBottom = From (кошелёк)
 *  * Доход   — slotTop = From (статья дохода), slotBottom = To (кошелёк)
 *  * Перевод — slotTop = From (откуда), slotBottom = To (куда)
 *
 * Всё сопоставление собрано в [topIsFrom], чтобы не размазывать его по коду.
 */
class TransactionActivity : AppCompatActivity() {

    companion object {
        const val cTranId = "TransactionActivity.TranId"
        const val cDate = "TransactionActivity.Date"
        const val cComment = "TransactionActivity.Comment"
        const val cAmount = "TransactionActivity.Amount"
        const val cFrom = "TransactionActivity.AccountFromId"
        const val cTo = "TransactionActivity.AccountToId"

        const val cIsEdit = "TransactionActivity.isEdit"

        /**
         * Тип новой операции: имя `DataFilter.OperationKind` (expense/income/transfer).
         * Нужен, когда по одним счетам тип не вывести — например, перевод, у которого
         * заполнено только «Откуда». При правке игнорируется: тип задают сами счета.
         */
        const val cKind = "TransactionActivity.Kind"
        /**
         * Результат «Сохранить»: `_id` главной проводки (расход, перевод, правка).
         * У удаления его нет — по нему экран «ИИ» отличает запись от удаления и
         * строит «Записано: …» по сохранённой операции, а не по черновику.
         */
        const val cSavedOperationId = "TransactionActivity.SavedOperationId"
        /** Текст строки «Подсказка ИИ: …» над полями; пусто — строки нет. */
        const val cHint = "TransactionActivity.Hint"
        /** int[] `_id` кошельков и категорий, которые пикер ставит первыми (кандидаты resolver). */
        const val cPriorityWalletIds = "TransactionActivity.PriorityWalletIds"
        const val cPriorityCategoryIds = "TransactionActivity.PriorityCategoryIds"
        /**
         * uuid, с которым сохранять новую операцию (строка импорта выписки — её `row_id`;
         * openspec add-ai-statement-import, operation-identity). Живая операция с ним уже
         * есть — окно открывается на её правку; удалённая — «Сохранить» восстанавливает ту
         * же строку. У перевода uuid получает главная проводка (списание с источника).
         */
        const val cOpUuid = "TransactionActivity.OpUuid"

        /**
         * Имя ПО УМОЛЧАНИЮ для категории комиссии за перевод — используется только если её
         * приходится завести с нуля ([WalletDbCRUD.GetOrCreateSystemCategory]). Системность
         * (запрет на удаление/переименование) с v75 определяет `system_kind`
         * ([AccountItem.SystemKind.TRANSFER_COMMISSION]), а не это имя.
         */
        private val cCommissionCategoryName: String = AccountItem.SYSTEM_COMMISSION_NAME

        /** Столько же, сколько DEFAULT у currency_pair.precision — на случай, если пары нет. */
        private const val cDefaultRatePrecision = 5

        private const val AccountSelectTop = 0
        private const val AccountSelectBottom = 1
        private const val AccountSelectCommission = 2

        /**
         * Операторы в том виде, в каком они лежат в поле. На клавишах подписи покрасивее
         * (× ÷ −), но в текст суммы идёт ASCII: шрифт суммы юникодных знаков может не знать.
         */
        private const val cOperators = "+-*/"

        private fun isOperator(key: String): Boolean {
            return key.length == 1 && isOperator(key[0])
        }

        private fun isOperator(symbol: Char): Boolean {
            return cOperators.indexOf(symbol) >= 0
        }

        private fun hasOperator(expression: String): Boolean {
            for (i in 0 until expression.length) {
                if (isOperator(expression[i])) return true
            }
            return false
        }

        /** Число, которое набирают прямо сейчас, — всё, что идёт после последнего оператора. */
        private fun currentOperand(expression: String): String {
            for (i in expression.length - 1 downTo 0) {
                if (isOperator(expression[i])) return expression.substring(i + 1)
            }
            return expression
        }
    }

    private enum class Kind { expense, income, transfer }

    private lateinit var mDbHelper: WalletDbHelper

    private var mKind = Kind.expense

    private var mIsEdit = false
    /** [cOpUuid] новой операции; null — uuid случайный (и всегда при правке). */
    private var mOpUuid: String? = null
    /** «Сохранить» уже записал операцию — второе быстрое нажатие ничего не делает. */
    private var mSaved = false
    private lateinit var mEditDate: EditText
    private lateinit var mEditTime: EditText

    private lateinit var mComment: EditText
    private lateinit var mAmount: EditText
    /** Кнопка сохранения — значок дискеты в колонке действий, см. activity_transaction.xml. */
    private var mBtnSave: ImageButton? = null
    private lateinit var mBtnSaveFloating: ImageButton
    /** Кнопка удаления — значок в шапке, виден только при правке (mIsEdit). */
    private lateinit var mBtnDelete: ImageButton

    private lateinit var mScreenTitle: TextView
    private lateinit var mAmountHint: TextView

    /** Подпись под суммой для текущего типа операции: пока в поле выражение, вместо неё результат. */
    private var mAmountHintText = ""

    private lateinit var mEditSlotTop: EditText
    private lateinit var mEditSlotBottom: EditText

    private lateinit var mImgSlotTop: ImageView
    private lateinit var mImgSlotBottom: ImageView
    private lateinit var mCurrencySymbol: TextView
    /** Знак «-» перед суммой расхода: только показывается, в поле суммы его нет. */
    private lateinit var mAmountSign: TextView

    // Чип типа операции — контейнер с иконкой и подписью, а не TextView: см. paintChip.
    private lateinit var mKindExpense: ViewGroup
    private lateinit var mKindIncome: ViewGroup
    private lateinit var mKindTransfer: ViewGroup

    private var mAccFrom: AccountItem? = null
    private var mAccTo: AccountItem? = null

    /**
     * Верхняя строка каждого типа операции — своя сущность (статья расхода, статья
     * дохода, кошелёк «Откуда»), поэтому при переключении вкладок она не переносится,
     * а откладывается сюда и возвращается при возврате на ту же вкладку.
     */
    private val mTopByKind = EnumMap<Kind, AccountItem?>(Kind::class.java)

    private var mTranId = 0
    private var mCurrencyPrecision = 2

    /** Поле, которое сейчас набирает наша клавиатура. Системную мы не показываем нигде. */
    private var mKeypadTarget: EditText? = null
    private lateinit var mKeypad: View

    // ── Перевод между разновалютными счетами: сумма во второй валюте ───────────
    // Не пишем в базу отдельно — операция хранит одну amount (см. TODO_transfers.md).
    private lateinit var mRowAmountTo: View
    private lateinit var mEditAmountTo: EditText
    private lateinit var mTvCurrencySymbolTo: TextView
    private lateinit var mTvRateInfo: TextView
    private var mCurrencyPrecisionTo = 2
    private var mAmountToUserEdited = false
    private var mUpdatingAmountToProgrammatically = false

    // ── Комиссия за перевод: только UI, в базу пока не пишем (TODO_transfers.md) ─
    private lateinit var mRowCommissionToggle: View
    private lateinit var mSwitchCommission: FrameLayout
    private lateinit var mGroupCommissionDetails: View
    private lateinit var mImgCommissionAccount: ImageView
    private lateinit var mEditCommissionAccount: EditText
    private lateinit var mEditCommissionAmount: EditText
    private lateinit var mTvCurrencySymbolCommission: TextView
    private lateinit var mRadioCommissionFromSource: RadioButton
    private lateinit var mRadioCommissionFromDest: RadioButton
    private var mAccCommission: AccountItem? = null
    private var mCommissionEnabled = false
    private var mCommissionPrecision = 2

    private lateinit var mCalDateTime: Calendar

    // originals for dirty-check (edit mode)
    private var mOrigAccFromId = 0
    private var mOrigAccToId = 0
    private var mOrigAmount: String? = null
    private var mOrigComment: String? = null
    private var mOrigDate: String? = null
    private var mOrigTime: String? = null
    private var mOrigAmountTo: String? = null
    private var mOrigCommissionOn = false
    private var mOrigCommissionAccId = 0
    private var mOrigCommissionAmount: String? = null
    private var mOrigCommissionFromDest = false

    /** Проводки этой же операции в базе; -1 — такой части нет. */
    private var mLegId = -1
    private var mCommissionId = -1


    private fun AssignComponents() {
        mComment = findViewById(R.id.editComment)
        mAmount  = findViewById(R.id.editAmount)
        mBtnSave = findViewById(R.id.buttonSave)
        mBtnSaveFloating = findViewById(R.id.buttonSaveFloating)
        mBtnDelete = findViewById(R.id.btnDelete)

        mScreenTitle = findViewById(R.id.screenTitle)
        mAmountHint  = findViewById(R.id.amountHint)

        mEditDate = findViewById(R.id.editDate)
        mEditTime = findViewById(R.id.editTime)

        mEditSlotTop    = findViewById(R.id.editSlotTop)
        mEditSlotBottom = findViewById(R.id.editSlotBottom)

        mImgSlotTop     = findViewById(R.id.imgSlotTop)
        mImgSlotBottom  = findViewById(R.id.imgSlotBottom)
        mCurrencySymbol = findViewById(R.id.tvCurrencySymbol)
        mAmountSign     = findViewById(R.id.tvAmountSign)

        mKindExpense  = findViewById(R.id.kindExpense)
        mKindIncome   = findViewById(R.id.kindIncome)
        mKindTransfer = findViewById(R.id.kindTransfer)

        mRowAmountTo      = findViewById(R.id.rowAmountTo)
        mEditAmountTo     = findViewById(R.id.editAmountTo)
        mTvCurrencySymbolTo = findViewById(R.id.tvCurrencySymbolTo)
        mTvRateInfo       = findViewById(R.id.tvRateInfo)

        mRowCommissionToggle    = findViewById(R.id.rowCommissionToggle)
        mSwitchCommission       = findViewById(R.id.switchCommission)
        mGroupCommissionDetails = findViewById(R.id.groupCommissionDetails)
        mImgCommissionAccount   = findViewById(R.id.imgCommissionAccount)
        mEditCommissionAccount  = findViewById(R.id.editCommissionAccount)
        mEditCommissionAmount   = findViewById(R.id.editCommissionAmount)
        mTvCurrencySymbolCommission = findViewById(R.id.tvCurrencySymbolCommission)
        mRadioCommissionFromSource  = findViewById(R.id.radioCommissionFromSource)
        mRadioCommissionFromDest    = findViewById(R.id.radioCommissionFromDest)

        mEditDate.isFocusable = false
        mEditTime.isFocusable = false
        mEditSlotTop.isFocusable = false
        mEditSlotBottom.isFocusable = false
        mEditCommissionAccount.isFocusable = false

        // Все суммы набираются нашей клавиатурой. Крупная сумма — цель по умолчанию.
        bindKeypadTarget(mAmount)
        bindKeypadTarget(mEditAmountTo)
        bindKeypadTarget(mEditCommissionAmount)
        // По крупной сумме удобнее попадать в строку целиком, а не в само поле.
        findViewById<View>(R.id.rowAmount).setOnClickListener { setKeypadTarget(mAmount) }
        setKeypadTarget(mAmount)

        // Долгое нажатие на ⌫ очищает поле целиком, а не по одному знаку.
        findViewById<View>(R.id.keypadDel).setOnLongClickListener {
            mKeypadTarget!!.text.clear()
            true
        }

        // Калькулятор и системная клавиатура вдвоём съедают почти весь экран —
        // пока пишется комментарий, калькулятор убираем.
        // Слушатель инсетов на content не годится: обёртка AppCompat (fitsSystemWindows)
        // съедает их раньше, и до content они не доходят. Поэтому смотрим на каждую
        // перекладку (adjustResize перекладывает окно при появлении клавиатуры), а
        // состояние берём у корня окна — там инсеты целые.
        mKeypad = findViewById(R.id.keypad)
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnGlobalLayoutListener {
            updateKeypadVisibility(ViewCompat.getRootWindowInsets(content))
        }
    }

    /**
     * Калькулятор скрыт, только пока комментарий в фокусе и системная клавиатура
     * на экране. Закрыли её («Назад») — калькулятор возвращается, а с ним и
     * «Сохранить», хотя фокус ещё в комментарии.
     */
    private fun updateKeypadVisibility(insets: WindowInsetsCompat?) {
        val imeVisible = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
        val typingComment = mComment.hasFocus() && imeVisible
        val visibility = if (typingComment) View.GONE else View.VISIBLE
        // Смена видимости сама вызывает перекладку — без проверки зациклились бы.
        if (mKeypad.visibility != visibility) mKeypad.visibility = visibility
        // Пока калькулятора нет, «Сохранить» живёт поверх поля комментария.
        val floating = if (typingComment) View.VISIBLE else View.GONE
        if (mBtnSaveFloating.visibility != floating) mBtnSaveFloating.visibility = floating
    }

    /** Убирает системную клавиатуру из комментария — ввод уходит в калькулятор. */
    private fun leaveComment() {
        if (!mComment.hasFocus()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(mComment.windowToken, 0)
        mComment.clearFocus()
    }

    /**
     * Отдаёт поле нашей клавиатуре: системная не поднимается, курсора нет,
     * тап только переключает на него ввод.
     */
    private fun bindKeypadTarget(field: EditText) {
        field.showSoftInputOnFocus = false
        field.isFocusable = false
        field.isClickable = true
        field.isCursorVisible = false
        field.setOnClickListener { setKeypadTarget(field) }
    }

    private fun setKeypadTarget(field: EditText) {
        // Оставляем в поле результат, а не выражение: следующий ввод продолжается уже
        // с него. Отдельной клавиши «=» нет — её роль играет тап по полю, в том числе
        // по тому же самому.
        mKeypadTarget?.let { commitExpression(it) }

        leaveComment()
        mKeypadTarget = field

        // Активное поле в полную силу, остальные приглушены — иначе не видно,
        // куда именно уйдёт следующая цифра.
        mAmount.alpha = if (field === mAmount) 1f else 0.4f
        mEditAmountTo.alpha = if (field === mEditAmountTo) 1f else 0.4f
        mEditCommissionAmount.alpha = if (field === mEditCommissionAmount) 1f else 0.4f
    }

    /** Заменяет выражение в поле его значением. Обычное число не трогает. */
    private fun commitExpression(field: EditText) {
        val text = field.text.toString()
        if (!hasOperator(text)) return
        field.setText(formatAmount(parseAmount(text), precisionOf(field)))
    }

    /** Точность — валюты того счёта, к которому привязано активное поле. */
    private fun targetPrecision(): Int {
        return precisionOf(mKeypadTarget!!)
    }

    private fun precisionOf(field: EditText): Int {
        if (field === mEditAmountTo) return mCurrencyPrecisionTo
        if (field === mEditCommissionAmount) return mCommissionPrecision
        return mCurrencyPrecision
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_transaction)

        AssignComponents()

        mDbHelper = WalletDbHelper(this)
        openExistingByUuid()

        mTranId  = intent.getIntExtra(cTranId, -1)
        mIsEdit  = intent.getBooleanExtra(cIsEdit, false)

        mAccFrom = accountById(intent.getIntExtra(cFrom, 0))
        mAccTo   = accountById(intent.getIntExtra(cTo, 0))
        mKind    = deriveKind(mAccFrom, mAccTo)
        if (!mIsEdit) {
            val kind = intent.getStringExtra(cKind)
            if ("income" == kind)        mKind = Kind.income
            else if ("transfer" == kind) mKind = Kind.transfer
            else if ("expense" == kind)  mKind = Kind.expense
        }

        val amount = intent.getDoubleExtra(cAmount, 0.0)
        mAmount.setText(if (amount == 0.0) "" else com.example.wallettransactions.AdapterUtils.plainNumber(amount))
        mComment.setText(intent.getStringExtra(cComment))

        // Экран получает одну проводку, а операция может состоять из нескольких.
        if (mIsEdit) loadLinkedParts()

        val mDateTime = DateUtils.StrToDate(intent.getStringExtra(cDate)!!)

        mScreenTitle.setText(if (mIsEdit) R.string.operation_title_edit else R.string.operation_title_new)

        val hint = intent.getStringExtra(cHint)
        val aiHint = findViewById<TextView>(R.id.tvAiHint)
        aiHint.text = hint
        aiHint.visibility = if (hint == null || hint.isEmpty()) View.GONE else View.VISIBLE
        // Удалить можно только уже существующую операцию.
        mBtnDelete.visibility = if (mIsEdit) View.VISIBLE else View.GONE
        // Подобные ищем только у уже сохранённой операции (similar-search).
        findViewById<View>(R.id.btnSimilar).visibility = if (mIsEdit) View.VISIBLE else View.GONE

        mCalDateTime = Calendar.getInstance()
        mCalDateTime.time = mDateTime
        ShowCalendarDateTime()

        renderKind()

        if (mIsEdit) {
            // Capture originals after all setters
            mOrigAccFromId = mAccFrom?.id ?: 0
            mOrigAccToId   = mAccTo?.id ?: 0
            mOrigAmount    = mAmount.text.toString()
            mOrigComment   = mComment.text.toString()
            mOrigDate      = mEditDate.text.toString()
            mOrigTime      = mEditTime.text.toString()

            // Связанные части тоже участвуют в проверке «что-то поменялось»: иначе
            // правка одной комиссии не включала бы «Сохранить».
            mOrigAmountTo         = mEditAmountTo.text.toString()
            mOrigCommissionOn     = mCommissionEnabled
            mOrigCommissionAccId  = mAccCommission?.id ?: 0
            mOrigCommissionAmount = mEditCommissionAmount.text.toString()
            mOrigCommissionFromDest = mRadioCommissionFromDest.isChecked
        }

        // Кнопку считаем по тем же правилам, что и при любой правке поля. Правка ещё
        // ничего не поменяла — погашена; новая операция, заполненная заранее (черновик
        // из ИИ), сохраняется сразу. Слушатели полей ниже вешаются уже после префилла,
        // поэтому сами они начального состояния не задают.
        updateSaveButton()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSaveButton() }
        }
        mAmount.addTextChangedListener(watcher)
        mComment.addTextChangedListener(watcher)
        mEditAmountTo.addTextChangedListener(watcher)
        mEditCommissionAmount.addTextChangedListener(watcher)

        // Сумма изменилась — пересчитываем и вторую валюту, и подпись с результатом.
        mAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                recomputeAmountTo()
                updateAmountPreview()
            }
        })

        // Как только пользователь тронул поле сам — курс больше не переписывает его значение.
        mEditAmountTo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!mUpdatingAmountToProgrammatically) mAmountToUserEdited = true
            }
        })
    }

    fun btnUp(view: View) {
        finish()
    }

    /**
     * [cOpUuid]: живая операция с этим uuid уже есть — окно становится её правкой
     * (те же extras, что передаёт список операций), а не создаёт вторую. Нет её или она
     * удалена — новая операция сохранится с этим uuid.
     */
    private fun openExistingByUuid() {
        val `in` = intent
        if (`in`.getBooleanExtra(cIsEdit, false)) return
        mOpUuid = `in`.getStringExtra(cOpUuid)
        if (mOpUuid == null) return
        val db = mDbHelper.readableDatabase
        val match = WalletDbCRUD.FindOperationByUuid(db, mOpUuid!!)
        if (match == null || match.deleted) return
        val c = db.rawQuery("SELECT date, acc_fr_id, acc_to_id, amount, comment FROM "
                + WalletContract.OperationEntry.TABLE_NAME + " WHERE _id=?", arrayOf(match.id.toString()))
        try {
            if (!c.moveToFirst()) return
            `in`.putExtra(cTranId, match.id.toInt())
            `in`.putExtra(cIsEdit, true)
            `in`.putExtra(cDate, c.getString(0))
            `in`.putExtra(cFrom, c.getInt(1))
            `in`.putExtra(cTo, c.getInt(2))
            `in`.putExtra(cAmount, c.getDouble(3))
            `in`.putExtra(cComment, c.getString(4))
            `in`.removeExtra(cHint)
            mOpUuid = null
        } finally {
            c.close()
        }
    }

    /** Главная проводка новой операции: с [cOpUuid] или, как раньше, со случайным uuid. */
    private fun insertMain(db: SQLiteDatabase, dateStr: String, fromId: Int, toId: Int, amount: Double, comment: String): Long {
        val uuid = mOpUuid
        if (uuid == null) return WalletDbCRUD.InsertOperation(db, dateStr, fromId, toId, amount, comment)
        val id = WalletDbCRUD.InsertOperationWithUuid(db, uuid, dateStr, fromId, toId, amount, comment)
        return if (id == WalletDbCRUD.UUID_TAKEN.toLong()) -1 else id
    }

    /**
     * «Найти подобные»: прежний фильтр снимается целиком и ставится то, что сейчас на
     * экране, — категория, кошелёк, непустой комментарий и вид (см.
     * [com.example.wallettransactions.DataItems.SimilarFilter]); затем поднимаются «Операции».
     */
    fun btnSimilarClick(view: View) {
        val similar = com.example.wallettransactions.DataItems.SimilarFilter.of(
                mAccFrom, mAccTo, mComment.text.toString())
        PeriodFilterState.get(this).presetFilter(this, similar.accountIds,
                similar.kind, similar.searchText)

        startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    /**
     * Кнопка видна только при правке (mIsEdit) — см. onCreate. Одиночную операцию
     * подтверждаем как раньше; операцию с ногой и/или комиссией — через
     * [showDeleteGroupDialog].
     */
    fun btnDeleteClick(view: View) {
        if (mLegId <= 0 && mCommissionId <= 0) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.action_delete)
                    .setMessage(R.string.operation_delete_question)
                    .setPositiveButton(R.string.action_delete) { _, _ -> deleteOperation(intArrayOf(mTranId)) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            return
        }
        showDeleteGroupDialog()
    }

    /**
     * Операция состоит из нескольких проводок ([mLegId]/[mCommissionId]) —
     * но произвольный выбор, какие из них удалить, не имеет смысла: mTranId/mLegId — это
     * два конца одного и того же перевода (дебет/кредит), удалить один и оставить другой
     * нельзя. Осмысленных вариантов ровно два: снять только комиссию (перевод остаётся
     * слинкован сам с собой) или удалить операцию целиком — этот выбор и даём.
     *
     * Если ноги нет (mLegId <= 0, обычная операция с комиссией), «целиком» — это
     * mTranId+mCommissionId, комиссию всё ещё можно снять отдельно. Если комиссии нет
     * (валютный перевод без неё) — делить вообще не на что, сразу спрашиваем про всё,
     * как для одиночной операции.
     */
    private fun showDeleteGroupDialog() {
        val group = if (mLegId > 0)
            intArrayOf(mTranId, mLegId, mCommissionId)
        else
            intArrayOf(mTranId, mCommissionId)
        val all = group.filter { id -> id > 0 }.toIntArray()

        if (mCommissionId <= 0) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.action_delete)
                    .setMessage(R.string.operation_delete_question)
                    .setPositiveButton(R.string.action_delete) { _, _ -> deleteOperation(all) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            return
        }

        val items = arrayOf(
                getString(R.string.operation_delete_commission),
                getString(R.string.operation_delete_whole)
        )
        AlertDialog.Builder(this)
                .setTitle(R.string.operation_delete_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> deleteOperation(intArrayOf(mCommissionId))
                        1 -> deleteOperation(all)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    /**
     * Удаляет перечисленные проводки — либо только комиссию, либо всю группу целиком
     * (см. [showDeleteGroupDialog] — других вариантов оттуда не приходит).
     *
     * Удаление мягкое ([WalletDbCRUD.DeleteOperation]): строки `operation_link`
     * не трогаем, они переживают soft-delete операции, само удаление доедет до сервера
     * и других устройств следующим синком.
     */
    private fun deleteOperation(ids: IntArray) {
        val db = mDbHelper.writableDatabase
        db.beginTransaction()
        try {
            val debtsBefore = DebtAutoArchive.begin(db)
            for (id in ids) WalletDbCRUD.DeleteOperation(db, id)
            debtsBefore.finish(db) // удаление могло обнулить заём (спека debts «Авто-архив»)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val resultIntent = Intent()
        resultIntent.putExtra(cTranId, mTranId)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Достаёт связанные проводки и разбирает операцию обратно в поля экрана.
     *
     * Экран открывают по одной проводке, а валютный перевод лежит двумя: главная идёт с
     * кошелька на категорию-мост, и без второй ноги её не отличить от обычного расхода.
     * Поэтому получателя, вторую сумму и комиссию берём из связанных проводок, а тип
     * операции ставим переводом уже по факту наличия ноги, а не по `deriveKind`.
     */
    private fun loadLinkedParts() {
        val db = mDbHelper.readableDatabase
        val currencyEq = PeriodFilterState.get(this).getCurrencyEq(this)

        if (mAccTo == null) return

        val linkTypes: Map<Int, OperationItem.LinkType> = WalletDbCRUD.GetLinkedOperations(db, mTranId)
        val children = LinkedHashMap<Int, OperationItem>()
        for (childId in linkTypes.keys) {
            val child = WalletDbLoader.LoadSingleOperation(db, childId,
                    Singleton.instance.getAccounts(this), currencyEq)
            if (child != null) children[childId] = child
        }
        if (children.isEmpty()) return

        // Ногу разбираем первой: она подменяет получателя, а сторону списания комиссии
        // сверяем уже с настоящим кошельком, а не с категорией-мостом.
        //
        // Нога опознаётся по явному operation_link.type (v77, AccountItem... то есть
        // OperationItem.LinkType) — раньше это был вывод по system_kind счёта-источника
        // (а до него — по случайному совпадению id с уже загруженным mAccTo), теперь
        // факт хранится в самой строке связи, а не вычисляется на каждой загрузке.
        for (e in children.entries) {
            val child = e.value
            if (linkTypes[e.key] != OperationItem.LinkType.LEG) continue

            mLegId = e.key
            mAccTo = child.AccTo
            mKind  = Kind.transfer

            val wallet = asWallet(mAccTo)
            setAmountToProgrammatically(formatAmount(child.Amount,
                    if (wallet != null) wallet.currency!!.precision else 2))
            // Сумма уже согласована пользователем — курс её не переписывает.
            mAmountToUserEdited = true
            break
        }

        for (e in children.entries) {
            if (e.key == mLegId) continue

            val child = e.value
            mCommissionId      = e.key
            mCommissionEnabled = true
            mAccCommission     = child.AccTo
            mEditCommissionAmount.setText(formatAmount(child.Amount,
                    if (child.currency != null) child.currency!!.precision else 2))

            // Комиссию списывали с той стороны перевода, с которой ушла её проводка.
            val fromDest = child.AccFrom.id == mAccTo!!.id
            mRadioCommissionFromDest.isChecked = fromDest
            mRadioCommissionFromSource.isChecked = !fromDest

            bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission)
            break
        }
    }

    // ── Тип операции ──────────────────────────────────────────────────────────

    /** Верхняя строка держит From для дохода и перевода, To — для расхода. */
    private fun topIsFrom(): Boolean {
        return mKind != Kind.expense
    }

    private fun accountById(id: Int): AccountItem? {
        return if (id > 0) Singleton.instance.getAccounts(this).getAccountById(id) else null
    }

    /** Тип операции по счетам: у кошелька isOperatingAccount() == true. */
    private fun deriveKind(from: AccountItem?, to: AccountItem?): Kind {
        val fromWallet = from != null && from.isOperatingAccount
        val toWallet   = to   != null && to.isOperatingAccount

        if (fromWallet && toWallet) return Kind.transfer
        if (!fromWallet && toWallet) return Kind.income
        return Kind.expense
    }

    /** Счёт верхней строки — «другая сторона» операции. */
    private fun getTop(): AccountItem? {
        return if (topIsFrom()) mAccFrom else mAccTo
    }

    /** Счёт нижней строки — кошелёк (для перевода «Куда»). */
    private fun getBottom(): AccountItem? {
        return if (topIsFrom()) mAccTo else mAccFrom
    }

    /** Раскладывает пару «верх/низ» в From/To согласно текущему типу операции. */
    private fun setSlots(top: AccountItem?, bottom: AccountItem?) {
        if (topIsFrom()) {
            mAccFrom = top
            mAccTo   = bottom
        } else {
            mAccFrom = bottom
            mAccTo   = top
        }
    }

    fun onKindClick(view: View) {
        val id = view.id
        val picked = if (id == R.id.kindIncome)        Kind.income
        else if (id == R.id.kindTransfer) Kind.transfer
        else                              Kind.expense

        if (picked == mKind) return

        // Кошелёк переезжает вместе с суммой и сохраняет направление денег: у расхода
        // деньги уходят с него — в переводе он «Откуда»; у дохода приходят на него — «Куда».
        // Раньше кошелёк всегда шёл в нижнюю строку, и «расход Сбер → Квартира», переделанный
        // в перевод, становился «Квартира → Сбер» (capital-assets, 2026-11-07). Вторая сторона
        // у каждого типа своя (статья расхода / дохода / другой кошелёк перевода) —
        // запоминаем её, чтобы вернуть, если пользователь придёт на эту вкладку обратно.
        val previous = mKind
        val wallet: AccountItem?
        val other: AccountItem?
        if (previous == Kind.transfer) {
            val walletIsFrom = picked == Kind.expense
            wallet = if (walletIsFrom) mAccFrom else mAccTo
            other  = if (walletIsFrom) mAccTo else mAccFrom
        } else {
            wallet = getBottom()
            other  = getTop()
        }
        mTopByKind[previous] = other

        mKind = picked

        var rest = mTopByKind[mKind]
        // Перевод сам на себя смысла не имеет — а запомненная вторая сторона вполне может
        // совпасть с кошельком, который переехал.
        if (mKind == Kind.transfer && rest != null && wallet != null
                && rest.id == wallet.id) {
            rest = null
        }

        if (mKind == Kind.transfer && previous == Kind.expense) {
            mAccFrom = wallet
            mAccTo   = rest
        } else {
            setSlots(rest, wallet)
        }

        renderKind()
        updateSaveButton()
    }

    /** Красит чипы, подписи строк и сумму под текущий тип операции. */
    private fun renderKind() {
        paintChip(mKindExpense,  mKind == Kind.expense)
        paintChip(mKindIncome,   mKind == Kind.income)
        paintChip(mKindTransfer, mKind == Kind.transfer)

        when (mKind) {
            Kind.income -> {
                mEditSlotTop.setHint(R.string.operation_slot_income_category)
                mEditSlotBottom.setHint(R.string.operation_slot_wallet)
                mAmountHintText = getString(R.string.operation_amount_income)
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colorprofit))
            }
            Kind.transfer -> {
                mEditSlotTop.setHint(R.string.operation_slot_from)
                mEditSlotBottom.setHint(R.string.operation_slot_to)
                mAmountHintText = getString(R.string.operation_amount_transfer)
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colortransfer))
            }
            else -> {
                mEditSlotTop.setHint(R.string.operation_slot_expense_category)
                mEditSlotBottom.setHint(R.string.operation_slot_wallet)
                mAmountHintText = getString(R.string.operation_amount_expense)
                mAmount.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse))
            }
        }

        // Подпись под суммой занята результатом, пока там выражение.
        updateAmountPreview()

        // «⇅» между строками — только у перевода: у расхода и дохода стороны разного рода.
        findViewById<View>(R.id.btnSwapSlots).visibility = if (mKind == Kind.transfer) View.VISIBLE else View.GONE

        // Минус — только у расхода, и только как подпись: сама сумма всегда без знака.
        mAmountSign.visibility = if (mKind == Kind.expense) View.VISIBLE else View.GONE

        updateCommissionVisibility()
        renderSlots()
    }

    private fun paintChip(chip: ViewGroup, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)

        val color = ContextCompat.getColor(this,
                if (active) R.color.colorSurface else R.color.colorTextSecondary)
        // Иконка типа операции живёт в самом чипе, поэтому красится вместе с подписью.
        // Порядок вьюх задан разметкой: сначала иконка, потом подпись.
        (chip.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (chip.getChildAt(1) as TextView).setTextColor(color)
    }

    /** Раскладывает mAccFrom/mAccTo по строкам согласно типу операции. */
    private fun renderSlots() {
        bindSlot(mImgSlotTop, mEditSlotTop, getTop())
        bindSlot(mImgSlotBottom, mEditSlotBottom, getBottom())

        // Валюта суммы — та же, что выведет OperationItem: кошелёк списания,
        // а если списывают с категории (доход) — кошелёк зачисления.
        var wallet = asWallet(mAccFrom)
        if (wallet == null) wallet = asWallet(mAccTo)

        if (wallet != null) {
            val cur = wallet.currency
            mCurrencySymbol.text = cur!!.symbol
            mCurrencyPrecision = cur!!.precision
            updateAmountFilter()
        } else {
            mCurrencySymbol.text = ""
        }

        updateCrossCurrencyUI()
        updateCommissionCurrency()
    }

    /**
     * «⇅» у перевода: «Откуда» и «Куда» меняются местами. У разновалютного перевода вместе
     * с ними меняются и суммы — каждая остаётся при своём кошельке; зачисление после этого
     * считается введённым вручную, чтобы курс не переписал его.
     */
    fun onSwapSlots(view: View) {
        if (mKind != Kind.transfer) return
        val wasCross = isCrossCurrencyActive()
        val amountFrom = mAmount.text.toString()
        val amountTo = mEditAmountTo.text.toString()

        val from = mAccFrom
        mAccFrom = mAccTo
        mAccTo = from

        val swapAmounts = wasCross && isCrossCurrencyActive() && amountTo.isNotEmpty()
        if (swapAmounts) mAmountToUserEdited = true
        renderSlots()
        if (swapAmounts) {
            mAmount.setText(amountTo)
            setAmountToProgrammatically(amountFrom)
        }
        updateSaveButton()
    }

    private fun asWallet(item: AccountItem?): AccountOperatingItem? {
        return item as? AccountOperatingItem
    }


    private fun bindSlot(img: ImageView, edit: EditText, item: AccountItem?) {
        if (item != null) {
            img.setImageBitmap(item.icon!!.bitmap)
            edit.setText(item.displayName)
        } else {
            val iconList: IconList = Singleton.instance.getIconList(this)
            img.setImageBitmap(iconList.unknownIcon!!.bitmap)
            edit.setText("")
        }
    }

    // ── Цифровая клавиатура ───────────────────────────────────────────────────

    fun onKeypadKey(view: View) {
        val tag = view.tag ?: return
        val key = tag.toString()

        val text = mKeypadTarget!!.text
        val len = text.length

        if ("del" == key) {
            if (len > 0) text.delete(len - 1, len)
            return
        }

        if ("clear" == key) {
            text.clear()
            return
        }

        if (isOperator(key)) {
            // Знак у суммы свой — тип операции, поэтому выражение начинается с числа.
            if (len == 0) return
            // Оператор за оператором не копится, а заменяет предыдущий: так исправляют
            // промах, не стирая набранное.
            if (isOperator(text[len - 1])) text.replace(len - 1, len, key)
            else text.append(key)
            return
        }

        // Точка и незначащий ноль относятся к числу, которое набирают прямо сейчас,
        // а не ко всей строке: в «120+3» дробная часть у каждого слагаемого своя.
        val operand = currentOperand(text.toString())

        if ("." == key) {
            // Дробная часть либо уже начата, либо не нужна вовсе.
            if (targetPrecision() == 0 || operand.indexOf('.') >= 0) return
            if (operand.isEmpty()) text.append("0")
            text.append(".")
            return
        }

        if ("000" == key) {
            // Тысячи дописывают к уже набранному числу: «000» само по себе не сумма.
            // В дробную часть они не влезут — лишние знаки отсечёт фильтр точности.
            if (operand.isEmpty() || "0" == operand) return
            text.append(key)
            return
        }

        // Незначащий ноль в начале числа заменяем первой набранной цифрой.
        if ("0" == operand) {
            text.replace(len - 1, len, key)
            return
        }

        // Фильтр точности отсечёт лишние знаки после запятой сам.
        text.append(key)
    }

    private fun updateSaveButton() {
        val btnSave = mBtnSave ?: return
        val enabled: Boolean
        if (mIsEdit) {
            val curFromId = mAccFrom?.id ?: 0
            val curToId   = mAccTo?.id ?: 0
            val curCommissionId = mAccCommission?.id ?: 0
            enabled = curFromId != mOrigAccFromId
                    || curToId   != mOrigAccToId
                    || mAmount.text.toString() != mOrigAmount
                    || mComment.text.toString() != mOrigComment
                    || mEditDate.text.toString() != mOrigDate
                    || mEditTime.text.toString() != mOrigTime
                    // Связанные части — тоже часть операции.
                    || mEditAmountTo.text.toString() != mOrigAmountTo
                    || mCommissionEnabled != mOrigCommissionOn
                    || curCommissionId != mOrigCommissionAccId
                    || mEditCommissionAmount.text.toString() != mOrigCommissionAmount
                    || mRadioCommissionFromDest.isChecked != mOrigCommissionFromDest
        } else {
            enabled = mAccFrom != null && mAccTo != null
                    && mAmount.text.toString().isNotEmpty()
        }
        btnSave.isEnabled = enabled
        btnSave.alpha = if (enabled) 1f else 0.4f
        // Плавающая копия — слегка прозрачная даже когда доступна, чтобы не перекрывать текст.
        mBtnSaveFloating.isEnabled = enabled
        mBtnSaveFloating.alpha = if (enabled) 0.75f else 0.3f
    }

    private fun ShowCalendarDateTime() {
        mEditDate.setText(String.format("%1\$td %1\$tb %1\$tY", mCalDateTime))
        mEditTime.setText(String.format("%1\$tH:%1\$tM", mCalDateTime))
        updateSaveButton()
        recomputeAmountTo() // курс ищется на дату операции — дата сменилась, курс обновляем
    }

    /** Кнопки «−»/«+» у даты: сдвиг на день; время не меняется (Calendar.add учитывает смену месяца и года). */
    fun btnDatePrevClick(view: View) { stepDate(-1) }

    fun btnDateNextClick(view: View) { stepDate(1) }

    private fun stepDate(days: Int) {
        mCalDateTime.add(Calendar.DAY_OF_MONTH, days)
        ShowCalendarDateTime()
    }

    fun btnDateClick(view: View) {
        // Время операции задаётся отдельным полем — календарь его не трогает.
        CalendarPicker.show(this, mCalDateTime) { year, month, day ->
            mCalDateTime.set(Calendar.YEAR, year)
            mCalDateTime.set(Calendar.MONTH, month)
            mCalDateTime.set(Calendar.DAY_OF_MONTH, day)

            ShowCalendarDateTime()
        }
    }


    fun btnTimeClick(view: View) {
        // Launch Time Picker Dialog

        val mHour = mCalDateTime.get(Calendar.HOUR)
        val mMinute = mCalDateTime.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(this,
                { _, hourOfDay, minute ->
                    mCalDateTime.set(Calendar.MINUTE, minute)
                    mCalDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay)

                    ShowCalendarDateTime()
                }, mHour, mMinute, true)
        timePickerDialog.show()
    }


    fun SaveClick(view: View?) {
        if (mSaved) return

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

        // Both accounts being categories means no currency → broken data
        if (accFrom !is AccountOperatingItem && accTo !is AccountOperatingItem) {
            Toast.makeText(this, R.string.operation_error_two_categories, Toast.LENGTH_LONG).show()
            return
        }

        // Вторая нога валютного перевода — отдельная проводка, и сумма у неё своя:
        // из курса её только предлагают, подтвердить должен пользователь.
        if (isCrossCurrencyActive() && parseAmount(mEditAmountTo.text.toString()) <= 0) {
            Toast.makeText(this, R.string.operation_error_amount_to, Toast.LENGTH_LONG).show()
            return
        }

        val db = mDbHelper.writableDatabase
        val values = ContentValues()

        val dateStr = DateUtils.DateToStr(Date(mCalDateTime.timeInMillis))
        val comment = mComment.text.toString()
        val amount = parseAmount(mAmount.text.toString())

        // На клавиатуре есть вычитание и деление, так что выражение легко сводится
        // к нулю или к минусу — такую сумму в базу пускать нельзя.
        if (amount <= 0) {
            Toast.makeText(this, R.string.operation_error_amount_zero, Toast.LENGTH_SHORT).show()
            return
        }
        // Заём на заём (debts) — не операция пользователя, а ошибка выбора: не пишем.
        val fromW = asWallet(accFrom)
        val toW = asWallet(accTo)
        if (fromW != null && toW != null && fromW.isDebt && toW.isDebt) {
            Toast.makeText(this, R.string.debt_error_loan_to_loan, Toast.LENGTH_LONG).show()
            return
        }
        if (askOverpay(db, amount)) return

        values.put(WalletContract.OperationEntry.COLUMN_DATE, dateStr)
        values.put(WalletContract.OperationEntry.COLUMN_FROM, accFrom.id)
        values.put(WalletContract.OperationEntry.COLUMN_TO, accTo.id)
        values.put(WalletContract.OperationEntry.COLUMN_COMMENT, comment)
        values.put(WalletContract.OperationEntry.COLUMN_AMOUNT, amount)

        var newRowId: Long = -1

        // Авто-архив погашенного займа (openspec wallets-filter-and-debt-autoarchive, D2): остатки
        // займов до записи и после неё, в одной транзакции с операцией.
        db.beginTransaction()
        try {
            val debtsBefore = DebtAutoArchive.begin(db)

            if (mIsEdit) {
                newRowId = saveEdit(db, dateStr, comment, amount)

            } else if (mKind == Kind.transfer) {
                newRowId = saveTransfer(db, dateStr, comment, amount)

            } else if (mOpUuid != null) {
                newRowId = insertMain(db, dateStr, accFrom.id, accTo.id, amount, comment)

            } else {
                // uuid — v72 (org/docs/TODO_mobile_sync.md, Фаза 3): единственный прямой
                // db.insert на operation в этом файле, остальные пути (saveTransfer/
                // saveEdit) уже идут через WalletDbCRUD.InsertOperation.
                values.put(WalletContract.OperationEntry.COLUMN_UUID, java.util.UUID.randomUUID().toString())
                newRowId = db.insert(WalletContract.OperationEntry.TABLE_NAME, null, values)
            }

            if (newRowId != -1L) {
                debtsBefore.finish(db)
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
        }

        if (newRowId == -1L) {
            // Если ID  -1, значит произошла ошибка
            Toast.makeText(this, R.string.operation_error_update, Toast.LENGTH_SHORT).show()
        } else {
            mSaved = true
            // Кошелёк операции: у расхода и перевода — списания, у дохода — зачисления.
            val wallet = if (mKind == Kind.income) mAccTo else mAccFrom
            val lastAsWallet = asWallet(wallet)
            if (wallet != null && wallet.isOperatingAccount && (lastAsWallet == null || !lastAsWallet.isDebt))
                AppSettings.setLastWalletId(this, wallet.id)

            val resultIntent = Intent()
            resultIntent.putExtra(cTranId, mTranId)
            resultIntent.putExtra(cSavedOperationId, newRowId)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    /**
     * Перевод: одна операция на экране — одна или несколько проводок в базе, связанных
     * через `operation_link`.
     *
     *  * одинаковые валюты — одна проводка кошелёк→кошелёк;
     *  * разные — две ноги через категорию «Transfer»: у операции одна сумма, а тут
     *    их две, в разных валютах, и вторая берётся из поля по курсу;
     *  * комиссия, если включена, — отдельная проводка с кошелька выбранной стороны
     *    на категорию комиссии.
     *
     * Главная проводка (`main_id`) — всегда списание с кошелька-источника; и вторая
     * нога, и комиссия цепляются к ней.
     *
     * @return id главной проводки или -1
     */
    private fun saveTransfer(db: SQLiteDatabase, dateStr: String, comment: String, amount: Double): Long {
        db.beginTransaction()
        try {
            val mainId: Long

            if (isCrossCurrencyActive()) {
                val bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                        AccountItem.SystemKind.TRANSFER.value,
                        AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
                if (bridgeId <= 0) return -1

                val amountTo = parseAmount(mEditAmountTo.text.toString())

                mainId = insertMain(db, dateStr, mAccFrom!!.id, bridgeId, amount, comment)
                val legId = WalletDbCRUD.InsertOperation(db, dateStr,
                        bridgeId, mAccTo!!.id, amountTo, comment)
                if (mainId <= 0 || legId <= 0) return -1

                WalletDbCRUD.LinkOperations(db, mainId, legId, OperationItem.LinkType.LEG)
            } else {
                mainId = insertMain(db, dateStr, mAccFrom!!.id, mAccTo!!.id, amount, comment)
                if (mainId <= 0) return -1
            }

            val commissionId = saveCommission(db, dateStr, comment)
            if (commissionId == -1L) return -1
            WalletDbCRUD.LinkOperations(db, mainId, commissionId, OperationItem.LinkType.COMMISSION)

            db.setTransactionSuccessful()
            return mainId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Сохранение правки. Операция может состоять из нескольких проводок, и правка меняет
     * их все разом: сумму и счета главной, вторую ногу и комиссию — включая случаи, когда
     * нога или комиссия появляются впервые либо, наоборот, больше не нужны (перевод
     * перестал быть валютным, комиссию выключили, тип операции сменили на расход).
     *
     * @return id главной проводки или -1
     */
    private fun saveEdit(db: SQLiteDatabase, dateStr: String, comment: String, amount: Double): Long {
        db.beginTransaction()
        try {
            val cross = mKind == Kind.transfer && isCrossCurrencyActive()

            var bridgeId = -1
            if (cross) {
                bridgeId = WalletDbCRUD.GetOrCreateSystemCategory(db,
                        AccountItem.SystemKind.TRANSFER.value,
                        AccountItem.SYSTEM_TRANSFER_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
                if (bridgeId <= 0) return -1
            }

            // У валютного перевода главная проводка идёт на мост, а не на кошелёк-получатель.
            val mainToId = if (cross) bridgeId else mAccTo!!.id
            WalletDbCRUD.UpdateOperation(db, mTranId, dateStr, mAccFrom!!.id, mainToId, amount, comment)

            if (cross) {
                val amountTo = parseAmount(mEditAmountTo.text.toString())
                if (mLegId > 0) {
                    WalletDbCRUD.UpdateOperation(db, mLegId, dateStr, bridgeId, mAccTo!!.id,
                            amountTo, comment)
                } else {
                    val legId = WalletDbCRUD.InsertOperation(db, dateStr, bridgeId, mAccTo!!.id,
                            amountTo, comment)
                    if (legId <= 0) return -1
                    WalletDbCRUD.LinkOperations(db, mTranId.toLong(), legId, OperationItem.LinkType.LEG)
                    mLegId = legId.toInt()
                }
            } else if (mLegId > 0) {
                // Валюты сравнялись или это уже не перевод — второй ноге больше не место.
                WalletDbCRUD.DeleteOperation(db, mLegId)
                mLegId = -1
            }

            if (!updateCommission(db, dateStr, comment)) return -1

            db.setTransactionSuccessful()
            return mTranId.toLong()
        } finally {
            db.endTransaction()
        }
    }

    /** @return false при ошибке. Заводит, правит или убирает проводку комиссии. */
    private fun updateCommission(db: SQLiteDatabase, dateStr: String, comment: String): Boolean {
        val commission = parseAmount(mEditCommissionAmount.text.toString())
        val fromDest = mRadioCommissionFromDest.isChecked
        val side = asWallet(if (fromDest) mAccTo else mAccFrom)

        val wanted = mKind == Kind.transfer && mCommissionEnabled && commission > 0 && side != null

        if (!wanted) {
            if (mCommissionId > 0) {
                WalletDbCRUD.DeleteOperation(db, mCommissionId)
                mCommissionId = -1
            }
            return true
        }

        val commissionAcc = mAccCommission
        val categoryId = if (commissionAcc != null)
            commissionAcc.id
        else
            WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                    cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME)
        if (categoryId <= 0) return false

        if (mCommissionId > 0) {
            WalletDbCRUD.UpdateOperation(db, mCommissionId, dateStr, side!!.id, categoryId,
                    commission, comment)
            return true
        }

        val id = WalletDbCRUD.InsertOperation(db, dateStr, side!!.id, categoryId, commission, comment)
        if (id <= 0) return false

        WalletDbCRUD.LinkOperations(db, mTranId.toLong(), id, OperationItem.LinkType.COMMISSION)
        mCommissionId = id.toInt()
        return true
    }

    /** @return id проводки комиссии, 0 — комиссии нет, -1 — ошибка. */
    private fun saveCommission(db: SQLiteDatabase, dateStr: String, comment: String): Long {
        if (!mCommissionEnabled) return 0

        val commission = parseAmount(mEditCommissionAmount.text.toString())
        if (commission <= 0) return 0

        // Комиссию платит та сторона перевода, которую выбрал пользователь.
        val fromDest = mRadioCommissionFromDest.isChecked
        val side = asWallet(if (fromDest) mAccTo else mAccFrom) ?: return 0

        val commissionAcc = mAccCommission
        val categoryId = if (commissionAcc != null)
            commissionAcc.id
        else
            WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                    cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME)
        if (categoryId <= 0) return -1

        val id = WalletDbCRUD.InsertOperation(db, dateStr, side.id, categoryId,
                commission, comment)
        return if (id > 0) id else -1
    }

    // ── Выбор счетов ──────────────────────────────────────────────────────────

    fun onPickTop(view: View) {
        val current = if (topIsFrom()) mAccFrom else mAccTo
        // Для перевода сверху кошелёк «Откуда», иначе — статья расхода/дохода.
        val type = if (mKind == Kind.transfer)
            AccountPickerActivity.cSelectedTypeOperationAccounts
        else
            AccountPickerActivity.cSelectedTypeChargeAccounts
        openAccountPicker(mEditSlotTop.hint.toString(), current, getBottom(), type, AccountSelectTop)
    }

    fun onPickBottom(view: View) {
        val current = if (topIsFrom()) mAccTo else mAccFrom
        openAccountPicker(mEditSlotBottom.hint.toString(), current, getTop(),
                AccountPickerActivity.cSelectedTypeOperationAccounts, AccountSelectBottom)
    }

    /**
     * Заголовок пикера — подпись самого слота («Статья расхода», «Куда», …): тип счёта
     * там уже задан, и переключателя категорий/кошельков на экране выбора не будет.
     * `pair` — счёт второго слота: по нему пикер сортирует «по частоте».
     */
    /** Приоритетные счета для пикера этого типа: кошельки, категории или оба списка. */
    private fun priorityIds(selectedType: Int): IntArray? {
        val wallets = intent.getIntArrayExtra(cPriorityWalletIds)
        val categories = intent.getIntArrayExtra(cPriorityCategoryIds)
        if (selectedType == AccountPickerActivity.cSelectedTypeOperationAccounts) return wallets
        if (selectedType == AccountPickerActivity.cSelectedTypeChargeAccounts) return categories
        val nw = wallets?.size ?: 0
        val nc = categories?.size ?: 0
        if (nw + nc == 0) return null
        val all = IntArray(nw + nc)
        if (nw > 0) System.arraycopy(wallets!!, 0, all, 0, nw)
        if (nc > 0) System.arraycopy(categories!!, 0, all, nw, nc)
        return all
    }

    private fun openAccountPicker(title: String, current: AccountItem?, pair: AccountItem?,
                                  selectedType: Int, requestCode: Int) {
        val intent = AccountPickerActivity.buildIntent(this, title, selectedType,
                if (current == null) 0 else current.id, pair, priorityIds(selectedType))
        // Долги (debts, design D6): над статьями — «Дать в долг» / «Взять в долг» / «Вернуть»;
        // среди кошельков новой операции займов нет, при правке — видны.
        intent.putExtra(AccountPickerActivity.cDebtActions,
                requestCode == AccountSelectTop && selectedType == AccountPickerActivity.cSelectedTypeChargeAccounts)
        intent.putExtra(AccountPickerActivity.cHideDebts, !mIsEdit)
        // Вид категории (category-kind): «Статья расхода» — расход и «Оба», «Статья дохода» —
        // доход и «Оба». Только верхний слот расхода/дохода; у перевода верх — кошелёк, а
        // категория комиссии выбирается отдельным путём (AccountSelectCommission) и не фильтруется.
        if (requestCode == AccountSelectTop
                && selectedType == AccountPickerActivity.cSelectedTypeChargeAccounts
                && mKind != Kind.transfer) {
            intent.putExtra(AccountPickerActivity.cCategoryKind, if (mKind == Kind.income) 1 else 0)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return
        if (data == null) return

        val accountId = data.getIntExtra(AccountPickerActivity.cAccountId, -1)
        if (accountId == -1) {
            Snackbar.make(this.window.decorView.rootView, R.string.operation_error_no_account, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            return
        }

        val picked = accountById(accountId)

        val debtDirection = data.getIntExtra(AccountPickerActivity.cDebtDirection, 0)
        if (debtDirection != 0 && picked != null) {
            applyDebtPick(picked, debtDirection)
            return
        }

        if (requestCode == AccountSelectCommission) {
            mAccCommission = picked
            bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission)
            return
        }

        val intoFrom = (requestCode == AccountSelectTop) == topIsFrom()

        if (intoFrom) mAccFrom = picked
        else          mAccTo = picked

        // Счёт поменяли — курс нужно предложить заново, а не держать старое ручное значение.
        mAmountToUserEdited = false

        renderSlots()
        updateSaveButton()
    }

    /**
     * Результат «Дать в долг» / «Взять в долг» / «Вернуть» (debts, design D6): операция
     * становится переводом между кошельком из нижней строки и займом, в нужную сторону.
     */
    private fun applyDebtPick(loan: AccountItem, direction: Int) {
        var wallet = getBottom()
        // «Ваш кошелёк» — только обычный: заём на заём перевода не бывает (debts; проверка на
        // Pixel 8 Pro — подставленный «последний кошелёк» оказался займом).
        val asLoan = asWallet(wallet)
        if (wallet != null && (!wallet.isOperatingAccount || wallet.id == loan.id
                || (asLoan != null && asLoan.isDebt))) wallet = null
        mTopByKind[mKind] = getTop()
        mKind = Kind.transfer
        if (direction == com.example.wallettransactions.Data.DebtDirection.WALLET_TO_LOAN) {
            mAccFrom = wallet
            mAccTo = loan
        } else {
            mAccFrom = loan
            mAccTo = wallet
        }
        mAmountToUserEdited = false
        renderKind()
        updateSaveButton()
    }

    /** Подтверждённая переплата — второй вызов SaveClick не спрашивает снова. */
    private var mOverpayConfirmed = false

    /**
     * Переплата займа (debts, D11): перевод гасит остаток и переходит через ноль — сначала
     * предупреждение. Только для новой операции: при правке старая проводка уже в остатке.
     *
     * @return true — спросили, сохранение ждёт ответа
     */
    private fun askOverpay(db: SQLiteDatabase, amount: Double): Boolean {
        if (mIsEdit || mOverpayConfirmed) return false
        val to = asWallet(mAccTo)
        val from = asWallet(mAccFrom)
        val toDebt = to != null && to.isDebt
        val fromDebt = from != null && from.isDebt
        if (toDebt == fromDebt) return false
        val loan = (if (toDebt) to else from)!!
        // Сумма в валюте займа: у разновалютного перевода получатель считает вторую ногу.
        val inLoanCurrency = if (toDebt && isCrossCurrencyActive())
            parseAmount(mEditAmountTo.text.toString()) else amount
        val balance = WalletDbLoader.LoadAccountBalance(db, loan.id)
        if (!com.example.wallettransactions.Data.DebtDirection.isOverpay(balance, toDebt, inLoanCurrency)) return false
        AlertDialog.Builder(this)
                .setTitle(R.string.debt_overpay_title)
                .setMessage(getString(R.string.debt_overpay_message,
                        DebtPickFlow.money(Math.abs(balance), loan.currency),
                        DebtPickFlow.money(inLoanCurrency, loan.currency)))
                .setPositiveButton(R.string.debt_overpay_ok) { _, _ ->
                    mOverpayConfirmed = true
                    SaveClick(null)
                }
                .setNegativeButton(android.R.string.cancel, null) // спека «Отказ»: форма как была
                .show()
        return true
    }

    // ── Комиссия за перевод ───────────────────────────────────────────────────

    fun onToggleCommission(view: View) {
        mCommissionEnabled = !mCommissionEnabled
        applySwitchState(mSwitchCommission, mCommissionEnabled)
        mGroupCommissionDetails.visibility = if (mCommissionEnabled) View.VISIBLE else View.GONE

        if (mCommissionEnabled) {
            // Категорию подставляем сразу, но менять её можно — см. onPickCommissionAccount.
            if (mAccCommission == null) {
                mAccCommission = defaultCommissionAccount()
                bindSlot(mImgCommissionAccount, mEditCommissionAccount, mAccCommission)
            }
            updateCommissionCurrency()
        } else if (mKeypadTarget === mEditCommissionAmount) {
            setKeypadTarget(mAmount)
        }
    }

    /**
     * Категория комиссии по умолчанию. В старых базах её может не быть — начальный
     * справочник накатывался до того, как комиссия появилась, — поэтому заводим на месте
     * и обновляем справочник в памяти, иначе строка осталась бы без имени и иконки.
     */
    private fun defaultCommissionAccount(): AccountItem? {
        val accounts = Singleton.instance.getAccounts(this)

        val existing = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER_COMMISSION)
        if (existing != null) return existing

        val id = WalletDbCRUD.GetOrCreateSystemCategory(mDbHelper.writableDatabase,
                AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                cCommissionCategoryName, AccountItem.SYSTEM_TRANSFER_NAME)
        if (id <= 0) return null

        WalletDbLoader.LoadAccounts(mDbHelper.readableDatabase, accounts,
                Singleton.instance.getCurrencyList(this),
                Singleton.instance.getIconList(this), null)
        return accounts.getAccountById(id)
    }

    fun onPickCommissionAccount(view: View) {
        // Пара — кошелёк, с которого списывается комиссия.
        val side = if (mRadioCommissionFromDest.isChecked) mAccTo else mAccFrom
        openAccountPicker(getString(R.string.operation_commission_account), mAccCommission, side,
                AccountPickerActivity.cSelectedTypeChargeAccounts, AccountSelectCommission)
    }

    fun onCommissionSideChanged(view: View) {
        updateCommissionCurrency()
    }

    /**
     * Блок комиссии есть только у перевода. На других вкладках он просто сворачивается:
     * набранное не стираем — вернётся вместе со вкладкой, как сумма и счета.
     */
    private fun updateCommissionVisibility() {
        val isTransfer = mKind == Kind.transfer
        mRowCommissionToggle.visibility = if (isTransfer) View.VISIBLE else View.GONE
        mGroupCommissionDetails.visibility = if (isTransfer && mCommissionEnabled) View.VISIBLE else View.GONE

        // Поля спрятали — клавиатуре нужна другая цель, иначе цифры уходят в никуда.
        if (!isTransfer && mKeypadTarget === mEditCommissionAmount) setKeypadTarget(mAmount)
    }

    /** Комиссия снимается с того же кошелька, что выбран как сторона списания. */
    private fun updateCommissionCurrency() {
        val fromDest = mRadioCommissionFromDest.isChecked
        val side = asWallet(if (fromDest) mAccTo else mAccFrom)
        mTvCurrencySymbolCommission.text = if (side != null) side.currency!!.symbol else ""
        if (side != null) {
            mCommissionPrecision = side.currency!!.precision
            applyDecimalFilter(mEditCommissionAmount, mCommissionPrecision)
        }
    }

    /** Копия переключателя из SettingsActivity: двигает бегунок и красит фон пилюли. */
    private fun applySwitchState(switchView: FrameLayout, on: Boolean) {
        switchView.tag = on
        switchView.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off)
        val thumb = switchView.getChildAt(0) ?: return
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (on) android.view.Gravity.END else android.view.Gravity.START
        thumb.layoutParams = lp
    }

    // ── Перевод между разновалютными счетами: сумма во второй валюте ───────────

    private fun isCrossCurrencyActive(): Boolean {
        val from = asWallet(mAccFrom)
        val to = asWallet(mAccTo)
        return mKind == Kind.transfer && from != null && to != null
                && from.currency!!.id != to.currency!!.id
    }

    /** Показывает/скрывает второе поле суммы и подбирает под него точность/символ валюты. */
    private fun updateCrossCurrencyUI() {
        if (!isCrossCurrencyActive()) {
            // Только прячем: набранную вручную сумму не стираем, иначе она пропадала бы от
            // одного переключения вкладки. Скрытое поле никуда не идёт — ни в базу, ни в
            // расчёты, — а сбрасывает его смена кошелька (см. onActivityResult).
            mRowAmountTo.visibility = View.GONE
            // Поле спрятали — клавиатуре нужна другая цель, иначе цифры уходят в никуда.
            if (mKeypadTarget === mEditAmountTo) setKeypadTarget(mAmount)
            return
        }

        val curTo = asWallet(mAccTo)!!.currency
        mCurrencyPrecisionTo = curTo!!.precision
        mTvCurrencySymbolTo.text = curTo!!.symbol
        mEditAmountTo.hint = zeroHint(mCurrencyPrecisionTo)
        applyDecimalFilter(mEditAmountTo, mCurrencyPrecisionTo)
        mRowAmountTo.visibility = View.VISIBLE

        recomputeAmountTo()
    }

    /**
     * Ищет курс на дату операции и, если пользователь ещё не тронул поле сам,
     * подставляет туда сумму по курсу. Дата/сумма/счета изменились — курс и подсказка
     * обновляются в любом случае, а значение поля — только пока оно не введено вручную.
     */
    private fun recomputeAmountTo() {
        if (!isCrossCurrencyActive()) return

        val curFrom = asWallet(mAccFrom)!!.currency
        val curTo   = asWallet(mAccTo)!!.currency

        val rate = WalletDbLoader.LoadRateOnDate(mDbHelper.readableDatabase,
                curFrom!!.id, curTo!!.id, mCalDateTime.time)

        if (rate == null) {
            mTvRateInfo.text = getString(R.string.operation_rate_not_found,
                    String.format("%1\$td.%1\$tm.%1\$tY", mCalDateTime))
            // Без курса подставить нечего, а прежнее значение осталось от другой пары
            // валют — показывать его как сумму зачисления нельзя.
            if (!mAmountToUserEdited) setAmountToProgrammatically("")
            return
        }

        // Дата — та, на которую курс реально стоит: ближайшая не позже даты операции,
        // а не сама дата операции. Точность — инструмента, а не валюты: у пары она
        // своя (currency_pair.precision), и двух знаков курсу обычно мало.
        mTvRateInfo.text = getString(R.string.operation_rate_info,
                String.format("%1\$td.%1\$tm.%1\$tY", rate.date), curFrom!!.iso,
                formatAmount(rate.rate, ratePrecision(curFrom, curTo)), curTo!!.iso)

        if (mAmountToUserEdited) return

        val fromAmount = parseAmount(mAmount.text.toString())
        setAmountToProgrammatically(if (fromAmount > 0) formatAmount(fromAmount * rate.rate, mCurrencyPrecisionTo) else "")
    }

    /**
     * Точность курса — свойство валютной пары (`currency_pair.precision`), а не
     * валют: курс сам по себе не сумма, и округление до копеек его портит.
     *
     * Пара ищется в любом направлении: обратный курс считает `v_currency_conversion_rate`,
     * а точность у пары одна. Если прямой пары нет — курс пришёл обходным путём через
     * `currency_conversion`, и остаётся значение по умолчанию, как у колонки.
     */
    private fun ratePrecision(from: Currency, to: Currency): Int {
        val pairs = Singleton.instance.getCurrencyPairList(this)

        for (i in 0 until pairs.size()) {
            val pair = pairs.getItem(i)
            val base = pair.baseCurrency
            val quote = pair.quoteCurrency
            if (base == null || quote == null) continue

            val direct  = base.id == from.id && quote.id == to.id
            val reverse = base.id == to.id && quote.id == from.id
            if (direct || reverse) return pair.precision
        }
        return cDefaultRatePrecision
    }

    private fun setAmountToProgrammatically(text: String) {
        mUpdatingAmountToProgrammatically = true
        mEditAmountTo.setText(text)
        mUpdatingAmountToProgrammatically = false
    }

    /**
     * В поле суммы лежит не обязательно число: клавиатура умеет + − × ÷, и там может
     * быть выражение вида `1200+35*2`. Незаконченный хвост («1200+») отбрасываем —
     * пользователь просто ещё не дописал, а деление на ноль и мусор дают 0.
     */
    private fun parseAmount(raw: String?): Double {
        if (raw == null) return 0.0

        var expression = raw.replace(",", "").replace(" ", "")
        while (expression.isNotEmpty() && isOperator(expression[expression.length - 1])) {
            expression = expression.substring(0, expression.length - 1)
        }
        if (expression.isEmpty()) return 0.0

        val value = evaluate(expression)
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    /**
     * Значение выражения из чисел и операторов [cOperators], приоритет обычный:
     * умножение и деление раньше сложения и вычитания. Разбор в один проход — цепочка
     * × ÷ копится в `term`, а в `total` слагаемое уходит только на + или −.
     *
     * @return NaN, если строка не выражение или делят на ноль
     */
    private fun evaluate(expression: String): Double {
        var total = 0.0
        var term = 0.0
        var addOp = '+'
        var mulOp = '\u0000'

        var i = 0
        while (i < expression.length) {
            val start = i
            while (i < expression.length && !isOperator(expression[i])) i++

            val value: Double
            try {
                value = java.lang.Double.parseDouble(expression.substring(start, i))
            } catch (e: NumberFormatException) {
                return Double.NaN
            }

            term = if (mulOp == '*') term * value
            else if (mulOp == '/') (if (value == 0.0) Double.NaN else term / value)
            else value

            if (i == expression.length) break

            val op = expression[i++]
            if (op == '*' || op == '/') {
                mulOp = op
            } else {
                total = if (addOp == '+') total + term else total - term
                addOp = op
                mulOp = '\u0000'
            }
        }

        return if (addOp == '+') total + term else total - term
    }

    private fun formatAmount(value: Double, precision: Int): String {
        return String.format(Locale.US, "%." + precision + "f", value)
    }

    private fun updateAmountFilter() {
        mAmount.hint = zeroHint(mCurrencyPrecision)
        applyDecimalFilter(mAmount, mCurrencyPrecision)
        updateAmountPreview() // точность сменилась — результат под суммой тоже
    }

    /**
     * Пока в сумме выражение, подпись под ней показывает, что получится: иначе
     * результат виден только после сохранения.
     */
    private fun updateAmountPreview() {
        val text = mAmount.text.toString()
        fitAmountTextSize(text)
        mAmountHint.text = if (hasOperator(text))
            "= " + formatAmount(parseAmount(text), mCurrencyPrecision)
        else
            mAmountHintText
    }

    /**
     * Крупный шрифт (40sp), пока текст помещается в строке суммы; мельчим шагами
     * лишь когда места не хватает — иначе поле обрежет длинное выражение.
     */
    @Suppress("DEPRECATION")
    private fun fitAmountTextSize(text: String) {
        val row = mAmount.parent as View
        val sign = findViewById<View>(R.id.tvAmountSign)
        val cur = findViewById<View>(R.id.tvCurrencySymbol)
        val available = row.width - row.paddingLeft - row.paddingRight -
                (if (sign.visibility == View.VISIBLE) sign.width else 0) -
                cur.width - (cur.layoutParams as ViewGroup.MarginLayoutParams).marginStart -
                mAmount.paddingLeft - mAmount.paddingRight
        var sp = 40f
        if (available > 0 && text.isNotEmpty()) {
            val density = resources.displayMetrics.scaledDensity
            val paint = android.graphics.Paint(mAmount.paint)
            while (sp > 20f) {
                paint.textSize = sp * density
                if (paint.measureText(text) <= available) break
                sp -= 2f
            }
        }
        if (mAmount.textSize != sp * resources.displayMetrics.scaledDensity) {
            mAmount.textSize = sp
        }
    }

    private fun zeroHint(precision: Int): String {
        val hint = StringBuilder("0")
        if (precision > 0) {
            hint.append(".")
            for (i in 0 until precision) hint.append("0")
        }
        return hint.toString()
    }

    private fun applyDecimalFilter(target: EditText, precision: Int) {
        target.filters = arrayOf<InputFilter>(object : InputFilter {
            override fun filter(src: CharSequence, start: Int, end: Int,
                                dst: Spanned, dstart: Int, dend: Int): CharSequence? {
                // Build the resulting string after the proposed edit
                val result = dst.subSequence(0, dstart).toString() +
                        src.subSequence(start, end) +
                        dst.subSequence(dend, dst.length)
                if (result.isEmpty() || result == ".") return null
                // Точность проверяем у каждого числа выражения отдельно: «10.50+2.25»
                // целиком под правило «не больше precision знаков» не подходит.
                for (operand in Regex("[+\\-*/]").split(result)) {
                    val dotIdx = operand.indexOf('.')
                    if (dotIdx >= 0) {
                        val decimals = operand.length - dotIdx - 1
                        if (decimals > precision) return ""
                    }
                }
                return null // accept
            }
        })
    }
}
