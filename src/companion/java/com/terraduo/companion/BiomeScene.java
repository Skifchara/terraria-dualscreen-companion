package com.terraduo.companion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Animated backdrop that follows where the player is (biome, depth, time of day, weather). It is drawn at low
 * resolution and scaled up without smoothing for a pixel-art look. When the game's own background sprites
 * for the biome have been fetched (see SpriteStore) they are used as parallax layers instead of the procedural hills.
 */
final class BiomeScene {
    private static final int PIXEL = 5;

    private static final class Theme {
        final int top, bottom, far, near;
        final boolean snow, embers, stars, spores;
        Theme(int top, int bottom, int far, int near, boolean snow, boolean embers, boolean stars, boolean spores) {
            this.top = top; this.bottom = bottom; this.far = far; this.near = near;
            this.snow = snow; this.embers = embers; this.stars = stars; this.spores = spores;
        }
    }

    private static final Theme FOREST_DAY = new Theme(0xff5fa4e8, 0xffbfe3ff, 0xff4f8a5a, 0xff2f6a3c, false, false, false, false);
    private static final Theme FOREST_NIGHT = new Theme(0xff070b24, 0xff1d2a5c, 0xff1b3a3a, 0xff102a26, false, false, true, false);
    private static final Theme SNOW = new Theme(0xff9cc4ee, 0xffe8f4ff, 0xffc7dcef, 0xffeaf5ff, true, false, false, false);
    private static final Theme SNOW_NIGHT = new Theme(0xff10204a, 0xff3a5a8c, 0xff5f7fa8, 0xff9fb8d4, true, false, true, false);
    private static final Theme JUNGLE = new Theme(0xff4fb06a, 0xffc2f0a0, 0xff2f7a3a, 0xff17562a, false, false, false, true);
    private static final Theme CORRUPT = new Theme(0xff3a2a58, 0xff7a5aa0, 0xff4a2f6a, 0xff2a1a44, false, false, true, false);
    private static final Theme CRIMSON = new Theme(0xff5a1018, 0xffa8383a, 0xff7a2a2a, 0xff4a1414, false, false, false, false);
    private static final Theme HALLOW = new Theme(0xff8ec6ff, 0xffffd0f0, 0xffb68ee0, 0xff7ad0c0, false, false, false, true);
    private static final Theme DESERT = new Theme(0xff7ab8e8, 0xfff4e2a8, 0xffd8b66a, 0xffbf8f45, false, false, false, false);
    private static final Theme MUSHROOM = new Theme(0xff0a1a3a, 0xff1a3a6a, 0xff2a4a8a, 0xff143060, false, false, true, true);
    private static final Theme UNDERWORLD = new Theme(0xff200808, 0xffb02808, 0xff5a1408, 0xff2a0804, false, true, false, false);
    private static final Theme DUNGEON = new Theme(0xff0c1230, 0xff2a3a6a, 0xff243257, 0xff161e3a, false, false, false, false);
    private static final Theme CAVE = new Theme(0xff2a2018, 0xff54402c, 0xff3a2c20, 0xff241a12, false, false, false, false);
    private static final Theme ROCK = new Theme(0xff1c1c24, 0xff3c3c4c, 0xff2c2c38, 0xff181820, false, false, false, false);
    private static final Theme SKY = new Theme(0xff2a64c8, 0xffa8d8ff, 0xffe8f4ff, 0xffffffff, false, false, false, false);
    private static final Theme SPACE = new Theme(0xff02030c, 0xff0c1448, 0xff141c50, 0xff0a1034, false, false, true, false);
    private static final Theme BEACH = new Theme(0xff6ab0f0, 0xffcdeeff, 0xff4a98d8, 0xfff0dc9c, false, false, false, false);

