package com.terraduo.companion;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** A small RGBA PNG writer (8 bits per channel, Sub filter), independent of the platform's image classes. */
final class Png {
    private Png() { }

    static void write(File file, int[] argb, int width, int height) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            write(out, argb, width, height);
        }
    }

    static void write(OutputStream out, int[] argb, int width, int height) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(13);
        DataOutputStream h = new DataOutputStream(header);
        h.writeInt(width);
        h.writeInt(height);
        h.write(new byte[]{8, 6, 0, 0, 0});
        chunk(data, "IHDR", header.toByteArray());

        ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(1024, width * height / 2));
        Deflater deflater = new Deflater(4);
        try (DeflaterOutputStream z = new DeflaterOutputStream(packed, deflater, 1 << 16)) {
            byte[] row = new byte[1 + width * 4];
            byte[] current = new byte[width * 4];
            for (int y = 0; y < height; y++) {
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    int p = argb[base + x];
                    int i = x * 4;
                    current[i] = (byte) (p >> 16);
                    current[i + 1] = (byte) (p >> 8);
                    current[i + 2] = (byte) p;
                    current[i + 3] = (byte) (p >>> 24);
                }
                row[0] = 1;
                for (int i = 0; i < width * 4; i++) row[1 + i] = (byte) (current[i] - (i >= 4 ? current[i - 4] : 0));
                z.write(row, 0, row.length);
            }
        } finally {
            deflater.end();
        }
        chunk(data, "IDAT", packed.toByteArray());
        chunk(data, "IEND", new byte[0]);
        data.flush();
    }

    private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
        out.writeInt(body.length);
        byte[] name = type.getBytes("US-ASCII");
        out.write(name);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.writeInt((int) crc.getValue());
    }
}
