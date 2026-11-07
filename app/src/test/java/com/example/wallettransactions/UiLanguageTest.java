package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.sync.UiLanguageSyncProbe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Язык интерфейса (openspec android-i18n, spec ui-language): полнота перевода — те же ключи
 * и те же подстановки в values-ru/ и values/ (базовый — английский); английские тексты на английском телефоне;
 * склонения по правилам языка.
 */
@RunWith(RobolectricTestRunner.class)
public class UiLanguageTest {

    private static final Pattern ENTRY = Pattern.compile(
            "<(string|plurals) name=\"([^\"]+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxX]");

    /** Ключ → подстановки (для plurals — объединение по всем формам). */
    private static Map<String, TreeSet<String>> read(String folder) throws Exception {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        File dir = new File("src/main/res/" + folder);
        File[] files = dir.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        assertTrue("нет " + dir.getAbsolutePath(), files != null && files.length > 0);
        for (File f : files) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = ENTRY.matcher(text);
            while (m.find()) {
                TreeSet<String> ph = new TreeSet<>();
                Matcher p = PLACEHOLDER.matcher(m.group(3));
                // Ширина и флаги (%02d, %,.2f) — оформление, а не аргумент: сравниваем номер и тип.
                while (p.find()) ph.add(p.group().replaceAll("[-#+ 0,(]|\\.\\d+|\\d+(?!\\$)", ""));
                out.put(m.group(2), ph);
            }
        }
        return out;
    }

    @Test
    public void everyStringTranslated_withSamePlaceholders() throws Exception {
        Map<String, TreeSet<String>> ru = read("values-ru");
        Map<String, TreeSet<String>> en = read("values");
        assertEquals("ключи values-ru и values", ru.keySet(), en.keySet());
        List<String> mismatched = new ArrayList<>();
        for (String key : ru.keySet()) {
            if (!ru.get(key).equals(en.get(key))) mismatched.add(key + ": " + ru.get(key) + " vs " + en.get(key));
        }
        assertTrue("подстановки различаются: " + mismatched, mismatched.isEmpty());
    }

    @Test
    @Config(qualifiers = "en")
    public void englishPhone_englishTexts() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertEquals("Save", ctx.getString(R.string.caption_btn_save));
        assertEquals("Transfer", ctx.getString(R.string.system_category_transfer));
    }

    /** spec «Немецкий телефон»: любой язык, кроме русского, — английский (базовый values/). */
    @Test
    @Config(qualifiers = "de")
    public void germanPhone_englishTexts() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertEquals("Save", ctx.getString(R.string.caption_btn_save));
    }

    @Test
    @Config(qualifiers = "ru")
    public void russianPhone_russianTexts() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertEquals("Сохранить", ctx.getString(R.string.caption_btn_save));
        assertEquals("Перевод", ctx.getString(R.string.system_category_transfer));
    }

    /** spec «Числа с окончаниями». */
    @Test
    @Config(qualifiers = "ru")
    public void plurals_russian() {
        assertEquals("Удалить 1 операцию с телефона? Отменить это нельзя.",
                Texts.plural(R.plurals.connection_confirm_discard, 1));
        assertEquals("Удалить 2 операции с телефона? Отменить это нельзя.",
                Texts.plural(R.plurals.connection_confirm_discard, 2));
        assertEquals("Удалить 5 операций с телефона? Отменить это нельзя.",
                Texts.plural(R.plurals.connection_confirm_discard, 5));
    }

    @Test
    @Config(qualifiers = "en")
    public void plurals_english() {
        assertEquals("Delete 1 operation from the phone? This can't be undone.",
                Texts.plural(R.plurals.connection_confirm_discard, 1));
        assertEquals("Delete 2 operations from the phone? This can't be undone.",
                Texts.plural(R.plurals.connection_confirm_discard, 2));
    }

    /** spec «Журнал синка на английском». */
    @Test
    @Config(qualifiers = "en")
    public void syncJournal_english() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("wallet.db");
        WalletDbHelper helper = new WalletDbHelper(ctx);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            String journal = UiLanguageSyncProbe.journalAfterCurrencyPull(db);
            assertTrue(journal, journal.contains("Added: Currency"));
        } finally {
            helper.close();
        }
    }
}