    private final Paint paint = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private Bitmap low;
    private Canvas lowCanvas;
    private Theme target = FOREST_DAY;
    private float[] shown = new float[8];
    private boolean first = true;
    private boolean raining;
    private boolean night;
    // the game's own background layers (far -> near), parallax follows the player's real position
    private int[] layerIds;
    private boolean forestSurface;
    private boolean surface;
    private boolean cave;
    private boolean underworld;
    private boolean sky;
    private JSONObject backgroundIds;
    private float playerX, playerY, worldW = 4200, surfaceY = 400, timeOfDay, lightning, rainPower;
    private float shownLightning;
    private RadialGradient vignette, flash;
    private int vignetteW, vignetteH;
    private final Paint layerPaint = new Paint();
    // Smooth changes of scenery: a layer fades in when it first appears, and the layers of the scene that was just left stay
    // underneath while the new ones fade in over them.
    private static final long FADE_MS = 800;
    private final java.util.HashMap<Integer, Long> seen = new java.util.HashMap<>();
    private int[] drawnLayers;
    private boolean drawnCave, drawnUnderworld, drawnSurface;
    private int[] oldLayers;
    private boolean oldCave, oldUnderworld, oldSurface;
    private long oldUntil;
    private boolean layerFading;
    private int[] recent;   // texture IDs selected from the game's current backdrop tables
    private static final int[] ANIMATED_SURFACE = {219, 220, 221, 235, 271, 272, 273, 281, 302,
        303, 305, 307, 309, 311, 313, 315, 317, 326, 337, 338, 341};
    private static final int[] ANIMATED_UNDERWORLD = {1, 6, 7, 8, 13};
    private static final int[] DEFAULT_UNDERWORLD = {4, 3, 2, 1, 0};

    BiomeScene() {
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        layerPaint.setFilterBitmap(false);
        layerPaint.setAntiAlias(false);
    }

    /** The background ids reported for the current scene, far to near. */
    void setRecent(JSONArray ids) {
        recent = idsOf(ids);
    }

    /** Where the player is, in tiles (parallax follows it) and the world's size / surface depth. */
    void setPlayer(float x, float y) { playerX = x; playerY = y; }


    /** Picks the theme for the environment the mod reported ({day, rain, zones[]}). */
    void update(JSONObject env, JSONObject backgrounds) {
        if (env == null) return;
        night = !env.optBoolean("day", true);
        raining = env.optBoolean("rain");
        JSONArray zones = env.optJSONArray("zones");
        java.util.HashSet<String> z = new java.util.HashSet<>();
        for (int index = 0; zones != null && index < zones.length(); index++) z.add(zones.optString(index));
        Theme theme;
        String group = null;
        underworld = z.contains("UnderworldHeight");
        sky = z.contains("SkyHeight");
        if (underworld) theme = UNDERWORLD;
        else if (z.contains("Dungeon")) theme = DUNGEON;
        else if (z.contains("Glowshroom")) { theme = MUSHROOM; group = "mushroomBG"; }
        else if (z.contains("Corrupt")) { theme = CORRUPT; group = "corruptBG"; }
        else if (z.contains("Crimson")) { theme = CRIMSON; group = "crimsonBG"; }
        else if (z.contains("Hallow")) { theme = HALLOW; group = "hallowBG"; }
        else if (z.contains("RockLayerHeight")) theme = ROCK;
        else if (z.contains("DirtLayerHeight")) theme = CAVE;
        else if (sky) theme = night ? SPACE : SKY;
        else if (z.contains("Snow")) { theme = night ? SNOW_NIGHT : SNOW; group = "snowBG"; }
        else if (z.contains("Jungle")) { theme = JUNGLE; group = "jungleBG"; }
        else if (z.contains("Desert")) { theme = DESERT; group = "desertBG"; }
        else if (z.contains("Beach")) theme = BEACH;
        else theme = night ? FOREST_NIGHT : FOREST_DAY;
        target = theme;
        layerIds = null;
        backgroundIds = backgrounds;
        worldW = Math.max(1000, (float) env.optDouble("worldW", worldW));
        surfaceY = (float) env.optDouble("surface", surfaceY);
        timeOfDay = (float) env.optDouble("time", 0) / (night ? 32400f : 54000f);
        lightning = (float) env.optDouble("lightning", 0);
        rainPower = (float) env.optDouble("rainPower", 0);
        cave = !underworld && (z.contains("DirtLayerHeight") || z.contains("RockLayerHeight") || z.contains("Dungeon"));
        surface = !cave && !underworld && !sky;
        boolean beach = z.contains("Beach");
        forestSurface = surface && group == null && !beach && !z.contains("Desert");
        if (underworld) {
            int[] variant = backgrounds == null ? null : idsOf(backgrounds.optJSONArray("underworldBG"));
            layerIds = variant != null && variant.length >= 5
                ? new int[]{variant[4], variant[3], variant[2], variant[1], variant[0]} : DEFAULT_UNDERWORLD;
        }
        if (sky && !night) layerIds = new int[]{0};
        // Tiled cave backgrounds for mods that do not report the game's current selection.
        if (cave) {
            boolean rock = z.contains("RockLayerHeight");
            int id;
            if (z.contains("Glowshroom")) id = rock ? 65 : 63;
            else if (z.contains("Corrupt")) id = rock ? 195 : 193;
            else if (z.contains("Crimson")) id = rock ? 201 : 202;
            else if (z.contains("Hallow")) id = rock ? 206 : 200;
            else if (z.contains("Snow")) id = rock ? 167 : 32;
            else if (z.contains("Jungle")) id = rock ? 149 : 147;
            else if (z.contains("Desert") || z.contains("UndergroundDesert")) id = rock ? 190 : 189;
            else {
                // plain dirt / stone caves: the game's style number picks the pair (brown dirt, grey stone).
                // style 4 was read from a running game (it loaded 71 and 72); the others are the neighbouring pairs.
                int style = env.optInt("under", 4);
                int[][] pairs = {{67, 69}, {74, 76}, {78, 80}, {81, 82}, {71, 72}, {84, 86}};
                int[] pair = pairs[Math.max(0, Math.min(pairs.length - 1, style))];
                id = rock ? pair[1] : pair[0];
            }
            layerIds = new int[]{id};
        }
        if (surface && backgrounds != null) {
            if (group != null) layerIds = idsOf(backgrounds.optJSONArray(group));
            else if (beach && backgrounds.has("oceanBG")) layerIds = new int[]{backgrounds.optInt("oceanBG", -1)};
        }
    }

