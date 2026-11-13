package com.terraduo.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * The bottom-screen UI, drawn on one canvas: biome backdrop, the game's own sprites and font, drag and drop,
 * item descriptions and actions, a game-style crafting screen, a large live map and NPC conversations.
 * All state comes from the TL Pro mod; every change goes back to the game as a validated command.
 */
final class TerraPanel extends View {
    private static final int[] STEPS = {1, 2, 4, 8, 16};
    private static final String[] PANES = {"inventory", "equipment", "craft", "map", "people"};
    private static final int INTERACTION = PANES.length;
    private static final float TEXT_BOOST = 1.45f;
    private static final int GOLD = 0xffffd866;
    private static final int INK = 0xfff2f6f8;
    private static final int MUTED = 0xffb5c5d4;
    private static final int[] RARITY = {0xff828282, 0xffffffff, 0xff9696ff, 0xff96ff96, 0xffffc896, 0xffff9696,
        0xffffa0ff, 0xffd2a0ff, 0xff96ff0a, 0xffffff0a, 0xff05c8ff, 0xffff2864, 0xffb428ff};

    private static final class Hit {
        final RectF rect = new RectF();
        int type;      // 0 slot, 1 tab, 2 button, 3 recipe, 4 recipe craft button, 8 overlay body, 9 overlay outside
        int group;
        int index;
        String name;
    }

    private final Consumer<JSONObject> send;
    private final Runnable refresh;
    private final SpriteStore sprites;
    private final GameFont font;
    private final BiomeScene scene = new BiomeScene();
    private final SharedPreferences prefs;
    private final ScaleGestureDetector scaler;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint infoText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pixels = new Paint();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final Path star = new Path();
    private final ArrayList<Hit> hits = new ArrayList<>();

    private JSONObject latest;
    private JSONObject backgrounds;

    // map
    private Bitmap mapBitmap;
    private MapCache mapCache;
    // chat, emotes and the on-screen keyboard (the window is not focusable, so there is no system keyboard)
    private boolean chatOpen;
    private int chatMode;             // 0 chat, 1 emotes
    private final StringBuilder chatInput = new StringBuilder();
    private JSONArray chatLines;
    private long lastChatAsk;
    private boolean keyShift;
    private int keyLayout;            // 0 English, 1 Russian
    private int emotePage;
    // quality-of-life additions: inventory filter, craft sub-tabs, recipe browser
    private int inventoryFilter;      // 0 all, 1 weapons, 2 tools, 3 armor, 4 potions, 5 blocks
    private int craftTab;             // 0 recipes, 1 banners, 2 guide (item picker)
    private float slotCap;            // when > 0 slots are drawn no bigger than this
    private boolean recipeViewOpen;
    private JSONObject recipeView;
    private int recipeViewTab;        // 0 makes, 1 uses
    private int recipeViewPage;
    private int requestCols, requestRows;
    private long lastCacheTry;
    private boolean mapWanted = true;   // the sampled region has to be refreshed now
    private long lastMapResponse;
    private int mapLeft, mapTop, mapStep = 2, mapCols, mapRows, playerX, playerY;
    private float viewScale = 3.5f;   // screen pixels per world tile, continuous
    private float targetScale = 3.5f;
    private long lastMapAsk;
    private boolean follow = true;
    private float viewCx, viewCy;
    private float dragStartCx, dragStartCy;
    private float mapBlock = 7;
    private final RectF mapPort = new RectF();

    private int pane;
    private int previousPane;
    private long paneChangedAt;
    private int revision;
    private int recipePage;
    private boolean hideRequested;
    private boolean fontRequested, backgroundsRequested;
    private boolean spriteTurn;
    private String feedback = "";
    private long feedbackUntil;
    private boolean feedbackLocal;   // a message made here (buff name, no room) that the mod's next reply must not wipe
    private int chosenGroup = -1, chosenIndex = -1;
    private float scrollY, viewHeight, neededHeight;
    private float listScroll;

    // item sheet
    private boolean sheetOpen;
    private int sheetGroup, sheetIndex;
    private JSONObject sheetInfo;
    private boolean infoWanted;
    private int splitMode;            // 0 none, 1 take one, 2 take half (waiting for an empty target slot)
    private long trashArmedAt;

    // crafting
    private int selectedRecipe = -1;
    private boolean craftHeld;
    private final Runnable craftRepeat = this::repeatCraft;

    // conversation
    private JSONObject npcInfo;
    private String gameCulture;
    private String interaction = "";
    private String interactionTitle = "chest";
    private float interactionWidth;
    private int shopSelection = -1;
    private boolean languagePicker;
    private boolean infoOpen;
    private long lastNpcRequest;

    private float shownHp, shownMana;
    private float highlightX = -1;

    // touch
    private Hit downHit;
    private float downX, downY, lastY;
    private boolean dragging, scrolling, scaling;
    private float dragX, dragY;
    private int edgeZone;        // 0 none, 1 top (drop), 2 bottom (use / equip / summon)
    private float edgeStrength;  // 0..1, how deep the finger is in that edge zone
    private Hit hover;
    private long downAt;

    TerraPanel(Context context, Consumer<JSONObject> send, Runnable refresh) {
        super(context);
        this.send = send;
        this.refresh = refresh;
        this.sprites = new SpriteStore(context);
        this.mapCache = new MapCache(context.getFilesDir());
        sprites.warm("ui:Heart");
        sprites.warm("ui:Mana");
        sprites.warm("ui:InventoryBack");
        for (int index = 2; index <= 18; index++) sprites.warm("ui:InventoryBack" + index);
        this.font = new GameFont(context);
        this.prefs = context.getSharedPreferences("terraduo", Context.MODE_PRIVATE);
        applyLanguage(null);
        pixels.setFilterBitmap(false);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setBackgroundColor(0xff0b1220);
        scaler = new ScaleGestureDetector(context, new PinchListener(this::pinch));
    }

    private boolean pinch(float factor) {
        if (pane != 3) return false;
        scaling = true;
        viewScale = Math.max(0.32f, Math.min(16f, viewScale * factor));
        targetScale = viewScale;
        return true;
    }

    /** World tiles per map sample: keeps samples about 6 screen pixels wide whatever the zoom. */
    private int desiredStep() {
        float ideal = 6f * (getHeight() / 1080f) / viewScale;
        int best = 1;
        for (int step : STEPS) if (Math.abs(step - ideal) < Math.abs(best - ideal)) best = step;
        return best;
    }

    // ------------------------------------------------------------------ contract with TlProActivity

    int revision() { return revision; }

    long pollInterval() { return pane == 3 ? 240 : pane == 2 ? 600 : 220; }

    JSONObject request(JSONObject action) {
        JSONObject result = action;
        long now = SystemClock.uptimeMillis();
        if (result == null) {
            if (font.needsMetrics() && !fontRequested) {
                fontRequested = true;
                result = op("font");
                TlProPanel.put(result, "name", "mouse");
            } else if (latest != null && backgrounds == null && !backgroundsRequested) {
                backgroundsRequested = true;
                result = op("backgrounds");
            } else if (sheetOpen && sheetInfo == null && infoWanted) {
                infoWanted = false;
                result = op("info");
                TlProPanel.put(result, "from", new JSONArray().put(sheetGroup).put(sheetIndex));
            } else if (talking() && shop() == null && now - lastNpcRequest > 1200) {
                lastNpcRequest = now;
                result = op("npc");
            } else {
                spriteTurn = !spriteTurn || sprites.backlog() > 40;
                result = spriteTurn ? sprites.nextRequest() : null;
                if (result == null && chatOpen && chatMode == 0 && now - lastChatAsk > 800) {
                    lastChatAsk = now;
                    result = op("chatlog");
                }
                if (result == null) {
                    // on the map tab the (slow) region sample is only asked for when it is due; in between the cheap state
                    // request keeps the player marker moving
                    boolean sampleDue = mapBitmap == null || mapWanted || now - lastMapResponse > 1500;
                    result = op(pane == 3 && sampleDue ? "map" : "state");
                }
                if (result.optString("op").equals("state") && latest != null && preloadStage < 2) {
                    preloadPane = preloadStage == 0 ? 1 : 2;
                    preloadStage++;
                }
            }
        }
        // every request carries the HUD flag: the mod hides/restores the game HUD from it
        int requestedPane = preloadPane >= 0 ? preloadPane : pane;
        if (preloadPane < 0 && pane == 2 && craftTab == 2) requestedPane = 0;   // the item picker needs the whole inventory
        TlProPanel.put(result, "pane", PANES[requestedPane == INTERACTION ? 0 : requestedPane]);
        TlProPanel.put(result, "page", preloadPane >= 0 ? 0 : recipePage);
        preloadPane = -1;
        TlProPanel.put(result, "hide", hideRequested);
        JSONObject openChest = chest();
        JSONObject openShop = shop();
        if (openChest != null) TlProPanel.put(result, "mirrorChest", openChest.optInt("id", -1));
        if (openShop != null) TlProPanel.put(result, "mirrorShop", openShop.optInt("id"));
        TlProPanel.put(result, "mapVisible", pane == 3 && mapBitmap != null);
        if (pane == 3 && "map".equals(result.optString("op"))) {
            int step = desiredStep();
            float sample = viewScale * step;
            TlProPanel.put(result, "step", step);
            TlProPanel.put(result, "cols", Math.max(16, Math.min(128, (int) Math.ceil(mapPort.width() / sample) + 6)));
            TlProPanel.put(result, "rows", Math.max(16, Math.min(96, (int) Math.ceil(mapPort.height() / sample) + 6)));
            if (!follow) {
                TlProPanel.put(result, "x", Math.round(viewCx));
                TlProPanel.put(result, "y", Math.round(viewCy));
            }
        }
        return result;
    }

    void disconnected() {
        sprites.abandonInFlight();
        preloadStage = 0;
        fontRequested = false;
        backgroundsRequested = false;
        if (latest != null) {
            latest = null;
            chosenGroup = -1;
            mapBitmap = null;
            follow = true;
            sheetOpen = false;
            npcInfo = null;
            interaction = "";
            shopSelection = -1;
            if (pane == INTERACTION) openPane(0);
        }
        postInvalidate();
    }

    void accept(JSONObject response, int responseRevision, boolean action) {
        if (response.has("glyphs") || response.has("textureCount")) {
            font.acceptMetrics(response);
        } else if (response.has("treeBGSet1")) {
            backgrounds = response;
            scene.update(latest == null ? null : latest.optJSONObject("env"), backgrounds);
        } else if (response.has("entries")) {
            sprites.deliver(response);
        } else if (response.has("chat") && !response.has("inventory")) {
            chatLines = response.optJSONArray("chat");
        } else if (response.has("recipesFor")) {
            recipeView = response.optJSONObject("recipesFor");
            if (recipeView != null) {
                JSONArray makes = recipeView.optJSONArray("makes");
                recipeViewTab = makes != null && makes.length() > 0 ? 0 : 1;
                JSONArray first = recipeView.optJSONArray(recipeViewTab == 0 ? "makes" : "uses");
                if (first != null) for (int index = 0; index < first.length(); index++) {
                    JSONArray entry = first.optJSONArray(index);
                    if (entry != null && entry.optJSONArray(1) != null) sprites.warm("item:" + entry.optJSONArray(1).optInt(0));
                }
            }
        } else if (response.has("tooltip")) {
            if (sheetOpen) sheetInfo = response;
        } else if (response.has("buttons") && response.has("talk")) {
            npcInfo = response;
        } else {
            if (response.has("error")) {
                feedback = response.optString("error");
                feedbackLocal = false;
                feedbackUntil = SystemClock.uptimeMillis() + 2800;
                if (sheetOpen && sheetInfo == null) sheetOpen = false;
            } else if (action && !feedbackLocal) {
                feedback = "";
            }
            JSONObject pad = response.optJSONObject("pad");
            if (pad != null) {
                int presses = pad.optInt("y");
                if (lastPadY < 0 || presses < lastPadY) lastPadY = presses;
                else if (presses > lastPadY) {
                    lastPadY = presses;
                    // Y on the controller: next tab, endlessly, while the game's own interface is hidden
                    if (hideRequested && !sheetOpen && !languagePicker) {
                        openPane((pane + 1) % (interaction.isEmpty() ? PANES.length : PANES.length + 1));
                    }
                }
            }
            if (response.has("inventory")) warmState(response);
            boolean backgroundPane = (response.has("equipment") && pane != 1) || (response.has("recipes") && pane != 2);
            if (response.has("inventory") && backgroundPane && latest != null) {
                // answer to a preload request for a pane that is not on screen: sprites only
            } else if (response.has("inventory") && responseRevision == revision) {
                latest = response;
                updateInteraction();
                if (response.has("lang")) applyLanguage(response.optString("lang"));
                scene.update(response.optJSONObject("env"), backgrounds);
                scene.setRecent(response.optJSONArray("bgNow"));
                if (!talking()) npcInfo = null;
                if (pane == 3 && response.has("map")) updateMap(response.optJSONObject("map"), response.optJSONArray("position"), response.optJSONObject("env"));
                else if (response.has("position")) {
                    JSONArray position = response.optJSONArray("position");
                    playerX = position.optInt(0);
                    playerY = position.optInt(1);
                    if (pane == 3 && follow) {
                        viewCx = playerX;
                        viewCy = playerY;
                    }
                }
            }
        }
        postInvalidate();
    }

    // ------------------------------------------------------------------ state helpers

    // ---- background loading: every sprite the UI is about to need is requested before it is drawn

    private int preloadPane = -1;
    private int lastPadY = -1;
    private int preloadStage;   // 0 equipment pane, 1 craft pane, 2 done

    private void warmItems(JSONArray list) {
        for (int index = 0; list != null && index < list.length(); index++) {
            JSONArray data = list.optJSONArray(index);
            if (data != null && data.optInt(0) != 0) sprites.warm("item:" + data.optInt(0));
        }
    }

    private void warmState(JSONObject state) {
        warmItems(state.optJSONArray("inventory"));
        JSONArray equipment = state.optJSONArray("equipment");
        for (int index = 0; equipment != null && index < equipment.length(); index++) warmItems(equipment.optJSONArray(index));
        JSONObject chest = state.optJSONObject("chest");
        if (chest != null) warmItems(chest.optJSONArray("items"));
        JSONObject shop = state.optJSONObject("shop");
        if (shop != null) warmItems(shop.optJSONArray("items"));
        JSONObject recipes = state.optJSONObject("recipes");
        JSONArray entries = recipes == null ? null : recipes.optJSONArray("entries");
        for (int index = 0; entries != null && index < entries.length(); index++) {
            JSONArray entry = entries.optJSONArray(index);
            if (entry == null) continue;
            JSONArray result = entry.optJSONArray(1);
            if (result != null && result.optInt(0) != 0) sprites.warm("item:" + result.optInt(0));
            JSONArray needs = entry.optJSONArray(2);
            for (int need = 0; needs != null && need < needs.length(); need++) {
                JSONArray ingredient = needs.optJSONArray(need);
                if (ingredient != null && ingredient.optInt(2) != 0) sprites.warm("item:" + ingredient.optInt(2));
            }
        }
        JSONArray buffs = state.optJSONArray("buffs");
        for (int index = 0; buffs != null && index < buffs.length(); index++) {
            JSONArray buff = buffs.optJSONArray(index);
            if (buff != null) sprites.warm("buff:" + buff.optInt(0));
        }
        JSONArray npcs = state.optJSONArray("npcs");
        for (int index = 0; npcs != null && index < npcs.length(); index++) {
            JSONArray npc = npcs.optJSONArray(index);
            if (npc != null && npc.optInt(2) >= 0) sprites.warm("npchead:" + npc.optInt(2));
        }
    }

    /** Ask the mod to play one of the game's own interface sounds (tick, open, close, grab). */
    private void sound(String name) {
        performHapticFeedback(name.equals("grab") ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP);
        JSONObject command = op("sound");
        TlProPanel.put(command, "name", name);
        send.accept(command);
    }

    private JSONObject op(String name) {
        JSONObject object = new JSONObject();
        TlProPanel.put(object, "op", name);
        return object;
    }

    private boolean talking() { return latest != null && latest.optInt("talk", -1) >= 0; }

    private JSONObject chest() { return latest == null ? null : latest.optJSONObject("chest"); }

    private JSONObject shop() { return latest == null ? null : latest.optJSONObject("shop"); }

    private void updateInteraction() {
        JSONObject shop = shop();
        JSONObject chest = chest();
        JSONArray near = latest == null ? null : latest.optJSONArray("nearby");
        String key = shop != null ? "shop/" + shop.optInt("id") + "/" + shop.optInt("talk")
            : chest != null ? "chest/" + chest.optInt("id") : near != null && near.length() > 0 ? "near" : "";
        if (key.equals(interaction)) return;
        interaction = key;
        shopSelection = -1;
        if (key.isEmpty()) {
            if (pane == INTERACTION) openPane(0);
        } else if (key.equals("near")) {
            interactionTitle = "nearby";   // the tab only slides in; it does not take over the screen
            if (pane == INTERACTION) scrollY = 0;
        } else {
            interactionTitle = shop != null ? "shop" : "chest";
            if (pane == INTERACTION) scrollY = 0;
            else openPane(INTERACTION);
        }
    }

    private void applyLanguage(String culture) {
        if (culture != null && !culture.isEmpty()) gameCulture = culture;
        String choice = prefs.getString("lang", "auto");
        Strings.setLanguage(Strings.Language.fromCulture(choice.equals("auto") ? gameCulture : choice));
    }

    private JSONArray slotData(int group, int index) {
        if (latest == null) return null;
        if (group == 0) {
            JSONArray inventory = latest.optJSONArray("inventory");
            return inventory == null ? null : inventory.optJSONArray(index);
        }
        if (group == 6) return latest.optJSONArray("trash");
        if (group == 7) {
            JSONObject shop = shop();
            JSONArray items = shop == null ? null : shop.optJSONArray("items");
            return items == null ? null : items.optJSONArray(index);
        }
        if (group == 5) {
            JSONObject chest = latest.optJSONObject("chest");
            JSONArray items = chest == null ? null : chest.optJSONArray("items");
            return items == null ? null : items.optJSONArray(index);
        }
        JSONArray equipment = latest.optJSONArray("equipment");
        JSONArray list = equipment == null ? null : equipment.optJSONArray(group - 1);
        return list == null ? null : list.optJSONArray(index);
    }

    private static int typeOf(JSONArray data) { return data == null ? 0 : data.optInt(0); }

    /** Same world -> same cache folder, so the map survives restarts of the app and the game. */
    private String worldKey(JSONObject env) {
        if (env == null) return "";
        int id = env.optInt("worldId");
        String name = env.optString("worldName");
        if (id != 0 || !name.isEmpty()) return "w" + id + "_" + name;
        return env.optInt("worldW") > 0 ? "x" + env.optInt("worldW") + "_" + env.optInt("worldH") : "";
    }

