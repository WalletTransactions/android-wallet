package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.CategoryKindChips
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.SyncRunner

class AccountItemActivity : AppCompatActivity() {

    private lateinit var imageAccView: ImageView
    private lateinit var imageCurencyView: ImageView
    private lateinit var tvCurrencyLabel: TextView
    private lateinit var editAccName: EditText
    private lateinit var editComment: EditText
    private lateinit var checkIsArchive: CheckBox
    private lateinit var operatingGroup: LinearLayout
    private lateinit var btnDelete: Button

    private lateinit var account: AccountItem
    private var selectedIconId = -1
    private var selectedCurrency: Currency? = null
    private lateinit var btnSave: Button

    // Original values for change detection
    private var originalName: String? = null
    private var originalComment: String? = null
    private var originalArchived = false

    /** Вид категории (category-kind): чипы есть только у обычной категории, у кошелька и системной — null. */
    private var categoryKindChips: CategoryKindChips? = null
    private var originalCategoryKind: AccountItem.CategoryKind? = null
    private var originalCurrencyId = -1
    private var originalIconId = -1

    /** Признак актива на входе (capital-assets); null — обычный кошелёк или категория. */
    private var originalAsset: AccountOperatingItem.AssetInfo? = null
    private var assetFields: AssetFields? = null

    /** Признак займа (debts); null — категория. */
    private var debtFields: DebtFields? = null

