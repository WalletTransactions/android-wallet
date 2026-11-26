package com.example.wallettransactions.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.wallettransactions.Activities.MainActivity
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.Texts
import com.example.wallettransactions.security.AppLockStore

/**
 * Уведомление «пришли новые операции» после фонового синка ([SyncWorker]).
 * Без GMS: уведомление локальное, телефон узнаёт о новом только на очередном
 * проходе по расписанию, так что задержка — интервал автоподключения.
 *
 * «Новые» — операции, которых до pull локально не было (см.
 * [SyncLog.getNewOperationUuids]), и заведённые тем же воркером по
 * периодическим правилам. Вторая нога валютного перевода и комиссия
 * отдельно не считаются — перевод с комиссией один для пользователя, а не три.
 */
object NewOperationsNotifier {

    private const val TAG = "NewOperationsNotifier"
    private const val CHANNEL_ID = "new_operations"
    // 1 занят уведомлением AuthSession о просроченной сессии.
    private const val NOTIFICATION_ID = 2
    // Строк в развёрнутом уведомлении — дальше Android всё равно обрезает.
    private const val MAX_LINES = 5

    /**
     * @param log          журнал синка; `null`, если синк не прошёл
     * @param recurringIds _id операций, только что заведённых по периодическим правилам
     */
    @JvmStatic
    fun notifyIfAny(ctx: Context, log: SyncLog?, recurringIds: List<Long>) {
        // Каждый отказ — строкой в лог: иначе "уведомления не было" не отличить
        // от "синк не дошёл до сюда" (adb logcat -s NewOperationsNotifier).
        if (!AppSettings.isNotifyNewOperationsEnabled(ctx)) { Log.i(TAG, "skip: выключено в настройках"); return }
        val pulledUuids: List<String> =
            if (log != null && !log.isFullReload()) log.getNewOperationUuids() else emptyList()
        if (pulledUuids.isEmpty() && recurringIds.isEmpty()) {
            Log.i(TAG, "skip: новых нет" + (if (log != null && log.isFullReload()) " (полная перезагрузка)" else ""))
            return
        }
        // Приложение на экране — новое и так видно в списке.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.i(TAG, "skip: приложение на экране, новых " + (pulledUuids.size + recurringIds.size))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "skip: нет разрешения POST_NOTIFICATIONS")
            return
        }

        val lines = describe(ctx, pulledUuids, recurringIds)
        if (lines.isEmpty()) { Log.i(TAG, "skip: все новые — связки или удалены"); return }
        Log.i(TAG, "notify: " + lines.size)

        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.notify_channel_new_operations), NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val intent = Intent(ctx, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.main_icon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val title: String
        if (lines.size == 1) {
            val recurring = lines[0].startsWith("↻ ")
            title = ctx.getString(if (recurring) R.string.notify_recurring_operation else R.string.notify_new_operation)
            builder.setContentTitle(title)
                .setContentText(if (recurring) lines[0].substring(2) else lines[0])
        } else {
            title = lines.size.toString() + " " + plural(lines.size)
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            for (i in 0 until minOf(lines.size, MAX_LINES)) style.addLine(lines[i])
            if (lines.size > MAX_LINES) style.setSummaryText(ctx.getString(R.string.notify_and_more, lines.size - MAX_LINES))
            builder.setContentTitle(title).setContentText(lines[0]).setStyle(style)
        }

        // Код доступа включён — на экране блокировки телефона только заголовок, без сумм
        // и комментариев (org/docs/TODO_app_lock.md, п. 4.1). Полный текст — после разблокировки.
        if (AppLockStore.isPinSet(ctx)) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.main_icon)
                        .setContentTitle(title)
                        .build()
                )
        }

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * По строке на операцию, по дате: «−350 ₽ · Продукты · комментарий»;
     * заведённые по периодическому правилу помечены «↻».
     */
    private fun describe(ctx: Context, uuids: List<String>, recurringIds: List<Long>): List<String> {
        val db = Singleton.instance.getReadableDatabase(ctx)
        val accounts = Singleton.instance.getAccounts(ctx)

        val c = queryMainOperations(db, uuids, recurringIds)
        val lines = ArrayList<String>()
        try {
            while (c.moveToNext()) {
                val from = accounts.getAccountById(c.getInt(0))
                val to = accounts.getAccountById(c.getInt(1))
                if (from == null || to == null) continue
                val line = describe(from, to, c.getDouble(2), c.getString(3))
                lines.add(if (recurringIds.contains(c.getLong(4))) "↻ $line" else line)
            }
        } finally {
            c.close()
        }
        return lines
    }

    /**
     * acc_fr_id, acc_to_id, amount, comment, _id операций из `uuids` и `ids` —
     * без удалённых и без тех, что прицеплены к другой через operation_link (нога, комиссия).
     */
    @JvmStatic
    internal fun queryMainOperations(db: SQLiteDatabase, uuids: List<String>, ids: List<Long>): Cursor {
        val args = ArrayList<String>(uuids)
        for (id in ids) args.add(id.toString())
        return db.rawQuery(
            "SELECT acc_fr_id, acc_to_id, amount, comment, _id FROM operation" +
                " WHERE (uuid IN (" + placeholders(uuids.size) + ")" +
                " OR _id IN (" + placeholders(ids.size) + "))" +
                " AND deleted_at IS NULL" +
                " AND _id NOT IN (SELECT link_id FROM operation_link WHERE deleted_at IS NULL)" +
                " ORDER BY date",
            args.toTypedArray()
        )
    }

    // SQLite допускает пустой список в IN () — отдельной ветки для него не нужно.
    private fun placeholders(n: Int): String = java.lang.String.join(",", java.util.Collections.nCopies(n, "?"))

    private fun describe(from: AccountItem, to: AccountItem, amount: Double, comment: String?): String {
        val currency: Currency? =
            if (from.isOperatingAccount) (from as AccountOperatingItem).currency
            else if (to.isOperatingAccount) (to as AccountOperatingItem).currency
            else null
        val sum = AdapterUtils.formatAmount(Math.abs(amount), currency) +
            (if (currency != null) " " + currency.symbol else "")

        val sb = StringBuilder()
        if (from.isOperatingAccount && to.isOperatingAccount) {
            sb.append(sum).append(" · ").append(from.displayName).append(" → ").append(to.displayName)
        } else if (from.isOperatingAccount) {
            sb.append('−').append(sum).append(" · ").append(to.displayName)
        } else {
            sb.append('+').append(sum).append(" · ").append(from.displayName)
        }
        if (comment != null && comment.trim().isNotEmpty()) sb.append(" · ").append(comment.trim())
        return sb.toString()
    }

    /** «новая операция» / «новые операции» / «новых операций» — после числа (ресурс plurals). */
    @JvmStatic
    internal fun plural(n: Int): String = Texts.plural(R.plurals.notify_new_operations_word, n)
}
