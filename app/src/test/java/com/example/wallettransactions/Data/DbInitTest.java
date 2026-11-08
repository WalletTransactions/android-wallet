package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Activities.DbInitActivity;
import com.example.wallettransactions.Activities.MainActivity;
import com.example.wallettransactions.Activities.StatementImportActivity;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.Singleton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;

/**
 * Первый запуск без ANR (openspec fix-first-start-anr): пока базы нет, точка входа
 * уступает место «Готовим кошелёк…», а база создаётся в фоне.
 */
@RunWith(RobolectricTestRunner.class)
public class DbInitTest {

    private Context context;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        DbInit.resetForTest();
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(WalletDbHelper.DATABASE_NAME);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
        DbInit.resetForTest();
    }

    @Test
    public void fresh_install_is_not_ready() {
        assertFalse(DbInit.isReady(context));
    }

    @Test
    public void ready_after_helper_created_db() {
        Singleton.instance.getWritableDatabase(context);
        DbInit.resetForTest();  // проверяем сам файл, а не флаг
        assertTrue(DbInit.isReady(context));
    }

    @Test
    public void older_db_version_is_not_ready() {
        android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase(
                WalletDbHelper.DATABASE_NAME, Context.MODE_PRIVATE, null);
        db.setVersion(WalletDbHelper.DATABASE_VERSION - 1);
        db.close();
        assertFalse("миграции тоже идут в фоне", DbInit.isReady(context));
    }

    @Test
    public void entry_point_redirects_to_init_screen_and_closes() {
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity entry = Robolectric.buildActivity(Activity.class, launch).create().get();

        assertTrue(DbInit.redirectIfNeeded(entry));
        assertTrue(entry.isFinishing());

        Intent started = shadowOf(entry).getNextStartedActivity();
        assertEquals(DbInitActivity.class.getName(), started.getComponent().getClassName());
        Intent next = started.getParcelableExtra(DbInit.EXTRA_NEXT);
        assertEquals(Activity.class.getName(), next.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN, next.getAction());
        assertEquals("флаги запуска не повторяем — задача уже есть", 0, next.getFlags());
    }

    @Test
    public void ready_db_does_not_redirect() {
        Singleton.instance.getWritableDatabase(context);
        Activity entry = Robolectric.buildActivity(Activity.class).create().get();
        assertFalse(DbInit.redirectIfNeeded(entry));
        assertFalse(entry.isFinishing());
        assertNull(shadowOf(entry).getNextStartedActivity());
    }

    @Test
    public void shared_file_keeps_uri_grant() {
        Uri file = Uri.parse("content://bank.app/statement.pdf");
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, file)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        share.setClipData(ClipData.newRawUri("", file));

        Intent init = DbInit.initIntent(context, share, StatementImportActivity.class);

        assertNotNull("право на файл доезжает до экрана подготовки", init.getClipData());
        assertTrue((init.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Intent next = init.getParcelableExtra(DbInit.EXTRA_NEXT);
        assertEquals(StatementImportActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(file, next.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, next.getFlags());
    }

    @Test
    public void init_screen_creates_db_in_background_then_opens_next() throws Exception {
        Intent next = new Intent(context, MainActivity.class);
        Intent init = new Intent(context, DbInitActivity.class).putExtra(DbInit.EXTRA_NEXT, next);
        ActivityController<DbInitActivity> controller =
                Robolectric.buildActivity(DbInitActivity.class, init).create().start().resume();
        DbInitActivity screen = controller.get();

        // База заводится в потоке db-init; итог он отдаёт главному потоку.
        Intent opened = null;
        for (int i = 0; i < 600 && opened == null; i++) {
            Thread.sleep(100);
            shadowOf(Looper.getMainLooper()).idle();
            opened = shadowOf(screen).getNextStartedActivity();
        }
        assertNotNull("экран подготовки так и не пустил дальше", opened);
        assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue(screen.isFinishing());
        DbInit.resetForTest();
        assertTrue("база создана", DbInit.isReady(context));
    }

    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            Object old = field.get(Singleton.instance);
            if (old instanceof android.database.sqlite.SQLiteOpenHelper) {
                ((android.database.sqlite.SQLiteOpenHelper) old).close();
            }
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new CurrencyPairList());
    }
}
