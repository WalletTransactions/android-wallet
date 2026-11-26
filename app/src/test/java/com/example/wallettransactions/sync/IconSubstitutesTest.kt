package com.example.wallettransactions.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * openspec server-managed-icons, задача 2.2: порядок выбора заместителя своей иконки
 * (та же картинка → то же имя без регистра → `unknown`) и его отсутствие. Без БД.
 */
class IconSubstitutesTest {

    companion object {
        private const val SHA_A = "aa"
        private const val SHA_B = "bb"
        private const val SHA_OWN = "ff"
    }

    @Test
    fun sameImageWinsOverSameName() {
        val subs = IconSubstitutes()
        subs.addServerIcon("u-name", "Cinema", SHA_A)
        subs.addServerIcon("u-image", "Movie", SHA_B)
        subs.addServerIcon("u-unknown", "unknown", "cc")
        assertEquals("u-image", subs.substituteFor("Cinema", SHA_B))
    }

    /** Сценарий спеки «Совпадает имя»: своя Cinema, с сервера cinema с другой картинкой. */
    @Test
    fun sameNameIgnoringCase() {
        val subs = IconSubstitutes()
        subs.addServerIcon("u-unknown", "unknown", "cc")
        subs.addServerIcon("u-cinema", "cinema", SHA_A)
        assertEquals("u-cinema", subs.substituteFor("Cinema", SHA_OWN))
    }

    /** Сценарий спеки «Ничего похожего» — общая unknown. */
    @Test
    fun fallsBackToUnknown() {
        val subs = IconSubstitutes()
        subs.addServerIcon("u-cinema", "cinema", SHA_A)
        subs.addServerIcon("u-unknown", "Unknown", "cc")
        assertEquals("u-unknown", subs.substituteFor("Моё фото", SHA_OWN))
    }

    /** Сценарий спеки «На сервере нет даже unknown». */
    @Test
    fun noSubstituteWithoutServerIcons() {
        val subs = IconSubstitutes()
        assertNull(subs.substituteFor("Cinema", SHA_OWN))

        subs.addServerIcon("u-cinema", "cinema", SHA_A)
        assertNull(subs.substituteFor("Моё фото", SHA_OWN))
    }

    @Test
    fun firstAddedWinsOnDuplicateKeys() {
        val subs = IconSubstitutes()
        subs.addServerIcon("u-first", "Cinema", SHA_A)
        subs.addServerIcon("u-second", "cinema", SHA_A)
        assertEquals("u-first", subs.substituteFor("CINEMA", SHA_A))
        assertEquals("u-first", subs.substituteFor("CINEMA", SHA_OWN))
    }

    @Test
    fun byteOverloadHashesImage() {
        val image = "abc".toByteArray(StandardCharsets.US_ASCII)
        val subs = IconSubstitutes()
        subs.addServerIcon("u-abc", "Другое имя", IconSubstitutes.sha256Hex(image))
        assertEquals("u-abc", subs.substituteFor("Cinema", image))
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IconSubstitutes.sha256Hex("abc".toByteArray(StandardCharsets.US_ASCII))
        )
    }

    @Test
    fun sameUuidIgnoresDashesAndCase() {
        assertTrue(
            IconSubstitutes.sameUuid(
                "0B9D6F3E-1111-4222-8333-444455556666",
                "0b9d6f3e111142228333444455556666"
            )
        )
        assertFalse(
            IconSubstitutes.sameUuid(
                "0b9d6f3e-1111-4222-8333-444455556666",
                "0b9d6f3e-1111-4222-8333-444455556667"
            )
        )
        assertFalse(IconSubstitutes.sameUuid(null, "x"))
    }
}
