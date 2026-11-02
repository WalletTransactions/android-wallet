package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * openspec server-managed-icons, задача 2.2: порядок выбора заместителя своей иконки
 * (та же картинка → то же имя без регистра → {@code unknown}) и его отсутствие. Без БД.
 */
public class IconSubstitutesTest {

    private static final String SHA_A = "aa";
    private static final String SHA_B = "bb";
    private static final String SHA_OWN = "ff";

    @Test
    public void sameImageWinsOverSameName() {
        IconSubstitutes subs = new IconSubstitutes();
        subs.addServerIcon("u-name", "Cinema", SHA_A);
        subs.addServerIcon("u-image", "Movie", SHA_B);
        subs.addServerIcon("u-unknown", "unknown", "cc");
        assertEquals("u-image", subs.substituteFor("Cinema", SHA_B));
    }

    /** Сценарий спеки «Совпадает имя»: своя Cinema, с сервера cinema с другой картинкой. */
    @Test
    public void sameNameIgnoringCase() {
        IconSubstitutes subs = new IconSubstitutes();
        subs.addServerIcon("u-unknown", "unknown", "cc");
        subs.addServerIcon("u-cinema", "cinema", SHA_A);
        assertEquals("u-cinema", subs.substituteFor("Cinema", SHA_OWN));
    }

    /** Сценарий спеки «Ничего похожего» — общая unknown. */
    @Test
    public void fallsBackToUnknown() {
        IconSubstitutes subs = new IconSubstitutes();
        subs.addServerIcon("u-cinema", "cinema", SHA_A);
        subs.addServerIcon("u-unknown", "Unknown", "cc");
        assertEquals("u-unknown", subs.substituteFor("Моё фото", SHA_OWN));
    }

    /** Сценарий спеки «На сервере нет даже unknown». */
    @Test
    public void noSubstituteWithoutServerIcons() {
        IconSubstitutes subs = new IconSubstitutes();
        assertNull(subs.substituteFor("Cinema", SHA_OWN));

        subs.addServerIcon("u-cinema", "cinema", SHA_A);
        assertNull(subs.substituteFor("Моё фото", SHA_OWN));
    }

    @Test
    public void firstAddedWinsOnDuplicateKeys() {
        IconSubstitutes subs = new IconSubstitutes();
        subs.addServerIcon("u-first", "Cinema", SHA_A);
        subs.addServerIcon("u-second", "cinema", SHA_A);
        assertEquals("u-first", subs.substituteFor("CINEMA", SHA_A));
        assertEquals("u-first", subs.substituteFor("CINEMA", SHA_OWN));
    }

    @Test
    public void byteOverloadHashesImage() {
        byte[] image = "abc".getBytes(StandardCharsets.US_ASCII);
        IconSubstitutes subs = new IconSubstitutes();
        subs.addServerIcon("u-abc", "Другое имя", IconSubstitutes.sha256Hex(image));
        assertEquals("u-abc", subs.substituteFor("Cinema", image));
    }

    @Test
    public void sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IconSubstitutes.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void sameUuidIgnoresDashesAndCase() {
        assertTrue(IconSubstitutes.sameUuid("0B9D6F3E-1111-4222-8333-444455556666",
                "0b9d6f3e111142228333444455556666"));
        assertFalse(IconSubstitutes.sameUuid("0b9d6f3e-1111-4222-8333-444455556666",
                "0b9d6f3e-1111-4222-8333-444455556667"));
        assertFalse(IconSubstitutes.sameUuid(null, "x"));
    }
}