    private static int[] idsOf(JSONArray array) {
        if (array == null) return null;
        int[] out = new int[array.length()];
        for (int index = 0; index < out.length; index++) out[index] = array.optInt(index, -1);
        return out;
    }

    /** The forest backdrop changes with the part of the world (four sections) the player stands in. */
    private int[] forestLayers() {
        if (backgroundIds == null) return null;
        int region = Math.max(0, Math.min(3, (int) (playerX / worldW * 4)));
        return idsOf(backgroundIds.optJSONArray("treeBGSet" + (region + 1)));
    }

    void draw(Canvas canvas, int width, int height, long now, SpriteStore sprites) {
        int w = Math.max(8, width / PIXEL);
        int h = Math.max(8, height / PIXEL);
        if (low == null || low.getWidth() != w || low.getHeight() != h) {
            low = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            lowCanvas = new Canvas(low);
        }
        float[] goal = {r(target.top), g(target.top), b(target.top), r(target.bottom), g(target.bottom),
            b(target.bottom), 0, 0};
        if (first) {
            System.arraycopy(goal, 0, shown, 0, 6);
            first = false;
        }
        for (int index = 0; index < 6; index++) shown[index] += (goal[index] - shown[index]) * 0.06f;
        float time = now / 1000f;
        // sky gradient in 16 bands
        int bands = 16;
        for (int band = 0; band < bands; band++) {
            float t = band / (float) (bands - 1);
            paint.setColor(0xff000000 | ((int) lerp(shown[0], shown[3], t) << 16)
                | ((int) lerp(shown[1], shown[4], t) << 8) | (int) lerp(shown[2], shown[5], t));
            lowCanvas.drawRect(0, band * h / bands, w, (band + 1) * h / bands + 1, paint);
        }
        if (target.stars) stars(w, h, time);
        if (target == FOREST_DAY || target == SKY || target == BEACH || target == HALLOW || target == DESERT
            || target == SNOW) clouds(w, h, time);
        int[] layers = layerIds;
        if (forestSurface) layers = forestLayers();
        if (hasLayer(sprites, recent)) layers = recent;
        boolean real = hasLayer(sprites, layers);
        trackScene(real ? layers : null, now);
        if ((!real || layerFading) && !sky) {
            hills(w, h, time * 1.2f, 0.52f, target.far, 7, 0.5f);
            hills(w, h, time * 2.4f, 0.66f, target.near, 5, 0.8f);
        }
        if (target.snow || raining) fall(w, h, time, target.snow);
        if (target.embers) embers(w, h, time);
        if (target.spores) spores(w, h, time);
        src.set(0, 0, w, h);
        dst.set(0, 0, width, height);
        canvas.drawBitmap(low, src, dst, paint);
        if (oldLayers != null && now < oldUntil) {
            boolean keepCave = cave, keepUnder = underworld, keepSurface = surface;
            cave = oldCave;
            underworld = oldUnderworld;
            surface = oldSurface;
            realLayers(canvas, width, height, time, sprites, oldLayers, now, false);
            cave = keepCave;
            underworld = keepUnder;
            surface = keepSurface;
        } else {
            oldLayers = null;
        }
        if (real) realLayers(canvas, width, height, time, sprites, layers, now, true);
        else layerFading = false;
        edges(canvas, width, height, time);
    }

