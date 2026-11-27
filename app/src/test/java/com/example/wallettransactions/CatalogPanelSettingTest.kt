package com.example.wallettransactions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** openspec catalog-filters-panel, задача 3.1: настройки исходного состояния панели справочника. */
@RunWith(RobolectricTestRunner::class)
class CatalogPanelSettingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsAreClosed() {
        assertFalse(AppSettings.isCatalogPanelOpen(context, true))
        assertFalse(AppSettings.isCatalogPanelOpen(context, false))
    }

    @Test
    fun settingsAreIndependent() {
        AppSettings.setCatalogPanelOpen(context, false, true)
        assertTrue(AppSettings.isCatalogPanelOpen(context, false))
        assertFalse(AppSettings.isCatalogPanelOpen(context, true))

        AppSettings.setCatalogPanelOpen(context, true, true)
        AppSettings.setCatalogPanelOpen(context, false, false)
        assertTrue(AppSettings.isCatalogPanelOpen(context, true))
        assertFalse(AppSettings.isCatalogPanelOpen(context, false))
    }

    @Test
    fun valueSurvivesReopeningPreferences() {
        AppSettings.setCatalogPanelOpen(context, true, true)
        // Те же prefs, прочитанные заново напрямую, — значение лежит в хранилище, а не в поле.
        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertTrue(raw.getBoolean("catalogPanelWallets", false))
        assertFalse(raw.contains("catalogPanelCategories"))
        assertTrue(AppSettings.isCatalogPanelOpen(context, true))
    }
}
