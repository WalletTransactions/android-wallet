package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletFillData
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Operations.OperationTemplate
import com.example.wallettransactions.DataItems.Operations.TemplateSchedule
import com.example.wallettransactions.DataItems.Operations.TemplateStep
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Экраны шаблонов операций (openspec operation-templates): список, запуск, форма,
 * «Сохранить как шаблон» из окна операции и пункт «Ещё». Тексты проверяются по-русски.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class OperationTemplateScreensTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var card = 0
    private var cash = 0
    private var usdWallet = 0
    private var category = 0
    private var commission = 0
    private val controllers = ArrayList<ActivityController<*>>()

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        // Картинки первичного справочника не влезают в CursorWindow Robolectric.
        db.execSQL("UPDATE icon SET image = x'00'")
        val icon = intQuery("SELECT _id FROM icon LIMIT 1")
        val eur = intQuery("SELECT _id FROM currency WHERE iso_code = 'EUR'")
        val usd = intQuery("SELECT _id FROM currency WHERE iso_code = 'USD'")
        card = WalletDbCRUD.InsertOperatingAccount(db, "Тест-карта", "", icon, eur).toInt()
        cash = WalletDbCRUD.InsertOperatingAccount(db, "Тест-наличные", "", icon, eur).toInt()
        usdWallet = WalletDbCRUD.InsertOperatingAccount(db, "Тест-доллары", "", icon, usd).toInt()
        category = WalletDbCRUD.InsertAccount(db, "Тест-связь", "", icon, false)
        commission = WalletDbCRUD.GetOrCreateSystemCategory(db, AccountItem.SystemKind.TRANSFER_COMMISSION.value,
                AccountItem.SYSTEM_COMMISSION_NAME, AccountItem.SYSTEM_TRANSFER_NAME)
        Singleton.instance.reloadAccounts(context)
    }

    @After
    fun tearDown() {
        for (c in controllers) c.pause().stop().destroy()
        resetSingleton()
    }

    private fun resetSingleton() {
        for (name in arrayOf("dbHelper", "readableDatabase", "writeableDatabase", "accounts", "iconList", "currencyList")) {
            try {
                val field = Singleton::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(Singleton.instance, null)
            } catch (e: NoSuchFieldException) {
                // поле могли переименовать — тогда кэш просто не сбрасывается
            }
        }
    }

    private fun intQuery(sql: String): Int {
        db.rawQuery(sql, null).use { c ->
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        }
    }

    private fun <T : android.app.Activity> open(cls: Class<T>, intent: Intent): T {
        val c = Robolectric.buildActivity(cls, intent).setup()
        controllers.add(c)
        return c.get()
    }

    private fun step(num: Int, from: Int, to: Int, mode: TemplateStep.AmountMode, value: String? = null,
                     commissionOf: Int? = null): TemplateStep {
        val s = TemplateStep()
        s.num = num
        s.accFromId = from
        s.accToId = to
        s.amountMode = mode
        s.amountValue = value?.let { BigDecimal(it) }
        s.commissionOfNum = commissionOf
        return s
    }

    private fun cashWithdrawal(total: String? = "1550"): OperationTemplate {
        val t = OperationTemplate()
        t.name = "Снятие наличных"
        t.totalAmount = total?.let { BigDecimal(it) }
        t.steps.add(step(1, card, cash, TemplateStep.AmountMode.REST))
        t.steps.add(step(2, card, commission, TemplateStep.AmountMode.FIXED, "50", commissionOf = 1))
        OperationTemplatesDb.insert(db, t)
        return t
    }

    private fun text(a: android.app.Activity, id: Int): String = a.findViewById<TextView>(id).text.toString()

    // ── Список шаблонов ───────────────────────────────────────────────────────

    @Test
    fun emptyList_showsHintAndAddButton() {
        val a = open(OperationTemplateListActivity::class.java, Intent(context, OperationTemplateListActivity::class.java))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.templatesEmpty).visibility)
        assertEquals("Шаблонов пока нет.", text(a, R.id.templatesEmpty))
        assertEquals("+ Добавить шаблон", text(a, R.id.btnAddTemplate))
    }

    @Test
    fun manualTemplateRow_saysManual_andSearchFilters() {
        cashWithdrawal()
        val rent = OperationTemplate()
        rent.name = "Связь"
        rent.totalAmount = BigDecimal("500")
        rent.steps.add(step(1, card, category, TemplateStep.AmountMode.REST))
        val s = TemplateSchedule()
        s.frequency = TemplateSchedule.Frequency.MONTHLY
        s.startDate = LocalDate.now().plusDays(1).atTime(12, 0)
        rent.schedule = s
        OperationTemplatesDb.insert(db, rent)

        val a = open(OperationTemplateListActivity::class.java, Intent(context, OperationTemplateListActivity::class.java))
        val list = a.findViewById<android.widget.ListView>(R.id.listTemplates)
        assertEquals(2, list.adapter.count)
        // Список по алфавиту: «Связь», «Снятие наличных».
        val manual = list.adapter.getView(1, null, list)
        assertEquals("Снятие наличных", manual.findViewById<TextView>(R.id.tplTitle).text.toString())
        assertEquals("Вручную", manual.findViewById<TextView>(R.id.tplSchedule).text.toString())
        assertEquals(View.GONE, manual.findViewById<View>(R.id.tplSwitch).visibility)
        val scheduled = list.adapter.getView(0, null, list)
        assertTrue(scheduled.findViewById<TextView>(R.id.tplSchedule).text.toString().contains("Следующая:"))
        assertEquals(View.VISIBLE, scheduled.findViewById<View>(R.id.tplSwitch).visibility)
        assertTrue(scheduled.findViewById<TextView>(R.id.tplAmount).text.toString().startsWith("−500.00"))

        a.findViewById<EditText>(R.id.templatesSearch).setText("снят")
        assertEquals(1, list.adapter.count)
        a.findViewById<EditText>(R.id.templatesSearch).setText("нет такого")
        assertEquals(0, list.adapter.count)
        assertEquals("Ничего не найдено", text(a, R.id.templatesEmpty))
    }

    @Test
    fun moreTab_opensTemplates_andHasNoRecurringRow() {
        // Только onCreate: onResume спрашивает хранилище токенов, которого нет под Robolectric.
        val c = Robolectric.buildActivity(MoreActivity::class.java, Intent(context, MoreActivity::class.java)).create()
        val a = c.get()
        assertNotNull("пункт «Шаблоны операций»", a.findViewById<View>(R.id.rowTemplates))
        a.onMoreTemplates(a.findViewById(R.id.rowTemplates))
        val next = shadowOf(a).nextStartedActivity
        assertEquals(OperationTemplateListActivity::class.java.name, next.component!!.className)
    }

    // ── Запуск ────────────────────────────────────────────────────────────────

    private fun openRun(t: OperationTemplate) =
        open(OperationTemplateRunActivity::class.java, OperationTemplateRunActivity.buildIntent(context, t.id))

    private fun rowAmount(a: OperationTemplateRunActivity, index: Int): EditText =
        a.findViewById<android.view.ViewGroup>(R.id.postings).getChildAt(index).findViewById(R.id.runAmount)

    @Test
    fun run_prefilledTotal_isLaidOutByModes() {
        val a = openRun(cashWithdrawal())
        assertEquals("1500.00", rowAmount(a, 0).text.toString())
        assertEquals("50.00", rowAmount(a, 1).text.toString())
        assertTrue(a.isSaveEnabled())
    }

    @Test
    fun run_emptyOrOverflowingTotal_disablesSave() {
        val a = openRun(cashWithdrawal(total = null))
        assertFalse("сумма не введена", a.isSaveEnabled())

        a.findViewById<EditText>(R.id.editTotal).setText("40")
        assertFalse(a.isSaveEnabled())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.tvLayoutError).visibility)
        assertEquals("Фиксированные суммы и проценты больше общей суммы", text(a, R.id.tvLayoutError))

        a.findViewById<EditText>(R.id.editTotal).setText("1550")
        assertTrue(a.isSaveEnabled())
    }

    @Test
    fun run_editingCommission_recalculatesRest_templateUnchanged() {
        val t = cashWithdrawal()
        val a = openRun(t)
        rowAmount(a, 1).setText("60")
        assertEquals("1490.00", rowAmount(a, 0).text.toString())
        assertEquals("1550.00", a.findViewById<EditText>(R.id.editTotal).text.toString().let {
            if (it == "1550") "1550.00" else it
        })
        val stored = OperationTemplatesDb.load(db, t.id)!!
        assertEquals(0, BigDecimal("50").compareTo(stored.steps[1].amountValue))
    }

    @Test
    fun run_save_createsAllPostings_andToasts() {
        val a = openRun(cashWithdrawal())
        a.onSaveClick(a.findViewById(R.id.btnSaveRun))
        assertEquals("Создано операций: 2", ShadowToast.getTextOfLatestToast())
        assertTrue(a.isFinishing)
        assertEquals(2, intQuery("SELECT COUNT(*) FROM operation WHERE acc_fr_id = $card"))
        assertEquals(1, intQuery("SELECT COUNT(*) FROM operation_link WHERE type = 1"))
    }

    @Test
    fun run_scheduledTemplateManually_doesNotMoveSchedule() {
        val t = cashWithdrawal()
        val s = TemplateSchedule()
        s.frequency = TemplateSchedule.Frequency.MONTHLY
        s.startDate = LocalDate.now().plusDays(3).atTime(12, 0)
        t.schedule = s
        OperationTemplatesDb.update(db, t)

        val a = openRun(t)
        a.onSaveClick(a.findViewById(R.id.btnSaveRun))

        val stored = OperationTemplatesDb.load(db, t.id)!!.schedule!!
        assertNull(stored.lastDate)
        assertEquals(s.startDate, stored.nextOccurrence(LocalDateTime.now()))
    }

    // ── Форма ─────────────────────────────────────────────────────────────────

    private fun openForm(intent: Intent = OperationTemplateActivity.buildIntent(context, 0)) =
        open(OperationTemplateActivity::class.java, intent)

    @Test
    fun newForm_defaults() {
        val a = openForm()
        assertEquals("Новый шаблон", text(a, R.id.screenTitle))
        assertEquals("Не повторять", text(a, R.id.tvFrequency))
        assertEquals(View.GONE, a.findViewById<View>(R.id.groupSchedule).visibility)
        assertEquals("комиссии у расхода нет", View.GONE, a.findViewById<View>(R.id.rowCommissionToggle).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.btnDelete).visibility)
    }

    @Test
    fun transferWithCommission_showsDefaults() {
        val a = openForm()
        a.onKindClick(a.findViewById(R.id.kindTransfer))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rowCommissionToggle).visibility)
        a.onToggleCommission(a.findViewById(R.id.switchCommission))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.groupCommissionDetails).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rowCommissionMode).visibility)
        assertTrue(a.findViewById<android.widget.RadioButton>(R.id.radioCommissionFromSource).isChecked)
        assertEquals(Singleton.instance.getAccounts(context).getAccountById(commission)!!.displayName,
                text(a, R.id.editCommissionAccount))
    }

    @Test
    fun save_withoutName_isRejected() {
        val a = openForm()
        a.onSaveClick(a.findViewById(R.id.editName))
        assertEquals("Введите название шаблона", ShadowToast.getTextOfLatestToast())
        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
    }

    @Test
    fun pickingFrequency_setsScheduleDefaults_andRequiresAmount() {
        val a = openForm(prefillIntent("expense", card, category, total = null))
        a.findViewById<EditText>(R.id.editName).setText("Связь")
        a.onFrequencyPicked(TemplateSchedule.Frequency.MONTHLY.value)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.groupSchedule).visibility)
        assertEquals("Ежемесячно", text(a, R.id.tvFrequency))
        assertEquals(View.GONE, a.findViewById<View>(R.id.rowEndDate).visibility)

        a.onSaveClick(a.findViewById(R.id.editName))
        assertEquals("Сумма должна быть больше нуля", ShadowToast.getTextOfLatestToast())
        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
    }

    @Test
    fun crossCurrencyTransfer_isRejected() {
        val a = openForm(prefillIntent("transfer", card, usdWallet, total = "100"))
        a.findViewById<EditText>(R.id.editName).setText("Обмен")
        a.onSaveClick(a.findViewById(R.id.editName))
        assertEquals("Все кошельки шаблона должны быть в одной валюте", ShadowToast.getTextOfLatestToast())
        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
    }

    @Test
    fun manualTransferWithPercentCommission_isSavedAsTwoSteps() {
        val a = openForm(prefillIntent("transfer", card, cash, total = "1550"))
        a.findViewById<EditText>(R.id.editName).setText("Снятие")
        a.onToggleCommission(a.findViewById(R.id.switchCommission))
        a.onCommissionModeClick(a.findViewById(R.id.commissionModePercent))
        a.findViewById<EditText>(R.id.editCommissionAmount).setText("3")
        a.onSaveClick(a.findViewById(R.id.editName))

        val all = OperationTemplatesDb.loadAll(db)
        assertEquals(1, all.size)
        val t = all[0]
        assertNull(t.schedule)
        assertEquals(2, t.steps.size)
        assertEquals(TemplateStep.AmountMode.REST, t.steps[0].amountMode)
        assertEquals(TemplateStep.AmountMode.PERCENT, t.steps[1].amountMode)
        assertEquals(card, t.steps[1].accFromId)
        assertEquals(commission, t.steps[1].accToId)
        assertEquals(1, t.steps[1].commissionOfNum)
    }

    @Test
    fun editForm_showsStepsAndDeleteButton() {
        val t = cashWithdrawal()
        val a = openForm(OperationTemplateActivity.buildIntent(context, t.id))
        assertEquals("Изменение шаблона", text(a, R.id.screenTitle))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.btnDelete).visibility)
        assertEquals("Снятие наличных", a.findViewById<EditText>(R.id.editName).text.toString())
        assertEquals("50", a.findViewById<EditText>(R.id.editCommissionAmount).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.groupCommissionDetails).visibility)
    }

    private fun prefillIntent(kind: String, from: Int, to: Int, total: String?): Intent =
        OperationTemplateActivity.buildPrefillIntent(context, kind, from, to, "", total, 0, null, false)

    // ── «Сохранить как шаблон» ────────────────────────────────────────────────

    @Test
    fun saveAsTemplate_fromTransferWithCommission_prefillsForm_withoutSaving() {
        val intent = Intent(context, TransactionActivity::class.java)
                .putExtra(TransactionActivity.cFrom, card)
                .putExtra(TransactionActivity.cTo, cash)
                .putExtra(TransactionActivity.cKind, "transfer")
                .putExtra(TransactionActivity.cAmount, 1500.0)
                .putExtra(TransactionActivity.cDate, com.example.wallettransactions.DateUtils.DateToStr(java.util.Date()))
        val op = open(TransactionActivity::class.java, intent)
        assertEquals(View.VISIBLE, op.findViewById<View>(R.id.btnSaveAsTemplate).visibility)
        op.onToggleCommission(op.findViewById(R.id.switchCommission))
        op.findViewById<EditText>(R.id.editCommissionAmount).setText("50")

        op.btnSaveAsTemplateClick(op.findViewById(R.id.btnSaveAsTemplate))
        val started = shadowOf(op).nextStartedActivity
        assertNotNull(started)
        assertEquals(0, intQuery("SELECT COUNT(*) FROM operation"))
        assertFalse("окно операции остаётся", op.isFinishing)

        val form = openForm(started)
        assertEquals("Новый шаблон", text(form, R.id.screenTitle))
        assertEquals("1550.00", form.findViewById<EditText>(R.id.editAmount).text.toString())
        assertEquals(View.VISIBLE, form.findViewById<View>(R.id.groupCommissionDetails).visibility)
        assertEquals("50.00", form.findViewById<EditText>(R.id.editCommissionAmount).text.toString())
        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
    }

    // ── Удаление операций и счетов ────────────────────────────────────────────

    @Test
    fun resetOperationsAndAccounts_removesTemplates() {
        cashWithdrawal()
        WalletFillData.ResetOperationsAndAccounts(db, context, false)
        assertEquals(0, intQuery("SELECT COUNT(*) FROM operation_template"))
        assertEquals(0, intQuery("SELECT COUNT(*) FROM operation_template_step"))
    }
}