    /** Remembers the scenery that was just left so it can be faded out, and forgets fade-in times of layers that are gone. */
    private void trackScene(int[] layers, long now) {
        if (drawnLayers != null && layers != null && !java.util.Arrays.equals(drawnLayers, layers)) {
            oldLayers = drawnLayers;
            oldCave = drawnCave;
            oldUnderworld = drawnUnderworld;
            oldSurface = drawnSurface;
            oldUntil = now + FADE_MS;
        }
        if (layers != null) {
            drawnLayers = layers;
            drawnCave = cave;
            drawnUnderworld = underworld;
            drawnSurface = surface;
        }
        java.util.Iterator<Integer> it = seen.keySet().iterator();
        while (it.hasNext()) {
            int id = it.next();
            if (layers == null || !contains(layers, id)) it.remove();
        }
    }

    private boolean hasLayer(SpriteStore sprites, int[] ids) {
        if (ids == null) return false;
        for (int id : ids) {
            Bitmap bitmap = id < 0 ? null : sprites.background(id, underworld);
            if (bitmap != null && (!cave || bitmap.getHeight() > 16)) return true;
        }
        return false;
    }

    /** Daylight 0..1 from the game's clock: full sun at noon, dark at night, a warm ramp at dawn and dusk. */
    private float daylight() {
        float t = Math.max(0f, Math.min(1f, timeOfDay));
        float arc = (float) Math.sin(Math.PI * t);
        return night ? 0.34f + 0.12f * arc : Math.min(1f, 0.34f + 1.1f * arc);
    }

    private int fadeAlpha(int id, long now, boolean fading) {
        if (!fading) return 255;
        Long first = seen.get(id);
        if (first == null) {
            first = now;
            seen.put(id, first);
        }
        float p = Math.min(1f, (now - first) / (float) FADE_MS);
        if (p < 1f) layerFading = true;
        return Math.round(255 * p * p * (3 - 2 * p));
    }

