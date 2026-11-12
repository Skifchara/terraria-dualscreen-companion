package com.terraduo.companion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The explored map, kept for good. Every map sample the game sends is merged into 64 x 64-sample chunks (per world and per
 * zoom step) that are written to the app's private folder, so the map is never rebuilt from scratch: not when you pan, not when
 * you zoom, not when the app or the game restarts. Unknown pixels are 0; a newer known pixel replaces an older one.
 */
final class MapCache {
    private static final int CHUNK = 64;

    private final File root;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<Long, int[]> chunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private String world = "";
    private boolean saveScheduled;
    private final int[] knownSteps = {1, 2, 4, 8, 16, 32};

    MapCache(File filesDir) {
        root = new File(filesDir, "mapcache");
    }

    private static long key(int step, int cx, int cy) {
        return ((long) step << 52) ^ (((long) cx & 0x3ffffff) << 26) ^ ((long) cy & 0x3ffffff);
    }

    /** Switch to another world's cache (flushes the previous one, loads the new one in the background). */
    synchronized void setWorld(String id) {
        if (id.equals(world)) return;
        final Map<Long, int[]> old = new HashMap<>(chunks);
        final Set<Long> oldDirty = new HashSet<>(dirty);
        final String oldWorld = world;
        chunks.clear();
        dirty.clear();
        world = id;
        io.execute(() -> {
            if (!oldWorld.isEmpty()) writeAll(oldWorld, old, oldDirty);
            load(id);
        });
    }

    synchronized boolean hasWorld() { return !world.isEmpty(); }

    synchronized int count() { return chunks.size(); }

    /** Merge a block of samples: sample (i, j) is the world tile (left + i * step, top + j * step). */
    synchronized void put(int left, int top, int step, int cols, int rows, int[] colors) {
        if (world.isEmpty()) return;
        int lastCx = Integer.MIN_VALUE;
        int lastCy = Integer.MIN_VALUE;
        int[] data = null;
        for (int j = 0; j < rows; j++) {
            int gy = Math.floorDiv(top + j * step, step);
            int cy = Math.floorDiv(gy, CHUNK);
            for (int i = 0; i < cols; i++) {
                int color = colors[j * cols + i];
                if (color == 0) continue;
                int gx = Math.floorDiv(left + i * step, step);
                int cx = Math.floorDiv(gx, CHUNK);
                if (cx != lastCx || cy != lastCy || data == null) {
                    long k = key(step, cx, cy);
                    data = chunks.get(k);
                    if (data == null) {
                        data = new int[CHUNK * CHUNK];
                        chunks.put(k, data);
                    }
                    dirty.add(k);
                    lastCx = cx;
                    lastCy = cy;
                }
                data[(gy - cy * CHUNK) * CHUNK + (gx - cx * CHUNK)] = color;
            }
        }
        scheduleSave();
    }

    /** Everything known for this block (0 = unknown). Missing chunks fall back to other zoom steps of the same world. */
    synchronized int[] compose(int left, int top, int step, int cols, int rows) {
        int[] out = new int[cols * rows];
        for (int j = 0; j < rows; j++) {
            int tileY = top + j * step;
            int gy = Math.floorDiv(tileY, step);
            int cy = Math.floorDiv(gy, CHUNK);
            for (int i = 0; i < cols; i++) {
                int tileX = left + i * step;
                int gx = Math.floorDiv(tileX, step);
                int cx = Math.floorDiv(gx, CHUNK);
                int[] data = chunks.get(key(step, cx, cy));
                int color = data == null ? 0 : data[(gy - cy * CHUNK) * CHUNK + (gx - cx * CHUNK)];
                if (color == 0) color = fromOtherSteps(tileX, tileY, step);
                out[j * cols + i] = color;
            }
        }
        return out;
    }

    private int fromOtherSteps(int tileX, int tileY, int skip) {
        for (int other : knownSteps) {
            if (other == skip) continue;
            int gx = Math.floorDiv(tileX, other);
            int gy = Math.floorDiv(tileY, other);
            int[] data = chunks.get(key(other, Math.floorDiv(gx, CHUNK), Math.floorDiv(gy, CHUNK)));
            if (data == null) continue;
            int color = data[(gy - Math.floorDiv(gy, CHUNK) * CHUNK) * CHUNK + (gx - Math.floorDiv(gx, CHUNK) * CHUNK)];
            if (color != 0) return color;
        }
        return 0;
    }

    /** Write everything that changed (called when the app goes to the background). */
    synchronized void flush() {
        final String name = world;
        final Map<Long, int[]> snapshot = new HashMap<>(chunks);
        final Set<Long> changed = new HashSet<>(dirty);
        dirty.clear();
        if (name.isEmpty() || changed.isEmpty()) return;
        io.execute(() -> writeAll(name, snapshot, changed));
    }

    private void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        io.execute(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                // save anyway
            }
            synchronized (MapCache.this) {
                saveScheduled = false;
            }
            flush();
        });
    }

    // ---- disk

    private File folder(String name) {
        return new File(root, name.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    private void writeAll(String name, Map<Long, int[]> all, Set<Long> changed) {
        File dir = folder(name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        android.util.Log.i("Terraduo", "map cache: writing " + changed.size() + " chunks for " + name);
        for (long k : changed) {
            int[] data = all.get(k);
            if (data == null) continue;
            int step = (int) (k >>> 52);
            // the chunk coordinates are recovered from a side file name written with the data
            int cx = (int) ((k >> 26) & 0x3ffffff);
            int cy = (int) (k & 0x3ffffff);
            cx = (cx << 6) >> 6;   // sign-extend 26 bits
            cy = (cy << 6) >> 6;
            File file = new File(dir, step + "_" + cx + "_" + cy + ".bin");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new DeflaterOutputStream(new FileOutputStream(file))))) {
                for (int value : data) out.writeInt(value);
            } catch (IOException ignored) {
                // the in-memory copy still works
            }
        }
    }

    private void load(String name) {
        File dir = folder(name);
        File[] files = dir.listFiles();
        android.util.Log.i("Terraduo", "map cache: " + (files == null ? 0 : files.length) + " chunk files found for " + name);
        if (files == null) return;
        for (File file : files) {
            String[] parts = file.getName().replace(".bin", "").split("_");
            if (parts.length != 3) continue;
            try {
                int step = Integer.parseInt(parts[0]);
                int cx = Integer.parseInt(parts[1]);
                int cy = Integer.parseInt(parts[2]);
                int[] data = new int[CHUNK * CHUNK];
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new FileInputStream(file))))) {
                    for (int index = 0; index < data.length; index++) data[index] = in.readInt();
                }
                synchronized (this) {
                    if (!name.equals(world)) return;   // switched worlds meanwhile
                    long k = key(step, cx, cy);
                    int[] fresh = chunks.get(k);
                    if (fresh == null) {
                        chunks.put(k, data);
                    } else {
                        for (int index = 0; index < data.length; index++) if (fresh[index] == 0) fresh[index] = data[index];
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // skip a damaged chunk
            }
        }
    }
}
