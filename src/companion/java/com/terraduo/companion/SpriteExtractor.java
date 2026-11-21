package com.terraduo.companion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decodes the game's own sprite atlases, fonts and background layers from the user's installed copy of Terraria, on the device.
 * The output (PNG pages in {@code outDir}) is the same as tools/extract_assets.py and tools/extract_backgrounds.py produce on a PC:
 * the game's textures are indexed images (an index plane, an optional alpha plane and a palette with one row per 256 x 256 block).
 * Nothing is bundled with the app and nothing leaves the device; the game's APK is only read.
 */
final class SpriteExtractor {
    /** Bumped when the decoding changes, so older results are redone (see atlas-version.txt). */
    static final int VERSION = 2;
    static final String BUNDLE_ENTRY = "assets/bin/Data/data.unity3d";
    private static final String[] DATABASES = {"Item", "UI", "Misc", "Extra", "Buff", "Cursor", "MiniMap", "MapBG", "Moon", "Star",
        "Sun", "Gem", "Coin", "NPC"};
    private static final String HIGH_RES = "content/packedimages/";

    interface Listener {
        void progress(int done, int total, String what);

        boolean cancelled();
    }

    private final UnityBundle bundle;
    private final UnityAssets resources;
    private final UnityAssets managers;
    private final File outDir;
    private final Listener listener;
    private final Map<String, List<UnityAssets.Tex>> textures = new HashMap<>();
    private int done;
    private int total;

    private SpriteExtractor(UnityBundle bundle, File outDir, Listener listener) throws IOException {
        this.bundle = bundle;
        this.outDir = outDir;
        this.listener = listener;
        this.resources = UnityAssets.open(bundle, "resources.assets");
        this.managers = UnityAssets.open(bundle, "globalgamemanagers");
    }

