package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.appcompat.app.AlertDialog;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.sync.LedgerConnection;
import com.example.wallettransactions.sync.SyncRunner;

/**
 * Диалог «Операции без входа» на главном экране (openspec new-user-default-set, spec
 * ledger-connection, design D8). Показывается, пока подключение ждёт ответа: при каждом
 * открытии главного экрана и после прохода синка, закончившегося «ждёт решения».
 */
public final class LedgerConnectionDialog {

    private LedgerConnectionDialog() {}

    /** Активность, на которой диалог уже открыт, — чтобы не открыть второй поверх. */
    private static Activity showingOn;

    /** Показывает вопрос, если подключение его ждёт; иначе ничего не делает. */
    public static void showIfAsked(Activity activity) {
        if (showingOn == activity) return;
        if (!AppSettings.isLedgerConnectionPending(activity)
                || AppSettings.getConnectionDecision(activity) != AppSettings.ConnectionDecision.ASKED) {
            return;
        }
        int n = Math.max(AppSettings.getConnectionOfflineOps(activity),
                LedgerConnection.offlineOperationCount(db(activity)));
        if (n == 0) {
            // Операций без входа уже нет (удалены вручную) — спрашивать не о чем.
            AppSettings.setConnectionDecision(activity, AppSettings.ConnectionDecision.TRANSFER, 0);
            startSync(activity);
            return;
        }
        showingOn = activity;
        new AlertDialog.Builder(activity)
                .setTitle(LedgerConnection.DIALOG_TITLE)
                .setMessage(LedgerConnection.questionText(n))
                .setPositiveButton(LedgerConnection.BUTTON_TRANSFER, (d, w) -> {
                    AppSettings.setConnectionDecision(activity, AppSettings.ConnectionDecision.TRANSFER, 0);
                    startSync(activity);
                })
                .setNegativeButton(LedgerConnection.BUTTON_DISCARD, (d, w) -> confirmDiscard(activity, n))
                .setOnDismissListener(d -> showingOn = null)
                .show();
    }

    private static void confirmDiscard(Activity activity, int n) {
        AlertDialog confirm = new AlertDialog.Builder(activity)
                .setMessage(LedgerConnection.confirmDiscardText(n))
                .setPositiveButton(LedgerConnection.BUTTON_DELETE, (d, w) -> {
                    showingOn = null;
                    LedgerConnection.discardOffline(db(activity));
                    AppSettings.setLedgerConnectionPending(activity, false);
                    Singleton.instance.reloadAccounts(activity);
                    Singleton.instance.reloadCurrencyList(activity);
                    Singleton.instance.reloadCurrencyPairList(activity);
                    PeriodFilterState.get(activity).markDataChanged();
                    startSync(activity);
                })
                .setNegativeButton(LedgerConnection.BUTTON_CANCEL, (d, w) -> {
                    showingOn = null;
                    showIfAsked(activity);
                })
                .setOnCancelListener(d -> {
                    showingOn = null;
                    showIfAsked(activity);
                })
                .create();
        // Первый диалог закрывается (onDismiss → showingOn = null) уже после клика —
        // подтверждение снова занимает экран, когда покажется.
        confirm.setOnShowListener(d -> showingOn = activity);
        confirm.show();
    }

    private static SQLiteDatabase db(Context ctx) {
        return Singleton.instance.getWritableDatabase(ctx);
    }

    /** Проход синка в фоне — ответ меняет то, что он сделает (перенос или обычный проход). */
    private static void startSync(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> SyncRunner.runOnce(app), "sync-after-connection-decision").start();
    }
}
