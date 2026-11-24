package com.gridgaming.lounge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Stand-in for the phone's MediaStore "Downloads" provider (Robolectric has none). Stores files in a temp folder. */
public class FakeMediaStore extends ContentProvider {
    static final List<ContentValues> inserted = new ArrayList<>();
    static File dir;
    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri uri, ContentValues v) {
        inserted.add(new ContentValues(v));
        return Uri.parse(uri.toString() + "/" + inserted.size());
    }
    static File fileFor(Uri uri) { return new File(dir, uri.getLastPathSegment() + ".bin"); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) {
        try {
            return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
