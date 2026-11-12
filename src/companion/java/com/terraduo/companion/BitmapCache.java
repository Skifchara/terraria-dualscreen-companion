package com.terraduo.companion;

import android.graphics.Bitmap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Size-bounded in-memory bitmap cache, least recently used first out. */
final class BitmapCache {
    private final LinkedHashMap<String, Bitmap> map = new LinkedHashMap<String, Bitmap>(64, 0.75f, true);
    private final int limit;
    private int size;

    BitmapCache(int bytes) { this.limit = bytes; }

    synchronized Bitmap get(String key) { return map.get(key); }

    synchronized void put(String key, Bitmap value) {
        Bitmap old = map.put(key, value);
        if (old != null) size -= old.getByteCount();
        size += value.getByteCount();
        Iterator<Map.Entry<String, Bitmap>> it = map.entrySet().iterator();
        while (size > limit && it.hasNext()) {
            Map.Entry<String, Bitmap> eldest = it.next();
            if (eldest.getValue() == value) continue;
            size -= eldest.getValue().getByteCount();
            it.remove();
        }
    }
}
