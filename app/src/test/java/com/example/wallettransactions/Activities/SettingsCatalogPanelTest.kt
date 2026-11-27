package com.example.wallettransactions.Activities

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * openspec catalog-filters-panel, задача 3.2: раздел «СПРАВОЧНИК» в «Настройках» — два
 * переключателя исходного состояния панели «Фильтры» справочника.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsCatalogPanelTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java).setup()

    private fun isOn(v: FrameLayout): Boolean = v.tag == true

    @Test
    fun sectionAndTwoSwitches_areOffByDefault() {
        val c = open()
        val a = c.get()

        val labels = ArrayList<String>()
        collectTexts(a.findViewById(android.R.id.content), labels)
        assertTrue("раздел «СПРАВОЧНИК»", labels.contains(a.getString(R.string.settings_catalog_section)))
        assertTrue(labels.contains(a.getString(R.string.settings_catalog_panel_wallets)))
        assertTrue(labels.contains(a.getString(R.string.settings_catalog_panel_categories)))

        assertFalse(isOn(a.findViewById(R.id.switchCatalogPanelWallets)))
        assertFalse(isOn(a.findViewById(R.id.switchCatalogPanelCategories)))
        c.pause().stop().destroy()
    }

    @Test
    fun tapWritesSettingAndIsIndependent() {
        val c = open()
        val a = c.get()

        a.findViewById<View>(R.id.switchCatalogPanelCategories).performClick()
        assertTrue(AppSettings.isCatalogPanelOpen(context, false))
        assertFalse("кошельки не затронуты", AppSettings.isCatalogPanelOpen(context, true))

        a.findViewById<View>(R.id.switchCatalogPanelWallets).performClick()
        assertTrue(AppSettings.isCatalogPanelOpen(context, true))

        a.findViewById<View>(R.id.switchCatalogPanelCategories).performClick()
        assertFalse(AppSettings.isCatalogPanelOpen(context, false))
        assertTrue(AppSettings.isCatalogPanelOpen(context, true))
        c.pause().stop().destroy()
    }

    @Test
    fun reopenedScreen_showsSavedValue() {
        AppSettings.setCatalogPanelOpen(context, false, true)
        val c = open()
        val a = c.get()
        assertTrue(isOn(a.findViewById(R.id.switchCatalogPanelCategories)))
        assertFalse(isOn(a.findViewById(R.id.switchCatalogPanelWallets)))
        // Подпись «развёрнута при открытии справочника» есть у обоих переключателей.
        val texts = ArrayList<String>()
        collectTexts(a.findViewById(android.R.id.content), texts)
        assertEquals(2, texts.count { it == a.getString(R.string.settings_catalog_panel_hint) })
        c.pause().stop().destroy()
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v is TextView) out.add(v.text.toString())
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }
}
