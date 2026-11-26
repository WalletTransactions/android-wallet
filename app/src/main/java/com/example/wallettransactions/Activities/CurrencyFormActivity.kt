package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.EquivalentCurrencies
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import java.util.Locale

/**
 * Форма валюты (activity_currency_form.xml, прототип 2a — блок curFormOpen).
 *
 * Открывается из «Списка валют»: по ✎ в строке — правка, по «+ Создать валюту» —
 * создание. Возвращает RESULT_OK, если список нужно перечитать.
 */
class CurrencyFormActivity : AppCompatActivity() {

    private lateinit var editName: EditText
    private lateinit var editIso: EditText
    private lateinit var editSymbol: EditText
    private lateinit var editPrecision: EditText
    private lateinit var imgIcon: ImageView
    private lateinit var switchShowInEquivalent: FrameLayout

    private var currencyId = -1
    private var iconId = -1

    /** У новой валюты флаг включён (spec currency-directory «Флаг в форме валюты»). */
    private var showInEquivalent = true
    private var readOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_form)

        editName = findViewById(R.id.editCurrencyName)
        editIso = findViewById(R.id.editCurrencyIso)
        editSymbol = findViewById(R.id.editCurrencySymbol)
        editPrecision = findViewById(R.id.editCurrencyPrecision)
        imgIcon = findViewById(R.id.imgCurrencyIcon)
        switchShowInEquivalent = findViewById(R.id.switchShowInEquivalent)

        currencyId = intent.getIntExtra(ARG_CURRENCY_ID, -1)

        if (currencyId > 0) {
            val currency = Singleton.instance.getCurrencyList(this).getCurrencyById(currencyId)
            if (currency != null) fillFrom(currency)
        } else {
            findViewById<TextView>(R.id.formTitle).setText(R.string.currency_new)
            iconId = Singleton.instance.getIconList(this).unknownIcon!!.id
            showInEquivalent = EquivalentCurrencies.defaultForNew(
                Singleton.instance.getCurrencyList(this)
            )
        }

        renderIcon()
        renderSwitch()
        if (readOnly) applyReadOnly()
    }

    private fun fillFrom(currency: Currency) {
        findViewById<TextView>(R.id.formTitle).text = currency.name
        findViewById<View>(R.id.btnDelete).visibility = View.VISIBLE

        editName.setText(currency.name)
        editIso.setText(currency.iso)
        editSymbol.setText(currency.symbol)
        editPrecision.setText(currency.precision.toString())
        iconId = if (currency.icon != null) currency.icon!!.id else -1
        showInEquivalent = currency.isShowInEquivalent
        readOnly = currency.isShared
    }

    /**
     * Общая валюта только для чтения (design D7): сервер молча пропускает push чужой
     * строки, и правка на телефоне тихо разошлась бы с ним. Поля и переключатель
     * неактивны, «Сохранить»/«Удалить» скрыты, видна подпись.
     */
    private fun applyReadOnly() {
        val views = arrayOf<View>(
            editName, editIso, editSymbol, editPrecision, imgIcon,
            findViewById(R.id.rowShowInEquivalent), switchShowInEquivalent
        )
        for (v in views) {
            v.isEnabled = false
            v.isClickable = false
        }
        switchShowInEquivalent.alpha = 0.5f
        findViewById<View>(R.id.btnSave).visibility = View.GONE
        findViewById<View>(R.id.btnDelete).visibility = View.GONE
        findViewById<View>(R.id.sharedNote).visibility = View.VISIBLE
    }

    /** Строка «Показывать в эквиваленте» (android:onClick). */
    fun onToggleShowInEquivalent(view: View) {
        if (readOnly) return
        showInEquivalent = !showInEquivalent
        renderSwitch()
    }

    /** Тот же переключатель, что в SettingsActivity.applySwitchState: фон on/off и бегунок start/end. */
    private fun renderSwitch() {
        switchShowInEquivalent.setBackgroundResource(
            if (showInEquivalent) R.drawable.bg_switch_on else R.drawable.bg_switch_off
        )
        val thumb = switchShowInEquivalent.getChildAt(0) ?: return
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (showInEquivalent) Gravity.END else Gravity.START
        thumb.layoutParams = lp
    }

    private fun renderIcon() {
        val icon = Singleton.instance.getIconList(this).getIconById(iconId)
            ?: Singleton.instance.getIconList(this).unknownIcon
        imgIcon.setImageBitmap(icon!!.bitmap)
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onPickIcon(view: View) {
        startActivityForResult(
            IconPickerActivity.buildIntent(this, getString(R.string.currency_icon_title), Icon.IconType.FLAG, iconId),
            REQUEST_PICK_ICON
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return

        iconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, iconId)
        renderIcon()
    }

    fun onSave(view: View) {
        if (readOnly) return
        val name = editName.text.toString().trim()
        val iso = editIso.text.toString().trim().uppercase(Locale.ROOT)
        var symbol = editSymbol.text.toString().trim()

        if (name.isEmpty()) {
            editName.error = getString(R.string.account_error_name_empty)
            return
        }
        if (iso.isEmpty()) {
            editIso.error = getString(R.string.currency_error_iso_empty)
            return
        }

        val clash = Singleton.instance.getCurrencyList(this).getCurrencyByISO(iso)
        if (clash != null && clash.id != currencyId) {
            editIso.error = getString(R.string.currency_error_iso_taken)
            return
        }

        if (symbol.isEmpty()) symbol = iso

        val precision = parsePrecision(editPrecision.text.toString().trim())

        val db = WalletDbHelper(this).writableDatabase
        // Код валюты из живой пары не меняется (design D8) — пара осталась бы со старым символом.
        if (WalletDbCRUD.IsIsoChangeBlocked(db, currencyId, iso)) {
            editIso.error = getString(R.string.currency_error_iso_in_pair)
            return
        }

        val ok = if (currencyId > 0) {
            WalletDbCRUD.UpdateCurrency(
                db, currencyId, name, iso, symbol, precision, iconId,
                showInEquivalent
            ) > 0
        } else {
            (WalletDbCRUD.InsertCurrency(
                db, name, iso, symbol, precision, iconId,
                showInEquivalent
            ) ?: 0L) > 0
        }

        if (!ok) {
            Toast.makeText(this, R.string.currency_error_save, Toast.LENGTH_SHORT).show()
            return
        }

        reloadCurrencies()
        setResult(RESULT_OK)
        finish()
    }

    /** Пустое поле — 2 знака, как у большинства валют; больше 8 знаков смысла не имеет. */
    private fun parsePrecision(raw: String): Int {
        if (raw.isEmpty()) return 2
        return try {
            Math.max(0, Math.min(8, raw.toInt()))
        } catch (e: NumberFormatException) {
            2
        }
    }

    fun onDelete(view: View) {
        if (readOnly) return
        val db = WalletDbHelper(this).writableDatabase

        if (WalletDbCRUD.IsCurrencyInUse(db, currencyId)) {
            Toast.makeText(this, R.string.currency_error_in_use, Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.currency_delete_question)
            .setMessage(editName.text.toString())
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                WalletDbCRUD.DeleteCurrency(db, currencyId)
                reloadCurrencies()
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    /**
     * Список валют лежит в синглтоне, поэтому после записи его надо перечитать — тем же
     * путём, что и синк: объекты обновляются на месте, а табы перерисуют шапку при
     * возврате (ревизия поднята, android-wallet#8).
     */
    private fun reloadCurrencies() {
        Singleton.instance.reloadCurrencyList(this)
    }

    companion object {
        const val ARG_CURRENCY_ID = "CurrencyForm.CurrencyId"

        private const val REQUEST_PICK_ICON = 1

        @JvmStatic
        fun buildIntent(ctx: Context, currencyId: Int): Intent {
            val intent = Intent(ctx, CurrencyFormActivity::class.java)
            intent.putExtra(ARG_CURRENCY_ID, currencyId)
            return intent
        }
    }
}
