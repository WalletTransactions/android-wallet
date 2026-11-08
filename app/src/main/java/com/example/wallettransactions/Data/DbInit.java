package com.example.wallettransactions.Data;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.example.wallettransactions.Activities.DbInitActivity;

import java.io.File;

/**
 * Первое открытие базы — не на главном потоке (openspec fix-first-start-anr).
 *
 * {@link WalletDbHelper#onCreate} заводит схему и заполняет справочник (иконки
 * перекодируются в PNG), {@code onUpgrade} гоняет миграции. Раньше это шло там, где
 * базу попросили первой, — в {@code MainActivity.onCreate}, — и на медленном телефоне
 * (Huawei P20 Pro) первый запуск ловил ANR. Теперь точки входа сперва спрашивают
 * {@link #redirectIfNeeded}: база не готова — экран уступает место
 * {@link DbInitActivity}, которая открывает её в фоне и потом пускает дальше.
 */
public final class DbInit {

    /** Исходный intent экрана, который надо открыть, когда база готова. */
    public static final String EXTRA_NEXT = "com.example.wallettransactions.DB_INIT_NEXT";

    private static volatile boolean ready;

    private DbInit() {}

    /**
     * База создана и не ждёт миграций: файл есть и его версия не ниже
     * {@link WalletDbHelper#DATABASE_VERSION}. Проверка — чтение заголовка файла,
     * без onCreate/onUpgrade; после первого «да» — просто флаг.
     */
    public static boolean isReady(Context ctx) {
        if (ready) return true;
        File file = ctx.getDatabasePath(WalletDbHelper.DATABASE_NAME);
        if (!file.exists()) return false;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY)) {
            if (db.getVersion() >= WalletDbHelper.DATABASE_VERSION) ready = true;
        } catch (SQLiteException e) {
            // Недописанный файл (процесс убили посреди onCreate) — пусть разбирается helper в фоне.
            return false;
        }
        return ready;
    }

    /** Вызывает {@link DbInitActivity}, когда база открыта. */
    public static void markReady() {
        ready = true;
    }

    /** Для тестов: забыть, что база уже проверена. */
    static void resetForTest() {
        ready = false;
    }

    /**
     * Звать в {@code onCreate} точки входа сразу после {@code super.onCreate}.
     * База готова — false, экран работает как обычно. Нет — открывает
     * {@link DbInitActivity} с исходным intent'ом, закрывает экран и возвращает true:
     * вызывающий должен сразу выйти из {@code onCreate}.
     */
    public static boolean redirectIfNeeded(Activity activity) {
        if (isReady(activity)) return false;
        activity.startActivity(initIntent(activity, activity.getIntent(), activity.getClass()));
        activity.finish();
        activity.overridePendingTransition(0, 0);
        return true;
    }

    /**
     * Intent экрана подготовки. Исходный уходит в {@link #EXTRA_NEXT} без флагов запуска
     * (задача уже есть), но с правами на файл из «Поделиться»: ClipData и флаги
     * GRANT_* копируются и на сам экран подготовки — иначе право на чужой URI
     * не доехало бы до экрана выписки.
     */
    static Intent initIntent(Context ctx, Intent original, Class<?> target) {
        int grants = original.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Intent next = new Intent(original).setClass(ctx, target).setFlags(grants);
        Intent init = new Intent(ctx, DbInitActivity.class).putExtra(EXTRA_NEXT, next);
        if (original.getClipData() != null) {
            init.setClipData(original.getClipData());
            init.addFlags(grants);
        }
        return init;
    }
}
