package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.sync.SyncLog
import com.example.wallettransactions.sync.SyncRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.lang.reflect.Proxy

/**
 * openspec summary-other-section, спека period-summary: раскрытие «Прочего» по кнопке и по
 * настройке. Таб — «Кошельки»: карточка у всех табов общая ([BaseTabActivity]), а
 * поднимается он в Robolectric без диалога восстановления. Помеченный актив делает строку
 * «Прочее» видимой независимо от сумм.
 */
@RunWith(RobolectricTestRunner::class)
class SummaryOtherExpandTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var originalPass: Any? = null

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        db.execSQL("UPDATE icon SET image = x'00'") // картинки сида не влезают в CursorWindow Robolectric
        val rubId = intQuery("SELECT _id FROM currency WHERE iso_code = 'RUB' AND deleted_at IS NULL")
        val iconId = intQuery("SELECT _id FROM icon WHERE name = 'unknown'")
        originalPass = swapSyncPass(noNetworkPass())

        val flat = WalletDbCRUD.InsertOperatingAccount(db, "Квартира для теста", "", iconId, rubId).toInt()
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        Singleton.instance.reloadAccounts(context)
    }

    @After
    fun tearDown() {
        swapSyncPass(originalPass)
        resetSingleton()
    }

    private fun openTab(saved: Bundle?): ActivityController<AccountActivity> {
        val c = Robolectric.buildActivity(
            AccountActivity::class.java,
            Intent(context, AccountActivity::class.java)
        )
        if (saved == null) c.setup() else c.setup(saved)
        idle()
        return c
    }

    private fun detailsVisibility(c: ActivityController<AccountActivity>): Int {
        return c.get().findViewById<View>(R.id.otherDetails).visibility
    }

    @Test
    fun defaultSetting_isCollapsed() {
        assertFalse("по умолчанию настройка выключена", AppSettings.isSummaryOtherExpanded(context))
        val tab = openTab(null)

        assertEquals(View.VISIBLE, tab.get().findViewById<View>(R.id.otherRow).visibility)
        assertEquals("видна только сумма", View.GONE, detailsVisibility(tab))
        tab.pause().stop().destroy()
    }

    @Test
    fun settingOn_opensExpanded() {
        AppSettings.setSummaryOtherExpanded(context, true)
        val tab = openTab(null)

        assertEquals(View.VISIBLE, detailsVisibility(tab))
        assertEquals(
            "строка «Капитал» в деталях", View.VISIBLE,
            tab.get().findViewById<View>(R.id.otherCapitalRow).visibility
        )
        tab.pause().stop().destroy()
    }

    @Test
    fun tapExpands_settingStays_reopenTakesSetting() {
        val tab = openTab(null)

        tab.get().findViewById<View>(R.id.otherRow).performClick()
        assertEquals("раскрыли вручную", View.VISIBLE, detailsVisibility(tab))
        assertFalse("настройка осталась выключенной", AppSettings.isSummaryOtherExpanded(context))

        tab.get().findViewById<View>(R.id.otherRow).performClick()
        assertEquals("повторное нажатие сворачивает", View.GONE, detailsVisibility(tab))

        tab.get().findViewById<View>(R.id.otherRow).performClick()
        tab.pause().stop().destroy()

        val reopened = openTab(null)
        assertEquals("экран открыт заново — вид из настройки", View.GONE, detailsVisibility(reopened))
        reopened.pause().stop().destroy()
    }

    @Test
    fun reload_keepsManualState() {
        val tab = openTab(null)
        tab.get().findViewById<View>(R.id.otherRow).performClick()

        tab.get().reloadSharedAndTabData() // смена периода, фильтра или валюты
        idle()

        assertEquals("смена периода не сворачивает «Прочее»", View.VISIBLE, detailsVisibility(tab))
        tab.pause().stop().destroy()
    }

    @Test
    fun settingChangedWhileOpen_doesNotFlipOpenScreen() {
        val tab = openTab(null)
        tab.get().findViewById<View>(R.id.otherRow).performClick()

        AppSettings.setSummaryOtherExpanded(context, false)
        tab.get().reloadSharedAndTabData()
        idle()

        assertEquals("раскрытое вручную не переворачивается настройкой", View.VISIBLE, detailsVisibility(tab))
        tab.pause().stop().destroy()
    }

    @Test
    fun rotation_keepsManualState() {
        val tab = openTab(null)
        tab.get().findViewById<View>(R.id.otherRow).performClick()
        val state = Bundle()
        tab.pause().saveInstanceState(state).stop().destroy()

        val recreated = openTab(state)

        assertEquals("после поворота вид сохранён", View.VISIBLE, detailsVisibility(recreated))
        assertFalse(AppSettings.isSummaryOtherExpanded(context))
        recreated.pause().stop().destroy()
    }

    @Test
    fun chevron_pointsDownCollapsedUpExpanded() {
        val tab = openTab(null)
        val chevron = tab.get().findViewById<View>(R.id.otherChevron)
        assertEquals(90f, chevron.rotation, 0.01f)

        tab.get().findViewById<View>(R.id.otherRow).performClick()
        assertEquals(-90f, chevron.rotation, 0.01f)
        tab.pause().stop().destroy()
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun intQuery(sql: String): Int {
        val cur = db.rawQuery(sql, null)
        try {
            assertTrue(sql, cur.moveToFirst())
            return cur.getInt(0)
        } finally {
            cur.close()
        }
    }

    /** Синк в фоне после записи — без сети и без чужого потока в базе теста. */
    private fun noNetworkPass(): Any {
        val passType = Class.forName("com.example.wallettransactions.sync.SyncRunner\$Pass")
        return Proxy.newProxyInstance(passType.classLoader, arrayOf(passType)) { _, method, _ ->
            if (method.name == "run") SyncRunner.Outcome(SyncRunner.Status.NO_SESSION, SyncLog(), null)
            else null
        }
    }

    private fun swapSyncPass(pass: Any?): Any? {
        val f = SyncRunner::class.java.getDeclaredField("pass")
        f.isAccessible = true
        val old = f.get(null)
        f.set(null, pass)
        return old
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
