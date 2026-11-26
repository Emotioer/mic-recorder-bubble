package com.instinct.micfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class AudioProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File baseDir() {
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = getContext().getFilesDir();
        return dir;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        File dir = baseDir();
        File f = new File(dir, uri.getLastPathSegment());
        if (f.getParentFile() == null || !f.getParentFile().equals(dir)) {
            throw new FileNotFoundException("bad path");
        }
        if (!f.exists()) throw new FileNotFoundException("missing");
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = resolve(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (FileNotFoundException ignore) { }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/mp4";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
