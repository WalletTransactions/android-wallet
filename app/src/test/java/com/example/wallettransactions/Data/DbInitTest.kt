package com.example.wallettransactions.Data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Activities.DbInitActivity
import com.example.wallettransactions.Activities.MainActivity
import com.example.wallettransactions.Activities.StatementImportActivity
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Первый запуск без ANR (openspec fix-first-start-anr): пока базы нет, точка входа
 * уступает место «Готовим кошелёк…», а база создаётся в фоне.
 */
@RunWith(RobolectricTestRunner::class)
class DbInitTest {

    private lateinit var context: Context

    @Before
    @Throws(Exception::class)
    fun setUp() {
        resetSingleton()
        DbInit.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(WalletDbHelper.DATABASE_NAME)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        resetSingleton()
        DbInit.resetForTest()
    }

    @Test
    fun fresh_install_is_not_ready() {
        assertFalse(DbInit.isReady(context))
    }

    @Test
    fun ready_after_helper_created_db() {
        Singleton.instance.getWritableDatabase(context)
        DbInit.resetForTest() // проверяем сам файл, а не флаг
        assertTrue(DbInit.isReady(context))
    }

    @Test
    fun older_db_version_is_not_ready() {
        val db = context.openOrCreateDatabase(
            WalletDbHelper.DATABASE_NAME, Context.MODE_PRIVATE, null
        )
        db.version = WalletDbHelper.DATABASE_VERSION - 1
        db.close()
        assertFalse("миграции тоже идут в фоне", DbInit.isReady(context))
    }

    @Test
    fun entry_point_redirects_to_init_screen_and_closes() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val entry = Robolectric.buildActivity(Activity::class.java, launch).create().get()

        assertTrue(DbInit.redirectIfNeeded(entry))
        assertTrue(entry.isFinishing)

        val started = shadowOf(entry).nextStartedActivity
        assertEquals(DbInitActivity::class.java.name, started.component!!.className)
        val next = started.getParcelableExtra<Intent>(DbInit.EXTRA_NEXT)!!
        assertEquals(Activity::class.java.name, next.component!!.className)
        assertEquals(Intent.ACTION_MAIN, next.action)
        assertEquals("флаги запуска не повторяем — задача уже есть", 0, next.flags)
    }

    @Test
    fun ready_db_does_not_redirect() {
        Singleton.instance.getWritableDatabase(context)
        val entry = Robolectric.buildActivity(Activity::class.java).create().get()
        assertFalse(DbInit.redirectIfNeeded(entry))
        assertFalse(entry.isFinishing)
        assertNull(shadowOf(entry).nextStartedActivity)
    }

    @Test
    fun shared_file_keeps_uri_grant() {
        val file = Uri.parse("content://bank.app/statement.pdf")
        val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, file)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        share.clipData = ClipData.newRawUri("", file)

        val init = DbInit.initIntent(context, share, StatementImportActivity::class.java)

        assertNotNull("право на файл доезжает до экрана подготовки", init.clipData)
        assertTrue((init.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val next = init.getParcelableExtra<Intent>(DbInit.EXTRA_NEXT)!!
        assertEquals(StatementImportActivity::class.java.name, next.component!!.className)
        assertEquals(file, next.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, next.flags)
    }

    @Test
    @Throws(Exception::class)
    fun init_screen_creates_db_in_background_then_opens_next() {
        val next = Intent(context, MainActivity::class.java)
        val init = Intent(context, DbInitActivity::class.java).putExtra(DbInit.EXTRA_NEXT, next)
        val controller: ActivityController<DbInitActivity> =
            Robolectric.buildActivity(DbInitActivity::class.java, init).create().start().resume()
        val screen = controller.get()

        // База заводится в потоке db-init; итог он отдаёт главному потоку.
        var opened: Intent? = null
        var i = 0
        while (i < 600 && opened == null) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
            opened = shadowOf(screen).nextStartedActivity
            i++
        }
        assertNotNull("экран подготовки так и не пустил дальше", opened)
        assertEquals(MainActivity::class.java.name, opened!!.component!!.className)
        assertTrue(screen.isFinishing)
        DbInit.resetForTest()
        assertTrue("база создана", DbInit.isReady(context))
    }

    @Throws(Exception::class)
    private fun resetSingleton() {
        for (f in arrayOf(
            "dbHelper", "readableDatabase", "writeableDatabase",
            "iconList", "accounts", "currencyList"
        )) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            val old = field.get(Singleton.instance)
            if (old is android.database.sqlite.SQLiteOpenHelper) {
                old.close()
            }
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, CurrencyPairList())
    }
}