    /** The APK (the base or one of its splits) that holds the game's data bundle, or null. */
    static File findBundleApk(List<File> apks) {
        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                if (zip.getEntry(BUNDLE_ENTRY) != null) return apk;
            } catch (IOException ignored) {
                // not readable: try the next one
            }
        }
        return null;
    }

    /** Runs the whole extraction. {@code tmpDir} receives a temporary copy of the game's data bundle (about 70 MB). */
    static void run(List<File> apks, File outDir, File tmpDir, Listener listener) throws IOException {
        File apk = findBundleApk(apks);
        if (apk == null) throw new IOException("The game's data bundle was not found in the installed APK");
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        File copy = new File(tmpDir, "data.unity3d");
        listener.progress(0, 1, "Reading the game data");
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(BUNDLE_ENTRY);
            try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(copy)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        }
        try (UnityBundle bundle = new UnityBundle(copy)) {
            new SpriteExtractor(bundle, outDir, listener).extract();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    private void extract() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        listener.progress(0, 1, "Indexing textures");
        for (UnityAssets.Obj obj : resources.objects) {
            if (obj.classId != UnityAssets.TEXTURE_2D) continue;
            UnityAssets.Tex tex = resources.texture(obj);
            List<UnityAssets.Tex> list = textures.get(tex.name);
            if (list == null) {
                list = new ArrayList<>();
                textures.put(tex.name, list);
            }
            list.add(tex);
        }
        // plan: every atlas page, the fonts and the background layers
        List<Object[]> pages = new ArrayList<>();
        for (String db : DATABASES) {
            Pattern pattern = Pattern.compile("^" + db + "(\\d+)$");
            List<String> names = new ArrayList<>();
            for (String name : textures.keySet()) if (pattern.matcher(name).matches()) names.add(name);
            Collections.sort(names, (a, b) -> a.length() != b.length() ? a.length() - b.length() : a.compareTo(b));
            for (String name : names) {
                int number = Integer.parseInt(name.substring(db.length()));
                for (UnityAssets.Tex base : textures.get(name)) pages.add(new Object[]{db, number, name, base});
            }
        }
        Map<String, Long> container = managers.resourceContainer("resources.assets");
        Long atlasId = container.get(HIGH_RES + "background");
        BackgroundAtlas atlas = atlasId == null ? null : new BackgroundAtlas(resources.textAsset(resources.byId.get(atlasId)));
        total = pages.size() + 3 + (atlas == null ? 0 : atlas.jobs.size());
        for (Object[] page : pages) {
            if (listener.cancelled()) return;
            extractPage((String) page[0], (Integer) page[1], (String) page[2], (UnityAssets.Tex) page[3]);
            step("Sprites " + page[0] + page[1]);
        }
        extractFonts();
        if (atlas != null) extractBackgrounds(atlas, container);
        File marker = new File(outDir, "atlas-version.txt");
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(Integer.toString(VERSION).getBytes("US-ASCII"));
        }
        listener.progress(total, total, "Done");
    }

    private void step(String what) {
        done++;
        listener.progress(Math.min(done, total), total, what);
    }

    // ---------------------------------------------------------------- pixels

    /** An Alpha8 texture as a top-down plane (Unity stores rows bottom-up). */
    private byte[] plane(UnityAssets.Tex tex) throws IOException {
        if (tex.format != 1) throw new IOException("Texture " + tex.name + " has format " + tex.format + ", expected Alpha8");
        int w = tex.width;
        int h = tex.height;
        byte[] raw = bundle.read(tex.dataOffset, w * h);
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(raw, (h - 1 - y) * w, out, y * w, w);
        return out;
    }

    /** An RGBA32 texture as top-down ARGB. */
    private int[] argb(UnityAssets.Tex tex) throws IOException {
        if (tex.format != 4) throw new IOException("Texture " + tex.name + " has format " + tex.format + ", expected RGBA32");
        int w = tex.width;
        int h = tex.height;
        byte[] raw = bundle.read(tex.dataOffset, w * h * 4);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int i = src + x * 4;
                out[y * w + x] = ((raw[i + 3] & 0xff) << 24) | ((raw[i] & 0xff) << 16) | ((raw[i + 1] & 0xff) << 8) | (raw[i + 2] & 0xff);
            }
        }
        return out;
    }

    /** Palette rows (top-down), colours only. */
    private int[][] palette(UnityAssets.Tex tex) throws IOException {
        int[] px = argb(tex);
        int rows = tex.height;
        int[][] out = new int[rows][256];
        for (int r = 0; r < rows; r++) for (int x = 0; x < 256; x++) out[r][x] = 0xff000000 | (px[r * tex.width + x] & 0xffffff);
        return out;
    }

    /**
     * The game's indexed atlas decoding: the page is cut into 256 x 256 blocks, each block has its own palette row
     * (row = blockY * columns + (columns - 1 - blockX)); alpha comes from the alpha plane (null = opaque).
     */
    private static int[] compose(byte[] index, byte[] alpha, int[][] palette, int w, int h) {
        int[] out = new int[w * h];
        int columns = (w + 255) / 256;
        int rows = (h + 255) / 256;
        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < columns; bx++) {
                int[] colours = palette[Math.min(by * columns + (columns - 1 - bx), palette.length - 1)];
                int y1 = Math.min(h, by * 256 + 256);
                int x1 = Math.min(w, bx * 256 + 256);
                for (int y = by * 256; y < y1; y++) {
                    for (int x = bx * 256; x < x1; x++) {
                        int i = y * w + x;
                        int a = alpha == null ? 255 : alpha[i] & 0xff;
                        out[i] = (a << 24) | (colours[index[i] & 0xff] & 0xffffff);
                    }
                }
            }
        }
        return out;
    }

    private static byte[] zeroMask(byte[] index) {
        byte[] mask = new byte[index.length];
        for (int i = 0; i < mask.length; i++) mask[i] = index[i] == 0 ? 0 : (byte) 255;
        return mask;
    }

    // ---------------------------------------------------------------- atlas pages

    private UnityAssets.Tex sameSize(String name, int w, int h) {
        List<UnityAssets.Tex> list = textures.get(name);
        if (list != null) for (UnityAssets.Tex t : list) if (t.width == w && t.height == h) return t;
        return null;
    }

    private void extractPage(String db, int number, String name, UnityAssets.Tex base) throws IOException {
        int w = base.width;
        int h = base.height;
        UnityAssets.Tex alpha = sameSize(name + "_A", w, h);
        UnityAssets.Tex pal = null;
        int blocks = ((w + 255) / 256) * ((h + 255) / 256);
        List<UnityAssets.Tex> palettes = new ArrayList<>();
        List<UnityAssets.Tex> all = textures.get(name + "_P");
        if (all != null) for (UnityAssets.Tex p : all) if (p.width == 256) palettes.add(p);
        for (UnityAssets.Tex p : palettes) if (p.height == blocks) { pal = p; break; }
        if (pal == null) for (UnityAssets.Tex p : palettes) if (p.height == h / 32 || p.height == h / 64) { pal = p; break; }
        if (pal == null && !palettes.isEmpty()) pal = palettes.get(0);

        int[] image;
        if (alpha == null && pal != null && db.equals("Buff")) {
            image = compose(plane(base), null, palette(pal), w, h);   // buff icons carry their own frame and have no alpha page
        } else if (alpha != null && pal != null) {
            image = compose(plane(base), plane(alpha), palette(pal), w, h);
        } else if (pal != null && pal.height == 1) {
            // one palette row and no alpha plane (the emote sheet ...): index 0 is transparent
            byte[] index = plane(base);
            image = compose(index, zeroMask(index), palette(pal), w, h);
        } else if (pal != null) {
            byte[] index = plane(base);
            image = compose(index, zeroMask(index), palette(pal), w, h);   // several rows, no alpha plane (most NPC pages)
        } else {
            image = argb(base);
        }
        Png.write(new File(outDir, db + number + "_" + w + ".png"), image, w, h);
    }

    private void extractFonts() throws IOException {
        String[][] fonts = {{"mouse", "Mouse_Text_"}, {"stack", "Item_Stack_"}};
        for (String[] font : fonts) {
            for (int n = 1; n < 40; n++) {
                List<UnityAssets.Tex> list = textures.get(font[1] + n + "_A");
                if (list == null) {
                    if (n > 1) break;
                    continue;
                }
                UnityAssets.Tex widest = list.get(0);
                for (UnityAssets.Tex t : list) if (t.width > widest.width) widest = t;
                byte[] alpha = plane(widest);
                int[] image = new int[alpha.length];
                for (int i = 0; i < image.length; i++) image[i] = ((alpha[i] & 0xff) << 24) | 0xffffff;
                Png.write(new File(outDir, "font_" + font[0] + "_" + (n - 1) + ".png"), image, widest.width, widest.height);
            }
            step("Fonts");
        }
        step("Fonts");
    }

    // ---------------------------------------------------------------- backgrounds

    /** The game's table of tiled background images (version 3 of its atlas database). */
    private static final class BackgroundAtlas {
        static final class Entry {
            int page;
            int w;
            int h;
            int x;
            int y;
            int tw;
            int th;
            boolean trim;
            short[] tiles;
        }

        static final class Job {
            final String name;
            final Entry entry;

            Job(String name, Entry entry) {
                this.name = name;
                this.entry = entry;
            }
        }

        final int[][] pages;   // width, height, remap
        final Map<Long, Entry> entries = new HashMap<>();
        final List<Job> jobs = new ArrayList<>();

        BackgroundAtlas(byte[] data) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int marker = b.getShort();
            int version = b.getShort();
            if (marker != 0 || version != 3) throw new IOException("Unsupported background atlas version " + marker + "." + version);
            int pageCount = b.getShort();
            pages = new int[pageCount][3];
            for (int i = 0; i < pageCount; i++) {
                pages[i][0] = b.getShort();
                pages[i][1] = b.getShort();
                pages[i][2] = b.getShort();
            }
            int count = b.getInt();
            for (int i = 0; i < count; i++) {
                long uid = b.getInt() & 0xffffffffL;
                Entry e = new Entry();
                e.page = b.getInt();
                e.w = b.getShort();
                e.h = b.getShort();
                e.x = b.getShort();
                e.y = b.getShort();
                int scale = b.getShort();
                e.tw = b.getShort();
                e.th = b.getShort();
                if (scale != 1) throw new IOException("Unexpected scale " + scale + " in the background atlas");
                if (e.tw > 0 && e.th > 0) {
                    e.trim = b.get() != 0;
                    int cx = (e.w + e.tw - 1) / e.tw;
                    int cy = (e.h + e.th - 1) / e.th;
                    int per = e.trim ? 6 : 4;
                    e.tiles = new short[cx * cy * per];
                    for (int t = 0; t < e.tiles.length; t++) e.tiles[t] = b.getShort();
                }
                entries.put(uid, e);
            }
            String[][] stems = {{"bg", "background_"}, {"underworld", "backgrounds/underworld "}};
            for (String[] stem : stems) {
                for (int number = 0; ; number++) {
                    CRC32 crc = new CRC32();
                    crc.update((stem[1] + number + ".png").getBytes("US-ASCII"));
                    Entry e = entries.get(crc.getValue());
                    if (e == null) break;
                    jobs.add(new Job(stem[0] + "_" + number + ".png", e));
                }
            }
            Collections.sort(jobs, (x, y) -> x.entry.page - y.entry.page);
        }
    }

    private UnityAssets.Tex resourceTexture(Map<String, Long> container, String key) throws IOException {
        Long id = container.get(key);
        UnityAssets.Obj obj = id == null ? null : resources.byId.get(id);
        return obj == null ? null : resources.texture(obj);
    }

    private int[] backgroundPage(BackgroundAtlas atlas, Map<String, Long> container, int page) throws IOException {
        int[] info = atlas.pages[page];
        UnityAssets.Tex base = resourceTexture(container, HIGH_RES + "background" + (info[2] + 1));
        if (base == null || base.width != info[0] || base.height != info[1]) throw new IOException("Background page " + page + " does not match the atlas");
        UnityAssets.Tex alpha = resourceTexture(container, HIGH_RES + "background" + (info[2] + 1) + "_a");
        UnityAssets.Tex pal = resourceTexture(container, HIGH_RES + "background" + (page + 1) + "_p");
        if (pal == null) return argb(base);
        byte[] index = plane(base);
        byte[] mask = alpha != null ? plane(alpha) : zeroMask(index);
        return compose(index, mask, palette(pal), base.width, base.height);
    }

    private void extractBackgrounds(BackgroundAtlas atlas, Map<String, Long> container) throws IOException {
        int cachedPage = -1;
        int[] page = null;
        int pageWidth = 0;
        for (BackgroundAtlas.Job job : atlas.jobs) {
            if (listener.cancelled()) return;
            BackgroundAtlas.Entry e = job.entry;
            if (e.page != cachedPage) {
                page = backgroundPage(atlas, container, e.page);
                pageWidth = atlas.pages[e.page][0];
                cachedPage = e.page;
            }
            int pageHeight = page.length / pageWidth;
            int[] out = new int[e.w * e.h];
            if (e.tiles == null) {
                copy(page, pageWidth, pageHeight, e.x, e.y, e.x + e.w, e.y + e.h, out, e.w, e.h, 0, 0);
            } else {
                int cx = (e.w + e.tw - 1) / e.tw;
                int per = e.trim ? 6 : 4;
                for (int i = 0; i < e.tiles.length / per; i++) {
                    int ox = e.trim ? e.tiles[i * per] : 0;
                    int oy = e.trim ? e.tiles[i * per + 1] : 0;
                    int x0 = e.tiles[i * per + per - 4];
                    int y0 = e.tiles[i * per + per - 3];
                    int x1 = e.tiles[i * per + per - 2];
                    int y1 = e.tiles[i * per + per - 1];
                    if (x1 > x0 && y1 > y0) copy(page, pageWidth, pageHeight, x0, y0, x1, y1, out, e.w, e.h, i % cx * e.tw - ox, i / cx * e.th - oy);
                }
            }
            Png.write(new File(new File(outDir, "backgrounds"), job.name), out, e.w, e.h);
            step("Backgrounds");
        }
    }

    /** Copies a rectangle of the source page into the target at (dx, dy), clipped on both sides; outside pixels stay transparent. */
    private static void copy(int[] src, int sw, int sh, int x0, int y0, int x1, int y1, int[] dst, int dw, int dh, int dx, int dy) {
        for (int y = y0; y < y1; y++) {
            int ty = dy + (y - y0);
            if (y < 0 || y >= sh || ty < 0 || ty >= dh) continue;
            for (int x = x0; x < x1; x++) {
                int tx = dx + (x - x0);
                if (x < 0 || x >= sw || tx < 0 || tx >= dw) continue;
                dst[ty * dw + tx] = src[y * sw + x];
            }
        }
    }
}
