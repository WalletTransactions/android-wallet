package com.example.wallettransactions.Activities

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.wallettransactions.Activities.Fragments.FilePickerFragment
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Data.WalletImportData

import java.util.regex.Pattern

class ImportActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100

        // Подсказки форматов — строки import_hint_* (openspec android-i18n).

        /** Ключи вида "имя": — только они, не строковые значения — подсвечиваются
         *  акцентным цветом, чтобы структура JSON читалась с одного взгляда. */
        private val JSON_KEY = Pattern.compile("\"[A-Za-z_][A-Za-z0-9_.]*\"(?=\\s*:)")

        /** Первая строка (заголовок столбцов CSV) — акцентным жирным, остальное как есть. */
        private fun highlightHeader(text: String, color: Int): CharSequence {
            val ss = SpannableString(text)
            val headerStart = text.indexOf('\n') + 1 // сразу после первой строки-заголовка подсказки
            var headerEnd = text.indexOf('\n', headerStart)
            if (headerEnd < 0) headerEnd = text.length
            ss.setSpan(ForegroundColorSpan(color), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(StyleSpan(Typeface.BOLD), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ss
        }

        /** Каждое `"ключ":` — акцентным жирным; значения и структура — обычным текстом. */
        private fun highlightJsonKeys(text: String, color: Int): CharSequence {
            val ss = SpannableString(text)
            val m = JSON_KEY.matcher(text)
            while (m.find()) {
                ss.setSpan(ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(StyleSpan(Typeface.BOLD), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return ss
        }
    }

    private lateinit var tvError: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnImportAccount: Button
    private lateinit var btnImportOperation: Button
    private lateinit var btnImportOperationJson: Button
    private lateinit var btnImportRate: Button

    /* Короткая подсказка вместо файла — полное описание формата переехало
     *  в диалог «Формат» (см. wireFormatInfo/FilePickerFragment.showFormatDialog),
     *  здесь остаётся только статус текущего импорта. */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        findViewById<TextView>(R.id.screenTitle).setText(R.string.import_title)
        tvError = findViewById<EditText>(R.id.error)
        tvError.setTextIsSelectable(true)
        tvError.isFocusableInTouchMode = false
        progressBar = findViewById(R.id.importProgressBar)
        btnImportAccount       = findViewById(R.id.btnImportAccount)
        btnImportOperation     = findViewById(R.id.btnImportOperation)
        btnImportOperationJson = findViewById(R.id.btnImportOperationJson)
        btnImportRate          = findViewById(R.id.btnImportRate)

        val accent = ContextCompat.getColor(this, R.color.colorexpanse)
        wireFormatInfo(R.id.fragmentCSVFileAccount, getString(R.string.import_format_accounts),
                highlightHeader(getString(R.string.import_hint_accounts), accent))
        wireFormatInfo(R.id.fragmentCSVFileOperation, getString(R.string.import_format_operations),
                highlightHeader(getString(R.string.import_hint_operations), accent))
        wireFormatInfo(R.id.fragmentCSVFileCurrencyPairRate, getString(R.string.import_format_rates),
                highlightHeader(getString(R.string.import_hint_rates), accent))
        wireFormatInfo(R.id.fragmentJSONFileOperation, getString(R.string.import_format_json),
                highlightJsonKeys(getString(R.string.import_hint_json), accent))
    }

    private fun wireFormatInfo(fragmentId: Int, title: String, content: CharSequence) {
        val fl = supportFragmentManager.findFragmentById(fragmentId) as FilePickerFragment
        fl.setFormatInfo(title, content)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    fun ImportOperationClick(view: View) {
        val fl = supportFragmentManager.findFragmentById(R.id.fragmentCSVFileOperation) as FilePickerFragment
        if (fl.GetFileName() == null) { tvError.setText(R.string.import_choose_file); return }
        if (!checkPermission()) { requestPermission(); return }

        val uri = fl.GetFileUri()!!
        setImporting(true, getString(R.string.import_busy_operations))
        Thread {
            var result: String
            try {
                result = WalletImportData.FillOperationDataFromFile(this,
                        contentResolver.openInputStream(uri)!!).describe(R.plurals.import_count_operations)
            } catch (e: Exception) {
                result = e.toString()
            }
            val msg = result
            runOnUiThread {
                setImporting(false, msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * JSON-импорт — умеет то, чего не может плоский CSV операций (см.
     * [WalletImportData.FillOperationDataFromJson]): валютный перевод и
     * комиссию одной структурой со связью в `operation_link`, а также, одним
     * файлом, счета с валютой, валютные пары и курсы (разделы опциональны).
     */
    fun ImportOperationJsonClick(view: View) {
        val fl = supportFragmentManager.findFragmentById(R.id.fragmentJSONFileOperation) as FilePickerFragment
        if (fl.GetFileName() == null) { tvError.setText(R.string.import_choose_file); return }
        if (!checkPermission()) { requestPermission(); return }

        val uri = fl.GetFileUri()!!
        setImporting(true, getString(R.string.import_busy_json))
        Thread {
            var result: String
            try {
                // Теперь может нести не только операции, но и счета/валютные пары/курсы
                // (см. WalletImportData.FillOperationDataFromJson) — счёт "элементов" общий.
                result = WalletImportData.FillOperationDataFromJson(this,
                        contentResolver.openInputStream(uri)!!).describe(R.plurals.import_count_items)
                // JSON мог завести/обновить счета (раздел "accounts") — тот же кэш-рефреш,
                // что и после ImportAccountClick, иначе новые счета не видны до перезапуска.
                Singleton.instance.reloadAccounts(this)
            } catch (e: Exception) {
                result = e.toString()
            }
            val msg = result
            runOnUiThread {
                setImporting(false, msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun ImportAccountClick(view: View) {
        val fl = supportFragmentManager.findFragmentById(R.id.fragmentCSVFileAccount) as FilePickerFragment
        if (fl.GetFileName() == null) { tvError.setText(R.string.import_choose_file); return }
        if (!checkPermission()) { requestPermission(); return }

        val uri = fl.GetFileUri()!!
        setImporting(true, getString(R.string.import_busy_accounts))
        Thread {
            var result: String
            try {
                val count = WalletImportData.FillAccountDataFromFile(this,
                        contentResolver.openInputStream(uri)!!)
                result = resources.getQuantityString(R.plurals.import_count_accounts, count, count)
                Singleton.instance.reloadAccounts(this)
            } catch (e: Exception) {
                result = e.toString()
            }
            val msg = result
            runOnUiThread {
                setImporting(false, msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /*select symbol||','|| cpr.date||','|| cpr.rate from currency_pair_rate cpr
join currency_pair cp
on cp."_id" =cpr.currency_pair_id */
    fun ImportCurrencyPairRateClick(view: View) {
        val fl = supportFragmentManager.findFragmentById(R.id.fragmentCSVFileCurrencyPairRate) as FilePickerFragment
        if (fl.GetFileName() == null) { tvError.setText(R.string.import_choose_file); return }
        if (!checkPermission()) { requestPermission(); return }

        val uri = fl.GetFileUri()!!
        setImporting(true, getString(R.string.import_busy_rates))
        Thread {
            var result: String
            try {
                val count = WalletImportData.FillCurrencyPairRateDataFromStream(this,
                        contentResolver.openInputStream(uri)!!)
                result = resources.getQuantityString(R.plurals.import_count_rates, count, count)
            } catch (e: Exception) {
                result = e.toString()
            }
            val msg = result
            runOnUiThread {
                setImporting(false, msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun setImporting(active: Boolean, statusText: String) {
        progressBar.visibility = if (active) View.VISIBLE else View.GONE
        btnImportAccount.isEnabled = !active
        btnImportOperation.isEnabled = !active
        btnImportOperationJson.isEnabled = !active
        btnImportRate.isEnabled = !active
        tvError.setText(statusText)
        // Импорт закончился (и при ошибке: часть строк могла записаться) — табы
        // перечитают остатки и ленту, а не покажут то, что было до импорта.
        if (!active) PeriodFilterState.get(this).markDataChanged()
    }


    private fun checkPermission(): Boolean {
        // SAF (file picker via content URI) does not require READ_EXTERNAL_STORAGE on API 29+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return true
        }
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Toast.makeText(this, "Write External Storage permission allows us to read files. Please allow this permission in App Settings.",
                    Toast.LENGTH_LONG).show()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        }
    }

    fun btnUp(view: View) {
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST_CODE ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.e("value", "Permission Granted, Now you can use local drive.")
                } else {
                    Log.e("value", "Permission Denied, You cannot use local drive.")
                }
        }
    }

}
