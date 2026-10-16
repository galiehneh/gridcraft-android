package com.galiehneh.gridcraft;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class RecentFilesTest {
    private static RecentFiles.Entry entry(String n) {
        return new RecentFiles.Entry("content://x/" + n, n);
    }

    @Test
    public void withEntry_putsNewestFirstAndDedupes() {
        List<RecentFiles.Entry> list = Arrays.asList(entry("a"), entry("b"), entry("c"));
        List<RecentFiles.Entry> out = RecentFiles.withEntry(list, entry("b"));
        assertEquals(Arrays.asList(entry("b"), entry("a"), entry("c")), out);
    }

    @Test
    public void withEntry_capsAtMax() {
        List<RecentFiles.Entry> list = new ArrayList<>();
        for (int i = 0; i < RecentFiles.MAX; i++) list.add(entry("f" + i));
        List<RecentFiles.Entry> out = RecentFiles.withEntry(list, entry("new"));
        assertEquals(RecentFiles.MAX, out.size());
        assertEquals(entry("new"), out.get(0));
        assertEquals(entry("f" + (RecentFiles.MAX - 2)), out.get(RecentFiles.MAX - 1));
    }

    @Test
    public void retain_dropsEntriesWithoutPermission() {
        List<RecentFiles.Entry> list = Arrays.asList(entry("a"), entry("b"));
        List<RecentFiles.Entry> out = RecentFiles.retain(list, new HashSet<>(Arrays.asList("content://x/b")));
        assertEquals(Arrays.asList(entry("b")), out);
    }
}
