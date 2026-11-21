package com.terraduo.companion;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Random access to the content of a Unity "UnityFS" asset bundle (format 7 / 8, LZ4 or stored blocks), such as the game's
 * data.unity3d. Blocks are decompressed on demand, so a 750 MB bundle never has to fit in memory or on disk.
 */
final class UnityBundle implements Closeable {
    /** One file inside the bundle (a serialized assets file, or a raw streamed resource). */
    static final class Node {
        final String name;
        final long offset;
        final long size;

        Node(String name, long offset, long size) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    private static final int CACHE = 8;

    private final RandomAccessFile file;
    private final long[] blockStart;
    private final long[] blockPos;
    private final int[] blockUncompressed;
    private final int[] blockCompressed;
    private final int[] blockFlags;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final int[] cachedIndex = new int[CACHE];
    private final byte[][] cachedData = new byte[CACHE][];
    private int cacheNext;
    private byte[] scratch = new byte[0];

    UnityBundle(java.io.File path) throws IOException {
        file = new RandomAccessFile(path, "r");
        byte[] head = new byte[(int) Math.min(512, file.length())];
        file.readFully(head);
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        int pos = 0;
        pos = skipString(head, pos);
        String signature = new String(head, 0, pos - 1, "ISO-8859-1");
        if (!signature.equals("UnityFS")) throw new IOException("Not a UnityFS bundle: " + signature);
        buffer.position(pos);
        int format = buffer.getInt();
        pos = skipString(head, buffer.position());
        pos = skipString(head, pos);
        buffer.position(pos);
        buffer.getLong();   // total size
        int infoCompressed = buffer.getInt();
        int infoUncompressed = buffer.getInt();
        int flags = buffer.getInt();
        long cursor = buffer.position();
        if (format >= 7) cursor = (cursor + 15) & ~15L;
        byte[] info = new byte[infoCompressed];
        if ((flags & 0x80) != 0) {
            file.seek(file.length() - infoCompressed);
        } else {
            file.seek(cursor);
        }
        file.readFully(info);
        if ((flags & 0x80) == 0) cursor += infoCompressed;
        byte[] plain = inflate(info, infoUncompressed, flags & 0x3f);
        if ((flags & 0x200) != 0) cursor = (cursor + 15) & ~15L;

        ByteBuffer meta = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
        meta.position(16);   // hash of the uncompressed data
        int blocks = meta.getInt();
        blockStart = new long[blocks];
        blockPos = new long[blocks];
        blockUncompressed = new int[blocks];
        blockCompressed = new int[blocks];
        blockFlags = new int[blocks];
        long uncompressed = 0;
        long filePos = cursor;
        for (int i = 0; i < blocks; i++) {
            blockUncompressed[i] = meta.getInt();
            blockCompressed[i] = meta.getInt();
            blockFlags[i] = meta.getShort() & 0xffff;
            blockStart[i] = uncompressed;
            blockPos[i] = filePos;
            uncompressed += blockUncompressed[i];
            filePos += blockCompressed[i];
        }
        int count = meta.getInt();
        for (int i = 0; i < count; i++) {
            long offset = meta.getLong();
            long size = meta.getLong();
            meta.getInt();   // flags
            int start = meta.position();
            int end = start;
            while (plain[end] != 0) end++;
            String name = new String(plain, start, end - start, "UTF-8");
            meta.position(end + 1);
            nodes.put(name, new Node(name, offset, size));
        }
        for (int i = 0; i < CACHE; i++) cachedIndex[i] = -1;
    }

    private static int skipString(byte[] data, int pos) {
        while (data[pos] != 0) pos++;
        return pos + 1;
    }

    private static byte[] inflate(byte[] source, int size, int type) throws IOException {
        if (type == 0) return source;
        if (type == 2 || type == 3) {
            byte[] out = new byte[size];
            Lz4.decompress(source, 0, source.length, out, 0, size);
            return out;
        }
        throw new IOException("Unsupported bundle compression " + type + " (LZMA bundles are not handled)");
    }

    Node node(String name) throws IOException {
        Node node = nodes.get(name);
        if (node == null) throw new IOException("Missing in the game data: " + name);
        return node;
    }

    boolean has(String name) {
        return nodes.containsKey(name);
    }

    private int blockFor(long offset) {
        int low = 0;
        int high = blockStart.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (blockStart[mid] <= offset) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    private byte[] block(int index) throws IOException {
        for (int i = 0; i < CACHE; i++) if (cachedIndex[i] == index) return cachedData[i];
        int compressed = blockCompressed[index];
        if (scratch.length < compressed) scratch = new byte[compressed];
        file.seek(blockPos[index]);
        file.readFully(scratch, 0, compressed);
        byte[] out;
        int type = blockFlags[index] & 0x3f;
        if (type == 0) {
            out = new byte[blockUncompressed[index]];
            System.arraycopy(scratch, 0, out, 0, out.length);
        } else if (type == 2 || type == 3) {
            out = new byte[blockUncompressed[index]];
            Lz4.decompress(scratch, 0, compressed, out, 0, out.length);
        } else {
            throw new IOException("Unsupported block compression " + type);
        }
        int slot = cacheNext;
        cacheNext = (cacheNext + 1) % CACHE;
        cachedIndex[slot] = index;
        cachedData[slot] = out;
        return out;
    }

    /** Copies {@code length} bytes of the uncompressed bundle stream starting at {@code offset}. */
    void read(long offset, byte[] dst, int dstOffset, int length) throws IOException {
        while (length > 0) {
            int index = blockFor(offset);
            byte[] data = block(index);
            int inside = (int) (offset - blockStart[index]);
            int n = Math.min(length, data.length - inside);
            if (n <= 0) throw new IOException("Read past the end of the bundle");
            System.arraycopy(data, inside, dst, dstOffset, n);
            offset += n;
            dstOffset += n;
            length -= n;
        }
    }

    byte[] read(long offset, int length) throws IOException {
        byte[] out = new byte[length];
        read(offset, out, 0, length);
        return out;
    }

    @Override public void close() throws IOException {
        file.close();
    }
}
