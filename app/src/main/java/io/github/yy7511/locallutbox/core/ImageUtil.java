package io.github.yy7511.locallutbox.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Image decode / EXIF / SAF storage helpers. No external dependencies. */
public final class ImageUtil {

    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif"};
    public static final String ALBUM = "LocalLutBox";

    private ImageUtil() {
    }

    public static boolean isImageName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static String baseName(String name) {
        if (name == null) {
            return "image";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    public static String getDisplayName(ContentResolver cr, Uri uri) {
        if (uri == null) {
            return "image";
        }
        try {
            Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        String n = cur.getString(0);
                        if (n != null && n.length() > 0) {
                            return n;
                        }
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) {
            return "image";
        }
        int colon = seg.lastIndexOf(':');
        return colon >= 0 ? seg.substring(colon + 1) : seg;
    }

    public static String readText(ContentResolver cr, Uri uri, int maxBytes) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取文件");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("文件过大");
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static Bitmap decodeUri(ContentResolver cr, Uri uri, int maxDim) throws IOException {
        if (uri == null) {
            throw new IOException("无效的图片地址");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            throw new IOException("无法打开图片");
        }
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不支持的图片格式");
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        in = cr.openInputStream(uri);
        if (in == null) {
            throw new IOException("无法打开图片");
        }
        try {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            closeQuietly(in);
        }
        if (bmp == null) {
            throw new IOException("图片解码失败");
        }
        bmp = scaleIfNeeded(bmp, maxDim);
        int orientation = readOrientation(cr, uri);
        return applyOrientation(bmp, orientation);
    }

    public static Bitmap decodeFile(File file, int maxDim) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        FileInputStream in = new FileInputStream(file);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }
        if (bounds.outWidth <= 0) {
            throw new IOException("图片解码失败");
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = new FileInputStream(file);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            closeQuietly(in);
        }
        if (bmp == null) {
            throw new IOException("图片解码失败");
        }
        bmp = scaleIfNeeded(bmp, maxDim);
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            return applyOrientation(bmp, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL));
        } catch (IOException ignored) {
            return bmp;
        }
    }

    private static int sampleSize(int w, int h, int maxDim) {
        if (maxDim <= 0) {
            return 1;
        }
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= maxDim) {
            s *= 2;
        }
        return s;
    }

    private static Bitmap scaleIfNeeded(Bitmap bmp, int maxDim) {
        int max = Math.max(bmp.getWidth(), bmp.getHeight());
        if (maxDim <= 0 || max <= maxDim) {
            return bmp;
        }
        float s = maxDim / (float) max;
        int w = Math.max(1, Math.round(bmp.getWidth() * s));
        int h = Math.max(1, Math.round(bmp.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
        if (scaled != bmp) {
            bmp.recycle();
        }
        return scaled;
    }

    private static int readOrientation(ContentResolver cr, Uri uri) {
        try {
            InputStream in = cr.openInputStream(uri);
            if (in == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            try {
                ExifInterface exif = new ExifInterface(in);
                return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } finally {
                closeQuietly(in);
            }
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap bmp, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bmp;
        }
        try {
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) {
                bmp.recycle();
            }
            return out;
        } catch (OutOfMemoryError e) {
            return bmp;
        }
    }

    public static Uri saveToGallery(Context c, Bitmap bmp, String displayName) throws IOException {
        return saveToGallery(c, bmp, displayName, 100, null);
    }

    /**
     * @param quality    JPEG quality (100 = keep the most detail)
     * @param exifSource original file whose EXIF block is copied into the output
     */
    public static Uri saveToGallery(Context c, Bitmap bmp, String displayName, int quality, Uri exifSource)
            throws IOException {
        ContentResolver cr = c.getContentResolver();
        byte[] exif = JpegExif.readExif(cr, exifSource);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new IOException("无法在相册创建文件");
            }
            OutputStream os = cr.openOutputStream(uri);
            try {
                if (os == null) {
                    throw new IOException("无法写入相册");
                }
                JpegExif.write(os, bmp, quality, exif);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            } finally {
                closeQuietly(os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        }
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) {
            dir = c.getFilesDir();
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File f = new File(dir, displayName);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            JpegExif.write(fos, bmp, quality, exif);
        } finally {
            closeQuietly(fos);
        }
        return Uri.fromFile(f);
    }

    public static Uri saveToTree(ContentResolver cr, String treeUri, String displayName, Bitmap bmp)
            throws IOException {
        return saveToTree(cr, treeUri, displayName, bmp, 100, null);
    }

    public static Uri saveToTree(ContentResolver cr, String treeUri, String displayName, Bitmap bmp,
                                 int quality, Uri exifSource) throws IOException {
        Uri tree = Uri.parse(treeUri);
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        deleteChild(cr, tree, treeId, displayName);
        Uri doc = DocumentsContract.createDocument(cr, parent, "image/jpeg", displayName);
        if (doc == null) {
            throw new IOException("无法写入输出文件夹");
        }
        byte[] exif = JpegExif.readExif(cr, exifSource);
        OutputStream os = cr.openOutputStream(doc);
        try {
            if (os == null) {
                throw new IOException("图片编码失败");
            }
            JpegExif.write(os, bmp, quality, exif);
        } finally {
            closeQuietly(os);
        }
        return doc;
    }

    private static void deleteChild(ContentResolver cr, Uri tree, String treeId, String displayName) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
            Cursor cur = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    while (cur.moveToNext()) {
                        String name = cur.getString(1);
                        if (displayName.equals(name)) {
                            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                            DocumentsContract.deleteDocument(cr, doc);
                        }
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static final class Doc {
        public String id;
        public String name;
        public String mime;
        public long modified;
    }

    public static ArrayList<Doc> listDocs(ContentResolver cr, String treeUri) throws IOException {
        if (treeUri == null || treeUri.length() == 0) {
            throw new IOException("未选择文件夹");
        }
        ArrayList<Doc> out = new ArrayList<Doc>();
        Uri tree = Uri.parse(treeUri);
        String treeId;
        try {
            treeId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            throw new IOException("文件夹地址无效");
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        Cursor cur = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
        if (cur == null) {
            throw new IOException("无法读取文件夹（授权可能已失效）");
        }
        try {
            while (cur.moveToNext()) {
                Doc d = new Doc();
                d.id = cur.getString(0);
                d.name = cur.getString(1);
                d.mime = cur.getString(2);
                d.modified = cur.getLong(3);
                if (d.name != null && isImageName(d.name)) {
                    out.add(d);
                }
            }
        } finally {
            cur.close();
        }
        return out;
    }

    public static Uri docUri(String treeUri, String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId);
    }

    public static String treeLabel(String treeUri) {
        if (treeUri == null || treeUri.length() == 0) {
            return "未选择";
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.length() == 0 ? "根目录" : path;
        } catch (Exception e) {
            return "已选择";
        }
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
