package com.terraduo.companion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One serialized assets file of the game (Unity 2021.3, serialized format 22, no type tree): the object table, plus readers for the
 * few object kinds the sprite extraction needs (Texture2D, TextAsset, ResourceManager).
 */
final class UnityAssets {
    static final int TEXTURE_2D = 28;
    static final int TEXT_ASSET = 49;
    static final int RESOURCE_MANAGER = 147;

    static final class Obj {
        final long pathId;
        final long start;      // absolute position in the bundle stream
        final int size;
        final int classId;

        Obj(long pathId, long start, int size, int classId) {
            this.pathId = pathId;
            this.start = start;
            this.size = size;
            this.classId = classId;
        }
    }

    /** A Texture2D: where its pixels are, not the pixels themselves. */
    static final class Tex {
        String name;
        int width;
        int height;
        int format;       // 1 Alpha8, 3 RGB24, 4 RGBA32, 34 ETC ...
        long dataOffset;  // absolute position in the bundle stream
        int dataSize;
    }

    /** Small cursor over a byte array. */
    static final class Bin {
        final byte[] data;
        int pos;
        boolean big;

        Bin(byte[] data, boolean big) {
            this.data = data;
            this.big = big;
        }

        int u8() {
            return data[pos++] & 0xff;
        }

        int i16() {
            int a = data[pos] & 0xff;
            int b = data[pos + 1] & 0xff;
            pos += 2;
            return (short) (big ? (a << 8) | b : (b << 8) | a);
        }

        int i32() {
            int v = big
                ? ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16) | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff)
                : ((data[pos + 3] & 0xff) << 24) | ((data[pos + 2] & 0xff) << 16) | ((data[pos + 1] & 0xff) << 8) | (data[pos] & 0xff);
            pos += 4;
            return v;
        }

        long i64() {
            long a = i32() & 0xffffffffL;
            long b = i32() & 0xffffffffL;
            return big ? (a << 32) | b : (b << 32) | a;
        }

        void align() {
            pos = (pos + 3) & ~3;
        }

        String cstring() {
            int end = pos;
            while (data[end] != 0) end++;
            String s = new String(data, pos, end - pos, java.nio.charset.Charset.forName("UTF-8"));
            pos = end + 1;
            return s;
        }

        /** A serialized string: length, bytes, padding to 4. */
        String string() {
            int n = i32();
            String s = new String(data, pos, n, java.nio.charset.Charset.forName("UTF-8"));
            pos += n;
            align();
            return s;
        }
    }

    final UnityBundle bundle;
    final String name;
    final long nodeOffset;
    final List<Obj> objects = new ArrayList<>();
    final Map<Long, Obj> byId = new HashMap<>();
    final List<String> externals = new ArrayList<>();

    private UnityAssets(UnityBundle bundle, String name) throws IOException {
        this.bundle = bundle;
        this.name = name;
        UnityBundle.Node node = bundle.node(name);
        this.nodeOffset = node.offset;
        byte[] head = bundle.read(node.offset, 48);
        Bin r = new Bin(head, true);
        r.i32();
        r.i32();
        int version = r.i32();
        r.i32();
        if (version < 22) throw new IOException("Unsupported serialized file version " + version);
        r.u8();
        r.pos += 3;
        int metadataSize = r.i32();
        r.i64();
        long dataOffset = r.i64();
        boolean big = (head[16] & 0xff) != 0;
        byte[] meta = bundle.read(node.offset + 48, metadataSize);
        r = new Bin(meta, big);
        r.cstring();   // Unity version
        r.i32();       // target platform
        if (r.u8() != 0) throw new IOException("Serialized file carries a type tree (not expected in a player build)");
        int types = r.i32();
        int[] classes = new int[types];
        for (int i = 0; i < types; i++) {
            int classId = r.i32();
            r.u8();    // stripped
            r.i16();   // script type index
            if (classId == 114) r.pos += 16;
            r.pos += 16;
            classes[i] = classId;
        }
        int count = r.i32();
        for (int i = 0; i < count; i++) {
            r.align();
            long pathId = r.i64();
            long start = r.i64() + dataOffset;
            int size = r.i32();
            int type = r.i32();
            Obj obj = new Obj(pathId, node.offset + start, size, classes[type]);
            objects.add(obj);
            byId.put(pathId, obj);
        }
        int scripts = r.i32();
        for (int i = 0; i < scripts; i++) {
            r.i32();
            r.align();
            r.i64();
        }
        int externalCount = r.i32();
        for (int i = 0; i < externalCount; i++) {
            r.cstring();
            r.pos += 16;
            r.i32();
            String path = r.cstring();
            externals.add(path.substring(path.lastIndexOf('/') + 1));
        }
    }

    static UnityAssets open(UnityBundle bundle, String name) throws IOException {
        return new UnityAssets(bundle, name);
    }

    byte[] bytes(Obj obj) throws IOException {
        return bundle.read(obj.start, obj.size);
    }

    /** Reads the Texture2D header of an object (Unity 2021.3 layout). */
    Tex texture(Obj obj) throws IOException {
        byte[] data = bytes(obj);
        Bin r = new Bin(data, false);
        Tex t = new Tex();
        t.name = r.string();
        r.i32();                      // forced fallback format
        r.u8();
        r.u8();                       // downscale fallback, alpha channel optional
        r.align();
        t.width = r.i32();
        t.height = r.i32();
        int completeSize = r.i32();
        r.i32();                      // mips stripped
        t.format = r.i32();
        r.i32();                      // mip count
        r.pos += 4;                   // readable, pre-processed, ignore master limit, streaming mipmaps
        r.i32();
        r.i32();
        r.i32();                      // streaming priority, image count, dimension
        r.pos += 8 + 4 + 12;          // filter, aniso, mip bias, wrap u / v / w
        r.i32();
        r.i32();                      // lightmap format, colour space
        int blob = r.i32();
        r.pos += blob;
        r.align();
        int inline = r.i32();
        int inlineAt = r.pos;
        r.pos += inline;
        r.align();
        long streamOffset = r.i64();
        int streamSize = r.i32();
        String path = r.string();
        if (inline > 0) {
            t.dataOffset = obj.start + inlineAt;
            t.dataSize = inline;
        } else {
            UnityBundle.Node node = bundle.node(path.substring(path.lastIndexOf('/') + 1));
            t.dataOffset = node.offset + streamOffset;
            t.dataSize = streamSize;
        }
        if (t.dataSize <= 0 && completeSize > 0) t.dataSize = completeSize;
        return t;
    }

    /** The bytes of a TextAsset. */
    byte[] textAsset(Obj obj) throws IOException {
        byte[] data = bytes(obj);
        Bin r = new Bin(data, false);
        r.string();
        int n = r.i32();
        byte[] out = new byte[n];
        System.arraycopy(data, r.pos, out, 0, n);
        return out;
    }

    /** The ResourceManager's name -> object table (Resources.Load paths) restricted to objects of the named file. */
    Map<String, Long> resourceContainer(String targetFile) throws IOException {
        Map<String, Long> result = new HashMap<>();
        for (Obj obj : objects) {
            if (obj.classId != RESOURCE_MANAGER) continue;
            Bin r = new Bin(bytes(obj), false);
            int n = r.i32();
            for (int i = 0; i < n; i++) {
                String key = r.string();
                int fileId = r.i32();
                long pathId = r.i64();
                String file = fileId == 0 ? name : fileId - 1 < externals.size() ? externals.get(fileId - 1) : "";
                if (file.equals(targetFile)) result.put(key, pathId);
            }
        }
        return result;
    }
}
