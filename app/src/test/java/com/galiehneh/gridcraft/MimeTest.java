package com.galiehneh.gridcraft;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MimeTest {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    public void validExtension_acceptsShortAlnumLowercase() {
        assertTrue(Mime.isValidExtension("xlsx"));
        assertTrue(Mime.isValidExtension("7z"));
        assertTrue(Mime.isValidExtension("a"));
    }

    @Test
    public void validExtension_rejectsBadValues() {
        assertFalse(Mime.isValidExtension(null));
        assertFalse(Mime.isValidExtension(""));
        assertFalse(Mime.isValidExtension("toolong"));
        assertFalse(Mime.isValidExtension("XLSX"));
        assertFalse(Mime.isValidExtension("a/b"));
        assertFalse(Mime.isValidExtension("..")); 
    }

    @Test
    public void extensionOf_lowercasesAndValidates() {
        assertEquals("xlsx", Mime.extensionOf("Book.XLSX"));
        assertEquals("csv", Mime.extensionOf("abc/data.csv"));
        assertNull(Mime.extensionOf(".hidden"));
        assertNull(Mime.extensionOf("noext"));
        assertNull(Mime.extensionOf("trailing."));
        assertNull(Mime.extensionOf("x.sp ace"));
        assertNull(Mime.extensionOf("x.verylongext"));
        assertNull(Mime.extensionOf(null));
    }

    @Test
    public void extensionForMime_ignoresParamsAndCase() {
        assertEquals("csv", Mime.extensionForMime("Text/CSV; charset=utf-8"));
        assertEquals("xlsx", Mime.extensionForMime(XLSX));
        assertNull(Mime.extensionForMime("application/x-unknown"));
        assertNull(Mime.extensionForMime(null));
    }

    @Test
    public void sanitizeFileName_stripsUnsafeChars() {
        assertEquals("a_b.xlsx", Mime.sanitizeFileName("a/b.xlsx"));
        assertEquals("evil", Mime.sanitizeFileName("..evil"));
        assertEquals(Mime.DEFAULT_FILE_NAME, Mime.sanitizeFileName(null));
        assertEquals(Mime.DEFAULT_FILE_NAME, Mime.sanitizeFileName("..."));
        assertEquals(120, Mime.sanitizeFileName("x".repeat(300)).length());
    }

    @Test
    public void sanitizeForSave_keepsKnownTypes() {
        assertArrayEquals(new String[]{XLSX, "a.xlsx"}, Mime.sanitizeForSave(XLSX, "a.xlsx"));
        assertArrayEquals(new String[]{"text/csv", "a.csv"}, Mime.sanitizeForSave(null, "a.csv"));
    }

    @Test
    public void sanitizeForSave_unknownBecomesBin() {
        assertArrayEquals(new String[]{Mime.OCTET_STREAM, "run.bin"}, Mime.sanitizeForSave(null, "run.exe"));
        assertArrayEquals(new String[]{Mime.OCTET_STREAM, "a.bin"}, Mime.sanitizeForSave("text/html", "a.html"));
    }

    @Test
    public void sanitizeForSave_addsExtensionFromMime() {
        assertArrayEquals(new String[]{"application/pdf", "report.pdf"}, Mime.sanitizeForSave("application/pdf", "report"));
        assertArrayEquals(new String[]{XLSX, "Workbook.xlsx"}, Mime.sanitizeForSave(null, null));
    }

    @Test
    public void acceptToMimeTypes_mapsAndFallsBack() {
        assertArrayEquals(new String[]{"*/*"}, Mime.acceptToMimeTypes(null));
        assertArrayEquals(new String[]{"*/*"}, Mime.acceptToMimeTypes(new String[]{".weird"}));
        assertArrayEquals(new String[]{"text/csv", XLSX}, Mime.acceptToMimeTypes(new String[]{".csv", ".xlsx"}));
    }
}
