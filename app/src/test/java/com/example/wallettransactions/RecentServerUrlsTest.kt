package com.example.wallettransactions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Последние адреса сервера с успешным входом ([AppSettings.rememberSuccessfulServerUrl]):
 * новые первыми, без повторов, не больше трёх.
 */
@RunWith(RobolectricTestRunner::class)
class RecentServerUrlsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun emptyByDefault() {
        assertTrue(AppSettings.getRecentServerUrls(app).isEmpty())
    }

    @Test
    fun newestFirst_cappedAtThree() {
        AppSettings.rememberSuccessfulServerUrl(app, "https://a/")
        AppSettings.rememberSuccessfulServerUrl(app, "https://b/")
        AppSettings.rememberSuccessfulServerUrl(app, "https://c/")
        AppSettings.rememberSuccessfulServerUrl(app, "https://d/")
        assertEquals(
            listOf("https://d/", "https://c/", "https://b/"),
            AppSettings.getRecentServerUrls(app)
        )
    }

    @Test
    fun repeatedUrlMovesToTop_withoutDuplicate() {
        AppSettings.rememberSuccessfulServerUrl(app, "https://a/")
        AppSettings.rememberSuccessfulServerUrl(app, "https://b/")
        AppSettings.rememberSuccessfulServerUrl(app, "https://a")
        assertEquals(listOf("https://a/", "https://b/"), AppSettings.getRecentServerUrls(app))
    }

    @Test
    fun blankIgnored() {
        AppSettings.rememberSuccessfulServerUrl(app, "  ")
        AppSettings.rememberSuccessfulServerUrl(app, null)
        assertTrue(AppSettings.getRecentServerUrls(app).isEmpty())
    }

    @Test
    fun corruptedValue_treatedAsEmpty() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("recentServerUrls", "not json").commit()
        assertTrue(AppSettings.getRecentServerUrls(app).isEmpty())
        AppSettings.rememberSuccessfulServerUrl(app, "https://a/")
        assertEquals(listOf("https://a/"), AppSettings.getRecentServerUrls(app))
    }
}
