package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the update check: release tags against this build, the daily rule, digest and notes. */
public class UpdateCheckTest {

    @Test
    public void aHigherReleaseIsNewer() {
        assertTrue(UpdateCheck.isNewer("v4.8", "4.7"));
        assertTrue(UpdateCheck.isNewer("v4.7.1", "4.7"));
        assertTrue(UpdateCheck.isNewer("v5.0", "4.12"));
    }

    @Test
    public void numbersCompareAsNumbersNotText() {
        assertTrue(UpdateCheck.isNewer("v4.10", "4.9"));
        assertFalse(UpdateCheck.isNewer("v4.9", "4.10"));
    }

    @Test
    public void theSameOrAnOlderReleaseIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("v4.7", "4.7"));
        assertFalse(UpdateCheck.isNewer("v4.7.0", "4.7"));
        assertFalse(UpdateCheck.isNewer("v4.6.2", "4.7"));
    }

    @Test
    public void anUnreadableTagIsNeverNewer() {
        assertFalse(UpdateCheck.isNewer("nightly", "4.7"));
        assertFalse(UpdateCheck.isNewer("v4.8-beta", "4.7"));
        assertFalse(UpdateCheck.isNewer("", "4.7"));
        assertFalse(UpdateCheck.isNewer(null, "4.7"));
        assertFalse(UpdateCheck.isNewer("v4.8", null));
    }

    @Test
    public void checksOnceADayAndAgainWhenTheClockWentBack() {
        long day = UpdateCheck.INTERVAL_MS;
        assertTrue(UpdateCheck.due(0, 1_000));
        assertFalse(UpdateCheck.due(10 * day, 10 * day + day - 1));
        assertTrue(UpdateCheck.due(10 * day, 11 * day));
        assertTrue(UpdateCheck.due(10 * day, 9 * day));
    }

    @Test
    public void readsGithubSha256Digests() {
        String hex = "e5255b1ad21d6a6996377e9fe07590c5246c2fa90f6187c379bfed6677e60fb4";
        assertEquals(hex, UpdateCheck.sha256Hex("sha256:" + hex));
        assertEquals(hex, UpdateCheck.sha256Hex("sha256:" + hex.toUpperCase()));
        assertNull(UpdateCheck.sha256Hex("sha1:" + hex));
        assertNull(UpdateCheck.sha256Hex("sha256:abc"));
        assertNull(UpdateCheck.sha256Hex(null));
    }

    @Test
    public void notesDropMarkdownAndStayShort() {
        assertEquals("Что нового\nНовый экран «Образы»\nСам находит обновления",
                UpdateCheck.notes("## Что нового\n\n- Новый экран **«Образы»**\n- Сам находит `обновления`"));
        String longText = new String(new char[500]).replace('\0', 'а');
        String cut = UpdateCheck.notes(longText);
        assertEquals(UpdateCheck.NOTES_MAX, cut.length());
        assertTrue(cut.endsWith("…"));
        String sentence = new String(new char[UpdateCheck.NOTES_MAX - 2]).replace('\0', 'б') + ". Дальше";
        assertTrue(UpdateCheck.notes(sentence).endsWith("б…"));
        assertEquals("", UpdateCheck.notes(null));
    }
}
