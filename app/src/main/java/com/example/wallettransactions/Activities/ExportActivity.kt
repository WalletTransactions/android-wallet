package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.wallettransactions.Data.WalletExportData
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportActivity : AppCompatActivity() {

    private lateinit var btnExportDb: Button
    private lateinit var btnExportJson: Button
    private lateinit var btnShare: Button
    private lateinit var progressExport: ProgressBar
    private lateinit var tvExportStatus: TextView
    private var lastExportedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.export_title)

        btnExportDb = findViewById(R.id.btnExportDb)
        btnExportJson = findViewById(R.id.btnExportJson)
        btnShare = findViewById(R.id.btnShare)
        progressExport = findViewById(R.id.progressExport)
        tvExportStatus = findViewById(R.id.tvExportStatus)

        btnShare.isEnabled = false
    }

    fun btnUp(view: View) {
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    fun ExportDatabaseClick(view: View) {
        btnExportDb.isEnabled = false
        btnShare.isEnabled = false
        progressExport.visibility = View.VISIBLE
        tvExportStatus.setText(R.string.export_busy)

        Thread {
            try {
                val exportedFile = exportDatabase()
                lastExportedFile = exportedFile
                val result = getString(R.string.export_done_path, exportedFile.absolutePath)

                runOnUiThread {
                    progressExport.visibility = View.GONE
                    tvExportStatus.text = result
                    btnExportDb.isEnabled = true
                    btnShare.isEnabled = true
                    Toast.makeText(this@ExportActivity, R.string.export_db_done, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val error = getString(R.string.error_with_reason, e.message)
                runOnUiThread {
                    progressExport.visibility = View.GONE
                    tvExportStatus.text = error
                    btnExportDb.isEnabled = true
                    Toast.makeText(this@ExportActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Экспорт в тот же JSON, что понимает импорт (см. [WalletExportData] и
     * `WalletImportData.FillOperationDataFromJson`) — счета/валюты, валютные пары,
     * курсы и операции (с восстановленными "leg"/"commission") одним читаемым файлом,
     * в отличие от бинарного wallet.db выше.
     */
    fun ExportJsonClick(view: View) {
        btnExportDb.isEnabled = false
        btnExportJson.isEnabled = false
        btnShare.isEnabled = false
        progressExport.visibility = View.VISIBLE
        tvExportStatus.setText(R.string.export_busy)

        Thread {
            try {
                val exportedFile = exportJson()
                lastExportedFile = exportedFile
                val result = getString(R.string.export_done_path, exportedFile.absolutePath)

                runOnUiThread {
                    progressExport.visibility = View.GONE
                    tvExportStatus.text = result
                    btnExportDb.isEnabled = true
                    btnExportJson.isEnabled = true
                    btnShare.isEnabled = true
                    Toast.makeText(this@ExportActivity, R.string.export_json_done, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val error = getString(R.string.error_with_reason, e.message)
                runOnUiThread {
                    progressExport.visibility = View.GONE
                    tvExportStatus.text = error
                    btnExportDb.isEnabled = true
                    btnExportJson.isEnabled = true
                    Toast.makeText(this@ExportActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun ShareDatabaseClick(view: View) {
        val file = lastExportedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.export_first, Toast.LENGTH_SHORT).show()
            return
        }

        // Get shareable URI using FileProvider
        val fileUri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".fileprovider",
            file
        )

        val isJson = file.name.endsWith(".json")

        val shareIntent = Intent()
        shareIntent.action = Intent.ACTION_SEND
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
        shareIntent.type = if (isJson) "application/json" else "application/octet-stream"
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        com.example.wallettransactions.security.AppLock.allowNextLeave()
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(if (isJson) R.string.export_share_json else R.string.export_share_db)
            )
        )
    }

    @Throws(IOException::class)
    private fun getDownloadsDir(): File = exportDir(this)

    @Throws(IOException::class)
    private fun exportDatabase(): File {
        // Source: app's database file
        val dbFile = getDatabasePath("wallet.db")
        if (!dbFile.exists()) {
            throw IOException(getString(R.string.export_no_db_file))
        }

        val exportFile = File(getDownloadsDir(), "wallet_android_" + timestamp() + ".db")

        // Copy file using FileChannels
        FileInputStream(dbFile).use { inStream ->
            FileOutputStream(exportFile).use { outStream ->
                val inChannel = inStream.channel
                val outChannel = outStream.channel

                inChannel.transferTo(0, inChannel.size(), outChannel)
            }
        }

        return exportFile
    }

    @Throws(IOException::class, JSONException::class)
    private fun exportJson(): File {
        val db = Singleton.instance.getReadableDatabase(this)
        val json = WalletExportData.BuildExportJson(db)

        val exportFile = File(getDownloadsDir(), "wallet_export_" + timestamp() + ".json")
        OutputStreamWriter(FileOutputStream(exportFile), StandardCharsets.UTF_8).use { w ->
            w.write(json.toString(2))
        }
        return exportFile
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        /**
         * Папка приложения `Android/data/<пакет>/files/Download` на всех версиях: писать
         * туда можно без разрешений. Общая `Download/` на Android 8–9 требовала
         * `WRITE_EXTERNAL_STORAGE`, которое никто не запрашивал, — экспорт падал (#38).
         */
        @JvmStatic
        @Throws(IOException::class)
        fun exportDir(ctx: Context): File {
            val downloadsDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null || (!downloadsDir.exists() && !downloadsDir.mkdirs())) {
                throw IOException(ctx.getString(R.string.export_no_downloads))
            }
            return downloadsDir
        }

        private fun timestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        }
    }
}
