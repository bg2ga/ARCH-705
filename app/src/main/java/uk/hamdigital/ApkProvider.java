package uk.hamdigital;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public final class ApkProvider extends ContentProvider {
    public static final String AUTHORITY_SUFFIX = ".files";
    public static final String UPDATE_FILE = "ic705-remote-update.apk";

    public static Uri uriFor(Context context) {
        return Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX + "/" + UPDATE_FILE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        Context context = getContext();
        if (context == null) return null;
        String last = uri.getLastPathSegment();
        if (!UPDATE_FILE.equals(last)) return null;
        return new File(context.getCacheDir(), UPDATE_FILE);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("APK not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
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

