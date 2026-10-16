package com.galiehneh.gridcraft;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Hands an already rendered PDF file to the system print framework. */
final class PdfPrintAdapter extends PrintDocumentAdapter {
    private static final String TAG = "GridCraft";
    private final File pdf;
    private final String jobName;

    PdfPrintAdapter(File pdf, String jobName) {
        this.pdf = pdf;
        this.jobName = jobName;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        callback.onLayoutFinished(new PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build(), true);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        new Thread(() -> {
            try (FileInputStream in = new FileInputStream(pdf);
                 FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buf, 0, n);
                }
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (IOException e) {
                Log.e(TAG, "Print write failed", e);
                callback.onWriteFailed(e.getMessage());
            }
        }).start();
    }
}