    private void updateMap(JSONObject map, JSONArray position, JSONObject env) {
        if (map == null || position == null) return;
        int cols = map.optInt("width");
        int rows = map.optInt("height");
        JSONArray data = map.optJSONArray("pixels");
        if (cols < 1 || rows < 1 || data == null || data.length() != cols * rows) return;
        int[] colors = new int[cols * rows];
        for (int index = 0; index < colors.length; index++) {
            int rgb = data.optInt(index);
            colors[index] = rgb == 0 ? 0 : 0xff000000 | rgb;
        }
        int left = map.optInt("left");
        int top = map.optInt("top");
        int step = Math.max(1, map.optInt("step", 1));
        String world = worldKey(env);
        if (!world.isEmpty()) mapCache.setWorld(world);
        // merge into the permanent cache and show everything known around here, not only what was just sampled
        mapCache.put(left, top, step, cols, rows, colors);
        int eCols = cols * 3;
        int eRows = rows * 3;
        int eLeft = left - cols * step;
        int eTop = top - rows * step;
        int[] merged = mapCache.hasWorld() ? mapCache.compose(eLeft, eTop, step, eCols, eRows) : null;
        if (merged == null) {
            merged = colors;
            eCols = cols;
            eRows = rows;
            eLeft = left;
            eTop = top;
        }
        if (mapBitmap == null || mapBitmap.getWidth() != eCols || mapBitmap.getHeight() != eRows) {
            mapBitmap = Bitmap.createBitmap(eCols, eRows, Bitmap.Config.ARGB_8888);
        }
        mapBitmap.setPixels(merged, 0, eCols, 0, 0, eCols, eRows);
        mapLeft = eLeft;
        mapTop = eTop;
        mapStep = step;
        mapCols = eCols;
        mapRows = eRows;
        requestCols = cols;
        requestRows = rows;
        playerX = position.optInt(0);
        playerY = position.optInt(1);
        mapWanted = false;
        lastMapResponse = SystemClock.uptimeMillis();
        if (follow) {
            viewCx = playerX;
            viewCy = playerY;
        }
    }