    /** Лист «Списать остаток?», пока открыт (archive-wallet-writeoff). */
    private var writeOffDialog: ArchiveWriteOffDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_item)

        imageAccView = findViewById(R.id.imageAccView)
        imageCurencyView = findViewById(R.id.imageCurencyView)
        tvCurrencyLabel = findViewById(R.id.tvCurrencyLabel)
        editAccName = findViewById(R.id.editAccName)
        editComment = findViewById(R.id.editComment)
        checkIsArchive = findViewById(R.id.checkIsArchive)
        operatingGroup = findViewById(R.id.operatingGroup)
        btnDelete = findViewById(R.id.btnDelete)
        btnSave = findViewById(R.id.btnSave)

        val found = Singleton.instance.getAccounts(this)
            .getAccountById(intent.getIntExtra(ARG_ACCOUNT_ID, 0))
        if (found == null) {
            finish()
            return
        }
        account = found

        // Remember originals before populating fields
        originalName = account.name
        originalComment = account.comment
        originalArchived = account.isArchived
        originalIconId = account.icon!!.id
        selectedIconId = originalIconId

        imageAccView.setImageBitmap(account.icon!!.bitmap)
        editAccName.setText(account.name)
        editComment.setText(account.comment)
        checkIsArchive.isChecked = account.isArchived

        val acc = account
        if (acc is AccountOperatingItem) {
            setScreenTitle(getString(R.string.account_title_wallet))
            selectedCurrency = acc.currency
            originalCurrencyId = selectedCurrency!!.id
            operatingGroup.visibility = View.VISIBLE
            refreshCurrencyUI()
            originalAsset = acc.asset
            assetFields = AssetFields(this, originalAsset, true) {
                updateSaveButton()
                debtFields?.refresh()
            }
            debtFields = DebtFields(this, acc.debt, account.name) { updateSaveButton() }
            // «Переоценить» — только у актива, сохранённого как актив (не у только что отмеченного).
            findViewById<View>(R.id.btnRevaluate).visibility = if (originalAsset != null) View.VISIBLE else View.GONE
        } else {
            setScreenTitle(getString(R.string.account_title_category))
            operatingGroup.visibility = View.GONE
        }

        val db = WalletDbHelper(this).readableDatabase
        if (!WalletDbCRUD.HasOperations(db, account.id) && !account.isSystem) {
            btnDelete.visibility = View.VISIBLE
        }
        // Перенос операций в актив — у обычной категории, если активы вообще есть.
        val canMove = !account.isOperatingAccount && !account.isSystem && WalletDbCRUD.HasAssets(db)
        findViewById<View>(R.id.btnMoveToAsset).visibility = if (canMove) View.VISIBLE else View.GONE

        if (account.isSystem) {
            // Системный счёт опознаётся по имени, поэтому переименование пришлось бы
            // считать удалением: правило обошли бы в один шаг.
            editAccName.isEnabled = false
            editComment.setHint(
                if (account.isSystemCommission) R.string.account_system_commission_hint
                else if (account.isSystemRevaluation) R.string.account_system_revaluation_hint
                else R.string.account_system_transfer_hint
            )
        }

        // Вид категории (openspec category-kind-income-expense): только у обычной категории.
        // У кошелька и системной категории (Transfer, комиссия, переоценка) вид всегда «Оба» и
        // выбора на экране нет — блок скрыт, а вид при сохранении не трогается.
        originalCategoryKind = account.categoryKind
        val chips = CategoryKindChips(this, originalCategoryKind) { updateSaveButton() }
        categoryKindChips = chips
        val regularCategory = !account.isOperatingAccount && !account.isSystem
        chips.setVisible(regularCategory)
        if (!regularCategory) categoryKindChips = null

        // Save is disabled until something changes
        btnSave.isEnabled = false
        btnSave.alpha = 0.4f

        val changeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSaveButton()
            }
        }
        editAccName.addTextChangedListener(changeWatcher)
        editComment.addTextChangedListener(changeWatcher)

        checkIsArchive.setOnCheckedChangeListener { _, _ -> updateSaveButton() }
    }

    private fun setScreenTitle(title: String) {
        findViewById<TextView>(R.id.screenTitle).text = title
    }

    fun btnUp(view: View) {
        finish()
    }

    /**
     * Операции этого счёта (operations-filter): прежний фильтр снимается целиком, остаётся
     * только он. «Операции» поднимаются так же, как по тапу строки в «Кошельках» и «Отчётах».
     */
    fun btnFilterClick(view: View) {
        PeriodFilterState.get(this).presetFilter(
            this,
            listOf(account.id), null, null
        )

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun updateSaveButton() {
        val currency = selectedCurrency
        val kindChips = categoryKindChips
        val af = assetFields
        val df = debtFields
        val changed =
            editAccName.text.toString().trim() != originalName
                    || editComment.text.toString().trim() != originalComment
                    || checkIsArchive.isChecked != originalArchived
                    || selectedIconId != originalIconId
                    || (currency != null && currency.id != originalCurrencyId)
                    || (af != null && af.differsFrom(originalAsset))
                    || (df != null && df.differs())
                    || (kindChips != null && kindChips.selected != originalCategoryKind)
        btnSave.isEnabled = changed
        btnSave.alpha = if (changed) 1f else 0.4f
    }

    /** Справочник иконок вместо галереи — см. AddCategoryActivity.pickIconClick. */
    fun pickIconClick(view: View) {
        val title = getString(
            if (account is AccountOperatingItem) R.string.icon_title_wallet else R.string.icon_title_category
        )
        startActivityForResult(
            IconPickerActivity.buildIntent(this, title, Icon.IconType.CATEGORY, selectedIconId),
            REQUEST_PICK_ICON
        )
    }

    /** Тот же экран «Список валют», что в «Валютных парах» (openspec wallet-currency-picker). */
    fun pickCurrencyClick(view: View) {
        startActivityForResult(
            CurrencyPickerActivity.buildIntent(
                this, getString(R.string.account_choose_currency),
                selectedCurrency?.iso
            ),
            REQUEST_PICK_CURRENCY
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (debtFields != null && debtFields!!.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode == REQUEST_PICK_WRITEOFF_CATEGORY) {
            if (resultCode != RESULT_OK || data == null || writeOffDialog == null) return
            val id = data.getIntExtra(AccountPickerActivity.cAccountId, 0)
            writeOffDialog!!.setCategory(Singleton.instance.getAccounts(this).getAccountById(id))
            return
        }
        if (requestCode == REQUEST_PICK_CURRENCY) {
            if (resultCode != RESULT_OK || data == null) return
            val picked = Singleton.instance.getCurrencyList(this)
                .getCurrencyByISO(data.getStringExtra(CurrencyPickerActivity.RESULT_ISO)) ?: return
            selectedCurrency = picked
            refreshCurrencyUI()
            updateSaveButton()
            return
        }
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return

        selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId)

        val icon = Singleton.instance.getIconList(this).getIconById(selectedIconId)
        if (icon != null) imageAccView.setImageBitmap(icon.bitmap)
        updateSaveButton()
    }

    fun btnSaveClick(view: View) {
        var name = editAccName.text.toString().trim()
        if (name.isEmpty()) {
            editAccName.error = getString(R.string.account_error_name_empty)
            return
        }

        // Поле имени у системного счёта отключено, но исходное имя всё равно
        // навязываем: правило опознаёт системность именно по нему.
        if (account.isSystem) name = originalName!!
        if (debtFields != null && !debtFields!!.validate()) return

        val db = WalletDbHelper(this).writableDatabase
        if (WalletDbCRUD.IsAccountNameTaken(db, name, account.id)) {
            editAccName.error = getString(R.string.account_error_name_taken)
            return
        }

        // Архивация кошелька с остатком — сначала лист «Списать остаток?»
        // (archive-wallet-writeoff); сохранение — по его кнопке.
        val balance = if (account is AccountOperatingItem)
            WalletDbCRUD.RoundedWalletBalance(db, account.id, precision()) else 0.0
        if (ArchiveWriteOffDialog.isOffered(
                account is AccountOperatingItem, originalArchived,
                checkIsArchive.isChecked, originalAsset != null,
                assetFields != null && assetFields!!.isAsset(), balance
            )
        ) {
            showWriteOffDialog(name, balance)
            return
        }
        save(name, 0, null)
    }

    /** Валюта кошелька: выбранная на экране или сохранённая; null — категория. */
    private fun walletCurrency(): Currency? {
        val acc = account
        return selectedCurrency ?: if (acc is AccountOperatingItem) acc.currency else null
    }

    private fun precision(): Int {
        val c = walletCurrency()
        return c?.precision ?: 2
    }

    private fun showWriteOffDialog(name: String, balance: Double) {
        val c = walletCurrency()
        val money = AdapterUtils.getFormat(precision()).format(balance) + " " + (c?.symbol ?: "")
        val dialog = ArchiveWriteOffDialog(
            this,
            getString(R.string.archive_writeoff_message, account.displayName, money),
            object : ArchiveWriteOffDialog.Listener {
                override fun onWriteOff(categoryId: Int, day: String) {
                    save(name, categoryId, day)
                }

                override fun onArchiveWithoutWriteOff() {
                    save(name, 0, null)
                }

                override fun onPickCategory(currentId: Int) {
                    val pick = AccountPickerActivity.buildIntent(
                        this@AccountItemActivity,
                        getString(R.string.archive_writeoff_picker_title),
                        AccountPickerActivity.cSelectedTypeChargeAccounts, currentId, account
                    )
                    pick.putExtra(AccountPickerActivity.cHideSystem, true)
                    startActivityForResult(pick, REQUEST_PICK_WRITEOFF_CATEGORY)
                }
            })
        writeOffDialog = dialog
        dialog.show()
    }

    /**
     * Запись экрана. `writeOffCategoryId` &gt; 0 — в той же транзакции списать остаток
     * кошелька на эту категорию датой `writeOffDay` (archive-wallet-writeoff, D9):
     * не вышло — откатывается всё, экран остаётся открытым.
     */
    private fun save(name: String, writeOffCategoryId: Int, writeOffDay: String?) {
        val db = WalletDbHelper(this).writableDatabase
        val iconId = if (selectedIconId > 0) selectedIconId else account.icon!!.id
        var wroteOff = false

        // Счёт, валюта и признак актива — одной транзакцией (capital-assets). Снятие
        // признака операции кошелька не трогает.
        db.beginTransaction()
        try {
            WalletDbCRUD.UpdateAccount(
                db, account.id, name,
                editComment.text.toString().trim(), iconId, checkIsArchive.isChecked,
                categoryKindChips?.selected
            )

            val currency = selectedCurrency
            if (account is AccountOperatingItem && currency != null) {
                WalletDbCRUD.UpdateAccountCurrency(db, account.id, currency.id)
            }
            val af = assetFields
            if (af != null && af.differsFrom(originalAsset)) {
                if (af.isAsset()) {
                    WalletDbCRUD.SetAccountAsset(db, account.id, af.type(), af.includeInNetWorth())
                } else {
                    WalletDbCRUD.ClearAccountAsset(db, account.id)
                }
            }
            // Заём (debts): после снятия актива — иначе SetAccountDebt откажет «уже актив».
            if (debtFields != null && !debtFields!!.save(db, account.id)) {
                Toast.makeText(this, R.string.debt_error_no_counterparty, Toast.LENGTH_LONG).show()
                return // без setTransactionSuccessful — откат правок
            }
            if (writeOffCategoryId > 0) {
                val r = WalletDbCRUD.WriteOffBalance(
                    db, account.id,
                    writeOffCategoryId, writeOffDay!!, precision(), getString(R.string.archive_writeoff_comment)
                )
                if (r == WalletDbCRUD.WriteOffResult.failed) {
                    Toast.makeText(this, R.string.archive_writeoff_failed, Toast.LENGTH_LONG).show()
                    return // без setTransactionSuccessful — откат правок вместе с проводкой
                }
                wroteOff = r == WalletDbCRUD.WriteOffResult.written
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        reloadSingleton()
        // Табы в стеке («Операции» — другая Activity, onActivityResult до неё не дойдёт)
        // перечитываются по ревизии: признак актива, имя, архив и валюта меняют их итоги
        // и списки (capital-assets, design п. 10, android-wallet#42).
        PeriodFilterState.get(this).markDataChanged()
        if (wroteOff) {
            val app: Context = applicationContext
            Thread { SyncRunner.runOnce(app) }.start()
            Toast.makeText(this, R.string.archive_writeoff_done, Toast.LENGTH_SHORT).show()
        }
        writeOffDialog?.dismiss()
        setResult(RESULT_OK)
        finish()
    }

    /**
     * Кнопка видна, только когда удалить можно, но решение всё равно за
     * [WalletDbCRUD.DeleteAccount]: операция по счёту могла появиться, пока экран
     * открыт. Отказ объясняем — причин две, и они разные.
     */
    fun btnDeleteClick(view: View) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.account_delete_question, account.displayName))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteAccount() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Мягкое удаление ([WalletDbCRUD.DeleteAccount]) — долетит до сервера/других устройств следующим синком. */
    private fun deleteAccount() {
        val db = WalletDbHelper(this).writableDatabase
        val result = WalletDbCRUD.DeleteAccount(db, account.id)

        if (result != WalletDbCRUD.DeleteAccountResult.deleted) {
            Toast.makeText(this, deleteRefusedMessage(result), Toast.LENGTH_LONG).show()
            return
        }
        reloadSingleton()
        PeriodFilterState.get(this).markDataChanged() // удалённый актив уносит строку «Капитал»
        setResult(RESULT_OK)
        finish()
    }

    private fun deleteRefusedMessage(result: WalletDbCRUD.DeleteAccountResult): String {
        // Системным бывает только счёт из двух зашитых по имени, а это категории.
        if (result == WalletDbCRUD.DeleteAccountResult.systemAccount) {
            return getString(R.string.account_delete_refused_system, account.displayName)
        }
        return getString(R.string.account_delete_refused_operations, account.displayName)
    }

    /** Диалог «Переоценка» (capital-assets, asset-revaluation). */
    fun btnRevaluateClick(view: View) {
        RevaluationDialog(this, account as AccountOperatingItem) {
            setResult(RESULT_OK)
            Toast.makeText(this, R.string.revaluation_saved, Toast.LENGTH_SHORT).show()
        }.show()
    }

    /**
     * «Перенести операции в актив» (capital-assets): выбор актива, подтверждение с числом
     * операций, перенос одной транзакцией ([WalletDbCRUD.MoveCategoryToAsset]), синк в
     * фоне. Пропущенные без курса — отдельным сообщением, категория тогда остаётся.
     */
    fun btnMoveToAssetClick(view: View) {
        val assets = ArrayList<AccountOperatingItem>()
        for (a in Singleton.instance.getAccounts(this).allAccounts) {
            if (a is AccountOperatingItem && a.isAsset && a.isArchived != true) {
                assets.add(a)
            }
        }
        if (assets.isEmpty()) return
        val names = Array(assets.size) { assets[it].displayName }
        AlertDialog.Builder(this)
            .setTitle(R.string.move_pick_asset)
            .setItems(names) { _, which -> confirmMove(assets[which]) }
            .show()
    }

    private fun confirmMove(asset: AccountOperatingItem) {
        val db = Singleton.instance.getWritableDatabase(this)
        val count = WalletDbCRUD.CountCategoryWalletOperations(db, account.id)
        AlertDialog.Builder(this)
            .setTitle(R.string.move_confirm_title)
            .setMessage(getString(R.string.move_confirm_message, count, asset.displayName, account.displayName))
            .setPositiveButton(R.string.move_confirm_ok) { _, _ -> moveTo(asset) }
            .setNegativeButton(R.string.move_confirm_cancel, null)
            .show()
    }

    private fun moveTo(asset: AccountOperatingItem) {
        val db = Singleton.instance.getWritableDatabase(this)
        val result = try {
            WalletDbCRUD.MoveCategoryToAsset(db, account.id, asset.id)
        } catch (e: RuntimeException) {
            Toast.makeText(this, getString(R.string.move_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        reloadSingleton()
        PeriodFilterState.get(this).markDataChanged()
        val app: Context = applicationContext
        Thread { SyncRunner.runOnce(app) }.start()
        setResult(RESULT_OK)
        if (result.skippedNoRate > 0) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.move_skipped, result.skippedNoRate))
                .setPositiveButton(R.string.move_skipped_ok) { _, _ -> finish() }
                .show()
        } else {
            Toast.makeText(this, getString(R.string.move_done, result.moved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshCurrencyUI() {
        val currency = selectedCurrency ?: return
        imageCurencyView.setImageBitmap(currency.icon!!.bitmap)
        tvCurrencyLabel.text = currency.iso + "  —  " + currency.name
    }

    private fun reloadSingleton() {
        val rdb = WalletDbHelper(this).readableDatabase
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this))
        WalletDbLoader.LoadAccounts(
            rdb,
            Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this),
            Singleton.instance.getIconList(this),
            null
        )
    }

    companion object {
        const val ARG_ACCOUNT_ID = "AccountItemActivity.account_id"

        private const val REQUEST_PICK_ICON = 2
        private const val REQUEST_PICK_WRITEOFF_CATEGORY = 3
        private const val REQUEST_PICK_CURRENCY = 4
    }
}
