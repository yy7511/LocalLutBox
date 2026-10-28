package io.github.yy7511.locallutbox.core;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps the original EXIF block when re-encoding a processed photo.
 * The APP1 Exif segment is copied verbatim except for the Orientation tag,
 * which is reset to 1 because the pixels are already rotated on decode.
 */
public final class JpegExif {

    private static final int HEAD_BYTES = 4 * 1024 * 1024;

    private JpegExif() {
    }

    /** Copies the APP1 Exif payload (starting at "Exif\0\0") out of a JPEG source. */
    public static byte[] readExif(ContentResolver cr, Uri uri) {
        if (cr == null || uri == null) {
            return null;
        }
        byte[] head = null;
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while (total < HEAD_BYTES && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            head = out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return extract(head);
    }

    static byte[] extract(byte[] d) {
        if (d == null || d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 4 <= d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                i += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return null;
            }
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > d.length) {
                return null;
            }
            int payload = i + 4;
            if (marker == 0xE1 && len >= 8 && payload + 6 <= d.length
                    && d[payload] == 'E' && d[payload + 1] == 'x' && d[payload + 2] == 'i'
                    && d[payload + 3] == 'f' && d[payload + 4] == 0 && d[payload + 5] == 0) {
                byte[] out = new byte[len - 2];
                System.arraycopy(d, payload, out, 0, out.length);
                return out;
            }
            i += 2 + len;
        }
        return null;
    }

    /** Sets IFD0 Orientation (0x0112) to 1 when present. Returns the same array. */
    static byte[] normalizeOrientation(byte[] exif) {
        if (exif == null || exif.length < 14) {
            return exif;
        }
        int tiff = 6;
        boolean little;
        if (exif[tiff] == 'I' && exif[tiff + 1] == 'I') {
            little = true;
        } else if (exif[tiff] == 'M' && exif[tiff + 1] == 'M') {
            little = false;
        } else {
            return exif;
        }
        int ifd = tiff + u32(exif, tiff + 4, little);
        if (ifd + 2 > exif.length) {
            return exif;
        }
        int count = u16(exif, ifd, little);
        for (int k = 0; k < count; k++) {
            int entry = ifd + 2 + k * 12;
            if (entry + 12 > exif.length) {
                break;
            }
            if (u16(exif, entry, little) == 0x0112 && u16(exif, entry + 8, little) != 1) {
                put16(exif, entry + 8, 1, little);
                break;
            }
        }
        return exif;
    }

    /** Encodes the bitmap and prepends the EXIF segment right after SOI. */
    public static byte[] encode(Bitmap bmp, int quality, byte[] exif) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512 * 1024);
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) {
            return null;
        }
        byte[] jpeg = bos.toByteArray();
        if (exif == null || exif.length == 0 || exif.length > 65533) {
            return jpeg;
        }
        normalizeOrientation(exif);
        int len = exif.length + 2;
        byte[] out = new byte[jpeg.length + exif.length + 4];
        System.arraycopy(jpeg, 0, out, 0, 2);                       // SOI
        out[2] = (byte) 0xFF;
        out[3] = (byte) 0xE1;
        out[4] = (byte) ((len >> 8) & 0xFF);
        out[5] = (byte) (len & 0xFF);
        System.arraycopy(exif, 0, out, 6, exif.length);
        System.arraycopy(jpeg, 2, out, 6 + exif.length, jpeg.length - 2);
        return out;
    }

    public static void write(OutputStream os, Bitmap bmp, int quality, byte[] exif) throws IOException {
        byte[] bytes = encode(bmp, quality, exif);
        if (bytes == null) {
            throw new IOException("图片编码失败");
        }
        os.write(bytes);
        os.flush();
    }

    private static int u16(byte[] d, int off, boolean little) {
        if (off + 2 > d.length) {
            return 0;
        }
        int a = d[off] & 0xFF;
        int b = d[off + 1] & 0xFF;
        return little ? (a | (b << 8)) : ((a << 8) | b);
    }

    private static int u32(byte[] d, int off, boolean little) {
        if (off + 4 > d.length) {
            return 0;
        }
        int a = d[off] & 0xFF;
        int b = d[off + 1] & 0xFF;
        int c = d[off + 2] & 0xFF;
        int e = d[off + 3] & 0xFF;
        return little ? (a | (b << 8) | (c << 16) | (e << 24))
                : ((a << 24) | (b << 16) | (c << 8) | e);
    }

    private static void put16(byte[] d, int off, int value, boolean little) {
        if (off + 2 > d.length) {
            return;
        }
        if (little) {
            d[off] = (byte) (value & 0xFF);
            d[off + 1] = (byte) ((value >> 8) & 0xFF);
        } else {
            d[off] = (byte) ((value >> 8) & 0xFF);
            d[off + 1] = (byte) (value & 0xFF);
        }
    }
}