    /** Before the first answer of the game: show what the permanent cache already knows around the player. */
    private void showCachedMap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastCacheTry < 700 || !mapCache.hasWorld() || mapCache.count() == 0 || (playerX == 0 && playerY == 0)) return;
        lastCacheTry = now;
        int step = desiredStep();
        int cols = 192;
        int rows = 144;
        int left = playerX - cols * step / 2;
        int top = playerY - rows * step / 2;
        int[] merged = mapCache.compose(left, top, step, cols, rows);
        boolean any = false;
        for (int value : merged) if (value != 0) { any = true; break; }
        if (!any) return;
        mapBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        mapBitmap.setPixels(merged, 0, cols, 0, 0, cols, rows);
        mapLeft = left;
        mapTop = top;
        mapStep = step;
        mapCols = cols;
        mapRows = rows;
        requestCols = cols;
        requestRows = rows;
        if (follow) {
            viewCx = playerX;
            viewCy = playerY;
        }
    }

    /** Called when the app leaves the screen: write the map cache. */
    void saveState() {
        mapCache.flush();
    }

    private void changeZoom(int offset) {
        targetScale = Math.max(0.32f, Math.min(16f, targetScale * (offset < 0 ? 1.7f : 1 / 1.7f)));
    }

    private void openPane(int target) {
        if (target == pane) return;
        previousPane = pane;
        pane = target;
        sound("tick");
        paneChangedAt = SystemClock.uptimeMillis();
        scrollY = 0;
        listScroll = 0;
        chosenGroup = -1;
        sheetOpen = false;
        revision++;
        refresh.run();
        postInvalidate();
    }

    private void openSheet(int group, int index) {
        if (typeOf(slotData(group, index)) == 0) return;
        sheetOpen = true;
        sound("open");
        sheetGroup = group;
        sheetIndex = index;
        sheetInfo = null;
        infoWanted = true;
        splitMode = 0;
        trashArmedAt = 0;
        refresh.run();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        int w = getWidth();
        int h = getHeight();
        float s = h / 1080f;
        hits.clear();
        scene.setPlayer(playerX, playerY);
        scene.draw(canvas, w, h, now, sprites);
        fill.setColor(0x38000814);
        canvas.drawRect(0, 0, w, h, fill);
        animateValues();
        watchChanges();

        drawTopBar(canvas, w, s);
        drawTabs(canvas, w, s);
        float contentTop = 164 * s;
        float contentBottom = h - 290 * s;
        viewHeight = contentBottom - contentTop;
        neededHeight = 0;
        canvas.save();
        canvas.clipRect(0, contentTop, w, contentBottom);
        int paneHits = hits.size();
        float slide = Math.min(1f, (now - paneChangedAt) / 220f);
        float eased = 1 - (1 - slide) * (1 - slide);
        if (slide < 1f) {
            float direction = pane > previousPane ? 1 : -1;
            canvas.save();
            canvas.translate(-direction * w * eased, 0);
            ArrayList<Hit> saved = new ArrayList<>(hits);
            drawPaneBody(canvas, previousPane, w, s, contentTop, contentBottom);
            hits.clear();
            hits.addAll(saved);
            canvas.restore();
            canvas.save();
            canvas.translate(direction * w * (1 - eased), 0);
            drawPaneBody(canvas, pane, w, s, contentTop, contentBottom);
            canvas.restore();
        } else {
            drawPaneBody(canvas, pane, w, s, contentTop, contentBottom);
        }
        for (int index = hits.size() - 1; index >= paneHits; index--) {
            if (slide < 1f || !hits.get(index).rect.intersect(0, contentTop, w, contentBottom)) hits.remove(index);
        }
        canvas.restore();
        drawStatus(canvas, w, h, s);
        drawHotbar(canvas, w, h, s, now);
        drawDrag(canvas, s);
        drawEdges(canvas, w, h, s);
        if (latest == null) {
            drawCentered(canvas, Strings.get("open_world"), w / 2f, h / 2f, 34 * s, INK);
        } else if (chatOpen) {
            drawChat(canvas, w, h, s);
        } else if (recipeViewOpen) {
            drawRecipeView(canvas, w, h, s);
        } else if (sheetOpen) {
            drawSheet(canvas, w, h, s);
        } else if (talking() && shop() == null && chest() == null) {
            drawDialogue(canvas, w, s, contentTop, contentBottom);
        }
        if (infoOpen && !infoLine().isEmpty()) drawInfoPicker(canvas, w, h, s);
        if (languagePicker) drawLanguagePicker(canvas, w, h, s);
        drawFlashes(canvas, w, h);
        postInvalidateDelayed(33);
    }

    private void animateValues() {
        if (latest == null) return;
        JSONArray hp = latest.optJSONArray("hp");
        JSONArray mana = latest.optJSONArray("mana");
        float hpTarget = hp == null ? 0 : hp.optInt(0);
        float manaTarget = mana == null ? 0 : mana.optInt(0);
        shownHp += (hpTarget - shownHp) * 0.18f;
        shownMana += (manaTarget - shownMana) * 0.18f;
        if (Math.abs(shownHp - hpTarget) < 0.5f) shownHp = hpTarget;
        if (Math.abs(shownMana - manaTarget) < 0.5f) shownMana = manaTarget;
    }

    // screen-edge flashes: 0 damage (red), 1 healing (green), 2 mana (blue)
    private static final int[] FLASH_COLOR = {0xff2a14, 0x3cff5a, 0x3a78ff};
    private final long[] flashAt = new long[3];
    private final float[] flashPower = new float[3];
    private float lastHp = -1, lastMana = -1;
    private android.graphics.RadialGradient[] flashShader = new android.graphics.RadialGradient[3];
    private int flashW, flashH;

    private void flash(int channel, float power) {
        flashAt[channel] = SystemClock.uptimeMillis();
        flashPower[channel] = Math.max(0.25f, Math.min(1f, power));
    }

    private void watchChanges() {
        if (latest == null) return;
        JSONArray hp = latest.optJSONArray("hp");
        JSONArray mana = latest.optJSONArray("mana");
        if (hp != null) {
            float value = hp.optInt(0);
            float max = Math.max(1, hp.optInt(1));
            if (lastHp >= 0 && value < lastHp && lastHp - value >= 3) flash(0, 0.35f + 2.2f * (lastHp - value) / max);
            else if (lastHp >= 0 && lastHp > 0 && value - lastHp >= Math.max(20, max * 0.08f)) flash(1, 0.3f + 1.6f * (value - lastHp) / max);
            lastHp = value;
        }
        if (mana != null) {
            float value = mana.optInt(0);
            float max = Math.max(1, mana.optInt(1));
            if (lastMana >= 0 && (lastMana - value >= 15 || value - lastMana >= Math.max(40, max * 0.2f))) {
                flash(2, 0.3f + 1.6f * Math.abs(value - lastMana) / max);
            }
            lastMana = value;
        }
    }

    private void drawFlashes(Canvas canvas, int w, int h) {
        long now = SystemClock.uptimeMillis();
        if (flashShader[0] == null || flashW != w || flashH != h) {
            flashW = w;
            flashH = h;
            float radius = (float) Math.hypot(w, h) * 0.5f;
            for (int channel = 0; channel < 3; channel++) {
                int rgb = FLASH_COLOR[channel];
                flashShader[channel] = new android.graphics.RadialGradient(w / 2f, h / 2f, radius,
                    new int[]{rgb, rgb, 0xff000000 | rgb}, new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            float age = (now - flashAt[channel]) / 420f;
            if (flashAt[channel] == 0 || age >= 1f) continue;
            float strength = flashPower[channel] * (1f - age) * (1f - age);
            fill.setShader(flashShader[channel]);
            fill.setAlpha((int) (210 * strength));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }
    }

    private boolean heartsMode;
    private long flipAt;

    private void drawBuffs(Canvas canvas, int w, int h, float s) {
        JSONArray buffs = latest == null ? null : latest.optJSONArray("buffs");
        if (buffs == null || buffs.length() == 0) return;
        float size = 58 * s;
        float gap = 8 * s;
        float x = 24 * s;
        float y = h - 288 * s;
        for (int index = 0; index < buffs.length() && x + size < w - 20 * s; index++) {
            JSONArray buff = buffs.optJSONArray(index);
            if (buff == null) continue;
            boolean bad = buff.optBoolean(2);
            Bitmap icon = sprites.get("buff:" + buff.optInt(0));
            if (icon != null) {
                float fit = Math.min(size / icon.getWidth(), size / icon.getHeight());
                drawSprite(canvas, icon, x + (size - icon.getWidth() * fit) / 2, y + (size - icon.getHeight() * fit) / 2,
                    icon.getWidth() * fit, icon.getHeight() * fit);
            }
            int seconds = buff.optInt(1) / 60;
            if (seconds > 0 && seconds < 3600) {
                String time = seconds >= 60 ? (seconds / 60) + "m" : seconds + "s";
                drawText(canvas, time, x + size - measure(time, 18 * s) - 3 * s, y + size - 22 * s, 18 * s, bad ? 0xffffb0b0 : INK, true);
            }
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "buff_" + index;
            hit.rect.set(x, y, x + size, y + size);
            hits.add(hit);
            x += size + gap;
        }
    }

    private static String clock(JSONArray time) {
        int hour = time.optInt(0);
        int minute = time.optInt(1);
        String suffix = hour >= 12 ? "PM" : "AM";
        int shown = hour % 12 == 0 ? 12 : hour % 12;
        return shown + ":" + (minute < 10 ? "0" : "") + minute + " " + suffix;
    }

    /** One line made from the info accessories the player carries (watch, depth meter, compass, weather radio). */
    private String infoLine() {
        JSONObject info = latest == null ? null : latest.optJSONObject("info");
        if (info == null) return "";
        JSONArray accessories = info.optJSONArray("accessories");
        if (accessories != null && accessories.length() > 0) {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < accessories.length(); index++) {
                JSONArray entry = accessories.optJSONArray(index);
                if (entry == null) continue;
                if (text.length() > 0) text.append("  ·  ");
                text.append(entry.optString(1));
            }
            return text.toString();
        }
        StringBuilder line = new StringBuilder();
        JSONArray time = info.optJSONArray("time");
        if (time != null) line.append(clock(time));
        if (info.has("depth")) {
            int depth = info.optInt("depth");
            if (line.length() > 0) line.append("  ·  ");
            line.append(Math.abs(depth)).append(' ').append(Strings.get("ft")).append(' ').append(Strings.get(depth >= 0 ? "below" : "above"));
            if (info.has("layer")) line.append(" · ").append(Strings.get(info.optString("layer")));
        }
        if (info.has("compass")) {
            int compass = info.optInt("compass");
            if (line.length() > 0) line.append("  ·  ");
            line.append(Math.abs(compass)).append(' ').append(Strings.get("ft")).append(' ').append(Strings.get(compass >= 0 ? "east" : "west"));
        }
        if (info.has("wind")) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(Strings.get("wind")).append(' ').append(Math.abs(info.optInt("wind")));
            if (info.optBoolean("rain")) line.append("  ").append(Strings.get("rain_on"));
        }
        return line.toString();
    }

    private void drawIcons(Canvas canvas, float x, float cy, float width, float value, float pointsPerIcon,
                           int count, int goldCount, boolean mana, boolean fancy, float s) {
        String key = mana ? "Mana" : "Heart";
        Bitmap icon = sprites.get("ui:" + (fancy ? "Fancy" : "") + key);
        Bitmap gold = mana || goldCount == 0 ? null : sprites.get("ui:" + (fancy ? "Fancy" : "") + "Heart2");
        if (icon == null && fancy) icon = sprites.get("ui:" + key);
        if (gold == null && goldCount > 0 && fancy) gold = sprites.get("ui:Heart2");
        int columns = Math.min(10, count);
        int rows = (count + columns - 1) / columns;
        float step = Math.min((mana ? 24 : 23) * s, width / columns);
        float rowStep = 25 * s;
        float top = cy - (rows - 1) * rowStep / 2;
        for (int index = 0; index < count; index++) {
            int row = index / columns;
            int col = index % columns;
            float cx = x + col * step + step / 2;
            float py = top + row * rowStep;
            float filled = Math.max(0, Math.min(1, value / pointsPerIcon - index));
            Bitmap sprite = index < goldCount && gold != null ? gold : icon;
            if (fancy) {
                int inRow = Math.min(columns, count - row * columns);
                drawIconBacking(canvas, cx, py, col, inRow, mana, step);
            }
            if (sprite == null) {
                fill.setColor(mana ? 0xff7baaff : 0xffe0414a);
                fill.setAlpha(30 + Math.round(225 * filled));
                canvas.drawCircle(cx, py, step * .3f, fill);
                fill.setAlpha(255);
                continue;
            }
            float w = step * .90f * (.75f + .25f * filled);
            float h = w * sprite.getHeight() / sprite.getWidth();
            pixels.setAlpha(30 + Math.round(225 * filled));
            drawSprite(canvas, sprite, cx - w / 2, py - h / 2, w, h);
            pixels.setAlpha(255);
        }
    }

    private void drawIconBacking(Canvas canvas, float cx, float cy, int index, int count, boolean mana, float step) {
        String name = mana
            ? (count == 1 ? "Single" : index == 0 ? "Top" : index == count - 1 ? "Bottom" : "Mid")
            : (count == 1 ? "SingleFancy" : index == 0 ? "Left" : index == count - 1 ? "Right" : "Mid");
        Bitmap plate = sprites.get("ui:Fancy" + (mana ? "Mana" : "Heart") + "Backing" + name);
        if (plate == null) return;
        float unit = step / (mana ? 22f : 24f);
        float w = unit * plate.getWidth();
        float h = unit * plate.getHeight();
        canvas.save();
        if (mana) canvas.rotate(-90, cx, cy);
        drawSprite(canvas, plate, cx - w / 2, cy - h / 2, w, h);
        canvas.restore();
    }

    private void drawTopBar(Canvas canvas, int w, float s) {
        panel(canvas, 12 * s, 6 * s, w - 12 * s, 86 * s, 0xbb101a38, 0xff5a6ea8, s);
        JSONArray hp = latest == null ? null : latest.optJSONArray("hp");
        JSONArray mana = latest == null ? null : latest.optJSONArray("mana");
        long nowMs = SystemClock.uptimeMillis();
        float phase = Math.min(1f, (nowMs - flipAt) / 520f);
        boolean flipping = phase < 1f;
        boolean showHearts = flipping && phase < 0.5f ? !heartsMode : heartsMode;
        boolean fancy = latest == null || latest.optInt("hudStyle", 1) != 0;
        float flipScale = flipping ? Math.max(0.04f, (float) Math.abs(Math.cos(Math.PI * phase))) : 1f;
        canvas.save();
        canvas.scale(1f, flipScale, 250 * s, 28 * s);
        if (showHearts) {
            int max = hp == null ? 100 : Math.max(20, hp.optInt(1));
            int base = hp == null ? max : Math.max(20, hp.optInt(2, max));
            int fruit = Math.max(0, Math.min(20, (base - 400) / 5));
            int originalCount = fruit > 0 ? Math.max(1, base / (20 + fruit / 4)) : Math.max(1, base / 20);
            float points = (fruit > 0 ? base / 20f : 20f) + Math.max(0, max - base) / originalCount;
            int count = Math.min(20, Math.max(1, (int) (max / points)));
            drawIcons(canvas, 30 * s, 29 * s, 460 * s, shownHp, points, count, fruit, false, fancy, s);
        } else {
            gameBar(canvas, 30 * s, 26 * s, 450 * s, 0.95f * s, shownHp, hp == null ? 1 : hp.optInt(1), "BarHeart", "ui:Heart",
                0xffe0414a, 0xff5a1018, Strings.get("hp") + " " + (int) shownHp + " / " + (hp == null ? 0 : hp.optInt(1)), s);
        }
        canvas.restore();
        canvas.save();
        canvas.scale(1f, flipScale, 250 * s, 64 * s);
        if (showHearts) {
            int count = mana == null ? 1 : Math.max(1, Math.min(20, mana.optInt(1) / 20));
            drawIcons(canvas, 30 * s, 68 * s, 460 * s, shownMana, 20, count, 0, true, fancy, s);
        } else {
            gameBar(canvas, 30 * s, 62 * s, 450 * s, 0.95f * s, shownMana, mana == null ? 1 : mana.optInt(1), "BarMana", "ui:Mana",
                0xff5a8cff, 0xff142a66, Strings.get("mana") + " " + (int) shownMana + " / " + (mana == null ? 0 : mana.optInt(1)), s);
        }
        canvas.restore();
        Hit flip = new Hit();
        flip.type = 2;
        flip.name = "barflip";
        flip.rect.set(24 * s, 8 * s, 500 * s, 84 * s);
        hits.add(flip);
        JSONObject env = latest == null ? null : latest.optJSONObject("env");
        if (env != null) {
            String line = Strings.get(env.optBoolean("day", true) ? "day" : "night");
            JSONArray zones = env.optJSONArray("zones");
            if (zones != null && zones.length() > 0) line += "  ·  " + Strings.zone(zones.optString(0));
            drawText(canvas, line, 510 * s, 12 * s, 22 * s, MUTED, true);
        }
        String info = infoLine();
        if (!info.isEmpty()) {
            float width = Math.max(24 * s, w - 850 * s);
            infoText.setTextSize(24 * s);
            String preview = android.text.TextUtils.ellipsize(info, infoText, width - 21 * s,
                android.text.TextUtils.TruncateAt.END).toString();
            drawText(canvas, preview + " ›", 510 * s, 48 * s, fitText(preview + " ›", 19 * s, width), 0xffffe9a8, true);
            addButtonHit("info", 506 * s, 42 * s, 510 * s + width, 84 * s);
        }
        float bx = w - 30 * s;
        bx = button(canvas, "lang", Strings.language().code.toUpperCase(java.util.Locale.ROOT),
            bx - 88 * s, 20 * s, 88 * s, 52 * s, 0xff2a3a6a, s);
        button(canvas, "chat", "", bx - 200 * s - 12 * s - 64 * s, 20 * s, 64 * s, 52 * s, chatOpen ? 0xff347461 : 0xff2a3a6a, s);
        boolean can = latest != null && latest.optBoolean("hudAvailable");
        button(canvas, "hud", Strings.get(hideRequested ? "show_hud" : "hide_hud"), bx - 200 * s, 20 * s, 200 * s, 52 * s,
            can ? (hideRequested ? 0xff347461 : 0xff2a3a6a) : 0xff1a2238, s);
    }

    /** HP/mana bar built like the game does it: 24-unit backing, 12-unit fill, ornate end cap at its natural size. */
    private void gameBar(Canvas canvas, float x, float cy, float width, float u, float value, float max, String set,
                         String icon, int color, int back, String label, float s) {
        Bitmap left = sprites.get("ui:" + set + "BackingLeft");
        Bitmap mid = sprites.get("ui:" + set + "BackingMid");
        Bitmap right = sprites.get("ui:" + set + "BackingRight");
        Bitmap fillBar = sprites.get("ui:" + set);
        Bitmap iconBitmap = sprites.get(icon);
        float inset = 0;
        if (iconBitmap != null) {
            float size = 24 * u * 1.15f;
            drawSprite(canvas, iconBitmap, x, cy - size / 2, size, size);
            inset = size + 8 * s;
        }
        float fraction = Math.max(0, Math.min(1, value / Math.max(1, max)));
        float bx = x + inset;
        float bw = width - inset;
        float barTop = cy - 12 * u;
        float inner0;
        float inner1;
        if (left != null && mid != null && right != null && fillBar != null) {
            float capW = right.getWidth() * u;
            float capH = right.getHeight() * u;
            drawSprite(canvas, left, bx, barTop, left.getWidth() * u, 24 * u);
            drawSprite(canvas, mid, bx + left.getWidth() * u, barTop, bw - left.getWidth() * u - capW * 0.5f, 24 * u);
            drawSprite(canvas, right, bx + bw - capW, cy - capH / 2, capW, capH);
            inner0 = bx + left.getWidth() * u;
            inner1 = bx + bw - capW + 12 * u;
            canvas.save();
            canvas.clipRect(inner0, cy - 6 * u, inner0 + (inner1 - inner0) * fraction, cy + 6 * u);
            drawSprite(canvas, fillBar, inner0, cy - 6 * u, inner1 - inner0, 12 * u);
            canvas.restore();
        } else {
            inner0 = bx;
            inner1 = bx + bw;
            fill.setColor(back);
            canvas.drawRect(inner0, cy - 8 * u, inner1, cy + 8 * u, fill);
            fill.setColor(color);
            canvas.drawRect(inner0, cy - 8 * u, inner0 + (inner1 - inner0) * fraction, cy + 8 * u, fill);
            stroke.setColor(0xff000000);
            stroke.setStrokeWidth(3 * s);
            canvas.drawRect(inner0, cy - 8 * u, inner1, cy + 8 * u, stroke);
        }
        drawText(canvas, label, inner0 + 12 * s, cy - 10 * u, 17 * u, INK, true);
    }

    private float button(Canvas canvas, String name, String label, float x, float y, float width, float height, int color, float s) {
        panel(canvas, x, y, x + width, y + height, color, 0xff8ea2d8, s);
        buttonLabel(canvas, name, label, x, y, width, height, Math.min(34 * s, height * 0.5f), s);
        addButtonHit(name, x, y, x + width, y + height);
        return x - 12 * s;
    }

    private void addButtonHit(String name, float left, float top, float right, float bottom) {
        Hit hit = new Hit();
        hit.type = 2;
        hit.name = name;
        hit.rect.set(left, top, right, bottom);
        hits.add(hit);
    }

    private float fitText(String label, float size, float width) {
        return Math.min(size, size * width / Math.max(1f, measure(label, size)));
    }

    private void buttonLabel(Canvas canvas, String name, String label, float x, float y, float width, float height, float size, float s) {
        String icon = buttonSprite(name);
        String symbol = icon == null ? buttonSymbol(name) : null;
        boolean decorated = icon != null || symbol != null;
        float iconSize = !decorated ? 0 : Math.min(32 * s, height * .53f);
        float gap = !decorated ? 0 : 6 * s;
        size = fitText(label, size, Math.max(1, width - iconSize - gap - 12 * s));
        float labelWidth = measure(label, size);
        float start = x + (width - iconSize - gap - labelWidth) / 2;
        if (icon != null) {
            Bitmap sprite = sprites.get(icon);
            if (sprite == null) drawCentered(canvas, "◆", start + iconSize / 2, y + height / 2, iconSize * .45f, GOLD);
            else {
                float fit = iconSize / Math.max(sprite.getWidth(), sprite.getHeight());
                float sw = sprite.getWidth() * fit;
                float sh = sprite.getHeight() * fit;
                drawSprite(canvas, sprite, start + (iconSize - sw) / 2, y + (height - sh) / 2, sw, sh);
            }
        }
        if (symbol != null) drawSymbol(canvas, symbol, start + iconSize / 2, y + height / 2, iconSize * 0.8f);
        drawCentered(canvas, label, start + iconSize + gap + labelWidth / 2, y + height / 2, size, INK);
    }

    private String buttonSprite(String name) {
        switch (name) {
            case "inventory": case "chest": case "chest_loot": case "chest_deposit": return "item:48";
            case "equipment": return "item:89";
            case "craft": return "item:36";
            case "map": case "center": return "item:393";
            case "people": return "npchead:1";
            case "shop": case "shop_open": case "shop_buy": case "sheet_sell": return "item:72";
            case "hud": return "ui:Heart";
            case "lang": return "item:149";
            case "sort": case "chest_sort": return "item:9";
            case "chest_quick": case "chest_restock": case "quickstack": return "item:3509";
            case "sheet_trash": return "ui:Trash";
            case "sheet_use": return "item:28";
            case "sheet_equip": return "item:89";
            case "sheet_deposit": return "item:48";
            case "sheet_recipes": case "rv_makes": case "ctab_0": return "item:36";
            case "rv_uses": return "item:3";
            case "ctab_2": return "npchead:1";
            case "filter_0": return "item:48";
            case "filter_1": return "item:4";
            case "filter_2": return "item:1";
            case "filter_3": return "item:89";
            case "filter_4": return "item:28";
            case "filter_5": return "item:3";
            case "pvp": return "item:4";
            default: return null;
        }
    }

    /** Buttons without a fitting game sprite get a small symbol drawn in the same slot. */
    private String buttonSymbol(String name) {
        if (name.startsWith("house_check")) return "magnifier";
        if (name.startsWith("house_move")) return "house";
        if (name.equals("ctab_1") || name.startsWith("banner_")) return "flag";
        if (name.equals("chat")) return "bubble";
        switch (name) {
            case "sheet_favorite": return "star";
            case "sheet_drop": return "arrowup";
            case "sheet_take": return "arrowdown";
            case "sheet_split1": case "sheet_split2": return "split";
            default: return null;
        }
    }

    private final Path symbolPath = new Path();

    private void drawSymbol(Canvas canvas, String symbol, float cx, float cy, float size) {
        float r = size / 2;
        fill.setColor(GOLD);
        stroke.setColor(GOLD);
        stroke.setStrokeWidth(Math.max(2f, size * 0.1f));
        symbolPath.reset();
        switch (symbol) {
            case "flag":
                canvas.drawLine(cx - r * 0.7f, cy - r, cx - r * 0.7f, cy + r, stroke);
                symbolPath.moveTo(cx - r * 0.7f, cy - r);
                symbolPath.lineTo(cx + r, cy - r * 0.45f);
                symbolPath.lineTo(cx - r * 0.7f, cy + r * 0.1f);
                symbolPath.close();
                canvas.drawPath(symbolPath, fill);
                break;
            case "magnifier":
                stroke.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.6f, stroke);
                canvas.drawLine(cx + r * 0.25f, cy + r * 0.25f, cx + r * 0.95f, cy + r * 0.95f, stroke);
                break;
            case "house":
                symbolPath.moveTo(cx - r, cy);
                symbolPath.lineTo(cx, cy - r);
                symbolPath.lineTo(cx + r, cy);
                symbolPath.lineTo(cx + r * 0.7f, cy);
                symbolPath.lineTo(cx + r * 0.7f, cy + r);
                symbolPath.lineTo(cx - r * 0.7f, cy + r);
                symbolPath.lineTo(cx - r * 0.7f, cy);
                symbolPath.close();
                canvas.drawPath(symbolPath, fill);
                break;
            case "star":
                drawStar(canvas, cx - r, cy - r, size);
                break;
            case "arrowup":
            case "arrowdown": {
                float dir = symbol.equals("arrowup") ? -1 : 1;
                symbolPath.moveTo(cx, cy + dir * r);
                symbolPath.lineTo(cx - r * 0.9f, cy - dir * r * 0.1f);
                symbolPath.lineTo(cx - r * 0.35f, cy - dir * r * 0.1f);
                symbolPath.lineTo(cx - r * 0.35f, cy - dir * r);
                symbolPath.lineTo(cx + r * 0.35f, cy - dir * r);
                symbolPath.lineTo(cx + r * 0.35f, cy - dir * r * 0.1f);
                symbolPath.lineTo(cx + r * 0.9f, cy - dir * r * 0.1f);
                symbolPath.close();
                canvas.drawPath(symbolPath, fill);
                break;
            }
            case "bubble":
                canvas.drawRoundRect(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.4f, r * 0.3f, r * 0.3f, fill);
                symbolPath.moveTo(cx - r * 0.5f, cy + r * 0.3f);
                symbolPath.lineTo(cx - r * 0.7f, cy + r * 0.95f);
                symbolPath.lineTo(cx, cy + r * 0.3f);
                symbolPath.close();
                canvas.drawPath(symbolPath, fill);
                break;
            case "split":
                canvas.drawRect(cx - r, cy - r * 0.5f, cx - r * 0.1f, cy + r * 0.5f, fill);
                canvas.drawRect(cx + r * 0.1f, cy - r * 0.5f, cx + r, cy + r * 0.5f, fill);
                break;
            default:
                break;
        }
        stroke.setStyle(Paint.Style.STROKE);
    }

    private void drawTabs(Canvas canvas, int w, float s) {
        float target = interaction.isEmpty() ? 0f : 1f;
        interactionWidth += (target - interactionWidth) * .30f;
        if (Math.abs(target - interactionWidth) < .01f) interactionWidth = target;
        float tabWidth = (w - 36 * s) / (PANES.length + interactionWidth);
        float y = 92 * s;
        float height = 62 * s;
        for (int index = 0; index <= INTERACTION; index++) {
            float width = index == INTERACTION ? tabWidth * interactionWidth : tabWidth;
            if (width <= 12 * s) continue;
            float x = 18 * s + index * tabWidth;
            boolean active = index == pane;
            panel(canvas, x + 4 * s, y, x + width - 4 * s, y + height, active ? 0xdd23457a : 0x99101a38,
                active ? GOLD : 0xff5a6ea8, s);
            String title = index == INTERACTION ? interactionTitle : PANES[index];
            if (width > 50 * s) buttonLabel(canvas, title, Strings.get(title), x + 4 * s, y, width - 8 * s, height, 28 * s, s);
            if (index == INTERACTION && interaction.isEmpty()) continue;
            Hit hit = new Hit();
            hit.type = 1;
            hit.index = index;
            hit.rect.set(x, y, x + width, y + height);
            hits.add(hit);
        }
    }

    private void drawPaneBody(Canvas canvas, int which, int w, float s, float top, float bottom) {
        if (latest == null && which != 3) return;
        if (which == INTERACTION && interactionTitle.equals("nearby")) drawNearby(canvas, w, s, top);
        else if (which == 0 || which == INTERACTION) drawInventory(canvas, w, s, top, which == INTERACTION);
        else if (which == 1) drawEquipment(canvas, w, s, top);
        else if (which == 2) drawCraft(canvas, w, s, top, bottom);
        else if (which == 3) drawMap(canvas, w, s, top, bottom);
        else drawPeople(canvas, w, s, top);
    }

    private float slotSize(int w, float s, int columns) {
        return Math.min(slotCap > 0 ? slotCap : 100 * s, (w - 40 * s) / columns - 6 * s);
    }

    private void slotRow(Canvas canvas, int w, float s, float y, int group, int start, int count, String[] labels, int columns) {
        float size = slotSize(w, s, columns);
        float total = columns * (size + 6 * s) - 6 * s;
        float x0 = (w - total) / 2;
        for (int offset = 0; offset < count; offset++) {
            drawSlotAt(canvas, x0 + offset * (size + 6 * s), y, size, group, start + offset, labels == null ? null : labels[offset]);
        }
    }

    private void drawInventory(Canvas canvas, int w, float s, float top, boolean interactionPane) {
        slotCap = interactionPane ? 0 : 92 * s;
        float size = slotSize(w, s, 10);
        float y = top + 8 * s - scrollY;
        if (!interactionPane) {
            // category chips: everything else in the grid is dimmed
            float chipGap = 8 * s;
            float chipWidth = (w - 60 * s - 5 * chipGap) / 6;
            for (int index = 0; index < 6; index++) {
                float cx = 30 * s + index * (chipWidth + chipGap);
                panel(canvas, cx, y, cx + chipWidth, y + 40 * s, index == inventoryFilter ? 0xff347461 : 0x99101a38,
                    index == inventoryFilter ? GOLD : 0xff5a6ea8, s);
                buttonLabel(canvas, "filter_" + index, Strings.get("filter_" + index), cx, y, chipWidth, 40 * s, 22 * s, s);
                Hit hit = new Hit();
                hit.type = 2;
                hit.name = "filter_" + index;
                hit.rect.set(cx, y, cx + chipWidth, y + 40 * s);
                hits.add(hit);
            }
            y += 48 * s;
        }
        if (interactionPane && shop() != null) y = drawShop(canvas, w, s, y, shop());
        else if (interactionPane && chest() != null) y = drawChest(canvas, w, s, y, chest());
        for (int row = 1; row < 5; row++) {
            slotRow(canvas, w, s, y, 0, row * 10, 10, null, 10);
            y += size + 6 * s;
        }
        drawText(canvas, Strings.get("coins_ammo"), 30 * s, y + 4 * s, 28 * s, MUTED, true);
        float bx = w - 30 * s;
        button(canvas, "sort", Strings.get("sort"), bx - 170 * s, y - 2 * s, 170 * s, 40 * s, 0xff2a3a6a, s);
        if (!interactionPane) {
            button(canvas, "quickstack", Strings.get("quick_stack"), bx - 170 * s - 12 * s - 250 * s, y - 2 * s, 250 * s, 40 * s,
                0xff2a3a6a, s);
        }
        y += 44 * s;
        slotRow(canvas, w, s, y, 0, 50, 8, null, 10);
        float trashX = (w - (10 * (size + 6 * s) - 6 * s)) / 2 + 9 * (size + 6 * s);
        JSONArray trash = slotData(6, 0);
        if (trash != null) drawSlotAt(canvas, trashX, y, size, 6, 0, null);
        if (trash != null && typeOf(trash) == 0) {
            Bitmap can = sprites.get("ui:Trash");
            if (can != null) {
                float fit = Math.min(size * 0.62f / can.getWidth(), size * 0.62f / can.getHeight());
                drawSprite(canvas, can, trashX + (size - can.getWidth() * fit) / 2, y + (size - can.getHeight() * fit) / 2,
                    can.getWidth() * fit, can.getHeight() * fit);
            } else {
                drawCentered(canvas, Strings.get("trash_slot"), trashX + size / 2, y + size / 2, 15 * s, 0x99b5c5d4);
            }
        }
        neededHeight = Math.max(neededHeight, y + size - top + scrollY);
        slotCap = 0;
    }

    private String chestTitle(JSONObject chest) {
        int id = chest.optInt("id");
        if (id == -2) return Strings.get("piggy");
        if (id == -3) return Strings.get("safe");
        if (id == -4) return Strings.get("forge");
        if (id == -5) return Strings.get("voidv");
        String name = chest.optString("name");
        return name.isEmpty() ? Strings.get("chest") : name;
    }

    /** Opened chest or bank, like the game's window: title, six buttons that run the game's own routines, the slots. */
    private float drawChest(Canvas canvas, int w, float s, float y, JSONObject chest) {
        float x = 30 * s;
        drawText(canvas, chestTitle(chest), x, y, 36 * s, GOLD, true);
        y += 50 * s;
        String[][] buttons = {{"c_loot", "loot"}, {"c_deposit", "deposit"}, {"c_quick", "quick"},
            {"c_restock", "restock"}, {"c_sort", "sort"}, {"c_close", "close"}};
        float gap = 10 * s;
        float bw = (w - 60 * s - 2 * gap) / 3;
        float bh = 76 * s;
        for (int index = 0; index < buttons.length; index++) {
            float bx = x + (index % 3) * (bw + gap);
            float by = y + (index / 3) * (bh + gap);
            button(canvas, "chest_" + buttons[index][1], Strings.get(buttons[index][0]), bx, by, bw, bh,
                index == 5 ? 0xff7a2a2f : 0xff2a3a6a, s);
        }
        y += 2 * bh + gap + 14 * s;
        JSONArray items = chest.optJSONArray("items");
        int count = items == null ? 0 : items.length();
        float size = slotSize(w, s, 10);
        for (int start = 0; start < count; start += 10) {
            slotRow(canvas, w, s, y, 5, start, Math.min(10, count - start), null, 10);
            y += size + 6 * s;
        }
        y += 6 * s;
        fill.setColor(0x55b5c5d4);
        canvas.drawRect(30 * s, y, w - 30 * s, y + 3 * s, fill);
        drawText(canvas, Strings.get("inventory"), x, y + 8 * s, 28 * s, MUTED, true);
        return y + 46 * s;
    }

    private float drawShop(Canvas canvas, int w, float s, float y, JSONObject shop) {
        float left = 30 * s;
        float width = w - 2 * left;
        String name = shop.optString("name", Strings.get("merchant"));
        drawText(canvas, name, left, y + 8 * s, fitText(name, 28 * s, width - 185 * s), GOLD, true);
        button(canvas, "shop_close", Strings.get("close_shop"), w - left - 170 * s, y, 170 * s, 46 * s, 0xff7a2a2f, s);
        y += 58 * s;
        JSONArray items = shop.optJSONArray("items");
        float size = slotSize(w, s, 10);
        float cell = size + 6 * s;
        float start = (w - (10 * cell - 6 * s)) / 2;
        int count = 0;
        for (int index = 0; items != null && index < items.length(); index++) {
            if (typeOf(items.optJSONArray(index)) == 0) continue;
            float x = start + (count % 10) * cell;
            float rowY = y + (count / 10) * cell;
            drawSlotAt(canvas, x, rowY, size, 7, index, null);
            hits.get(hits.size() - 1).type = 5;
            if (index == shopSelection) {
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(3 * s);
                canvas.drawRect(x, rowY, x + size, rowY + size, stroke);
            }
            count++;
        }
        y += ((count + 9) / 10) * cell + 8 * s;
        JSONArray item = slotData(7, shopSelection);
        if (typeOf(item) != 0) {
            String label = item.optString(4);
            JSONArray price = shopPrice();
            if (price != null) label += "  ·  " + formatShopPrice(price);
            drawText(canvas, label, left, y + 8 * s, fitText(label, 23 * s, width - 190 * s), INK, true);
            if (price != null) {
                button(canvas, "shop_buy", Strings.get("buy") + " ×1", w - left - 170 * s, y, 170 * s, 48 * s, 0xff2c6a3c, s);
            }
            y += 60 * s;
        }
        drawText(canvas, Strings.get("inventory"), left, y, 26 * s, MUTED, true);
        return y + 42 * s;
    }

    private JSONArray shopPrice() {
        JSONObject shop = shop();
        JSONArray prices = shop == null ? null : shop.optJSONArray("prices");
        return prices == null ? null : prices.optJSONArray(shopSelection);
    }

    private String formatShopPrice(JSONArray price) {
        long amount = price.optLong(0);
        if (price.optInt(1) == 3817) return amount + " × " + Strings.get("medals");
        return (amount >= 1000000 ? amount / 1000000 + " P  " : "")
            + (amount >= 10000 ? amount / 10000 % 100 + " G  " : "")
            + (amount >= 100 ? amount / 100 % 100 + " S  " : "") + amount % 100 + " C";
    }

    private String[] gearLabels() {
        return new String[]{Strings.get("head"), Strings.get("body"), Strings.get("legs"), Strings.get("accessory") + " 1",
            Strings.get("accessory") + " 2", Strings.get("accessory") + " 3", Strings.get("accessory") + " 4",
            Strings.get("accessory") + " 5", Strings.get("accessory") + " 6", Strings.get("accessory") + " 7"};
    }

    private void drawEquipment(Canvas canvas, int w, float s, float top) {
        float size = slotSize(w, s, 10) * 0.86f;
        float y = top + 4 * s - scrollY;
        // header: how much armour you wear, and the three armour sets (equipment loadouts) of the game
        {
            int defense = latest == null ? 0 : latest.optInt("defense");
            int loadout = latest == null ? 0 : latest.optInt("loadout");
            Bitmap shield = sprites.get("item:938");
            float bh = 52 * s;
            fill.setColor(0x66101a38);
            canvas.drawRect(20 * s, y, w - 20 * s, y + bh, fill);
            if (shield != null) drawSprite(canvas, shield, 30 * s, y + 4 * s, 44 * s, 44 * s);
            drawText(canvas, Strings.get("armor_total") + ": " + defense, 84 * s, y + 8 * s, 32 * s, GOLD, true);
            drawText(canvas, Strings.get("loadout"), w * 0.5f - 90 * s, y + 12 * s, 24 * s, MUTED, true);
            for (int set = 0; set < 3; set++) {
                float bx = w - 20 * s - (3 - set) * 76 * s;
                button(canvas, "loadout_" + set, Integer.toString(set + 1), bx, y + 2 * s, 68 * s, bh - 4 * s,
                    set == loadout ? 0xff347461 : 0xff2a3a6a, s);
            }
            y += bh + 8 * s;
        }
        String[] labels = gearLabels();
        String[] utility = {Strings.get("pet"), Strings.get("light"), Strings.get("minecart"), Strings.get("mount"), Strings.get("hook")};
        String[] titles = {"armor", "vanity", "dyes", "pets", "pet_dyes"};
        int[][] rows = {{1, 0, 10}, {1, 10, 10}, {2, 0, 10}, {3, 0, 5}, {4, 0, 5}};
        for (int row = 0; row < rows.length; row++) {
            drawText(canvas, Strings.get(titles[row]), 30 * s, y, 26 * s, MUTED, true);
            y += 32 * s;
            slotRow(canvas, w, s, y, rows[row][0], rows[row][1], rows[row][2], rows[row][0] >= 3 ? utility : labels, 10);
            y += size + 12 * s;
        }
        neededHeight = Math.max(neededHeight, y - top + scrollY);
    }

    /** One line about the last housing check / move-in the game reported. */
    private String housingText() {
        JSONObject result = latest == null ? null : latest.optJSONObject("housing");
        if (result == null) return "";
        if ("move".equals(result.optString("command"))) {
            return result.optString("npc") + ": " + Strings.get(result.optBoolean("ok") ? "house_moved"
                : result.optBoolean("enclosed", true) ? "house_failed" : "house_not_enclosed");
        }
        if (!result.optBoolean("enclosed")) return Strings.get("house_not_enclosed");
        JSONArray missing = result.optJSONArray("missing");
        if (missing != null && missing.length() > 0) {
            StringBuilder text = new StringBuilder(Strings.get("house_missing"));
            for (int index = 0; index < missing.length(); index++) {
                text.append(index == 0 ? " " : ", ").append(Strings.get("house_" + missing.optString(index)));
            }
            return text.toString();
        }
        return Strings.get(result.optBoolean("needs") ? "house_ok" : "house_unsuitable");
    }

    private static final int[] TEAM_COLORS = {0xff5a6578, 0xffd04848, 0xff3fa653, 0xff4a6ed8, 0xffd8c040, 0xffd868b8};

    /** The game's housing / players / team page: team and PvP, town NPCs with their homes, other players. */
    private void drawPeople(Canvas canvas, int w, float s, float top) {
        JSONObject people = latest == null ? null : latest.optJSONObject("people");
        float y = top + 8 * s - scrollY;
        int team = latest == null ? 0 : latest.optInt("team");
        boolean pvp = latest != null && latest.optBoolean("pvp");
        drawText(canvas, Strings.get("team"), 30 * s, y, 28 * s, GOLD, true);
        y += 40 * s;
        float gap = 8 * s;
        float bw = (w - 60 * s - 5 * gap) / 6;
        for (int index = 0; index < 6; index++) {
            float bx = 30 * s + index * (bw + gap);
            panel(canvas, bx, y, bx + bw, y + 62 * s, TEAM_COLORS[index], index == team ? GOLD : 0xff5a6ea8, s);
            String label = Strings.get("team_" + index);
            drawCentered(canvas, label, bx + bw / 2, y + 31 * s, fitText(label, 24 * s, bw - 10 * s), INK);
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "team_" + index;
            hit.rect.set(bx, y, bx + bw, y + 62 * s);
            hits.add(hit);
        }
        y += 74 * s;
        button(canvas, "pvp", "PvP: " + Strings.get(pvp ? "on" : "off"), 30 * s, y, 260 * s, 62 * s, pvp ? 0xff8a2a2f : 0xff2a3a6a, s);
        y += 82 * s;
        drawText(canvas, Strings.get("npcs_town"), 30 * s, y, 28 * s, GOLD, true);
        String housing = housingText();
        if (!housing.isEmpty()) drawText(canvas, housing, 30 * s + measure(Strings.get("npcs_town"), 28 * s) + 24 * s, y + 3 * s, 24 * s, 0xffffe9a8, true);
        y += 42 * s;
        JSONArray npcs = people == null ? null : people.optJSONArray("npcs");
        for (int index = 0; npcs != null && index < npcs.length(); index++) {
            JSONArray npc = npcs.optJSONArray(index);
            if (npc == null) continue;
            panel(canvas, 30 * s, y, w - 30 * s, y + 62 * s, 0x99101a38, 0xff5a6ea8, s);
            Bitmap head = npc.optInt(0) >= 0 ? sprites.get("npchead:" + npc.optInt(0)) : null;
            if (head != null) {
                float fit = Math.min(48 * s / head.getWidth(), 48 * s / head.getHeight());
                drawSprite(canvas, head, 44 * s, y + 31 * s - head.getHeight() * fit / 2, head.getWidth() * fit, head.getHeight() * fit);
            }
            drawText(canvas, npc.optString(1), 110 * s, y + 14 * s, 30 * s, INK, true);
            boolean homeless = npc.optBoolean(2);
            String status = Strings.get(homeless ? "homeless" : "has_home");
            drawText(canvas, status, w - 430 * s - measure(status, 24 * s), y + 17 * s, 24 * s, homeless ? 0xffffb06a : 0xff7be07b, true);
            int npcIndex = npc.optInt(8, -1);
            if (npcIndex >= 0) {
                button(canvas, "house_check_" + npcIndex, Strings.get("house_check"), w - 410 * s, y + 5 * s, 180 * s, 52 * s, 0xff2a3a6a, s);
                button(canvas, "house_move_" + npcIndex, Strings.get("house_move"), w - 220 * s, y + 5 * s, 180 * s, 52 * s, 0xff347461, s);
            }
            y += 70 * s;
        }
        if (npcs == null || npcs.length() == 0) {
            drawText(canvas, "-", 40 * s, y, 28 * s, MUTED, true);
            y += 44 * s;
        }
        y += 14 * s;
        drawText(canvas, Strings.get("players_list"), 30 * s, y, 28 * s, GOLD, true);
        y += 42 * s;
        JSONArray others = people == null ? null : people.optJSONArray("players");
        for (int index = 0; others != null && index < others.length(); index++) {
            JSONArray other = others.optJSONArray(index);
            if (other == null) continue;
            panel(canvas, 30 * s, y, w - 30 * s, y + 62 * s, 0x99101a38, 0xff5a6ea8, s);
            fill.setColor(TEAM_COLORS[Math.max(0, Math.min(5, other.optInt(1)))]);
            canvas.drawCircle(58 * s, y + 31 * s, 14 * s, fill);
            drawText(canvas, other.optString(0), 90 * s, y + 14 * s, 30 * s, INK, true);
            String tail = (other.optBoolean(2) ? "PvP  " : "") + other.optInt(3) + " / " + other.optInt(4);
            drawText(canvas, tail, w - 50 * s - measure(tail, 26 * s), y + 17 * s, 26 * s, MUTED, true);
            y += 70 * s;
        }
        if (others == null || others.length() == 0) {
            drawText(canvas, Strings.get("no_players"), 40 * s, y, 28 * s, MUTED, true);
            y += 44 * s;
        }
        neededHeight = Math.max(neededHeight, y - top + scrollY + 20 * s);
    }

    // ---- crafting, laid out like the game: list of craftable results on the left, details on the right

    private void drawCraft(Canvas canvas, int w, float s, float top, float bottom) {
        // sub-tabs: recipes, banners to claim, and the Guide's recipe lookup
        float tabGap = 10 * s;
        float tabWidth = (w - 40 * s - 2 * tabGap) / 3;
        for (int index = 0; index < 3; index++) {
            float tx = 20 * s + index * (tabWidth + tabGap);
            boolean active = craftTab == index;
            panel(canvas, tx, top + 4 * s, tx + tabWidth, top + 56 * s, active ? 0xdd23457a : 0x99101a38, active ? GOLD : 0xff5a6ea8, s);
            String label = Strings.get("ctab_" + index);
            if (index == 1) {
                JSONArray banners = latest == null ? null : latest.optJSONArray("banners");
                if (banners != null && banners.length() > 0) label += " (" + banners.length() + ")";
            }
            buttonLabel(canvas, "ctab_" + index, label, tx, top + 4 * s, tabWidth, 52 * s, 28 * s, s);
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "ctab_" + index;
            hit.rect.set(tx, top + 4 * s, tx + tabWidth, top + 56 * s);
            hits.add(hit);
        }
        if (craftTab == 1) drawBanners(canvas, w, s, top + 64 * s, bottom);
        else if (craftTab == 2) drawGuidePicker(canvas, w, s, top + 64 * s);
        else drawCraftRecipes(canvas, w, s, top + 64 * s, bottom);
    }

    // ---- nearby NPCs: the Guide's recipes and a way to start a conversation (and so a trade) with anyone close by

    private void drawNearby(Canvas canvas, int w, float s, float top) {
        JSONArray near = latest == null ? null : latest.optJSONArray("nearby");
        float y = top + 12 * s;
        for (int index = 0; near != null && index < near.length(); index++) {
            JSONArray npc = near.optJSONArray(index);
            if (npc == null) continue;
            panel(canvas, 30 * s, y, w - 30 * s, y + 96 * s, 0x99101a38, 0xff5a6ea8, s);
            Bitmap head = npc.optInt(3) >= 0 ? sprites.get("npchead:" + npc.optInt(3)) : null;
            if (head != null) {
                float fit = Math.min(64 * s / head.getWidth(), 64 * s / head.getHeight());
                drawSprite(canvas, head, 50 * s, y + 48 * s - head.getHeight() * fit / 2, head.getWidth() * fit, head.getHeight() * fit);
            }
            drawText(canvas, npc.optString(2), 130 * s, y + 14 * s, 32 * s, INK, true);
            String line = npc.optInt(4) + " " + Strings.get("tiles") + (npc.optBoolean(5) ? "  ·  " + Strings.get("trades_here") : "");
            drawText(canvas, line, 130 * s, y + 54 * s, 24 * s, MUTED, true);
            float bx = w - 50 * s - 210 * s;
            button(canvas, "near_talk_" + npc.optInt(0), Strings.get("talk"), bx, y + 22 * s, 210 * s, 52 * s, 0xff347461, s);
            if (npc.optInt(1) == 22) {
                button(canvas, "near_recipes", Strings.get("recipes_btn"), bx - 230 * s, y + 22 * s, 210 * s, 52 * s, 0xff2a3a6a, s);
            }
            y += 108 * s;
        }
        neededHeight = Math.max(neededHeight, y - top + scrollY + 20 * s);
    }

    // ---- chat with the on-screen keyboard, and the game's emote bubbles

    private static final String[][] KEYS = {
        {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"},
        {"1234567890", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"}};

    private static String cleanChat(String text) {
        return text.replaceAll("\\[c/[0-9a-fA-F]{1,8}:([^\\]]*)\\]", "$1").replaceAll("\\[[a-z]+(/[^:\\]]*)?:[^\\]]*\\]", "");
    }

    private void chatButton(String name) {
        if (name.startsWith("emote_")) {
            if (name.equals("emote_prev")) emotePage = Math.max(0, emotePage - 1);
            else if (name.equals("emote_next")) emotePage = Math.min(3, emotePage + 1);
            else {
                JSONObject command = op("act");
                TlProPanel.put(command, "kind", "emote");
                TlProPanel.put(command, "id", Integer.parseInt(name.substring(6)));
                send.accept(command);
            }
            return;
        }
        if (name.startsWith("key_")) {
            String key = name.substring(4);
            switch (key) {
                case "back": if (chatInput.length() > 0) chatInput.setLength(chatInput.length() - 1); break;
                case "space": if (chatInput.length() < 240) chatInput.append(' '); break;
                case "shift": keyShift = !keyShift; break;
                case "lang": keyLayout = 1 - keyLayout; break;
                case "send": sendChatInput(); break;
                default:
                    if (chatInput.length() < 240) {
                        chatInput.append(keyShift ? key.toUpperCase() : key);
                        keyShift = false;
                    }
                    break;
            }
            return;
        }
        switch (name) {
            case "chat_close": chatOpen = false; sound("close"); break;
            case "chat_mode_0": chatMode = 0; break;
            case "chat_mode_1": chatMode = 1; break;
            default: break;
        }
    }

    private void sendChatInput() {
        String text = chatInput.toString().trim();
        if (text.isEmpty()) return;
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", "say");
        TlProPanel.put(command, "text", text);
        send.accept(command);
        chatInput.setLength(0);
        lastChatAsk = 0;
    }

    private void drawChat(Canvas canvas, int w, int h, float s) {
        hits.clear();
        fill.setColor(0xcc02060f);
        canvas.drawRect(0, 0, w, h, fill);
        Hit outside = new Hit();
        outside.type = 8;
        outside.rect.set(0, 0, w, h);
        hits.add(outside);
        float left = 16 * s;
        float right = w - 16 * s;
        float top = 14 * s;
        float bottom = h - 14 * s;
        panel(canvas, left, top, right, bottom, 0xf0101a38, 0xff8ea2d8, s);
        float tabW = 220 * s;
        button(canvas, "chat_mode_0", Strings.get("chat_title"), left + 20 * s, top + 14 * s, tabW, 54 * s, chatMode == 0 ? 0xff347461 : 0xff2a3a6a, s);
        button(canvas, "chat_mode_1", Strings.get("emotes"), left + 36 * s + tabW, top + 14 * s, tabW, 54 * s, chatMode == 1 ? 0xff347461 : 0xff2a3a6a, s);
        button(canvas, "chat_close", "x", right - 96 * s, top + 14 * s, 76 * s, 54 * s, 0xff7a2a2f, s);
        if (chatMode == 1) {
            drawEmotes(canvas, w, s, top + 84 * s, bottom - 12 * s);
            return;
        }
        float keyboardH = 5 * 74 * s;
        float inputTop = bottom - keyboardH - 72 * s - 12 * s;
        float listTop = top + 84 * s;
        // messages, newest at the bottom
        canvas.save();
        canvas.clipRect(left + 10 * s, listTop, right - 10 * s, inputTop - 6 * s);
        float y = inputTop - 14 * s;
        boolean any = false;
        for (int index = 0; chatLines != null && index < chatLines.length() && y > listTop; index++) {
            JSONArray line = chatLines.optJSONArray(index);
            if (line == null) continue;
            any = true;
            java.util.ArrayList<String> parts = wrap(cleanChat(line.optString(0)), right - left - 60 * s, 26 * s);
            int color = 0xff000000 | (line.optInt(1) << 16) | (line.optInt(2) << 8) | line.optInt(3);
            for (int part = parts.size() - 1; part >= 0; part--) {
                y -= 34 * s;
                drawText(canvas, parts.get(part), left + 30 * s, y, 26 * s, color, true);
            }
            y -= 6 * s;
        }
        if (!any) drawText(canvas, Strings.get("chat_empty"), left + 30 * s, listTop + 10 * s, 28 * s, MUTED, true);
        canvas.restore();
        // the text being typed
        panel(canvas, left + 16 * s, inputTop, right - 16 * s, inputTop + 64 * s, 0xcc060b1a, 0xff5a6ea8, s);
        String shown = chatInput.length() == 0 ? Strings.get("chat_hint") : chatInput.toString();
        boolean cursor = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        float textSize = fitText(shown + (cursor ? "|" : ""), 32 * s, right - left - 100 * s);
        drawText(canvas, chatInput.length() == 0 ? shown : shown + (cursor ? "|" : ""), left + 40 * s, inputTop + 16 * s, textSize,
            chatInput.length() == 0 ? MUTED : INK, true);
        // keyboard
        float ky = inputTop + 72 * s;
        float keyGap = 6 * s;
        String[] rows = KEYS[keyLayout];
        for (int row = 0; row < rows.length; row++) {
            String letters = rows[row];
            float kw = Math.min(100 * s, (right - left - 32 * s - (letters.length() - 1) * keyGap) / letters.length());
            float rowWidth = letters.length() * kw + (letters.length() - 1) * keyGap;
            float kx = (w - rowWidth) / 2;
            for (int index = 0; index < letters.length(); index++) {
                String key = String.valueOf(letters.charAt(index));
                String label = keyShift ? key.toUpperCase() : key;
                button(canvas, "key_" + key, label, kx + index * (kw + keyGap), ky, kw, 68 * s, 0xff2a3a6a, s);
            }
            ky += 74 * s;
        }
        float unit = (right - left - 32 * s - 5 * keyGap) / 11;
        float kx = left + 16 * s;
        button(canvas, "key_shift", "Aa", kx, ky, unit * 1.5f, 68 * s, keyShift ? 0xff347461 : 0xff2a3a6a, s);
        kx += unit * 1.5f + keyGap;
        button(canvas, "key_lang", keyLayout == 0 ? "EN" : "RU", kx, ky, unit * 1.5f, 68 * s, 0xff2a3a6a, s);
        kx += unit * 1.5f + keyGap;
        String[] extra = {"/", ".", ",", "!", "?"};
        for (String symbol : extra) {
            button(canvas, "key_" + symbol, symbol, kx, ky, unit * 0.9f, 68 * s, 0xff2a3a6a, s);
            kx += unit * 0.9f + keyGap;
        }
        button(canvas, "key_space", "", kx, ky, unit * 2.6f, 68 * s, 0xff2a3a6a, s);
        kx += unit * 2.6f + keyGap;
        button(canvas, "key_back", "<-", kx, ky, unit * 1.2f, 68 * s, 0xff2a3a6a, s);
        kx += unit * 1.2f + keyGap;
        button(canvas, "key_send", Strings.get("chat_send"), kx, ky, right - 16 * s - kx, 68 * s, 0xff347461, s);
    }

    private void drawEmotes(Canvas canvas, int w, float s, float top, float bottom) {
        Bitmap sheet = sprites.get("extra:48");
        if (sheet == null) {
            drawCentered(canvas, "…", w / 2f, (top + bottom) / 2, 40 * s, MUTED);
            return;
        }
        int frameW = sheet.getWidth() / 8;
        int frameH = sheet.getHeight() / 39;
        int columns = 8;
        float cell = (w - 80 * s) / columns;
        float cellH = cell * frameH / frameW;
        int perPage = 40;
        for (int slot = 0; slot < perPage; slot++) {
            int id = emotePage * perPage + slot;
            if (id >= 151) break;
            float x = 40 * s + (slot % columns) * cell;
            float y = top + (slot / columns) * (cellH + 8 * s);
            // the white bubble, then the emote on top of it
            srcRect.set(frameW, 0, frameW * 2, frameH);
            dstRect.set(x + 6 * s, y, x + cell - 6 * s, y + cellH);
            canvas.drawBitmap(sheet, srcRect, dstRect, pixels);
            int col = (id % 4) * 2 + 1;
            int row = 1 + id / 4;
            srcRect.set(col * frameW, row * frameH, (col + 1) * frameW, (row + 1) * frameH);
            canvas.drawBitmap(sheet, srcRect, dstRect, pixels);
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "emote_" + id;
            hit.rect.set(x, y, x + cell, y + cellH);
            hits.add(hit);
        }
        float by = bottom - 62 * s;
        button(canvas, "emote_prev", "‹", 40 * s, by, 100 * s, 54 * s, emotePage > 0 ? 0xff2a3a6a : 0xff1a2238, s);
        drawCentered(canvas, (emotePage + 1) + " / 4", w / 2f, by + 27 * s, 30 * s, INK);
        button(canvas, "emote_next", "›", w - 140 * s, by, 100 * s, 54 * s, emotePage < 3 ? 0xff2a3a6a : 0xff1a2238, s);
    }

    private void drawBanners(Canvas canvas, int w, float s, float top, float bottom) {
        JSONArray banners = latest == null ? null : latest.optJSONArray("banners");
        if (banners == null || banners.length() == 0) {
            for (String line : wrap(Strings.get("banners_empty"), w - 120 * s, 30 * s)) {
                top += 44 * s;
                drawCentered(canvas, line, w / 2f, top + (bottom - top) / 3, 30 * s, MUTED);
            }
            return;
        }
        float y = top + 8 * s - scrollY;
        button(canvas, "banner_all", Strings.get("banner_all"), w - 30 * s - 260 * s, y, 260 * s, 52 * s, 0xff347461, s);
        drawText(canvas, Strings.get("ctab_1") + ": " + banners.length(), 30 * s, y + 8 * s, 30 * s, GOLD, true);
        y += 66 * s;
        float cell = Math.min(110 * s, (w - 60 * s) / 6 - 8 * s);
        int columns = Math.max(1, (int) ((w - 60 * s) / (cell + 8 * s)));
        for (int index = 0; index < banners.length(); index++) {
            JSONArray banner = banners.optJSONArray(index);
            if (banner == null) continue;
            float bx = 30 * s + (index % columns) * (cell + 8 * s);
            float by = y + (index / columns) * (cell + 8 * s);
            Bitmap back = sprites.get("ui:InventoryBack");
            if (back != null) drawSprite(canvas, back, bx, by, cell, cell);
            else panel(canvas, bx, by, bx + cell, by + cell, 0xcc243a73, 0xff6a80c0, s);
            drawItem(canvas, new JSONArray().put(banner.optInt(1)).put(banner.optInt(2)).put(0).put(false).put(banner.optString(4)),
                bx + cell * 0.1f, by + cell * 0.1f, cell * 0.8f);
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "banner_" + banner.optInt(0) + "_" + banner.optInt(2);
            hit.rect.set(bx, by, bx + cell, by + cell);
            hits.add(hit);
        }
        int rows = (banners.length() + columns - 1) / columns;
        neededHeight = Math.max(neededHeight, 66 * s + rows * (cell + 8 * s) + 20 * s + 8 * s);
    }

    private void drawGuidePicker(Canvas canvas, int w, float s, float top) {
        boolean listening = talking() && npcInfo != null && npcInfo.optInt("id") == 22;
        float y = top + 6 * s;
        for (String line : wrap(Strings.get(listening ? "guide_talking" : "guide_hint"), w - 60 * s, 28 * s)) {
            drawText(canvas, line, 30 * s, y, 28 * s, listening ? GOLD : MUTED, true);
            y += 38 * s;
        }
        y += 6 * s;
        slotCap = 74 * s;
        float size = slotSize(w, s, 10);
        for (int row = 1; row < 5; row++) {
            slotRow(canvas, w, s, y, 0, row * 10, 10, null, 10);
            y += size + 6 * s;
        }
        y += 6 * s;
        slotRow(canvas, w, s, y, 0, 0, 10, null, 10);
        slotCap = 0;
    }

    /** The Guide's recipe lookup: what makes an item and what it is used in. */
    private void drawRecipeView(Canvas canvas, int w, int h, float s) {
        hits.clear();
        fill.setColor(0xbb02060f);
        canvas.drawRect(0, h * 0.0f, w, h, fill);
        Hit outside = new Hit();
        outside.type = 9;
        outside.rect.set(0, 0, w, h);
        hits.add(outside);
        float left = 24 * s;
        float right = w - 24 * s;
        float top = 70 * s;
        float bottom = h - 90 * s;
        panel(canvas, left, top, right, bottom, 0xf0101a38, 0xff8ea2d8, s);
        Hit body = new Hit();
        body.type = 8;
        body.rect.set(left, top, right, bottom);
        hits.add(body);
        String title = recipeView == null ? "…" : recipeView.optString("name");
        drawText(canvas, title, left + 30 * s, top + 20 * s, 38 * s, GOLD, true);
        button(canvas, "rv_close", "x", right - 100 * s, top + 14 * s, 76 * s, 56 * s, 0xff2a3a6a, s);
        JSONArray makes = recipeView == null ? null : recipeView.optJSONArray("makes");
        JSONArray uses = recipeView == null ? null : recipeView.optJSONArray("uses");
        int makesCount = makes == null ? 0 : makes.length();
        int usesCount = uses == null ? 0 : uses.length();
        float tabY = top + 84 * s;
        float tabW = (right - left - 90 * s) / 2;
        button(canvas, "rv_makes", Strings.get("rv_makes") + " (" + makesCount + ")", left + 30 * s, tabY, tabW, 56 * s,
            recipeViewTab == 0 ? 0xff347461 : 0xff2a3a6a, s);
        button(canvas, "rv_uses", Strings.get("rv_uses") + " (" + usesCount + ")", left + 60 * s + tabW, tabY, tabW, 56 * s,
            recipeViewTab == 1 ? 0xff347461 : 0xff2a3a6a, s);
        JSONArray list = recipeViewTab == 0 ? makes : uses;
        float rowTop = tabY + 70 * s;
        float rowHeight = 110 * s;
        int perPage = Math.max(1, (int) ((bottom - rowTop - 70 * s) / rowHeight));
        int total = list == null ? 0 : list.length();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        recipeViewPage = Math.max(0, Math.min(pages - 1, recipeViewPage));
        if (total == 0) {
            drawCentered(canvas, recipeView == null ? "…" : Strings.get("rv_none"), w / 2f, rowTop + 60 * s, 32 * s, MUTED);
        }
        for (int row = 0; row < perPage; row++) {
            int index = recipeViewPage * perPage + row;
            if (list == null || index >= total) break;
            JSONArray entry = list.optJSONArray(index);
            if (entry == null) continue;
            float y = rowTop + row * rowHeight;
            panel(canvas, left + 24 * s, y, right - 24 * s, y + rowHeight - 8 * s, 0x99101a38, 0xff5a6ea8, s);
            JSONArray result = entry.optJSONArray(1);
            Bitmap back = sprites.get("ui:InventoryBack");
            if (back != null) drawSprite(canvas, back, left + 38 * s, y + 10 * s, 72 * s, 72 * s);
            drawItem(canvas, result, left + 38 * s + 7 * s, y + 17 * s, 58 * s);
            String name = result == null ? "" : result.optString(4);
            drawText(canvas, name + (result != null && result.optInt(1) > 1 ? "  ×" + result.optInt(1) : ""), left + 126 * s, y + 8 * s, 28 * s, INK, true);
            String station = entry.optString(3);
            if (!station.isEmpty()) {
                String text = station;
                drawText(canvas, text, right - 40 * s - measure(text, 22 * s), y + 12 * s, 22 * s, 0xffffe9a8, true);
            }
            JSONArray ingredients = entry.optJSONArray(2);
            float ix = left + 126 * s;
            for (int need = 0; ingredients != null && need < ingredients.length() && ix < right - 110 * s; need++) {
                JSONArray ingredient = ingredients.optJSONArray(need);
                if (ingredient == null) continue;
                Bitmap icon = sprites.get("item:" + ingredient.optInt(2));
                if (icon != null) {
                    float fit = Math.min(40 * s / icon.getWidth(), 40 * s / icon.getHeight());
                    drawSprite(canvas, icon, ix, y + 54 * s, icon.getWidth() * fit, icon.getHeight() * fit);
                }
                boolean enough = ingredient.optInt(3) >= ingredient.optInt(1);
                String count = ingredient.optInt(1) + "";
                drawText(canvas, count, ix + 42 * s, y + 60 * s, 22 * s, enough ? 0xff7be07b : 0xffe06b6b, true);
                ix += 46 * s + measure(count, 22 * s) + 14 * s;
            }
        }
        if (pages > 1) {
            float by = bottom - 66 * s;
            button(canvas, "rv_prev", "‹", left + 30 * s, by, 90 * s, 52 * s, recipeViewPage > 0 ? 0xff2a3a6a : 0xff1a2238, s);
            drawCentered(canvas, (recipeViewPage + 1) + " / " + pages, w / 2f, by + 26 * s, 28 * s, INK);
            button(canvas, "rv_next", "›", right - 120 * s, by, 90 * s, 52 * s, recipeViewPage + 1 < pages ? 0xff2a3a6a : 0xff1a2238, s);
        }
    }

    private void drawCraftRecipes(Canvas canvas, int w, float s, float top, float bottom) {
        JSONObject list = latest == null ? null : latest.optJSONObject("recipes");
        JSONArray entries = list == null ? null : list.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            drawCentered(canvas, Strings.get("no_recipes"), w / 2f, (top + bottom) / 2, 34 * s, MUTED);
            return;
        }
        if (selectedRecipe < 0 || findRecipe(entries, selectedRecipe) == null) {
            JSONArray first = entries.optJSONArray(0);
            selectedRecipe = first == null ? -1 : first.optInt(0);
        }
        float listWidth = w * 0.42f;
        panel(canvas, 18 * s, top + 4 * s, listWidth, bottom - 4 * s, 0x99101a38, 0xff5a6ea8, s);
        int columns = 4;
        float cell = Math.min(96 * s, (listWidth - 18 * s - 24 * s) / columns - 6 * s);
        float y0 = top + 14 * s - listScroll;
        int total = list.optInt("total");
        canvas.save();
        canvas.clipRect(18 * s, top + 4 * s, listWidth, bottom - 4 * s);
        for (int index = 0; index < entries.length(); index++) {
            JSONArray entry = entries.optJSONArray(index);
            if (entry == null) continue;
            float x = 30 * s + (index % columns) * (cell + 6 * s);
            float y = y0 + (index / columns) * (cell + 6 * s);
            if (y + cell < top || y > bottom) continue;
            boolean active = entry.optInt(0) == selectedRecipe;
            Bitmap back = sprites.get("ui:InventoryBack");
            if (back != null) drawSprite(canvas, back, x, y, cell, cell);
            else panel(canvas, x, y, x + cell, y + cell, 0xcc243a73, 0xff6a80c0, s);
            drawItem(canvas, entry.optJSONArray(1), x + cell * 0.1f, y + cell * 0.1f, cell * 0.8f);
            if (active) {
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(4 * s);
                canvas.drawRect(x, y, x + cell, y + cell, stroke);
            }
            Hit hit = new Hit();
            hit.type = 3;
            hit.index = entry.optInt(0);
            hit.rect.set(x, y, x + cell, y + cell);
            hits.add(hit);
        }
        canvas.restore();
        int rows = (entries.length() + columns - 1) / columns;
        neededHeight = Math.max(neededHeight, rows * (cell + 6 * s) + 20 * s);
        if (total > 24) {
            float px = 30 * s;
            float py = bottom - 64 * s;
            button(canvas, "prev", "‹", px, py, 70 * s, 52 * s, recipePage > 0 ? 0xff2a3a6a : 0xff1a2238, s);
            drawCentered(canvas, Strings.get("page") + " " + (recipePage + 1), listWidth / 2 + 10 * s, py + 26 * s, 24 * s, INK);
            button(canvas, "next", "›", listWidth - 90 * s, py, 70 * s, 52 * s, (recipePage + 1) * 24 < total ? 0xff2a3a6a : 0xff1a2238, s);
        }
        float dx = listWidth + 16 * s;
        float dw = w - dx - 18 * s;
        panel(canvas, dx, top + 4 * s, dx + dw, bottom - 4 * s, 0x99101a38, 0xff5a6ea8, s);
        JSONArray selected = findRecipe(entries, selectedRecipe);
        if (selected == null) {
            drawCentered(canvas, Strings.get("select_recipe"), dx + dw / 2, (top + bottom) / 2, 30 * s, MUTED);
            return;
        }
        JSONArray result = selected.optJSONArray(1);
        float y = top + 20 * s;
        Bitmap back = sprites.get("ui:InventoryBack");
        float big = 104 * s;
        if (back != null) drawSprite(canvas, back, dx + 20 * s, y, big, big);
        else panel(canvas, dx + 20 * s, y, dx + 20 * s + big, y + big, 0xcc243a73, 0xff6a80c0, s);
        drawItem(canvas, result, dx + 20 * s + big * 0.1f, y + big * 0.1f, big * 0.8f);
        drawText(canvas, result == null ? "" : result.optString(4), dx + big + 40 * s, y + 8 * s, 34 * s, GOLD, true);
        drawText(canvas, "×" + (result == null ? 1 : result.optInt(1)), dx + big + 40 * s, y + 52 * s, 28 * s, INK, true);
        y += big + 20 * s;
        drawText(canvas, Strings.get("ingredients"), dx + 20 * s, y, 26 * s, MUTED, true);
        y += 34 * s;
        JSONArray ingredients = selected.optJSONArray(2);
        boolean enough = true;
        for (int part = 0; ingredients != null && part < ingredients.length(); part++) {
            JSONArray ingredient = ingredients.optJSONArray(part);
            if (ingredient == null) continue;
            int need = ingredient.optInt(1);
            int have = ingredient.optInt(3, -1);
            boolean ok = have < 0 || have >= need;
            enough &= ok;
            float rowTop = y;
            Bitmap icon = sprites.get("item:" + ingredient.optInt(2));
            if (icon != null) drawSprite(canvas, icon, dx + 20 * s, rowTop - 4 * s, 44 * s, 44 * s);
            drawText(canvas, ingredient.optString(0), dx + 76 * s, rowTop + 4 * s, 28 * s, INK, true);
            String count = have < 0 ? "×" + need : have + " / " + need;
            drawText(canvas, count, dx + dw - 20 * s - measure(count, 28 * s), rowTop + 4 * s, 28 * s, ok ? 0xff7fe08a : 0xffff8a8a, true);
            y += 58 * s;
            if (y > bottom - 190 * s) break;
        }
        String station = selected.optString(3, "");
        if (!station.isEmpty()) {
            drawText(canvas, Strings.get("requires") + ": " + station, dx + 20 * s, y + 4 * s, 26 * s, MUTED, true);
            y += 36 * s;
        }
        JSONArray needs = selected.optJSONArray(4);
        for (int index = 0; needs != null && index < needs.length(); index++) {
            drawText(canvas, "• " + Strings.get(needs.optString(index)), dx + 20 * s, y, 24 * s, MUTED, true);
            y += 30 * s;
        }
        float by = bottom - 4 * s - 84 * s;
        Hit craft = new Hit();
        craft.type = 4;
        craft.index = selectedRecipe;
        craft.rect.set(dx + 20 * s, by, dx + dw - 20 * s, by + 70 * s);
        panel(canvas, craft.rect.left, craft.rect.top, craft.rect.right, craft.rect.bottom,
            enough ? (craftHeld ? 0xff3f9a52 : 0xff2f7a42) : 0xff3a3a46, enough ? 0xffbfffc8 : 0xff6a6a78, s);
        drawCentered(canvas, Strings.get("craft_one") + "  " + (result == null ? "" : result.optString(4)),
            (craft.rect.left + craft.rect.right) / 2, by + 35 * s, 30 * s, INK);
        hits.add(craft);
    }

    private static JSONArray findRecipe(JSONArray entries, int id) {
        for (int index = 0; entries != null && index < entries.length(); index++) {
            JSONArray entry = entries.optJSONArray(index);
            if (entry != null && entry.optInt(0) == id) return entry;
        }
        return null;
    }

    // ---- map: fixed framed viewport; the picture pans smoothly in world coordinates

    private void drawMap(Canvas canvas, int w, float s, float top, float bottom) {
        float pad = 18 * s;
        mapPort.set(pad, top + 8 * s, w - pad, bottom - 8 * s);
        viewScale += (targetScale - viewScale) * 0.28f;
        mapBlock = viewScale * mapStep;
        long nowMs = SystemClock.uptimeMillis();
        if (mapBitmap != null && nowMs - lastMapAsk > 450 && (desiredStep() != mapStep
            || mapPort.width() > mapBitmap.getWidth() * mapBlock + 4 || mapPort.height() > mapBitmap.getHeight() * mapBlock + 4)) {
            lastMapAsk = nowMs;
            // In-flight samples still belong to the current map, even when the viewport changes.
            mapWanted = true;
            refresh.run();
        }
        panel(canvas, mapPort.left - 6 * s, mapPort.top - 6 * s, mapPort.right + 6 * s, mapPort.bottom + 6 * s, 0xff3a2a14, 0xffd8a84a, s);
        fill.setColor(0xff070d18);
        canvas.drawRect(mapPort, fill);
        if (mapBitmap == null) showCachedMap();
        if (mapBitmap == null) {
            drawCentered(canvas, Strings.get("map_loading"), w / 2f, (top + bottom) / 2, 34 * s, MUTED);
        } else {
            float perTile = viewScale;
            float cx = (mapPort.left + mapPort.right) / 2;
            float cy = (mapPort.top + mapPort.bottom) / 2;
            float x = cx + (mapLeft - viewCx) * perTile;
            float y = cy + (mapTop - viewCy) * perTile;
            canvas.save();
            canvas.clipRect(mapPort);
            srcRect.set(0, 0, mapBitmap.getWidth(), mapBitmap.getHeight());
            dstRect.set(x, y, x + mapBitmap.getWidth() * mapBlock, y + mapBitmap.getHeight() * mapBlock);
            canvas.drawBitmap(mapBitmap, srcRect, dstRect, pixels);
            float markerX = cx + (playerX - viewCx) * perTile;
            float markerY = cy + (playerY - viewCy) * perTile;
            float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 220.0);
            fill.setColor(0xff000000);
            canvas.drawCircle(markerX, markerY, (13 + 4 * pulse) * s, fill);
            fill.setColor(GOLD);
            canvas.drawCircle(markerX, markerY, (8 + 3 * pulse) * s, fill);
            JSONArray npcs = latest == null ? null : latest.optJSONArray("npcs");
            for (int index = 0; npcs != null && index < npcs.length(); index++) {
                JSONArray npc = npcs.optJSONArray(index);
                if (npc == null || npc.optInt(2) < 0) continue;
                float nx = cx + (npc.optInt(0) - viewCx) * perTile;
                float ny = cy + (npc.optInt(1) - viewCy) * perTile;
                if (!mapPort.contains(nx, ny)) continue;
                boolean boss = npc.optInt(4) == 1;
                Bitmap head = sprites.get((boss ? "bosshead:" : "npchead:") + npc.optInt(2));
                float hs = (boss ? 40 : 28) * s;
                if (head != null) {
                    float fit = Math.min(hs / head.getWidth(), hs / head.getHeight());
                    float hw = head.getWidth() * fit;
                    float hh = head.getHeight() * fit;
                    drawSprite(canvas, head, nx - hw / 2, ny - hh / 2, hw, hh);
                } else {
                    fill.setColor(boss ? 0xffe0414a : 0xff5ae08a);
                    canvas.drawCircle(nx, ny, hs * 0.3f, fill);
                }
                if (boss && npc.optInt(6) > 0) {
                    float ratio = Math.max(0f, Math.min(1f, npc.optInt(5) / (float) npc.optInt(6)));
                    float bw = 56 * s;
                    fill.setColor(0xcc200808);
                    canvas.drawRect(nx - bw / 2, ny + hs / 2 + 3 * s, nx + bw / 2, ny + hs / 2 + 11 * s, fill);
                    fill.setColor(0xffe0414a);
                    canvas.drawRect(nx - bw / 2, ny + hs / 2 + 3 * s, nx - bw / 2 + bw * ratio, ny + hs / 2 + 11 * s, fill);
                }
                if (boss || viewScale > 2.2f) {
                    String label = npc.optString(3);
                    drawText(canvas, label, nx - measure(label, 14 * s) / 2, ny - hs / 2 - 20 * s, 14 * s, boss ? 0xffffb0b0 : INK, true);
                }
            }
            canvas.restore();
        }
        stroke.setColor(0xfff0c860);
        stroke.setStrokeWidth(3 * s);
        canvas.drawRect(mapPort, stroke);
        fill.setColor(0xffd8a84a);
        float r = 7 * s;
        canvas.drawCircle(mapPort.left, mapPort.top, r, fill);
        canvas.drawCircle(mapPort.right, mapPort.top, r, fill);
        canvas.drawCircle(mapPort.left, mapPort.bottom, r, fill);
        canvas.drawCircle(mapPort.right, mapPort.bottom, r, fill);
        float bx = mapPort.right - 14 * s;
        bx = button(canvas, "zin", "+", bx - 74 * s, mapPort.top + 14 * s, 74 * s, 64 * s, 0xdd2a3a6a, s);
        bx = button(canvas, "zout", "-", bx - 74 * s, mapPort.top + 14 * s, 74 * s, 64 * s, 0xdd2a3a6a, s);
        button(canvas, "center", Strings.get("to_player"), bx - 200 * s, mapPort.top + 14 * s, 200 * s, 64 * s, 0xdd2a3a6a, s);
    }

    // ---- status, hotbar, slots

    private void drawStatus(Canvas canvas, int w, int h, float s) {
        String message;
        int color = MUTED;
        if (feedbackActive()) {
            message = feedback;
            color = 0xffff9a8a;
        } else if (latest == null) message = "";
        else if (splitMode != 0) message = Strings.get("pick_empty");
        else if (chosenGroup >= 0) {
            JSONArray data = slotData(chosenGroup, chosenIndex);
            message = (data == null ? "" : data.optString(4)) + "  ·  " + Strings.get("pick_target");
        } else {
            JSONArray inventory = latest.optJSONArray("inventory");
            int selected = latest.optBoolean("override") ? latest.optInt("hotbar", 0) : latest.optInt("selected", latest.optInt("hotbar", 0));
            JSONArray data = inventory == null ? null : inventory.optJSONArray(selected);
            String name = data == null || typeOf(data) == 0 ? "" : data.optString(4);
            message = name + (pane <= 1 ? (name.isEmpty() ? "" : "  ·  ") + Strings.get("hint_drag") : "");
        }
        drawCenteredBox(canvas, message, w / 2f, h - 212 * s, 30 * s, color);
        drawBuffs(canvas, w, h, s);
    }

    private boolean feedbackActive() {
        return !feedback.isEmpty() && SystemClock.uptimeMillis() < feedbackUntil;
    }

    private void drawHotbar(Canvas canvas, int w, int h, float s, long now) {
        float size = Math.min(112 * s, (w - 36 * s) / 10 - 8 * s);
        float total = 10 * (size + 6 * s) - 6 * s;
        float x0 = (w - total) / 2;
        float y = h - 162 * s;
        panel(canvas, x0 - 14 * s, y - 14 * s, x0 + total + 14 * s, y + size + 14 * s, 0xaa101a38, 0xff5a6ea8, s);
        // The frame follows the item actually in the hand. While the game holds a temporary override (auto-swap of a
        // tool, a potion...) the stable hotbar slot is kept and the override slot only pulses.
        boolean overridden = latest != null && latest.optBoolean("override");
        int stable = latest == null ? -1 : latest.optInt("hotbar", latest.optInt("selected", -1));
        int inHand = latest == null ? -1 : latest.optInt("selected", stable);
        int selected = overridden ? stable : inHand;
        int overrideSlot = overridden ? inHand : -1;
        float targetX = selected < 0 ? -1 : x0 + selected * (size + 6 * s);
        if (highlightX < 0 || targetX < 0) highlightX = targetX;
        else highlightX += (targetX - highlightX) * 0.35f;
        for (int index = 0; index < 10; index++) {
            drawSlotAt(canvas, x0 + index * (size + 6 * s), y, size, 0, index, Integer.toString(index == 9 ? 0 : index + 1));
        }
        if (highlightX >= 0) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
            stroke.setColor(GOLD);
            stroke.setStrokeWidth(5 * s);
            canvas.drawRect(highlightX - 4 * s, y - 4 * s, highlightX + size + 4 * s, y + size + 4 * s, stroke);
            if (overrideSlot >= 0 && overrideSlot != selected && overrideSlot < 10) {
                stroke.setColor(((int) (120 + 120 * pulse) << 24) | 0xffffff);
                stroke.setStrokeWidth(3 * s);
                float ox = x0 + overrideSlot * (size + 6 * s);
                canvas.drawRect(ox - 2 * s, y - 2 * s, ox + size + 2 * s, y + size + 2 * s, stroke);
            }
        }
    }

    private String slotBack(int group, int index) {
        if (group == 1) return index < 10 ? "ui:InventoryBack3" : "ui:InventoryBack8";
        if (group == 2 || group == 4) return "ui:InventoryBack12";
        if (group == 3 || group == 6) return "ui:InventoryBack7";
        if (group == 5) {
            JSONObject chest = latest == null ? null : latest.optJSONObject("chest");
            return chest != null && chest.optInt("id") >= 0 ? "ui:InventoryBack5" : "ui:InventoryBack2";
        }
        return "ui:InventoryBack";
    }

    private void drawSlotAt(Canvas canvas, float x, float y, float size, int group, int index, String label) {
        float s = size / 112f;
        JSONArray data = slotData(group, index);
        boolean locked = group == 1 && latest != null && latest.optJSONArray("unlocked") != null
            && !latest.optJSONArray("unlocked").optBoolean(index % 10, true);
        boolean favoured = data != null && typeOf(data) != 0 && data.optBoolean(3);
        Bitmap back = sprites.get(favoured ? "ui:InventoryBack10" : slotBack(group, index));
        if (back != null) drawSprite(canvas, back, x, y, size, size);
        else panel(canvas, x, y, x + size, y + size, 0xcc243a73, 0xff6a80c0, s);
        if (locked) {
            fill.setColor(0xaa000000);
            canvas.drawRect(x, y, x + size, y + size, fill);
        }
        boolean chosen = chosenGroup == group && chosenIndex == index;
        boolean hovered = hover != null && hover.type == 0 && hover.group == group && hover.index == index;
        boolean dragSource = dragging && downHit != null && downHit.type == 0 && downHit.group == group && downHit.index == index;
        float shake = 0;
        if (shakeGroup == group && shakeIndex == index) {
            float age = (SystemClock.uptimeMillis() - shakeAt) / 450f;
            if (age < 1f) shake = (float) Math.sin(age * 28) * (1f - age) * 9 * s;
        }
        canvas.save();
        canvas.translate(shake, 0);
        if (!dragSource) drawItem(canvas, data, x + size * 0.1f, y + size * 0.1f, size * 0.8f);
        canvas.restore();
        if (inventoryFilter != 0 && pane == 0 && group == 0 && index >= 10 && index < 50 && data != null && typeOf(data) != 0
            && data.optInt(5) != inventoryFilter) {
            fill.setColor(0xb0060b1a);
            canvas.drawRect(x, y, x + size, y + size, fill);
        }
        if (data != null && typeOf(data) == 0 && label != null) {
            drawCentered(canvas, label, x + size / 2, y + size / 2, Math.max(14, 18 * s), 0x99b5c5d4);
        } else if (label != null && group == 0 && index < 10) {
            drawText(canvas, label, x + 6 * s, y + 4 * s, 20 * s, 0xaaffffff, true);
        }
        if (locked) drawCentered(canvas, Strings.get("locked"), x + size / 2, y + size / 2, 18 * s, 0xaab5c5d4);
        if (chosen || hovered || (splitMode != 0 && typeOf(data) == 0 && group == 0)) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 180.0);
            stroke.setColor(hovered ? 0xffffffff : 0xffff9a3c);
            stroke.setStrokeWidth((4 + 2 * pulse) * s);
            canvas.drawRect(x, y, x + size, y + size, stroke);
        }
        Hit hit = new Hit();
        hit.type = 0;
        hit.group = group;
        hit.index = index;
        hit.rect.set(x, y, x + size, y + size);
        hits.add(hit);
    }

    private void drawItem(Canvas canvas, JSONArray data, float x, float y, float size) {
        if (data == null || typeOf(data) == 0) return;
        Bitmap icon = sprites.get("item:" + data.optInt(0));
        float s = size / 90f;
        if (icon != null) {
            // like the game: one UI unit per sprite pixel, big sprites shrunk to fit a 32-pixel box
            float unit = size / 36f;
            float fit = Math.min(1f, 36f / Math.max(icon.getWidth(), icon.getHeight()));
            float width = icon.getWidth() * fit * unit;
            float height = icon.getHeight() * fit * unit;
            drawSprite(canvas, icon, x + (size - width) / 2, y + (size - height) / 2, width, height);
        } else {
            String name = data.optString(4, "?");
            drawCentered(canvas, name.isEmpty() ? "?" : name.substring(0, 1), x + size / 2, y + size / 2, 36 * s, GOLD);
        }
        if (data.optInt(1) > 1) {
            String count = Integer.toString(data.optInt(1));
            drawText(canvas, count, x + size - measure(count, 22 * s) - 2 * s, y + size - 24 * s, 22 * s, INK, true);
        }
        if (data.optBoolean(3)) drawStar(canvas, x + 4 * s, y + 4 * s, 18 * s);
    }

    private void drawStar(Canvas canvas, float x, float y, float size) {
        star.reset();
        float cx = x + size / 2;
        float cy = y + size / 2;
        for (int point = 0; point < 10; point++) {
            double angle = -Math.PI / 2 + point * Math.PI / 5;
            float radius = point % 2 == 0 ? size / 2 : size / 4.2f;
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            if (point == 0) star.moveTo(px, py);
            else star.lineTo(px, py);
        }
        star.close();
        fill.setColor(0xff000000);
        canvas.save();
        canvas.scale(1.25f, 1.25f, cx, cy);
        canvas.drawPath(star, fill);
        canvas.restore();
        fill.setColor(GOLD);
        canvas.drawPath(star, fill);
    }

    /** Pulling an item to the top edge lights a red gradient (drop); to the bottom edge a green one (use / equip). */
    private void drawEdges(Canvas canvas, int w, int h, float s) {
        if (dragging && downHit != null && ((downHit.group == 0 && downHit.index >= 10) || downHit.group == 5)) {
            // one big zone along the whole bottom (status line and hotbar included): release anywhere in it to use
            boolean active = edgeZone == 2 && edgeStrength >= 1f;
            fill.setColor(active ? 0x7043c970 : 0x2643c970);
            canvas.drawRect(0, h - 262 * s, w, h, fill);
            drawCentered(canvas, Strings.get(downHit.group == 5 ? "take" : "zone_use"), w / 2f, h - 219 * s, (active ? 38 : 30) * s,
                active ? 0xffffffff : 0xccffffff);
            if (edgeZone == 2) return;
        }
        if (!dragging || edgeZone == 0 || edgeStrength <= 0.02f) return;
        if (edgeZone == 4) {
            int c = 0xffffc83c;
            int a = (int) (Math.min(1f, edgeStrength * 1.15f) * 230);
            float d = 300 * s;
            fill.setShader(new android.graphics.LinearGradient(0, 0, d, 0, (a << 24) | (c & 0xffffff), c & 0xffffff,
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, d, h, fill);
            fill.setShader(null);
            canvas.save();
            canvas.rotate(-90, 56 * s, h / 2f);
            drawCentered(canvas, Strings.get("zone_fav"), 56 * s, h / 2f, (30 + 8 * edgeStrength) * s, 0xffffffff);
            canvas.restore();
            return;
        }
        if (edgeZone == 3) {
            int c = 0xff9a6bff;
            int a = (int) (Math.min(1f, edgeStrength * 1.15f) * 230);
            float d = 300 * s;
            fill.setShader(new android.graphics.LinearGradient(w, 0, w - d, 0, (a << 24) | (c & 0xffffff), c & 0xffffff,
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(w - d, 0, w, h, fill);
            fill.setShader(null);
            canvas.save();
            canvas.rotate(90, w - 56 * s, h / 2f);
            drawCentered(canvas, Strings.get("zone_trash"), w - 56 * s, h / 2f, (30 + 8 * edgeStrength) * s, 0xffffffff);
            canvas.restore();
            return;
        }
        boolean top = edgeZone == 1;
        int color = top ? 0xffe0414a : 0xff43c970;
        float depth = 440 * s;
        int alpha = (int) (Math.min(1f, edgeStrength * 1.15f) * 230);
        android.graphics.LinearGradient gradient = top
            ? new android.graphics.LinearGradient(0, 0, 0, depth, (alpha << 24) | (color & 0xffffff), color & 0xffffff,
                android.graphics.Shader.TileMode.CLAMP)
            : new android.graphics.LinearGradient(0, h, 0, h - depth, (alpha << 24) | (color & 0xffffff), color & 0xffffff,
                android.graphics.Shader.TileMode.CLAMP);
        fill.setShader(gradient);
        canvas.drawRect(0, top ? 0 : h - depth, w, top ? depth : h, fill);
        fill.setShader(null);
        String label = Strings.get(top ? "zone_drop" : "zone_use");
        float size = (30 + 8 * edgeStrength) * s;
        int textColor = edgeStrength > 0.22f ? 0xffffffff : 0xccffffff;
        if (top || edgeStrength < 1f) drawCentered(canvas, label, w / 2f, top ? 70 * s : h - 70 * s, size, textColor);
        if (edgeStrength > 0.22f && (top || edgeStrength < 1f)) {
            stroke.setColor(textColor);
            stroke.setStrokeWidth(4 * s);
            canvas.drawLine(w * 0.3f, top ? 118 * s : h - 118 * s, w * 0.7f, top ? 118 * s : h - 118 * s, stroke);
        }
    }

    private void drawDrag(Canvas canvas, float s) {
        if (!dragging || downHit == null) return;
        JSONArray data = slotData(downHit.group, downHit.index);
        float size = 130 * s;
        canvas.save();
        canvas.translate(dragX - size / 2, dragY - size / 2 - 40 * s);
        canvas.scale(1.1f, 1.1f, size / 2, size / 2);
        fill.setColor(0x66000000);
        canvas.drawCircle(size / 2, size / 2 + 6 * s, size * 0.42f, fill);
        drawItem(canvas, data, 0, 0, size);
        canvas.restore();
    }

    // ---- item sheet: description and actions

    private static String coins(int value) {
        if (value <= 0) return "—";
        StringBuilder out = new StringBuilder();
        int platinum = value / 1000000;
        int gold = value / 10000 % 100;
        int silver = value / 100 % 100;
        int copper = value % 100;
        if (platinum > 0) out.append(platinum).append("p ");
        if (gold > 0) out.append(gold).append("g ");
        if (silver > 0) out.append(silver).append("s ");
        if (copper > 0) out.append(copper).append("c");
        return out.toString().trim();
    }

    private void drawSheet(Canvas canvas, int w, int h, float s) {
        hits.clear();   // everything below the sheet stops reacting to touches
        fill.setColor(0xbb02060f);
        canvas.drawRect(0, 0, w, h, fill);
        Hit outside = new Hit();
        outside.type = 9;
        outside.rect.set(0, 0, w, h);
        hits.add(outside);
        JSONArray data = slotData(sheetGroup, sheetIndex);
        if (typeOf(data) == 0) {
            sheetOpen = false;
            return;
        }
        float left = 28 * s;
        float right = w - 28 * s;
        float big = 190 * s;
        // ---- what the window shows
        boolean inventory = sheetGroup == 0;
        boolean chestOpen = latest != null && latest.optJSONObject("chest") != null;
        boolean favorited = data.optBoolean(3);
        boolean wear = sheetInfo != null && (sheetInfo.optBoolean("accessory") || sheetInfo.optBoolean("vanity") || sheetInfo.optInt("defense") > 0);
        boolean usable = sheetInfo != null && (sheetInfo.optBoolean("consumable") || sheetInfo.optInt("healLife") > 0
            || sheetInfo.optInt("healMana") > 0);
        ArrayList<String[]> actions = new ArrayList<>();
        if (sheetGroup == 5) actions.add(new String[]{"take", Strings.get("take")});
        if (inventory && chestOpen && sheetIndex < 50) actions.add(new String[]{"deposit", Strings.get("deposit")});
        if (inventory && shop() != null && sheetIndex < 50 && !data.optBoolean(3)) {
            actions.add(new String[]{"sell", Strings.get("sell")});
        }
        if (inventory && wear) actions.add(new String[]{"equip", Strings.get("equip")});
        if (inventory && usable) actions.add(new String[]{"use", Strings.get("use")});
        actions.add(new String[]{"recipes", Strings.get("recipes_btn")});
        actions.add(new String[]{"favorite", Strings.get(favorited ? "unfavorite" : "favorite")});
        if (inventory && data.optInt(1) >= 2) {
            actions.add(new String[]{"split1", Strings.get("split_one")});
            actions.add(new String[]{"split2", Strings.get("split_half")});
        }
        if (inventory && sheetIndex < 50) actions.add(new String[]{"drop", Strings.get("drop")});
        actions.add(new String[]{"trash", trashArmed() ? Strings.get("trash_confirm") : Strings.get("trash")});
        ArrayList<String> text = new ArrayList<>();
        if (sheetInfo != null) {
            ArrayList<String> lines = new ArrayList<>();
            int damage = sheetInfo.optInt("damage");
            JSONArray kinds = sheetInfo.optJSONArray("kinds");
            if (damage > 0) {
                String kind = kinds != null && kinds.length() > 0 ? " (" + Strings.get(kinds.optString(0)) + ")" : "";
                lines.add(damage + " " + Strings.get("damage") + kind);
            }
            if (sheetInfo.optInt("crit") > 0 && damage > 0) lines.add(sheetInfo.optInt("crit") + "% " + Strings.get("crit"));
            if (sheetInfo.optInt("useTime") > 0 && damage > 0) lines.add(Strings.get("speed") + ": " + sheetInfo.optInt("useTime"));
            if (sheetInfo.optInt("defense") > 0) lines.add(sheetInfo.optInt("defense") + " " + Strings.get("defense"));
            if (sheetInfo.optInt("pick") > 0) lines.add(sheetInfo.optInt("pick") + "% " + Strings.get("pick_power"));
            if (sheetInfo.optInt("axe") > 0) lines.add(sheetInfo.optInt("axe") * 5 + "% " + Strings.get("axe_power"));
            if (sheetInfo.optInt("hammer") > 0) lines.add(sheetInfo.optInt("hammer") + "% " + Strings.get("hammer_power"));
            if (sheetInfo.optInt("healLife") > 0) lines.add(Strings.get("heals") + " " + sheetInfo.optInt("healLife"));
            if (sheetInfo.optInt("healMana") > 0) lines.add(Strings.get("restores_mana") + " " + sheetInfo.optInt("healMana"));
            if (sheetInfo.optInt("mana") > 0 && damage > 0) lines.add(Strings.get("uses_mana") + " " + sheetInfo.optInt("mana"));
            JSONArray tooltip = sheetInfo.optJSONArray("tooltip");
            for (int index = 0; tooltip != null && index < tooltip.length(); index++) lines.add(tooltip.optString(index));
            for (String line : lines) text.addAll(wrap(line, right - left - 80 * s, 32 * s));
        }
        ArrayList<String> modLines = new ArrayList<>();
        ArrayList<Integer> modColors = new ArrayList<>();
        JSONArray mods = sheetInfo == null ? null : sheetInfo.optJSONArray("mods");
        for (int index = 0; mods != null && index < mods.length(); index++) {
            JSONArray mod = mods.optJSONArray(index);
            if (mod == null) continue;
            int value = mod.optInt(1);
            String kind = mod.optString(0);
            // less mana is good, everything else: more is good
            boolean good = kind.equals("mana") ? value < 0 : value > 0;
            modLines.add((value > 0 ? "+" : "") + value + "% " + Strings.get("mod_" + kind));
            modColors.add(good ? 0xff7be07b : 0xffe06b6b);
        }
        float lineStep = 46 * s;
        float gap = 12 * s;
        float bw = (right - left - 68 * s - 2 * gap) / 3;
        float bh = 84 * s;
        int rows = (actions.size() + 2) / 3;
        float buttonsHeight = rows * bh + (rows - 1) * gap;
        float bodyHeight = Math.max(text.isEmpty() && modLines.isEmpty() ? lineStep : 0, (text.size() + modLines.size()) * lineStep);
        float total = 34 * s + big + 26 * s + bodyHeight + 22 * s + buttonsHeight + 34 * s;
        float maxTotal = h - 80 * s;
        if (total > maxTotal) {
            int fit = (int) ((maxTotal - (total - bodyHeight)) / lineStep);
            while (text.size() > Math.max(1, fit)) text.remove(text.size() - 1);
            total = maxTotal;
        }
        float panelTop = Math.max(30 * s, (h - total) / 2f);
        float panelBottom = panelTop + total;
        panel(canvas, left, panelTop, right, panelBottom, 0xf0101a38, 0xff8ea2d8, s);
        Hit body = new Hit();
        body.type = 8;
        body.rect.set(left, panelTop, right, panelBottom);
        hits.add(body);
        // ---- header: sprite, name, stack, value
        float bx0 = left + 34 * s;
        float by0 = panelTop + 34 * s;
        Bitmap back = sprites.get(slotBack(sheetGroup, sheetIndex));
        if (back != null) drawSprite(canvas, back, bx0, by0, big, big);
        else panel(canvas, bx0, by0, bx0 + big, by0 + big, 0xcc243a73, 0xff6a80c0, s);
        drawItem(canvas, data, bx0 + big * 0.1f, by0 + big * 0.1f, big * 0.8f);
        int rare = sheetInfo == null ? 0 : sheetInfo.optInt("rare");
        int color = RARITY[Math.max(0, Math.min(RARITY.length - 1, rare + 1))];
        String name = sheetInfo == null ? data.optString(4) : sheetInfo.optString("name", data.optString(4));
        float tx = bx0 + big + 30 * s;
        float y = by0 - 2 * s;
        for (String part : wrap(name, right - tx - 100 * s, 46 * s)) {
            drawText(canvas, part, tx, y, 46 * s, color, true);
            y += 56 * s;
        }
        if (data.optInt(1) > 1) {
            drawText(canvas, Strings.get("stack_of") + ": " + data.optInt(1), tx, y, 30 * s, MUTED, true);
            y += 42 * s;
        }
        if (sheetInfo != null) {
            drawText(canvas, Strings.get("value") + ": " + coins(sheetInfo.optInt("value") * Math.max(1, data.optInt(1))), tx, y, 30 * s, GOLD, true);
        }
        // ---- description
        float ty = by0 + big + 26 * s;
        if (sheetInfo == null) drawText(canvas, "…", left + 40 * s, ty, 34 * s, MUTED, true);
        for (String part : text) {
            drawText(canvas, part, left + 40 * s, ty, 32 * s, INK, true);
            ty += lineStep;
        }
        for (int index = 0; index < modLines.size(); index++) {
            drawText(canvas, modLines.get(index), left + 40 * s, ty, 32 * s, modColors.get(index), true);
            ty += lineStep;
        }
        // ---- buttons
        float buttonsTop = panelBottom - 34 * s - buttonsHeight;
        for (int index = 0; index < actions.size(); index++) {
            float bx = left + 34 * s + (index % 3) * (bw + gap);
            float by = buttonsTop + (index / 3) * (bh + gap);
            String kind = actions.get(index)[0];
            int tint = kind.equals("trash") ? 0xff7a2a2f : kind.equals("take") || kind.equals("deposit") ? 0xff2c6a3c : 0xff2a3a6a;
            panel(canvas, bx, by, bx + bw, by + bh, tint, 0xff8ea2d8, s);
            buttonLabel(canvas, "sheet_" + kind, actions.get(index)[1], bx, by, bw, bh, 34 * s, s);
            Hit hit = new Hit();
            hit.type = 2;
            hit.name = "sheet_" + kind;
            hit.rect.set(bx, by, bx + bw, by + bh);
            hits.add(hit);
        }
        Hit close = new Hit();
        close.type = 2;
        close.name = "sheet_close";
        close.rect.set(right - 94 * s, panelTop + 16 * s, right - 18 * s, panelTop + 92 * s);
        panel(canvas, close.rect.left, close.rect.top, close.rect.right, close.rect.bottom, 0xff2a3a6a, 0xff8ea2d8, s);
        drawCentered(canvas, "x", (close.rect.left + close.rect.right) / 2, (close.rect.top + close.rect.bottom) / 2, 42 * s, INK);
        hits.add(close);
    }

    private boolean trashArmed() {
        return trashArmedAt != 0 && SystemClock.uptimeMillis() - trashArmedAt < 2500;
    }

    private ArrayList<String> wrap(String value, float maxWidth, float size) {
        ArrayList<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && measure(candidate, size) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private void drawDialogue(Canvas canvas, int w, float s, float top, float bottom) {
        float left = 40 * s;
        float right = w - 40 * s;
        float panelBottom = bottom - 6 * s;
        float panelTop = top + 20 * s;
        panel(canvas, left, panelTop, right, panelBottom, 0xee101a38, GOLD, s);
        String name = npcInfo == null ? "" : npcInfo.optString("name");
        String kind = npcInfo == null ? "" : npcInfo.optString("kind");
        drawText(canvas, (name.isEmpty() ? Strings.get("talking") : name) + (kind.isEmpty() ? "" : "  -  " + kind),
            left + 24 * s, panelTop + 18 * s, 34 * s, GOLD, true);
        String chat = npcInfo == null ? "" : npcInfo.optString("text");
        boolean canShop = npcInfo != null && npcInfo.optInt("talk", -1) == latest.optInt("talk", -1)
            && npcInfo.optBoolean("canShop");
        float y = panelTop + 70 * s;
        for (String line : wrap(chat, right - left - 48 * s, 28 * s)) {
            drawText(canvas, line, left + 24 * s, y, 28 * s, INK, true);
            y += 46 * s;
            if (y > panelBottom - (canShop ? 100 : 20) * s) break;
        }
        if (canShop) {
            button(canvas, "shop_open", Strings.get("open_shop"), left + 24 * s, panelBottom - 76 * s,
                260 * s, 54 * s, 0xff2c6a3c, s);
        }
    }

    private void drawLanguagePicker(Canvas canvas, int w, int h, float s) {
        hits.clear();
        addButtonHit("dismiss_overlay", 0, 0, w, h);
        float width = 470 * s;
        float left = (w - width) / 2;
        float top = (h - 422 * s) / 2;
        panel(canvas, left, top, left + width, top + 422 * s, 0xff101a38, GOLD, s);
        drawCentered(canvas, Strings.get("language"), w / 2f, top + 36 * s, 31 * s, GOLD);
        String current = prefs.getString("lang", "auto");
        languageOption(canvas, "auto", Strings.get("language_auto"), current, left, top + 72 * s, width, s);
        int index = 1;
        for (Strings.Language language : Strings.Language.values()) {
            languageOption(canvas, language.code, language.label, current, left, top + (72 + 65 * index++) * s, width, s);
        }
    }

    private void languageOption(Canvas canvas, String code, String label, String current, float left, float y, float width, float s) {
        button(canvas, "language_" + code, label, left + 26 * s, y, width - 52 * s, 52 * s,
            current.equals(code) ? 0xff347461 : 0xff2a3a6a, s);
    }

    private void drawInfoPicker(Canvas canvas, int w, int h, float s) {
        hits.clear();
        addButtonHit("dismiss_overlay", 0, 0, w, h);
        float left = 75 * s;
        float top = 176 * s;
        float width = w - 2 * left;
        String[] lines = infoLine().split("  ·  ");
        float height = (Math.min(7, (lines.length + 1) / 2) * 56 + 92) * s;
        panel(canvas, left, top, left + width, top + height, 0xff101a38, GOLD, s);
        float column = (width - 48 * s) / 2;
        for (int i = 0; i < Math.min(14, lines.length); i++) {
            drawText(canvas, lines[i], left + 24 * s + (i % 2) * column, top + (22 + (i / 2) * 56) * s,
                fitText(lines[i], 23 * s, column - 18 * s), INK, true);
        }
        button(canvas, "dismiss_overlay", Strings.get("close"), left + 24 * s, top + height - 65 * s,
            200 * s, 48 * s, 0xff2a3a6a, s);
    }

    // ---- drawing helpers

    /** Nine-slice: corners keep the game's pixel look, edges and centre stretch. */
    private void drawPlate(Canvas canvas, Bitmap plate, float l, float t, float r, float b, float s, float alpha) {
        int w = plate.getWidth();
        int h = plate.getHeight();
        int cs = Math.max(3, Math.min(w, h) * 28 / 100);
        float cd = Math.min(cs * 1.9f * s * 1.15f, Math.min(r - l, b - t) / 2.3f);
        l = Math.round(l);
        t = Math.round(t);
        r = Math.round(r);
        b = Math.round(b);
        cd = Math.round(cd);
        // solid centre first, so no hairline gaps can show between slices
        int centre = plate.getPixel(w / 2, h / 2);
        fill.setColor((centre & 0x00ffffff) | ((int) (255 * alpha) << 24));
        canvas.drawRect(l + cd - 1, t + cd - 1, r - cd + 1, b - cd + 1, fill);
        int[] sx = {0, cs, w - cs, w};
        int[] sy = {0, cs, h - cs, h};
        float[] dx = {l, l + cd, r - cd, r};
        float[] dy = {t, t + cd, b - cd, b};
        pixels.setAlpha((int) (255 * alpha));
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                if (i == 1 && j == 1) continue;
                plateSrc.set(sx[i], sy[j], sx[i + 1], sy[j + 1]);
                plateDst.set(dx[i] - (i > 0 ? 0.5f : 0), dy[j] - (j > 0 ? 0.5f : 0), dx[i + 1] + (i < 2 ? 0.5f : 0), dy[j + 1] + (j < 2 ? 0.5f : 0));
                canvas.drawBitmap(plate, plateSrc, plateDst, pixels);
            }
        }
        pixels.setAlpha(255);
    }

    private void drawSprite(Canvas canvas, Bitmap bitmap, float x, float y, float width, float height) {
        srcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        dstRect.set(x, y, x + width, y + height);
        canvas.drawBitmap(bitmap, srcRect, dstRect, pixels);
    }

    /** Plate colour variants of the game's slot sprite, chosen from the colour the caller asked for. */
    private String plateFor(int color, int border) {
        if (border == GOLD || border == 0xfff0c860 || border == 0xffd8a84a) return "ui:InventoryBack14";
        int r = (color >> 16) & 255;
        int g = (color >> 8) & 255;
        int b = color & 255;
        if (r > g + 30 && r > b + 10) return "ui:InventoryBack2";
        if (g > r + 30 && g >= b) return "ui:InventoryBack3";
        if (r < 44 && g < 52 && b < 70) return "ui:InventoryBack12";
        return "ui:InventoryBack";
    }

    private final Rect plateSrc = new Rect();
    private final RectF plateDst = new RectF();

    private void panel(Canvas canvas, float l, float t, float r, float b, int color, int border, float s) {
        Bitmap plate = sprites.get(plateFor(color, border));
        if (plate != null && r - l > 20 && b - t > 20) {
            drawPlate(canvas, plate, l, t, r, b, s, (color >>> 24) < 0xb0 ? 0.86f : 1f);
            return;
        }
        fill.setColor(color);
        canvas.drawRect(l, t, r, b, fill);
        stroke.setColor(border);
        stroke.setStrokeWidth(3 * s);
        canvas.drawRect(l + 1.5f * s, t + 1.5f * s, r - 1.5f * s, b - 1.5f * s, stroke);
        stroke.setColor(0xff000000);
        stroke.setStrokeWidth(2 * s);
        canvas.drawRect(l, t, r, b, stroke);
    }

    private float measure(String value, float size) {
        size *= TEXT_BOOST;
        if (font.canDraw(value)) return font.measure(value, size);
        text.setTextSize(size);
        return text.measureText(value);
    }

    private void drawText(Canvas canvas, String value, float x, float y, float size, int color, boolean outline) {
        if (value == null || value.isEmpty()) return;
        size *= TEXT_BOOST;
        if (font.canDraw(value)) {
            font.draw(canvas, value, x, y, size, color, outline);
            return;
        }
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        if (outline) {
            text.setColor(0xff000000);
            canvas.drawText(value, x + 2, y + size * 0.85f + 2, text);
        }
        text.setColor(color);
        canvas.drawText(value, x, y + size * 0.85f, text);
    }

    private void drawCentered(Canvas canvas, String value, float cx, float cy, float size, int color) {
        if (value == null || value.isEmpty()) return;
        drawText(canvas, value, cx - measure(value, size) / 2, cy - size * TEXT_BOOST * 0.5f, size, color, true);
    }

    private void drawCenteredBox(Canvas canvas, String value, float cx, float top, float size, int color) {
        if (value == null || value.isEmpty()) return;
        drawText(canvas, value, cx - measure(value, size) / 2, top, size, color, true);
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        if (scaler.isInProgress() || scaling && event.getPointerCount() > 1) return true;
        float x = event.getX();
        float y = event.getY();
        float s = getHeight() / 1080f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scaling = false;
                downX = x;
                downY = y;
                lastY = y;
                downAt = SystemClock.uptimeMillis();
                downHit = hitAt(x, y);
                dragging = false;
                scrolling = false;
                hover = null;
                dragStartCx = viewCx;
                dragStartCy = viewCy;
                if (downHit != null && downHit.type == 4) startCraftHold(downHit);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaling) return true;
                float distance = Math.max(Math.abs(x - downX), Math.abs(y - downY));
                if (pane == 3 && downHit == null && mapBitmap != null && !sheetOpen) {
                    if (distance > 8 * s || !follow) {
                        float perTile = viewScale;
                        follow = false;
                        viewCx = dragStartCx - (x - downX) / perTile;
                        viewCy = dragStartCy - (y - downY) / perTile;
                        maybeRefreshMap(false);
                    }
                    return true;
                }
                if (downHit != null && downHit.type == 0 && !dragging && distance > 18 * s && !sheetOpen
                    && typeOf(slotData(downHit.group, downHit.index)) != 0 && latest != null
                    && (Math.abs(x - downX) >= Math.abs(y - downY) * 0.5f || neededHeight <= viewHeight + 8 * s
                        || (downHit.group == 0 && downHit.index < 10) || downHit.group == 6)) {
                    dragging = true;
                    sound("grab");
                    chosenGroup = -1;
                }
                if (dragging) {
                    dragX = x;
                    dragY = y;
                    float zone = 340 * s;
                    float fromTop = y;
                    float fromBottom = getHeight() - y;
                    float fromRight = getWidth() - x;
                    float trashZone = 120 * s;
                    boolean aboveHotbar = y < getHeight() - 200 * s;
                    if (aboveHotbar && latest != null && latest.has("trash") && fromRight < trashZone && downHit != null && downHit.group != 6
                        && (downHit.group == 0 || downHit.group == 5)) {
                        edgeZone = 3;
                        edgeStrength = 1 - fromRight / trashZone;
                    } else if (aboveHotbar && x < trashZone && downHit != null) {
                        edgeZone = 4;
                        edgeStrength = 1 - x / trashZone;
                    } else if (fromTop < zone) {
                        edgeZone = 1;
                        edgeStrength = 1 - fromTop / zone;
                    } else if (fromBottom < zone) {
                        edgeZone = 2;
                        edgeStrength = 1 - fromBottom / zone;
                    } else {
                        edgeZone = 0;
                        edgeStrength = 0;
                    }
                    Hit under = hitAt(x, y - 40 * s);
                    hover = under != null && under.type == 0 ? under : null;
                    // a slot under the item wins over the edge zones, except right at the very edge
                    if (hover != null && edgeStrength < 0.85f) {
                        edgeZone = 0;
                        edgeStrength = 0;
                    }
                    // the use band above the hotbar: a strip that always means use / equip / summon, so the zone has no
                    // dead stretch between the slots and the screen edge
                    if (downHit != null && ((downHit.group == 0 && downHit.index >= 10) || downHit.group == 5)
                        && y >= getHeight() - 262 * s) {
                        edgeZone = 2;
                        edgeStrength = 1f;
                        hover = null;
                    }
                } else if (!scrolling && distance > 18 * s && !sheetOpen
                    && (downHit == null || downHit.type == 3 || downHit.type == 0 || downHit.type == 5)) {
                    scrolling = true;
                }
                if (scrolling) {
                    float delta = y - lastY;
                    if (pane == 2) {
                        if (downX < getWidth() * 0.45f) {
                            listScroll = Math.max(0, Math.min(Math.max(0, neededHeight - viewHeight + 80 * s), listScroll - delta));
                        }
                    } else {
                        scrollY = Math.max(0, Math.min(Math.max(0, neededHeight - viewHeight), scrollY - delta));
                    }
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                downX = x;
                downY = y;
                dragStartCx = viewCx;
                dragStartCy = viewCy;
                return true;
            case MotionEvent.ACTION_UP:
                stopCraftHold();
                if (scaling) { scaling = false; return true; }
                finishTouch(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                stopCraftHold();
                dragging = false;
                edgeZone = 0;
                edgeStrength = 0;
                scrolling = false;
                hover = null;
                return true;
            default:
                return true;
        }
    }

    private void maybeRefreshMap(boolean force) {
        if (mapBitmap == null) return;
        float centerX = mapLeft + mapCols * mapStep / 2f;
        float centerY = mapTop + mapRows * mapStep / 2f;
        float limitX = Math.max(1, requestCols) * mapStep * 0.18f;
        float limitY = Math.max(1, requestRows) * mapStep * 0.18f;
        if (force || Math.abs(viewCx - centerX) > limitX || Math.abs(viewCy - centerY) > limitY) {
            mapWanted = true;
            refresh.run();
        }
    }

    private void finishTouch(float x, float y) {
        if (pane == 3 && downHit == null && mapBitmap != null && !sheetOpen) {
            if (!follow) maybeRefreshMap(true);
            return;
        }
        if (dragging) {
            Hit target = hover;
            dragging = false;
            hover = null;
            int zone = edgeZone;
            float strength = edgeStrength;
            edgeZone = 0;
            edgeStrength = 0;
            if (zone != 0 && strength > 0.22f && downHit != null) {
                String edge = downHit.group == 5 ? "take" : zone == 1 ? "drop" : "use";
                if (zone == 3) edge = "trashswap";
                if (zone == 4) edge = "favorite";
                act(edge, downHit.group, downHit.index, slotData(downHit.group, downHit.index));
                return;
            }
            if (target != null && downHit != null && (target.group != downHit.group || target.index != downHit.index)) {
                transfer(downHit.group, downHit.index, target.group, target.index);
            }
            return;
        }
        hover = null;
        if (scrolling) { scrolling = false; return; }
        Hit hit = downHit;
        if (hit == null) return;
        Hit now = hitAt(x, y);
        if (now == null || now.type != hit.type || now.group != hit.group || now.index != hit.index
            || !java.util.Objects.equals(now.name, hit.name)) return;
        boolean longPress = SystemClock.uptimeMillis() - downAt > 420;
        switch (hit.type) {
            case 1: openPane(hit.index); break;
            case 2:
                if (longPress && hit.name.startsWith("buff_")) cancelBuff(Integer.parseInt(hit.name.substring(5)));
                else pressButton(hit.name);
                break;
            case 3: selectedRecipe = hit.index; break;
            case 5: shopSelection = hit.index; break;
            case 9: sheetOpen = false; recipeViewOpen = false; splitMode = 0; sound("close"); break;
            case 4: break;
            case 8: break;
            default: tapSlot(hit, longPress); break;
        }
    }

    private Hit hitAt(float x, float y) {
        for (int index = hits.size() - 1; index >= 0; index--) {
            Hit hit = hits.get(index);
            if (hit.rect.contains(x, y)) return hit;
        }
        return null;
    }

    /** Ends a buff (a tap on a pet, a long press on anything else); debuffs cannot be removed. */
    private void cancelBuff(int index) {
        JSONArray buffs = latest == null ? null : latest.optJSONArray("buffs");
        JSONArray buff = buffs == null ? null : buffs.optJSONArray(index);
        if (buff == null) return;
        sound("tick");
        if (buff.optBoolean(2)) {
            feedback = Strings.get("buff_debuff");
            feedbackLocal = true;
            feedbackUntil = SystemClock.uptimeMillis() + 2200;
            return;
        }
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", "cancelbuff");
        TlProPanel.put(command, "type", buff.optInt(0));
        TlProPanel.put(command, "slot", buff.optInt(5, index));
        send.accept(command);
        feedback = Strings.get("buff_removed") + ": " + buff.optString(3);
        feedbackLocal = true;
        feedbackUntil = SystemClock.uptimeMillis() + 2200;
    }

    private void pressButton(String name) {
        if (!name.startsWith("sheet_") && !name.startsWith("chest_")) sound("tick");
        if (name.startsWith("sheet_")) {
            sheetAction(name.substring(6));
            return;
        }
        if (name.equals("chat")) {
            chatOpen = !chatOpen;
            chatLines = null;
            lastChatAsk = 0;
            return;
        }
        if (name.startsWith("chat_") || name.startsWith("key_") || name.startsWith("emote_")) {
            chatButton(name);
            return;
        }
        if (name.startsWith("near_")) {
            if (name.equals("near_recipes")) {
                craftTab = 2;
                openPane(2);
                return;
            }
            JSONObject command = op("act");
            TlProPanel.put(command, "kind", "talk");
            TlProPanel.put(command, "npc", Integer.parseInt(name.substring(name.lastIndexOf('_') + 1)));
            send.accept(command);
            return;
        }
        if (name.startsWith("filter_")) {
            inventoryFilter = Integer.parseInt(name.substring(7));
            return;
        }
        if (name.startsWith("ctab_")) {
            craftTab = Integer.parseInt(name.substring(5));
            scrollY = 0;
            return;
        }
        if (name.equals("quickstack")) {
            act("quickstackall", 0, 0, null);
            return;
        }
        if (name.startsWith("banner_")) {
            if (name.equals("banner_all")) {
                JSONArray banners = latest == null ? null : latest.optJSONArray("banners");
                for (int index = 0; banners != null && index < banners.length(); index++) {
                    JSONArray banner = banners.optJSONArray(index);
                    if (banner != null) claimBanner(banner.optInt(0), Math.min(99, banner.optInt(2)));
                }
            } else {
                String[] parts = name.split("_");
                claimBanner(Integer.parseInt(parts[1]), 1);
            }
            return;
        }
        if (name.startsWith("rv_")) {
            switch (name) {
                case "rv_close": recipeViewOpen = false; sound("close"); break;
                case "rv_makes": recipeViewTab = 0; recipeViewPage = 0; break;
                case "rv_uses": recipeViewTab = 1; recipeViewPage = 0; break;
                case "rv_prev": recipeViewPage = Math.max(0, recipeViewPage - 1); break;
                case "rv_next": recipeViewPage++; break;
                default: break;
            }
            return;
        }
        if (name.startsWith("house_")) {
            // house_check_<npc> / house_move_<npc>: the game's own room check at the player's position
            String[] parts = name.split("_");
            JSONObject command = op("act");
            TlProPanel.put(command, "kind", "housing");
            TlProPanel.put(command, "command", parts[1]);
            TlProPanel.put(command, "npc", Integer.parseInt(parts[2]));
            send.accept(command);
            return;
        }
        if (name.startsWith("loadout_") || name.startsWith("team_") || name.equals("pvp")) {
            JSONObject command = op("act");
            TlProPanel.put(command, "kind", name.startsWith("loadout_") ? "loadout" : name.equals("pvp") ? "pvp" : "team");
            if (!name.equals("pvp")) TlProPanel.put(command, "value", Integer.parseInt(name.substring(name.indexOf('_') + 1)));
            send.accept(command);
            return;
        }
        if (name.startsWith("buff_")) {
            JSONArray buffs = latest == null ? null : latest.optJSONArray("buffs");
            JSONArray buff = buffs == null ? null : buffs.optJSONArray(Integer.parseInt(name.substring(5)));
            if (buff != null && buff.optBoolean(4)) {
                // a pet or light pet: tapping its icon sends it away, like clicking the buff in the game
                cancelBuff(Integer.parseInt(name.substring(5)));
            } else if (buff != null) {
                int seconds = buff.optInt(1) / 60;
                feedback = buff.optString(3) + (seconds > 0 ? "  " + (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60) : "");
                feedbackLocal = true;
                feedbackUntil = SystemClock.uptimeMillis() + 2600;
            }
            return;
        }
        if (name.startsWith("chest_")) {
            JSONObject command = op("act");
            TlProPanel.put(command, "kind", "chest");
            TlProPanel.put(command, "command", name.substring(6));
            putContainer(command);
            send.accept(command);
            return;
        }
        if (name.startsWith("shop_")) {
            trade(name.substring(5), -1);
            return;
        }
        if (name.startsWith("language_")) {
            prefs.edit().putString("lang", name.substring(9)).apply();
            applyLanguage(null);
            languagePicker = false;
            return;
        }
        switch (name) {
            case "barflip":
                heartsMode = !heartsMode;
                flipAt = SystemClock.uptimeMillis();
                break;
            case "lang":
                languagePicker = true;
                break;
            case "info":
                infoOpen = true;
                break;
            case "dismiss_overlay":
                languagePicker = false;
                infoOpen = false;
                break;
            case "hud":
                if (latest != null && latest.optBoolean("hudAvailable")) {
                    hideRequested = !hideRequested;
                    refresh.run();
                }
                break;
            case "sort":
                act("sort", 0, 0, null);
                break;
            case "zin": changeZoom(-1); break;
            case "zout": changeZoom(1); break;
            case "center": follow = true; refresh.run(); break;
            case "prev": if (recipePage > 0) { recipePage--; revision++; refresh.run(); } break;
            case "next": recipePage++; revision++; refresh.run(); break;
            default: break;
        }
    }

    private long lastTapAt;
    private int lastTapGroup = -1, lastTapIndex = -1;
    private long shakeAt;
    private int shakeGroup = -1, shakeIndex = -1;
    private int pendingGroup = -1, pendingIndex;
    private final Runnable openPendingSheet = () -> {
        if (pendingGroup >= 0) openSheet(pendingGroup, pendingIndex);
        pendingGroup = -1;
    };

    private int freeSlotFor(int sourceIndex) {
        JSONArray inventory = latest == null ? null : latest.optJSONArray("inventory");
        if (inventory == null) return -1;
        for (int index = 10; index < Math.min(50, inventory.length()); index++) {
            if (index != sourceIndex && typeOf(inventory.optJSONArray(index)) == 0) return index;
        }
        for (int index = 0; index < Math.min(10, inventory.length()); index++) {
            if (index != sourceIndex && typeOf(inventory.optJSONArray(index)) == 0) return index;
        }
        return -1;
    }

    /** Double tap on a stack: put half of it into the first free slot; with no room the stack just shakes. */
    private void splitInHalf(int group, int index) {
        int free = freeSlotFor(index);
        if (free < 0) {
            shakeGroup = group;
            shakeIndex = index;
            shakeAt = SystemClock.uptimeMillis();
            feedback = Strings.get("no_room");
            feedbackLocal = true;
            feedbackUntil = shakeAt + 2000;
            return;
        }
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", "split");
        TlProPanel.put(command, "amount", "half");
        TlProPanel.put(command, "from", new JSONArray().put(group).put(index));
        TlProPanel.put(command, "expectFrom", expected(slotData(group, index)));
        TlProPanel.put(command, "to", new JSONArray().put(0).put(free));
        TlProPanel.put(command, "expectTo", expected(slotData(0, free)));
        send.accept(command);
    }

    private void tapSlot(Hit hit, boolean longPress) {
        if (latest == null) return;
        if (pane == 2 && craftTab == 2 && hit.group == 0 && !sheetOpen) {
            showRecipesFor(typeOf(slotData(0, hit.index)));
            return;
        }
        long tapTime = SystemClock.uptimeMillis();
        JSONArray tapped = slotData(hit.group, hit.index);
        if (splitMode == 0 && hit.group == 0 && lastTapGroup == 0 && lastTapIndex == hit.index && tapTime - lastTapAt < 340
            && typeOf(tapped) != 0 && tapped.optInt(1) >= 2) {
            handler.removeCallbacks(openPendingSheet);
            pendingGroup = -1;
            lastTapAt = 0;
            lastTapGroup = -1;
            chosenGroup = -1;
            splitInHalf(hit.group, hit.index);
            return;
        }
        lastTapAt = tapTime;
        lastTapGroup = hit.group;
        lastTapIndex = hit.index;
        if (splitMode != 0) {
            JSONArray data = slotData(hit.group, hit.index);
            if (hit.group == 0 && typeOf(data) == 0) {
                JSONObject command = op("act");
                TlProPanel.put(command, "kind", "split");
                TlProPanel.put(command, "amount", splitMode == 2 ? "half" : "one");
                TlProPanel.put(command, "from", new JSONArray().put(sheetGroup).put(sheetIndex));
                TlProPanel.put(command, "expectFrom", expected(slotData(sheetGroup, sheetIndex)));
                TlProPanel.put(command, "to", new JSONArray().put(hit.group).put(hit.index));
                TlProPanel.put(command, "expectTo", expected(data));
                send.accept(command);
            }
            splitMode = 0;
            return;
        }
        if (hit.group == 6) {
            if (chosenGroup >= 0) {
                transfer(chosenGroup, chosenIndex, 6, 0);
                chosenGroup = -1;
            } else if (typeOf(slotData(6, 0)) != 0) {
                chosenGroup = 6;
                chosenIndex = 0;
            }
            return;
        }
        boolean same = chosenGroup == hit.group && chosenIndex == hit.index;
        if (same) {
            chosenGroup = -1;
            return;
        }
        JSONArray data = slotData(hit.group, hit.index);
        if (chosenGroup >= 0) {
            transfer(chosenGroup, chosenIndex, hit.group, hit.index);
            chosenGroup = -1;
            return;
        }
        boolean hotbar = hit.group == 0 && hit.index < 10;
        if (hotbar && !longPress) {
            JSONObject action = op("select");
            TlProPanel.put(action, "slot", hit.index);
            send.accept(action);
        } else if (typeOf(data) != 0) {
            if (hit.group == 0 && data.optInt(1) >= 2) {
                pendingGroup = hit.group;
                pendingIndex = hit.index;
                handler.removeCallbacks(openPendingSheet);
                handler.postDelayed(openPendingSheet, 300);
            } else {
                openSheet(hit.group, hit.index);
            }
        }
    }

    private void claimBanner(int banner, int amount) {
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", "bannerclaim");
        TlProPanel.put(command, "banner", banner);
        TlProPanel.put(command, "amount", amount);
        send.accept(command);
    }

    /** Ask the mod for every recipe that makes / uses an item (the Guide's recipe lookup) and open the browser. */
    private void showRecipesFor(int type) {
        if (type <= 0) return;
        JSONObject command = op("recipesfor");
        TlProPanel.put(command, "type", type);
        send.accept(command);
        recipeView = null;
        recipeViewOpen = true;
        recipeViewTab = 0;
        recipeViewPage = 0;
    }

    private void sheetAction(String name) {
        switch (name) {
            case "recipes":
                showRecipesFor(typeOf(slotData(sheetGroup, sheetIndex)));
                sheetOpen = false;
                break;
            case "sell":
                trade("sell", sheetIndex);
                sheetOpen = false;
                break;
            case "close":
                sheetOpen = false;
                sound("close");
                splitMode = 0;
                break;
            case "split1":
                splitMode = 1;
                sheetOpen = false;
                break;
            case "split2":
                splitMode = 2;
                sheetOpen = false;
                break;
            case "trash":
                if (trashArmed()) {
                    act("trash", sheetGroup, sheetIndex, slotData(sheetGroup, sheetIndex));
                    sheetOpen = false;
                } else {
                    trashArmedAt = SystemClock.uptimeMillis();
                }
                break;
            case "drop":
                JSONObject hotbar = op("select");
                TlProPanel.put(hotbar, "slot", sheetIndex);
                if (latest != null && latest.optInt("hotbar") != sheetIndex) send.accept(hotbar);
                final int group = sheetGroup;
                final int index = sheetIndex;
                handler.postDelayed(() -> act("drop", group, index, slotData(group, index)), 450);
                sheetOpen = false;
                break;
            default:
                act(name, sheetGroup, sheetIndex, slotData(sheetGroup, sheetIndex));
                sheetOpen = false;
                break;
        }
    }

    private void act(String kind, int group, int index, JSONArray data) {
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", kind);
        if (data != null) {
            TlProPanel.put(command, "from", new JSONArray().put(group).put(index));
            TlProPanel.put(command, "expectFrom", expected(data));
        }
        if (group == 5 || kind.equals("deposit")) putContainer(command);
        if (kind.equals("trashswap")) TlProPanel.put(command, "expectTrash", expected(slotData(6, 0)));
        if (kind.equals("use")) TlProPanel.put(command, "exact", true);
        send.accept(command);
    }

    private void putContainer(JSONObject command) {
        JSONObject chest = chest();
        if (chest != null) TlProPanel.put(command, "container", chest.optInt("id"));
    }

    private void trade(String kind, int inventorySlot) {
        if (latest == null) return;
        JSONObject shop = shop();
        JSONObject command = op("act");
        TlProPanel.put(command, "kind", "shop");
        TlProPanel.put(command, "command", kind);
        TlProPanel.put(command, "talk", latest.optInt("talk", -1));
        if (shop != null) TlProPanel.put(command, "shop", shop.optInt("id"));
        if (kind.equals("buy")) {
            TlProPanel.put(command, "slot", shopSelection);
            TlProPanel.put(command, "expectFrom", expected(slotData(7, shopSelection)));
            JSONArray price = shopPrice();
            TlProPanel.put(command, "price", price == null ? JSONObject.NULL : price);
        } else if (kind.equals("sell")) {
            TlProPanel.put(command, "from", new JSONArray().put(0).put(inventorySlot));
            TlProPanel.put(command, "expectFrom", expected(slotData(0, inventorySlot)));
        }
        send.accept(command);
    }

    private static JSONArray expected(JSONArray data) {
        return new JSONArray().put(typeOf(data)).put(data == null ? 0 : data.optInt(1)).put(data == null ? 0 : data.optInt(2))
            .put(data != null && data.optBoolean(3));
    }

    private void transfer(int fromGroup, int fromIndex, int toGroup, int toIndex) {
        if (fromGroup == 6 && toGroup == 6) return;
        if (toGroup == 6 || fromGroup == 6) {
            int group = toGroup == 6 ? fromGroup : toGroup;
            int index = toGroup == 6 ? fromIndex : toIndex;
            if (group != 0 && group != 5) return;
            JSONObject command = op("act");
            TlProPanel.put(command, "kind", "trashswap");
            TlProPanel.put(command, "from", new JSONArray().put(group).put(index));
            TlProPanel.put(command, "expectFrom", expected(slotData(group, index)));
            TlProPanel.put(command, "expectTrash", expected(slotData(6, 0)));
            TlProPanel.put(command, "recover", fromGroup == 6);
            if (group == 5) putContainer(command);
            send.accept(command);
            return;
        }
        JSONObject command = op("transfer");
        TlProPanel.put(command, "from", new JSONArray().put(fromGroup).put(fromIndex));
        TlProPanel.put(command, "expectFrom", expected(slotData(fromGroup, fromIndex)));
        TlProPanel.put(command, "to", new JSONArray().put(toGroup).put(toIndex));
        TlProPanel.put(command, "expectTo", expected(slotData(toGroup, toIndex)));
        if (fromGroup == 5 || toGroup == 5) putContainer(command);
        send.accept(command);
    }

    // ---- crafting: tap crafts once; holding repeats like the game

    private void startCraftHold(Hit hit) {
        craftHeld = true;
        doCraft(hit.index);
        handler.removeCallbacks(craftRepeat);
        handler.postDelayed(craftRepeat, 520);
    }

    private void stopCraftHold() {
        craftHeld = false;
        handler.removeCallbacks(craftRepeat);
    }

    private void repeatCraft() {
        if (!craftHeld) return;
        doCraft(selectedRecipe);
        handler.postDelayed(craftRepeat, 240);
    }

    private void doCraft(int id) {
        JSONObject list = latest == null ? null : latest.optJSONObject("recipes");
        JSONArray entries = list == null ? null : list.optJSONArray("entries");
        JSONArray entry = findRecipe(entries, id);
        if (entry == null) return;
        JSONObject command = op("craft");
        TlProPanel.put(command, "id", id);
        TlProPanel.put(command, "expect", expected(entry.optJSONArray(1)));
        send.accept(command);
    }
}
