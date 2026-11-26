package com.example.wallettransactions.Activities

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Папка экспорта (android-wallet#38): на Android 8–9 экспорт писал в общую Download/
 * без WRITE_EXTERNAL_STORAGE и падал. Теперь на всех версиях — папка приложения, писать
 * туда можно без разрешений, а «Поделиться» отдаёт файл через FileProvider.
 */
@RunWith(RobolectricTestRunner::class)
class ExportDirTest {

    private fun assertAppDirAndShareable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ExportActivity.exportDir(ctx)
        val appFiles = ctx.getExternalFilesDir(null)!!
        assertTrue(
            "$dir вне $appFiles",
            dir.canonicalPath.startsWith(appFiles.canonicalPath + File.separator)
        )
        assertTrue(dir.isDirectory)

        val f = File(dir, "wallet_export_test.json")
        assertTrue(f.createNewFile() || f.exists())
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        assertEquals(ctx.packageName + ".fileprovider", uri.authority)
        assertTrue(f.delete())
    }

    @Test
    @Config(sdk = [28])
    fun android9_exportsToAppDir() {
        assertAppDirAndShareable()
    }

    @Test
    @Config(sdk = [34])
    fun android14_exportsToAppDir() {
        assertAppDirAndShareable()
    }
}
