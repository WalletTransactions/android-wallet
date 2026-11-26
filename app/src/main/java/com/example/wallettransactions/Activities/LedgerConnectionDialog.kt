package com.example.wallettransactions.Activities

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase

import androidx.appcompat.app.AlertDialog

import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.LedgerConnection
import com.example.wallettransactions.sync.SyncRunner

/**
 * Диалог «Операции без входа» на главном экране (openspec new-user-default-set, spec
 * ledger-connection, design D8). Показывается, пока подключение ждёт ответа: при каждом
 * открытии главного экрана и после прохода синка, закончившегося «ждёт решения».
 */
object LedgerConnectionDialog {

    /** Активность, на которой диалог уже открыт, — чтобы не открыть второй поверх. */
    private var showingOn: Activity? = null

    /** Показывает вопрос, если подключение его ждёт; иначе ничего не делает. */
    @JvmStatic
    fun showIfAsked(activity: Activity) {
        if (showingOn === activity) return
        if (!AppSettings.isLedgerConnectionPending(activity)
                || AppSettings.getConnectionDecision(activity) != AppSettings.ConnectionDecision.ASKED) {
            return
        }
        val n = Math.max(AppSettings.getConnectionOfflineOps(activity),
                LedgerConnection.offlineOperationCount(db(activity)))
        if (n == 0) {
            // Операций без входа уже нет (удалены вручную) — спрашивать не о чем.
            AppSettings.setConnectionDecision(activity, AppSettings.ConnectionDecision.TRANSFER, 0)
            startSync(activity)
            return
        }
        showingOn = activity
        AlertDialog.Builder(activity)
                .setTitle(R.string.connection_title)
                .setMessage(LedgerConnection.questionText(n))
                .setPositiveButton(R.string.connection_transfer) { _, _ ->
                    AppSettings.setConnectionDecision(activity, AppSettings.ConnectionDecision.TRANSFER, 0)
                    startSync(activity)
                }
                .setNegativeButton(R.string.connection_discard) { _, _ -> confirmDiscard(activity, n) }
                .setOnDismissListener { showingOn = null }
                .show()
    }

    private fun confirmDiscard(activity: Activity, n: Int) {
        val confirm = AlertDialog.Builder(activity)
                .setMessage(LedgerConnection.confirmDiscardText(n))
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    showingOn = null
                    LedgerConnection.discardOffline(db(activity))
                    AppSettings.setLedgerConnectionPending(activity, false)
                    Singleton.instance.reloadAccounts(activity)
                    Singleton.instance.reloadCurrencyList(activity)
                    Singleton.instance.reloadCurrencyPairList(activity)
                    PeriodFilterState.get(activity).markDataChanged()
                    startSync(activity)
                }
                .setNegativeButton(R.string.action_cancel) { _, _ ->
                    showingOn = null
                    showIfAsked(activity)
                }
                .setOnCancelListener {
                    showingOn = null
                    showIfAsked(activity)
                }
                .create()
        // Первый диалог закрывается (onDismiss → showingOn = null) уже после клика —
        // подтверждение снова занимает экран, когда покажется.
        confirm.setOnShowListener { showingOn = activity }
        confirm.show()
    }

    private fun db(ctx: Context): SQLiteDatabase = Singleton.instance.getWritableDatabase(ctx)

    /** Проход синка в фоне — ответ меняет то, что он сделает (перенос или обычный проход). */
    private fun startSync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ SyncRunner.runOnce(app) }, "sync-after-connection-decision").start()
    }
}
