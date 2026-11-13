package com.terraduo.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Game sprites, taken from the game's own atlas pages. The running game (through the TL Pro mod) says where a sprite
 * lives (database, page, rectangle); the page images were decoded from the player's own installation on a PC
 * (tools/extract_assets.py) and pushed to this app's private folder. Crops are cached; nothing is bundled with the app.
 * Keys: "item:3507", "ui:Heart", "background:7", "buff:3", "npchead:5".
 *
 * Nothing here touches the disk or the atlas on the UI thread: a lookup that misses just schedules the work on a
 * background thread, and {@link #warm} lets the UI ask for sprites before they are drawn.
 */
final class SpriteStore {
    private static final int BATCH = 24;

    private final File cacheDir;
    private final File atlasDir;
    private final BitmapCache memory = new BitmapCache(110 * 1024 * 1024);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> known = new HashSet<>();     // waiting for the mod or for a crop
    private final Set<String> loading = new HashSet<>();   // being read from the cache folder
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Map<String, BitmapRegionDecoder> decoders = new HashMap<>();   // io thread only
    private final ArrayList<String> inFlight = new ArrayList<>();
    private volatile int revision;

    SpriteStore(Context context) {
        cacheDir = new File(context.getFilesDir(), "sprites8");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File external = context.getExternalFilesDir(null);
        atlasDir = external == null ? null : new File(external, "atlas");
    }

    /** Bumped every time a new sprite arrives so the UI knows to repaint. */
    int revision() { return revision; }

    /** Ask for a sprite ahead of time; the result is only used by later {@link #get} calls. */
    void warm(String key) { get(key); }

    /** @return the sprite, or null while it is being loaded (that starts automatically). */
    Bitmap get(String key) {
        Bitmap cached = memory.get(key);
        if (cached != null) return cached;
        synchronized (this) {
            if (known.contains(key) || loading.contains(key)) return null;
            Long retry = retryAt.get(key);
            if (retry != null && retry >= android.os.SystemClock.uptimeMillis()) return null;
            loading.add(key);
        }
        io.execute(() -> loadFromCache(key));
        return null;
    }

    private final Set<String> missingBackgrounds = new HashSet<>();

    /** A whole background layer rebuilt from the game's tile atlas (tools/extract_backgrounds.py); null until loaded. */
    Bitmap background(int id, boolean underworld) {
        String name = (underworld ? "underworld_" : "bg_") + id;
        String key = "bgfile:" + name;
        Bitmap cached = memory.get(key);
        if (cached != null) return cached;
        synchronized (this) {
            if (loading.contains(key) || missingBackgrounds.contains(key) || atlasDir == null) return null;
            loading.add(key);
        }
        io.execute(() -> {
            File file = new File(new File(atlasDir, "backgrounds"), name + ".png");
            Bitmap loaded = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
            synchronized (this) {
                loading.remove(key);
                if (loaded != null) {
                    memory.put(key, loaded);
                    revision++;
                } else {
                    missingBackgrounds.add(key);
                }
            }
        });
        return null;
    }

    private void loadFromCache(String key) {
        Bitmap loaded = null;
        File file = file(key);
        if (file.isFile()) loaded = BitmapFactory.decodeFile(file.getPath());
        synchronized (this) {
            loading.remove(key);
            if (loaded != null) {
                memory.put(key, loaded);
                revision++;
            } else {
                known.add(key);
                queue.add(key);
            }
        }
    }

    /** Sprites that are neither cached nor requested yet. */
    synchronized int backlog() { return queue.size() + loading.size(); }

    /** The next "entries" request for the mod (several sprites at once), or null when nothing is pending. */
    synchronized JSONObject nextRequest() {
        if (!inFlight.isEmpty() || queue.isEmpty()) return null;
        JSONArray items = new JSONArray();
        while (!queue.isEmpty() && inFlight.size() < BATCH) {
            String key = queue.poll();
            int colon = key.indexOf(':');
            String kind = key.substring(0, colon);
            String value = key.substring(colon + 1);
            JSONObject item = new JSONObject();
            TlProPanel.put(item, "kind", kind);
            if (kind.equals("ui")) TlProPanel.put(item, "name", value);
            else TlProPanel.put(item, "id", Integer.parseInt(value));
            items.put(item);
            inFlight.add(key);
        }
        JSONObject request = new JSONObject();
        TlProPanel.put(request, "op", "entries");
        TlProPanel.put(request, "items", items);
        return request;
    }

    void deliver(JSONObject response) {
        final JSONArray list = response.optJSONArray("entries");
        final ArrayList<String> keys;
        synchronized (this) {
            keys = new ArrayList<>(inFlight);
            inFlight.clear();
        }
        io.execute(() -> {
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                JSONObject entry = list == null ? null : list.optJSONObject(index);
                Bitmap bitmap = null;
                if (entry != null && !entry.has("error")) bitmap = crop(entry);
                if (bitmap == null) {
                    synchronized (this) {
                        known.remove(key);
                        retryAt.put(key, android.os.SystemClock.uptimeMillis() + 20_000);
                    }
                    continue;
                }
                try (FileOutputStream out = new FileOutputStream(file(key))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (IOException ignored) {
                    // the in-memory copy still works
                }
                synchronized (this) {
                    memory.put(key, bitmap);
                    known.remove(key);
                    revision++;
                }
            }
        });
    }

    synchronized void abandonInFlight() {
        for (String key : inFlight) known.remove(key);
        inFlight.clear();
    }

    private Bitmap crop(JSONObject entry) {
        if (atlasDir == null) return null;
        String name = entry.optString("db") + (entry.optInt("page") + 1) + "_" + entry.optInt("pw") + ".png";
        File page = new File(atlasDir, name);
        if (!page.isFile()) return null;
        int width = entry.optInt("sw", entry.optInt("w"));
        int height = entry.optInt("sh", entry.optInt("h")) / Math.max(1, entry.optInt("frames", 1));
        int x = entry.optInt("x");
        int y = entry.optInt("y");
        try {
            BitmapRegionDecoder decoder = decoders.get(name);
            if (decoder == null) {
                decoder = BitmapRegionDecoder.newInstance(page.getPath(), false);
                decoders.put(name, decoder);
            }
            if (x < 0 || y < 0 || x + width > decoder.getWidth() || y + height > decoder.getHeight()) return null;
            Bitmap region = decoder.decodeRegion(new Rect(x, y, x + width, y + height), null);
            JSONArray tint = entry.optJSONArray("tint");
            return tint == null || region == null ? region : tinted(region, tint);
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    /** The game draws such items (Gel...) from a grey sprite, tinted with the item's colour; do the same once. */
    private static Bitmap tinted(Bitmap source, JSONArray tint) {
        Bitmap out = source.copy(Bitmap.Config.ARGB_8888, true);
        int tr = tint.optInt(0), tg = tint.optInt(1), tb = tint.optInt(2);
        int opacity = 255 - Math.max(0, Math.min(200, tint.optInt(4)));
        int[] pixels = new int[out.getWidth() * out.getHeight()];
        out.getPixels(pixels, 0, out.getWidth(), 0, 0, out.getWidth(), out.getHeight());
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int alpha = pixel >>> 24;
            if (alpha == 0) continue;
            float grey = (((pixel >> 16) & 255) + ((pixel >> 8) & 255) + (pixel & 255)) / 765f;
            float light = 0.55f + 0.9f * grey;
            int r = Math.min(255, (int) (tr * light + 40 * grey));
            int g = Math.min(255, (int) (tg * light + 40 * grey));
            int b = Math.min(255, (int) (tb * light + 40 * grey));
            int a = Math.min(255, alpha * Math.max(160, opacity + 80) / 255);
            pixels[index] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        out.setPixels(pixels, 0, out.getWidth(), 0, 0, out.getWidth(), out.getHeight());
        return out;
    }

    private File file(String key) {
        return new File(cacheDir, key.replace(':', '_') + ".png");
    }
}
