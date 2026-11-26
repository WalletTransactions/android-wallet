package com.example.wallettransactions.Activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

class AddWalletActivity : AppCompatActivity() {

    private lateinit var imgIconPreview: ImageView
    private lateinit var iconPickerHint: View
    private lateinit var imgCurrencyFlag: ImageView
    private lateinit var tvCurrencyLabel: TextView
    private lateinit var editName: EditText
    private lateinit var editComment: EditText

    /** -1 — не выбрана: при сохранении подставим «неизвестную». */
    private var selectedIconId = -1
    private var selectedCurrency: Currency? = null
    private lateinit var assetFields: AssetFields

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_wallet)
        assetFields = AssetFields(this, null, false, null)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.account_new_wallet)

        imgIconPreview = findViewById(R.id.imgIconPreview)
        iconPickerHint = findViewById(R.id.iconPickerHint)
        imgCurrencyFlag = findViewById(R.id.imgCurrencyFlag)
        tvCurrencyLabel = findViewById(R.id.tvCurrencyLabel)
        editName = findViewById(R.id.editWalletName)
        editComment = findViewById(R.id.editWalletComment)
    }

    /** Справочник иконок вместо галереи — см. AddCategoryActivity.pickIconClick. */
    fun pickIconClick(view: View) {
        val current = if (selectedIconId > 0) selectedIconId
        else Singleton.instance.getIconList(this).unknownIcon!!.id
        startActivityForResult(
            IconPickerActivity.buildIntent(this, getString(R.string.icon_title_wallet), Icon.IconType.CATEGORY, current),
            REQUEST_PICK_ICON
        )
    }

    /**
     * Валюта выбирается тем же экраном «Список валют», что и в «Валютных парах»
     * (openspec wallet-currency-picker): поиск, флаги, текущая отмечена.
     */
    fun pickCurrencyClick(view: View) {
        startActivityForResult(
            CurrencyPickerActivity.buildIntent(
                this, getString(R.string.account_choose_currency),
                selectedCurrency?.iso
            ),
            REQUEST_PICK_CURRENCY
        )
    }

    private fun renderCurrency() {
        val currency = selectedCurrency!!
        imgCurrencyFlag.setImageBitmap(currency.icon!!.bitmap)
        imgCurrencyFlag.visibility = View.VISIBLE
        tvCurrencyLabel.text = currency.iso + "  —  " + currency.name
        tvCurrencyLabel.setTextColor(resources.getColor(R.color.colorTextPrimary))
    }

    fun btnUp(view: View) {
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_PICK_ICON) {
            selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId)
            renderIcon()
        } else if (requestCode == REQUEST_PICK_CURRENCY) {
            val picked = Singleton.instance.getCurrencyList(this)
                .getCurrencyByISO(data.getStringExtra(CurrencyPickerActivity.RESULT_ISO)) ?: return
            selectedCurrency = picked
            renderCurrency()
        }
    }

    private fun renderIcon() {
        val icon = Singleton.instance.getIconList(this).getIconById(selectedIconId) ?: return

        imgIconPreview.setImageBitmap(icon.bitmap)
        imgIconPreview.visibility = View.VISIBLE
        iconPickerHint.visibility = View.GONE
    }

    fun btnSaveClick(view: View) {
        val name = editName.text.toString().trim()
        if (name.isEmpty()) {
            editName.error = getString(R.string.account_error_name_empty)
            return
        }
        val currency = selectedCurrency
        if (currency == null) {
            Toast.makeText(this, R.string.account_choose_currency, Toast.LENGTH_SHORT).show()
            return
        }

        val db = WalletDbHelper(this).writableDatabase
        if (WalletDbCRUD.IsAccountNameTaken(db, name, -1)) {
            editName.error = getString(R.string.account_error_name_taken)
            return
        }

        val iconId = if (selectedIconId > 0) selectedIconId
        else Singleton.instance.getIconList(this).unknownIcon!!.id

        // Кошелёк и признак актива — одной транзакцией (capital-assets).
        db.beginTransaction()
        try {
            val id = WalletDbCRUD.InsertOperatingAccount(
                db, name,
                editComment.text.toString().trim(), iconId, currency.id
            )
            if (id > 0 && assetFields.isAsset()) {
                WalletDbCRUD.SetAccountAsset(db, id.toInt(), assetFields.type(), assetFields.includeInNetWorth())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val rdb = WalletDbHelper(this).readableDatabase
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this))
        WalletDbLoader.LoadAccounts(
            rdb,
            Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this),
            Singleton.instance.getIconList(this),
            null
        )
        // Новый актив сразу даёт строку «Капитал» в карточке периода — табы в стеке
        // перечитываются по ревизии (capital-assets, design п. 10, android-wallet#42).
        PeriodFilterState.get(this).markDataChanged()

        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val REQUEST_PICK_ICON = 1
        const val REQUEST_PICK_CURRENCY = 2
    }
}
