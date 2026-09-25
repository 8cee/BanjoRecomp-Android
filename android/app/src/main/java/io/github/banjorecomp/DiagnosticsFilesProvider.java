package io.github.banjorecomp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public final class DiagnosticsFilesProvider extends ContentProvider {
    public static Uri shareUri(Context context, String fileName) {
        return Uri.parse("content://" + context.getPackageName() + ".diagnostics/" + Uri.encode(fileName));
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("Read-only provider");
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("Log not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
        File file = resolve(uri);
        if (file != null) cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    private File resolve(Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
            File dir = DiagnosticsLogger.getDiagnosticsDir(getContext()).getCanonicalFile();
            File file = new File(dir, name).getCanonicalFile();
            if (!file.getPath().startsWith(dir.getPath() + File.separator)) return null;
            return file.isFile() ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
