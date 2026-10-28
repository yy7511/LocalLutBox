package io.github.yy7511.locallutbox.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * On-disk storage for imported LUTs. Payloads are AES-GCM encrypted with a key
 * derived from the signing certificate, so a cached LUT cannot be lifted out of
 * the app sandbox and read by another tool. Legacy plain "LUTB" files are still
 * readable (they are transparently re-encrypted on the next load).
 */
public final class LutCodec {

    private static final int MAGIC_ENC = 0x4C555458; // "LUTX"
    private static final int MAGIC_PLAIN = 0x4C555442; // "LUTB"
    private static final int VERSION = 2;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] KEY;
    private static final SecureRandom RNG = new SecureRandom();

    static {
        byte[] material = null;
        try {
            Context c = AppRef.get();
            if (c != null) {
                PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                Signature[] sigs = pi.signatures;
                if (sigs != null && sigs.length > 0) {
                    material = sigs[0].toByteArray();
                }
            }
        } catch (Throwable ignored) {
        }
        if (material == null) {
            material = new byte[]{0x4C, 0x6F, 0x63, 0x61, 0x6C};
        }
        byte[] key = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(material);
            md.update("io.github.yy7511.locallutbox/lut-v2".getBytes("UTF-8"));
            key = md.digest();
        } catch (Exception ignored) {
        }
        KEY = key == null ? new byte[32] : key;
    }

    private LutCodec() {
    }

    public static void write(Lut lut, File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512 * 1024);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(VERSION);
        out.writeInt(lut.kind);
        out.writeInt(lut.size);
        out.writeFloat(lut.domainMin);
        out.writeFloat(lut.domainMax);
        out.writeInt(ColorSpace.clampSpace(lut.space));
        out.writeInt(lut.data.length);
        for (int i = 0; i < lut.data.length; i++) {
            out.writeFloat(lut.data[i]);
        }
        out.flush();
        byte[] plain = bos.toByteArray();

        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        byte[] sealed;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            sealed = c.doFinal(plain);
        } catch (Exception e) {
            throw new IOException("LUT 加密失败");
        }

        DataOutputStream fo = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(file)));
        try {
            fo.writeInt(MAGIC_ENC);
            fo.writeInt(iv.length);
            fo.write(iv);
            fo.write(sealed);
        } finally {
            fo.close();
        }
    }

    public static Lut read(Context ctx, File file) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
        byte[] payload;
        try {
            int magic = in.readInt();
            if (magic == MAGIC_ENC) {
                int ivLen = in.readInt();
                if (ivLen != IV_LEN) {
                    throw new IOException("无效的 LUT 缓存文件");
                }
                byte[] iv = new byte[ivLen];
                in.readFully(iv);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(TAG_BITS, iv));
                    payload = c.doFinal(bos.toByteArray());
                } catch (Exception e) {
                    throw new IOException("LUT 缓存解密失败");
                }
            } else if (magic == MAGIC_PLAIN) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                payload = bos.toByteArray();
            } else {
                throw new IOException("无效的 LUT 缓存文件");
            }
        } finally {
            in.close();
        }

        DataInputStream in2 = new DataInputStream(new ByteArrayInputStream(payload));
        int version = in2.readInt();
        if (version != 1 && version != VERSION) {
            throw new IOException("不支持的 LUT 缓存版本");
        }
        Lut lut = new Lut();
        lut.kind = in2.readInt();
        lut.size = in2.readInt();
        lut.domainMin = in2.readFloat();
        lut.domainMax = in2.readFloat();
        lut.space = version >= 2 ? ColorSpace.clampSpace(in2.readInt()) : ColorSpace.SRGB;
        int count = in2.readInt();
        if (count <= 0 || count > 30000000) {
            throw new IOException("LUT 数据大小异常");
        }
        float[] data = new float[count];
        for (int i = 0; i < count; i++) {
            data[i] = in2.readFloat();
        }
        lut.data = data;
        return lut;
    }
}
