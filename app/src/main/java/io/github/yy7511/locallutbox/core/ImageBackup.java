package io.github.yy7511.locallutbox.core;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * App-private backup of imported photos.
 *
 * <p>The SAF URI still points at the user's own file, which may be moved, renamed
 * or deleted at any time. Every imported photo is therefore copied byte-for-byte
 * into {@code filesDir/photos}, which is invisible to the user and survives such
 * changes. The backup is the primary source for decoding; the URI is only a fallback.</p>
 */
public final class ImageBackup {

    private static final long MIN_BYTES = 256;

    private ImageBackup() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** Existing backup file for an entry, or null when none has been made yet. */
    public static File fileOf(Store.ImageEntry e) {
        if (e == null || e.file == null || e.file.length() == 0) {
            return null;
        }
        return new File(e.file);
    }

    /** Returns the usable backup for an entry, or null when it is missing/unreadable. */
    public static File existing(Store.ImageEntry e) {
        File f = fileOf(e);
        return f != null && f.isFile() && f.length() >= MIN_BYTES ? f : null;
    }

    /**
     * Copies the entry's source URI into app-private storage when no backup exists.
     * Safe to call repeatedly; returns the backup file or null on failure.
     * Updates {@code e.file} on success (caller persists the entry list).
     */
    public static File ensure(Context c, Store.ImageEntry e) {
        if (e == null || e.uri == null || e.uri.length() == 0) {
            return null;
        }
        File have = existing(e);
        if (have != null) {
            return have;
        }
        File target = new File(dir(c), idOf(e) + extensionOf(e.name));
        File tmp = new File(dir(c), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(Uri.parse(e.uri));
            if (in == null) {
                return null;
            }
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            if (total < MIN_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            e.file = target.getAbsolutePath();
            return target;
        } catch (Exception | OutOfMemoryError ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    /** Removes the private copy when the photo is removed from the list. */
    public static void delete(Store.ImageEntry e) {
        File f = fileOf(e);
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        if (e != null) {
            e.file = "";
        }
    }

    private static String idOf(Store.ImageEntry e) {
        String name = e.name == null ? "" : e.name;
        return "p" + Integer.toHexString(e.uri.hashCode()) + "_" + Integer.toHexString(name.hashCode());
    }

    private static String extensionOf(String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) {
                    return "." + ext;
                }
            }
        }
        return ".img";
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
