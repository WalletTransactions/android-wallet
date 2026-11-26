package com.example.wallettransactions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.sync.UiLanguageSyncProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.TreeMap
import java.util.TreeSet
import java.util.regex.Pattern

/**
 * Язык интерфейса (openspec android-i18n, spec ui-language): полнота перевода — те же ключи
 * и те же подстановки в values-ru/ и values/ (базовый — английский); английские тексты на английском телефоне;
 * склонения по правилам языка.
 */
@RunWith(RobolectricTestRunner::class)
class UiLanguageTest {

    @Test
    fun everyStringTranslated_withSamePlaceholders() {
        val ru = read("values-ru")
        val en = read("values")
        assertEquals("ключи values-ru и values", ru.keys, en.keys)
        val mismatched = ArrayList<String>()
        for (key in ru.keys) {
            if (ru[key] != en[key]) mismatched.add("$key: ${ru[key]} vs ${en[key]}")
        }
        assertTrue("подстановки различаются: $mismatched", mismatched.isEmpty())
    }

    @Test
    @Config(qualifiers = "en")
    fun englishPhone_englishTexts() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals("Save", ctx.getString(R.string.caption_btn_save))
        assertEquals("Transfer", ctx.getString(R.string.system_category_transfer))
    }

    /** spec «Немецкий телефон»: любой язык, кроме русского, — английский (базовый values/). */
    @Test
    @Config(qualifiers = "de")
    fun germanPhone_englishTexts() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals("Save", ctx.getString(R.string.caption_btn_save))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianPhone_russianTexts() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals("Сохранить", ctx.getString(R.string.caption_btn_save))
        assertEquals("Перевод", ctx.getString(R.string.system_category_transfer))
    }

    /** spec «Числа с окончаниями». */
    @Test
    @Config(qualifiers = "ru")
    fun plurals_russian() {
        assertEquals(
            "Удалить 1 операцию с телефона? Отменить это нельзя.",
            Texts.plural(R.plurals.connection_confirm_discard, 1)
        )
        assertEquals(
            "Удалить 2 операции с телефона? Отменить это нельзя.",
            Texts.plural(R.plurals.connection_confirm_discard, 2)
        )
        assertEquals(
            "Удалить 5 операций с телефона? Отменить это нельзя.",
            Texts.plural(R.plurals.connection_confirm_discard, 5)
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun plurals_english() {
        assertEquals(
            "Delete 1 operation from the phone? This can't be undone.",
            Texts.plural(R.plurals.connection_confirm_discard, 1)
        )
        assertEquals(
            "Delete 2 operations from the phone? This can't be undone.",
            Texts.plural(R.plurals.connection_confirm_discard, 2)
        )
    }

    /** spec «Журнал синка на английском». */
    @Test
    @Config(qualifiers = "en")
    fun syncJournal_english() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("wallet.db")
        val helper = WalletDbHelper(ctx)
        val db = helper.writableDatabase
        try {
            val journal = UiLanguageSyncProbe.journalAfterCurrencyPull(db)
            assertTrue(journal, journal.contains("Added: Currency"))
        } finally {
            helper.close()
        }
    }

    companion object {
        private val ENTRY = Pattern.compile(
            "<(string|plurals) name=\"([^\"]+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL
        )
        private val PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxX]")

        /** Ключ → подстановки (для plurals — объединение по всем формам). */
        private fun read(folder: String): Map<String, TreeSet<String>> {
            val out: MutableMap<String, TreeSet<String>> = TreeMap()
            val dir = File("src/main/res/$folder")
            val files = dir.listFiles { _, n -> n.startsWith("strings") && n.endsWith(".xml") }
            assertTrue("нет " + dir.absolutePath, files != null && files.isNotEmpty())
            for (f in files!!) {
                val text = String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                val m = ENTRY.matcher(text)
                while (m.find()) {
                    val ph = TreeSet<String>()
                    val p = PLACEHOLDER.matcher(m.group(3))
                    // Ширина и флаги (%02d, %,.2f) — оформление, а не аргумент: сравниваем номер и тип.
                    while (p.find()) ph.add(p.group().replace(Regex("[-#+ 0,(]|\\.\\d+|\\d+(?!\\$)"), ""))
                    out[m.group(2)!!] = ph
                }
            }
            return out
        }
    }
}
