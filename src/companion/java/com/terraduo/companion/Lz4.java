package com.terraduo.companion;

import java.io.IOException;

/** LZ4 block decompression (the format Unity uses for the blocks of an asset bundle). */
final class Lz4 {
    private Lz4() { }

    static void decompress(byte[] src, int srcPos, int srcLen, byte[] dst, int dstPos, int dstLen) throws IOException {
        int s = srcPos;
        int sEnd = srcPos + srcLen;
        int d = dstPos;
        int dEnd = dstPos + dstLen;
        while (s < sEnd) {
            int token = src[s++] & 0xff;
            int literals = token >>> 4;
            if (literals == 15) {
                int extra;
                do {
                    extra = src[s++] & 0xff;
                    literals += extra;
                } while (extra == 255);
            }
            if (literals > 0) {
                if (d + literals > dEnd || s + literals > sEnd) throw new IOException("LZ4: literal run out of range");
                System.arraycopy(src, s, dst, d, literals);
                s += literals;
                d += literals;
            }
            if (s >= sEnd) break;
            int offset = (src[s] & 0xff) | ((src[s + 1] & 0xff) << 8);
            s += 2;
            int length = token & 15;
            if (length == 15) {
                int extra;
                do {
                    extra = src[s++] & 0xff;
                    length += extra;
                } while (extra == 255);
            }
            length += 4;
            int from = d - offset;
            if (offset == 0 || from < dstPos || d + length > dEnd) throw new IOException("LZ4: match out of range");
            if (offset >= length) {
                System.arraycopy(dst, from, dst, d, length);
                d += length;
            } else {
                for (int i = 0; i < length; i++) dst[d++] = dst[from + i];
            }
        }
        if (d != dEnd) throw new IOException("LZ4: produced " + (d - dstPos) + " of " + dstLen + " bytes");
    }
}
