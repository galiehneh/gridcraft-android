package com.galiehneh.gridcraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileImporterTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String PKG = "com.galiehneh.gridcraft";

    @Test
    public void docIdFromPath_extractsId() {
        assertEquals(ID, FileImporter.docIdFromPath(ID + "/Book.xlsx"));
        assertEquals(ID, FileImporter.docIdFromPath(ID));
    }

    @Test
    public void docIdFromPath_rejectsMalformed() {
        assertNull(FileImporter.docIdFromPath(null));
        assertNull(FileImporter.docIdFromPath(""));
        assertNull(FileImporter.docIdFromPath("../etc/passwd"));
        assertNull(FileImporter.docIdFromPath(ID.toUpperCase() + "/a.xlsx"));
        assertNull(FileImporter.docIdFromPath(ID + "x/a.xlsx"));
    }

    @Test
    public void isAllowedSource_acceptsForeignContent() {
        assertTrue(FileImporter.isAllowedSource("content", "com.android.providers.media", PKG));
        assertTrue(FileImporter.isAllowedSource("CONTENT", "x", PKG));
    }

    @Test
    public void isAllowedSource_rejectsFileOwnProviderAndOthers() {
        assertFalse(FileImporter.isAllowedSource("file", null, PKG));
        assertFalse(FileImporter.isAllowedSource("http", "example.com", PKG));
        assertFalse(FileImporter.isAllowedSource(null, null, PKG));
        assertFalse(FileImporter.isAllowedSource("content", PKG + ".fileprovider", PKG));
    }

    @Test
    public void withInferredExtension_keepsExistingExtension() {
        assertEquals("a.csv", FileImporter.withInferredExtension("a.csv", "application/pdf"));
    }

    @Test
    public void withInferredExtension_appendsFromMime() {
        assertEquals("data.csv", FileImporter.withInferredExtension("data", "text/csv"));
        assertEquals("data", FileImporter.withInferredExtension("data", "application/x-unknown"));
        assertEquals("data", FileImporter.withInferredExtension("data", null));
    }

    @Test
    public void fileTooLargeException_mentionsLimit() {
        String msg = new FileImporter.FileTooLargeException(FileImporter.MAX_IMPORT_BYTES).getMessage();
        assertTrue(msg.contains("too large"));
        assertTrue(msg.contains("500 MB"));
    }
}
