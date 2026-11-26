package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.Data.DbInit
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * «Готовим кошелёк…» — первое открытие базы в фоне (openspec fix-first-start-anr),
 * см. [DbInit]. Когда база открыта, открывает исходный экран из
 * [DbInit.EXTRA_NEXT] и закрывается.
 */
class DbInitActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_db_init)

        // После поворота — новый поток: Singleton/SQLiteOpenHelper открывают базу один раз,
        // второй поток просто дождётся первого.
        val app: Context = applicationContext
        val worker = Thread({
            val started = System.currentTimeMillis()
            try {
                Singleton.instance.getWritableDatabase(app)
                Singleton.instance.getReadableDatabase(app)
                DbInit.markReady()
                Log.i(TAG, "база готова за " + (System.currentTimeMillis() - started) + " мс")
                runOnUiThread { proceed() }
            } catch (e: RuntimeException) {
                Log.e(TAG, "база не открылась", e)
                runOnUiThread { showError(e) }
            }
        }, "db-init")
        worker.start()
    }

    private fun proceed() {
        if (isFinishing || isDestroyed) return
        val next: Intent = intent.getParcelableExtra(DbInit.EXTRA_NEXT)
            ?: Intent(this, MainActivity::class.java)
        startActivity(next)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun showError(e: RuntimeException) {
        if (isFinishing || isDestroyed) return
        findViewById<View>(R.id.progressDbInit).visibility = View.GONE
        val why = e.message ?: e.javaClass.simpleName
        findViewById<TextView>(R.id.tvDbInit).text = getString(R.string.db_init_failed, why)
    }

    private companion object {
        const val TAG = "DbInit"
    }
}
