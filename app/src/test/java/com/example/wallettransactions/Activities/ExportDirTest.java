package com.example.wallettransactions.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Папка экспорта (android-wallet#38): на Android 8–9 экспорт писал в общую Download/
 * без WRITE_EXTERNAL_STORAGE и падал. Теперь на всех версиях — папка приложения, писать
 * туда можно без разрешений, а «Поделиться» отдаёт файл через FileProvider.
 */
@RunWith(RobolectricTestRunner.class)
public class ExportDirTest {

    private static void assertAppDirAndShareable() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File dir = ExportActivity.exportDir(ctx);
        File appFiles = ctx.getExternalFilesDir(null);
        assertTrue(dir + " вне " + appFiles,
                dir.getCanonicalPath().startsWith(appFiles.getCanonicalPath() + File.separator));
        assertTrue(dir.isDirectory());

        File f = new File(dir, "wallet_export_test.json");
        assertTrue(f.createNewFile() || f.exists());
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        assertEquals(ctx.getPackageName() + ".fileprovider", uri.getAuthority());
        assertTrue(f.delete());
    }

    @Test
    @Config(sdk = 28)
    public void android9_exportsToAppDir() throws Exception {
        assertAppDirAndShareable();
    }

    @Test
    @Config(sdk = 34)
    public void android14_exportsToAppDir() throws Exception {
        assertAppDirAndShareable();
    }
}
