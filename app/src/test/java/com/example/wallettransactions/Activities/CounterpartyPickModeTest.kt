package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Карточка займа выбирает контрагента на экране «Контрагенты» (спека debts, «Выбор контрагента
 * в карточке займа»): тап по строке возвращает контрагента, архивные не предлагаются.
 */
@RunWith(RobolectricTestRunner::class)
class CounterpartyPickModeTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var controller: ActivityController<CounterpartiesActivity>? = null

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'")
        WalletDbCRUD.CreateCounterparty(db, "Ктест Вася", "")
        WalletDbCRUD.CreateCounterparty(db, "Ктест Петя", "")
        val archived = WalletDbCRUD.CreateCounterparty(db, "Ктест Архивный", "")
        WalletDbCRUD.ArchiveCounterparty(db, archived, true)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        resetSingleton()
    }

    @Test
    fun pick_tapOnRow_returnsCounterparty() {
        val activity = start(CounterpartiesActivity.buildPickIntent(context, null))

        val row = rowByName(activity, "Ктест Петя")
        assertNotNull("нет строки Ктест Петя", row)
        row!!.performClick()

        assertTrue(activity.isFinishing)
        val result = shadowOf(activity).resultIntent
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("Ктест Петя", result.getStringExtra(CounterpartiesActivity.RESULT_NAME))
        assertEquals(
            WalletDbCRUD.CounterpartyUuid(db, WalletDbCRUD.GetCounterpartyIdByName(db, "Ктест Петя")),
            result.getStringExtra(CounterpartiesActivity.RESULT_UUID)
        )
    }

    @Test
    fun pick_hidesArchived_exceptSelected() {
        val plain = start(CounterpartiesActivity.buildPickIntent(context, null))
        assertEquals(null, rowByName(plain, "Ктест Архивный"))

        val archivedUuid = WalletDbCRUD.CounterpartyUuid(
            db,
            WalletDbCRUD.GetCounterpartyIdByName(db, "Ктест Архивный")
        )
        val withSelected = start(CounterpartiesActivity.buildPickIntent(context, archivedUuid))
        assertNotNull(rowByName(withSelected, "Ктест Архивный"))
    }

    @Test
    fun normalMode_tapOpensCard_notResult() {
        val activity = start(Intent(context, CounterpartiesActivity::class.java))

        rowByName(activity, "Ктест Вася")!!.performClick()

        assertFalse(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(CounterpartyCardActivity::class.java.name, next.component!!.className)
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun start(intent: Intent): CounterpartiesActivity {
        controller?.pause()?.stop()?.destroy()
        val c = Robolectric.buildActivity(CounterpartiesActivity::class.java, intent).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    /** Строка списка, чей первый TextView — имя контрагента. */
    private fun rowByName(activity: CounterpartiesActivity, name: String): View? {
        val list = activity.findViewById<LinearLayout>(R.id.counterpartiesList)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child is ViewGroup && child.childCount > 0) {
                val first = child.getChildAt(0)
                if (first is TextView && name.contentEquals(first.text)) return child
            }
        }
        return null
    }

    private fun resetSingleton() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase", "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
        pairs.isAccessible = true
        pairs.set(Singleton.instance, CurrencyPairList())
    }
}
