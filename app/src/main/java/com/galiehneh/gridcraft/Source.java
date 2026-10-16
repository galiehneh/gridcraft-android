package com.galiehneh.gridcraft;

import android.net.Uri;

/** Content URI the open document was imported from; saves of the same type may overwrite it when writable. */
final class Source {
    final Uri uri;
    final String ext;
    final String docId;
    final boolean writable;

    Source(Uri uri, String ext, String docId, boolean writable) {
        this.uri = uri;
        this.ext = ext;
        this.docId = docId;
        this.writable = writable;
    }
}