    private void realLayers(Canvas canvas, int width, int height, float time, SpriteStore sprites, int[] layers, long now,
                            boolean fading) {
        if (fading) layerFading = false;
        float light = daylight();
        float t = Math.max(0f, Math.min(1f, timeOfDay));
        float warm = night ? 0f : Math.max(0f, 1f - Math.min(t, 1f - t) / 0.12f);   // dawn / dusk
        int red = (int) (255 * Math.min(1f, light * (1f + 0.25f * warm)));
        int green = (int) (255 * Math.min(1f, light * (1f - 0.1f * warm)));
        int blue = (int) (255 * Math.min(1f, light * (night ? 1.45f : 1f - 0.28f * warm)));
        LightingColorFilter dayFilter = new LightingColorFilter(0xff000000 | (red << 16) | (green << 8) | blue, 0);
        layerPaint.setColorFilter(underworld ? null : dayFilter);
        float scale = height / 1080f * 1.15f;
        // clouds high in the sky, slow drift
        if (surface && backgroundIds != null) {
            int[] clouds = idsOf(backgroundIds.optJSONArray("cloudBG"));
            Bitmap cloud = clouds != null && clouds.length > 0 && clouds[0] >= 0 && !contains(layers, clouds[0])
                ? sprites.background(clouds[0], false) : null;
            if (cloud != null) {
                float cs = height / 1080f * 0.9f;
                float cw = cloud.getWidth() * cs;
                float drift = -((time * 7f + playerX * 0.35f) % cw);
                for (float x = drift; x < width; x += cw) {
                    dst.set(Math.round(x), Math.round(height * 0.02f), Math.round(x + cw),
                        Math.round(height * 0.02f + cloud.getHeight() * cs));
                    src.set(0, 0, cloud.getWidth(), cloud.getHeight());
                    layerPaint.setAlpha(night ? 90 : 220);
                    canvas.drawBitmap(cloud, src, dst, layerPaint);
                }
                layerPaint.setAlpha(255);
            }
        }
        float[] speed = {0.10f, 0.22f, 0.40f};
        int panorama = 0;
        for (int id : layers) {
            if (id < 0) continue;
            Bitmap bitmap = sprites.background(id, underworld);
            if (bitmap == null) continue;
            layerPaint.setAlpha(fadeAlpha(id, now, fading));
            if (cave) {
                tiledTexture(canvas, bitmap, width, height, time);
                layerPaint.setColorFilter(dayFilter);
                continue;
            }
            if (id == 0 && !underworld) {
                src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                dst.set(0, 0, width, height);
                canvas.drawBitmap(bitmap, src, dst, layerPaint);
                continue;
            }
            int frame = ((int) (time * 4)) % 4;
            int sw = bitmap.getWidth();
            int sh = bitmap.getHeight();
            int sx = 0;
            int sy = 0;
            // Main.GetBackgroundRect and DrawUnderworldBackgroudLayer split these sheets into four frames.
            if (contains(underworld ? ANIMATED_UNDERWORLD : ANIMATED_SURFACE, id)) {
                sw /= 2;
                sh /= 2;
                sx = (frame % 2) * sw;
                sy = (frame / 2) * sh;
            }
            int slot = Math.min(2, panorama++);
            float layerScale = underworld ? height / 858f : scale;
            float bw = sw * layerScale;
            float bh = sh * layerScale;
            // horizontal parallax: the real position of the player, plus a gentle idle sway
            float shift = -(playerX * 16f * speed[slot] * layerScale * 0.5f + time * (1.5f + slot * 1.2f));
            shift = shift % bw;
            if (shift > 0) shift -= bw;
            // vertical: deeper -> the scenery sinks a little
            float sink = Math.max(-0.04f, Math.min(0.12f, (playerY - surfaceY) / 400f)) * height * (0.4f + slot * 0.3f);
            float top = underworld ? (height - bh) / 2 : height - bh + sink + height * 0.04f;
            src.set(sx, sy, sx + sw, sy + sh);
            for (float x = shift; x < width; x += bw) {
                dst.set(Math.round(x), Math.round(top), Math.round(x + bw) + 1, Math.round(top + bh));
                canvas.drawBitmap(bitmap, src, dst, layerPaint);
            }
        }
        layerPaint.setAlpha(255);
        layerPaint.setColorFilter(null);
    }

    /** Cave walls: a small texture the game tiles across the whole view, moving slower than the player. */
    private static boolean contains(int[] ids, int value) {
        for (int id : ids) if (id == value) return true;
        return false;
    }

    private void tiledTexture(Canvas canvas, Bitmap bitmap, int width, int height, float time) {
        if (bitmap.getHeight() <= 16) return;   // the 16-pixel strips are the ragged edge at the surface
        float scale = height / 1080f * 3f;
        float tw = bitmap.getWidth() * scale;
        float th = bitmap.getHeight() * scale;
        float x0 = -((playerX * 16f * 0.35f * scale * 0.5f + time * 2f) % tw);
        float y0 = -((playerY * 16f * 0.35f * scale * 0.5f) % th);
        if (x0 > 0) x0 -= tw;
        if (y0 > 0) y0 -= th;
        float dim = 0.78f;
        layerPaint.setColorFilter(new LightingColorFilter(0xff000000 | ((int) (255 * dim) << 16) | ((int) (255 * dim) << 8)
            | (int) (255 * dim), 0));
        for (float y = y0; y < height; y += th) {
            for (float x = x0; x < width; x += tw) {
                dst.set(Math.round(x), Math.round(y), Math.round(x + tw) + 1, Math.round(y + th) + 1);
                src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                canvas.drawBitmap(bitmap, src, dst, layerPaint);
            }
        }
    }

