package io.github.yy7511.locallutbox.core;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

/**
 * Best-effort color-space detection from the embedded ICC profile.
 * JPEG: APP2 ICC_PROFILE segments, PNG: iCCP / sRGB chunks.
 * Falls back to sRGB when nothing usable is found (same convention as browsers).
 */
public final class ColorProfile {

    private static final int HEAD_BYTES = 512 * 1024;

    private ColorProfile() {
    }

    public static int detect(ContentResolver cr, Uri uri) {
        byte[] head;
        try {
            head = readHead(cr, uri);
        } catch (Throwable t) {
            return ColorSpace.SRGB;
        }
        if (head == null || head.length < 16) {
            return ColorSpace.SRGB;
        }
        try {
            if (isPng(head)) {
                return fromPng(head);
            }
            return fromJpeg(head);
        } catch (Throwable t) {
            return ColorSpace.SRGB;
        }
    }

    private static byte[] readHead(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while (total < HEAD_BYTES && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    // ---- JPEG -------------------------------------------------------------

    private static int fromJpeg(byte[] d) {
        int i = 2;
        while (i + 4 <= d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2) {
                break;
            }
            int start = i + 4;
            int end = Math.min(d.length, i + 2 + len);
            if (marker == 0xE2 && end - start > 14 && hasIccHeader(d, start)) {
                // ICC payload: 12 tag bytes, 1 sequence number, 1 chunk count.
                int seg = start + 14;
                String desc = iccDescription(d, seg, end);
                int bySpace = fromIccSpace(d, seg);
                if (bySpace > 0) {
                    return bySpace;
                }
                int byName = fromName(desc);
                if (byName > 0) {
                    return byName;
                }
            }
            i += 2 + len;
        }
        return ColorSpace.SRGB;
    }

    private static boolean hasIccHeader(byte[] d, int at) {
        if (at + 12 > d.length) {
            return false;
        }
        return d[at] == 'I' && d[at + 1] == 'C' && d[at + 2] == 'C'
                && d[at + 3] == '_' && d[at + 7] == 'L' && d[at + 10] == 'F';
    }

    private static int fromIccSpace(byte[] d, int profile) {
        if (profile + 20 > d.length) {
            return -1;
        }
        // Header bytes 16..19 hold the data colour space signature.
        if (d[profile + 16] == 'G' && d[profile + 17] == 'R' && d[profile + 18] == 'A') {
            return ColorSpace.SRGB;
        }
        if (d[profile + 16] == 'C' && d[profile + 17] == 'M' && d[profile + 18] == 'Y') {
            return ColorSpace.SRGB;
        }
        return -1;
    }

    private static String iccDescription(byte[] d, int profile, int limit) {
        if (profile + 132 > limit) {
            return "";
        }
        int count = be32(d, profile + 128);
        if (count <= 0 || count > 512) {
            return "";
        }
        for (int k = 0; k < count; k++) {
            int entry = profile + 132 + k * 12;
            if (entry + 12 > limit) {
                break;
            }
            String sig = ascii(d, entry, 4);
            if (!"desc".equals(sig) && !"mluc".equals(sig)) {
                continue;
            }
            int off = profile + be32(d, entry + 4);
            int size = be32(d, entry + 8);
            if (off < 0 || off + 12 > limit || size <= 0) {
                continue;
            }
            String type = ascii(d, off, 4);
            if ("desc".equals(type)) {
                int n = be32(d, off + 8);
                if (n > 0 && off + 12 + n <= limit) {
                    return ascii(d, off + 12, Math.max(0, n - 1)).trim();
                }
            } else if ("mluc".equals(type)) {
                int recs = be32(d, off + 8);
                int recSize = be32(d, off + 12);
                if (recs > 0 && recSize >= 12 && off + 20 + recSize <= limit) {
                    int len = be32(d, off + 20);
                    int strOff = be32(d, off + 24);
                    int str = off + strOff;
                    if (len > 1 && str >= 0 && str + len <= limit) {
                        try {
                            return new String(d, str, len, "UTF-16BE").trim();
                        } catch (UnsupportedEncodingException ignored) {
                        }
                    }
                }
            }
        }
        return "";
    }

    // ---- PNG --------------------------------------------------------------

    private static int fromPng(byte[] d) {
        int i = 8;
        while (i + 8 <= d.length) {
            int len = be32(d, i);
            String type = ascii(d, i + 4, 4);
            int data = i + 8;
            if (len < 0 || data + len > d.length) {
                break;
            }
            if ("iCCP".equals(type)) {
                String profileName = "";
                for (int k = data; k < data + len && k < d.length; k++) {
                    if (d[k] == 0) {
                        profileName = ascii(d, data, k - data);
                        break;
                    }
                }
                int by = fromName(profileName);
                if (by > 0) {
                    return by;
                }
            } else if ("sRGB".equals(type)) {
                return ColorSpace.SRGB;
            } else if ("IDAT".equals(type) || "IEND".equals(type)) {
                break;
            }
            i = data + len + 4;
        }
        return ColorSpace.SRGB;
    }

    // ---- helpers ----------------------------------------------------------

    /** @return a space constant, or -1 when the name is unrecognised. */
    private static int fromName(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.toLowerCase(Locale.US);
        if (s.length() == 0) {
            return -1;
        }
        if (s.contains("adobe")) {
            return ColorSpace.ADOBE_RGB;
        }
        if (s.contains("prophoto") || s.contains("romm")) {
            return ColorSpace.PROPHOTO;
        }
        if (s.contains("display p3") || s.contains("p3 d65") || s.equals("p3")) {
            return ColorSpace.DISPLAY_P3;
        }
        if (s.contains("2020")) {
            return ColorSpace.REC2020;
        }
        if (s.contains("srgb") || s.contains("iec61966")) {
            return ColorSpace.SRGB;
        }
        return -1;
    }

    private static String ascii(byte[] d, int off, int len) {
        if (off < 0 || len <= 0 || off + len > d.length) {
            return "";
        }
        return new String(d, off, len, java.nio.charset.Charset.forName("US-ASCII"));
    }

    private static int be32(byte[] d, int off) {
        if (off + 4 > d.length) {
            return 0;
        }
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }
}
