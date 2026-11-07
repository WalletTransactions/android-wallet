package com.example.wallettransactions.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.example.wallettransactions.Activities.MainActivity;
import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.security.AppLockStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Уведомление «пришли новые операции» после фонового синка ({@link SyncWorker}).
 * Без GMS: уведомление локальное, телефон узнаёт о новом только на очередном
 * проходе по расписанию, так что задержка — интервал автоподключения.
 * <p>
 * «Новые» — операции, которых до pull локально не было (см.
 * {@link SyncLog#getNewOperationUuids}), и заведённые тем же воркером по
 * периодическим правилам. Вторая нога валютного перевода и комиссия
 * отдельно не считаются — перевод с комиссией один для пользователя, а не три.
 */
public final class NewOperationsNotifier {

    private static final String TAG = "NewOperationsNotifier";
    private static final String CHANNEL_ID = "new_operations";
    // 1 занят уведомлением AuthSession о просроченной сессии.
    private static final int NOTIFICATION_ID = 2;
    // Строк в развёрнутом уведомлении — дальше Android всё равно обрезает.
    private static final int MAX_LINES = 5;

    private NewOperationsNotifier() {}

    /**
     * @param log          журнал синка; {@code null}, если синк не прошёл
     * @param recurringIds _id операций, только что заведённых по периодическим правилам
     */
    public static void notifyIfAny(Context ctx, SyncLog log, List<Long> recurringIds) {
        // Каждый отказ — строкой в лог: иначе "уведомления не было" не отличить
        // от "синк не дошёл до сюда" (adb logcat -s NewOperationsNotifier).
        if (!AppSettings.isNotifyNewOperationsEnabled(ctx)) { Log.i(TAG, "skip: выключено в настройках"); return; }
        List<String> pulledUuids = log != null && !log.isFullReload()
                ? log.getNewOperationUuids() : Collections.emptyList();
        if (pulledUuids.isEmpty() && recurringIds.isEmpty()) {
            Log.i(TAG, "skip: новых нет" + (log != null && log.isFullReload() ? " (полная перезагрузка)" : ""));
            return;
        }
        // Приложение на экране — новое и так видно в списке.
        if (ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            Log.i(TAG, "skip: приложение на экране, новых " + (pulledUuids.size() + recurringIds.size()));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "skip: нет разрешения POST_NOTIFICATIONS");
            return;
        }

        List<String> lines = describe(ctx, pulledUuids, recurringIds);
        if (lines.isEmpty()) { Log.i(TAG, "skip: все новые — связки или удалены"); return; }
        Log.i(TAG, "notify: " + lines.size());

        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.notify_channel_new_operations), NotificationManager.IMPORTANCE_DEFAULT));

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                ctx, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.main_icon)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        String title;
        if (lines.size() == 1) {
            boolean recurring = lines.get(0).startsWith("↻ ");
            title = ctx.getString(recurring ? R.string.notify_recurring_operation : R.string.notify_new_operation);
            builder.setContentTitle(title)
                    .setContentText(recurring ? lines.get(0).substring(2) : lines.get(0));
        } else {
            title = lines.size() + " " + plural(lines.size());
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle().setBigContentTitle(title);
            for (int i = 0; i < Math.min(lines.size(), MAX_LINES); i++) style.addLine(lines.get(i));
            if (lines.size() > MAX_LINES) style.setSummaryText(ctx.getString(R.string.notify_and_more, lines.size() - MAX_LINES));
            builder.setContentTitle(title).setContentText(lines.get(0)).setStyle(style);
        }

        // Код доступа включён — на экране блокировки телефона только заголовок, без сумм
        // и комментариев (org/docs/TODO_app_lock.md, п. 4.1). Полный текст — после разблокировки.
        if (AppLockStore.isPinSet(ctx)) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(new NotificationCompat.Builder(ctx, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.main_icon)
                            .setContentTitle(title)
                            .build());
        }

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * По строке на операцию, по дате: «−350 ₽ · Продукты · комментарий»;
     * заведённые по периодическому правилу помечены «↻».
     */
    private static List<String> describe(Context ctx, List<String> uuids, List<Long> recurringIds) {
        SQLiteDatabase db = Singleton.instance.getReadableDatabase(ctx);
        AccountList accounts = Singleton.instance.getAccounts(ctx);

        Cursor c = queryMainOperations(db, uuids, recurringIds);
        List<String> lines = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                AccountItem from = accounts.getAccountById(c.getInt(0));
                AccountItem to = accounts.getAccountById(c.getInt(1));
                if (from == null || to == null) continue;
                String line = describe(from, to, c.getDouble(2), c.getString(3));
                lines.add(recurringIds.contains(c.getLong(4)) ? "↻ " + line : line);
            }
        } finally {
            c.close();
        }
        return lines;
    }

    /**
     * acc_fr_id, acc_to_id, amount, comment, _id операций из {@code uuids} и {@code ids} —
     * без удалённых и без тех, что прицеплены к другой через operation_link (нога, комиссия).
     */
    static Cursor queryMainOperations(SQLiteDatabase db, List<String> uuids, List<Long> ids) {
        List<String> args = new ArrayList<>(uuids);
        for (Long id : ids) args.add(String.valueOf(id));
        return db.rawQuery(
                "SELECT acc_fr_id, acc_to_id, amount, comment, _id FROM operation"
                        + " WHERE (uuid IN (" + placeholders(uuids.size()) + ")"
                        + " OR _id IN (" + placeholders(ids.size()) + "))"
                        + " AND deleted_at IS NULL"
                        + " AND _id NOT IN (SELECT link_id FROM operation_link WHERE deleted_at IS NULL)"
                        + " ORDER BY date",
                args.toArray(new String[0]));
    }

    // SQLite допускает пустой список в IN () — отдельной ветки для него не нужно.
    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static String describe(AccountItem from, AccountItem to, double amount, String comment) {
        Currency currency = from.isOperatingAccount() ? ((AccountOperatingItem) from).getCurrency()
                : to.isOperatingAccount() ? ((AccountOperatingItem) to).getCurrency() : null;
        String sum = AdapterUtils.formatAmount(Math.abs(amount), currency) + (currency != null ? " " + currency.getSymbol() : "");

        StringBuilder sb = new StringBuilder();
        if (from.isOperatingAccount() && to.isOperatingAccount()) {
            sb.append(sum).append(" · ").append(from.getDisplayName()).append(" → ").append(to.getDisplayName());
        } else if (from.isOperatingAccount()) {
            sb.append('−').append(sum).append(" · ").append(to.getDisplayName());
        } else {
            sb.append('+').append(sum).append(" · ").append(from.getDisplayName());
        }
        if (comment != null && !comment.trim().isEmpty()) sb.append(" · ").append(comment.trim());
        return sb.toString();
    }

    /** «новая операция» / «новые операции» / «новых операций» — после числа (ресурс plurals). */
    static String plural(int n) {
        return com.example.wallettransactions.Texts.plural(R.plurals.notify_new_operations_word, n);
    }
}