    /** Soft dark vignette; a lightning strike nearby makes the edges flash white. */
    private void edges(Canvas canvas, int width, int height, float time) {
        if (vignette == null || vignetteW != width || vignetteH != height) {
            vignetteW = width;
            vignetteH = height;
            float radius = (float) Math.hypot(width, height) * 0.55f;
            vignette = new RadialGradient(width / 2f, height / 2f, radius, new int[]{0x00000000, 0x00000000, 0x88000008},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            flash = new RadialGradient(width / 2f, height / 2f, radius, new int[]{0x00ffffff, 0x00ffffff, 0xffffffff},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(vignette);
        paint.setAlpha(255);
        canvas.drawRect(0, 0, width, height, paint);
        shownLightning += (lightning - shownLightning) * (lightning > shownLightning ? 0.8f : 0.18f);
        if (shownLightning > 0.02f) {
            paint.setShader(flash);
            paint.setAlpha((int) (255 * Math.min(1f, shownLightning * 1.4f)));
            canvas.drawRect(0, 0, width, height, paint);
        }
        paint.setShader(null);
        paint.setAlpha(255);
    }

    private void hills(int w, int h, float time, float base, int color, int waves, float amplitude) {
        paint.setColor(color);
        for (int x = 0; x < w; x++) {
            float phase = x * 0.045f + time * 0.18f;
            float y = base + 0.05f * (float) (Math.sin(phase) * amplitude + Math.sin(phase * 2.3f + 1.7f) * 0.5f
                + Math.sin(phase * 0.5f + waves) * 0.7f);
            int top = Math.max(0, Math.min(h, Math.round(y * h)));
            lowCanvas.drawRect(x, top, x + 1, h, paint);
        }
    }

    private void clouds(int w, int h, float time) {
        paint.setColor(night ? 0x33ffffff : 0xccffffff);
        for (int cloud = 0; cloud < 5; cloud++) {
            float speed = 1.5f + cloud * 0.6f;
            int cx = (int) (((cloud * 53 + time * speed) % (w + 40)) - 20);
            int cy = 4 + (cloud * 17) % Math.max(1, h / 3);
            lowCanvas.drawRect(cx, cy, cx + 14, cy + 3, paint);
            lowCanvas.drawRect(cx + 3, cy - 2, cx + 10, cy, paint);
            lowCanvas.drawRect(cx - 3, cy + 1, cx + 3, cy + 3, paint);
        }
    }

    private void stars(int w, int h, float time) {
        for (int star = 0; star < 46; star++) {
            int x = (star * 37 + star * star * 11) % w;
            int y = (star * 29 + star * 7) % Math.max(1, h * 2 / 3);
            float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 1.8f + star);
            paint.setColor(((int) (140 + 115 * twinkle) << 24) | 0xffffff);
            lowCanvas.drawRect(x, y, x + 1, y + 1, paint);
        }
    }

    private void fall(int w, int h, float time, boolean snow) {
        paint.setColor(snow ? 0xddffffff : 0x99a8c8ff);
        int count = snow ? 40 : 60;
        for (int drop = 0; drop < count; drop++) {
            float speed = snow ? 6 + drop % 5 : 28 + drop % 9;
            int x = (int) (((drop * 31) % w + (snow ? Math.sin(time + drop) * 3 : -time * 4)) % w);
            if (x < 0) x += w;
            int y = (int) ((drop * 17 + time * speed) % h);
            lowCanvas.drawRect(x, y, x + 1, y + (snow ? 1 : 3), paint);
        }
    }

    private void embers(int w, int h, float time) {
        for (int ember = 0; ember < 28; ember++) {
            int x = (int) ((ember * 41 + Math.sin(time * 0.8 + ember) * 4 + w) % w);
            int y = h - (int) ((ember * 13 + time * (4 + ember % 6)) % h);
            paint.setColor(0xff000000 | (0xff << 16) | ((90 + ember * 4 % 120) << 8));
            lowCanvas.drawRect(x, y, x + 1, y + 1, paint);
        }
    }

    private void spores(int w, int h, float time) {
        for (int spore = 0; spore < 24; spore++) {
            int x = (int) ((spore * 47 + Math.sin(time * 0.6 + spore) * 5 + w) % w);
            int y = h - (int) ((spore * 19 + time * (2 + spore % 4)) % h);
            paint.setColor(0xaaffffcc);
            lowCanvas.drawRect(x, y, x + 1, y + 1, paint);
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float r(int c) { return (c >> 16) & 255; }
    private static float g(int c) { return (c >> 8) & 255; }
    private static float b(int c) { return c & 255; }
}
