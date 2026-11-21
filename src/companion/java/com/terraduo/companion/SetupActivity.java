package com.terraduo.companion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The all-in-one installer: checks that Terraria and TL Pro are present, decodes the game's sprites on the device, hands the mod
 * pack to TL Pro and shows live whether the mod answers in the running game. Nothing needs a PC.
 */
public final class SetupActivity extends Activity {
    static final String GAME = "com.and.games505.TerrariaPaid";
    static final String TLPRO = "com.pixelcurves.terlauncher";

    private static final int BACKGROUND = 0xff121b25;
    private static final int CARD = 0xff1b2a3d;
    private static final int GREEN = 0xff347461;
    private static final int BLUE = 0xff2a3a6a;
    private static final int GOLD = 0xfff0c860;
    private static final int MUTED = 0xffb5c5d4;
    private static final int OK = 0xff7be07b;
    private static final int WARN = 0xffffb06a;

    /** The running sprite extraction; lives outside the activity so a rotation or a pause does not lose it. */
    private static final class Job {
        static volatile boolean running;
        static volatile boolean cancel;
        static volatile int done;
        static volatile int total = 1;
        static volatile String what = "";
        static volatile String error;
    }

    private static final class JobListener implements SpriteExtractor.Listener {
        @Override public void progress(int done, int total, String what) {
            Job.done = done;
            Job.total = Math.max(1, total);
            Job.what = what;
        }

        @Override public boolean cancelled() {
            return Job.cancel;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile int modProtocol = -1;
    private volatile int watchGeneration;
    private String notice = "";

    private TextView terrariaStatus;
    private TextView tlproStatus;
    private TextView spritesStatus;
    private TextView modStatus;
    private TextView modLive;
    private TextView startNote;
    private TextView terrariaButton;
    private TextView tlproButton;
    private TextView spritesButton;
    private TextView cancelButton;
    private TextView modButton;
    private TextView startButton;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("terraduo", MODE_PRIVATE);
        String choice = prefs.getString("lang", "auto");
        Strings.setLanguage(Strings.Language.fromCulture(choice.equals("auto") ? Locale.getDefault().toLanguageTag() : choice));
        if (!getIntent().getBooleanExtra("force", false) && alreadySetUp()) {
            // everything is in place: go straight to the bottom screen like before
            startActivity(new Intent(this, TlProActivity.class));
            finish();
            return;
        }
        build();
    }

    // ---------------------------------------------------------------- state

    private int packVersion() {
        try (ZipInputStream zip = new ZipInputStream(getAssets().open(ModProvider.ASSET))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals("Settings.json")) continue;
                StringBuilder text = new StringBuilder();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = zip.read(buffer)) > 0) text.append(new String(buffer, 0, n, "UTF-8"));
                return new org.json.JSONObject(text.toString()).optInt("version", 0);
            }
        } catch (Exception ignored) {
            // no pack in this build
        }
        return 0;
    }

    private File atlasDir() {
        File base = getExternalFilesDir(null);
        return base == null ? null : new File(base, "atlas");
    }

    private int atlasVersion() {
        File dir = atlasDir();
        if (dir == null) return 0;
        File marker = new File(dir, "atlas-version.txt");
        if (!marker.isFile()) return 0;
        try (InputStream in = new java.io.FileInputStream(marker)) {
            byte[] data = new byte[16];
            int n = in.read(data);
            return n <= 0 ? 0 : Integer.parseInt(new String(data, 0, n, "US-ASCII").trim());
        } catch (Exception error) {
            return 0;
        }
    }

    private int atlasPages() {
        File dir = atlasDir();
        String[] names = dir == null ? null : dir.list((d, n) -> n.endsWith(".png"));
        return names == null ? 0 : names.length;
    }

    private boolean spritesReady() {
        return atlasPages() > 50 && atlasVersion() >= SpriteExtractor.VERSION;
    }

    private boolean alreadySetUp() {
        int pack = packVersion();
        int satisfied = Math.max(prefs.getInt("mod_sent_version", 0), prefs.getInt("mod_ok_version", 0));
        return spritesReady() && prefs.getBoolean("started_once", false) && (pack == 0 || satisfied >= pack);
    }

    private PackageInfo info(String name) {
        try {
            return getPackageManager().getPackageInfo(name, 0);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private List<File> gameApks() {
        List<File> apks = new ArrayList<>();
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(GAME, 0);
            apks.add(new File(ai.sourceDir));
            if (ai.splitSourceDirs != null) for (String split : ai.splitSourceDirs) apks.add(new File(split));
        } catch (PackageManager.NameNotFoundException ignored) {
            // not installed
        }
        return apks;
    }

    // ---------------------------------------------------------------- views

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView button(String label, int color, View.OnClickListener click) {
        TextView view = text(label, 17, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(52));
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));
        view.setBackground(shape);
        view.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(dp(14));
        card.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        card.setLayoutParams(params);
        card.addView(text(title, 19, GOLD, true));
        parent.addView(card);
        return card;
    }

    private TextView line(LinearLayout card, float sp, int color) {
        TextView view = text("", sp, color, false);
        view.setPadding(0, dp(6), 0, 0);
        card.addView(view);
        return view;
    }

    private void build() {
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        // on a wide landscape screen keep the content in a readable column
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(660));
        scroll.addView(root, new android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        root.addView(text(Strings.get("setup_title"), 28, Color.WHITE, true));
        TextView intro = text(Strings.get("setup_intro"), 15, MUTED, false);
        intro.setPadding(0, dp(6), 0, 0);
        root.addView(intro);

        LinearLayout c1 = card(root, Strings.get("st_terraria"));
        terrariaStatus = line(c1, 16, Color.WHITE);
        terrariaButton = button(Strings.get("get_play"), BLUE, v -> openStore(GAME));
        c1.addView(terrariaButton);

        LinearLayout c2 = card(root, Strings.get("st_tlpro"));
        tlproStatus = line(c2, 16, Color.WHITE);
        tlproButton = button(Strings.get("get_play"), BLUE, v -> openStore(TLPRO));
        c2.addView(tlproButton);

        LinearLayout c3 = card(root, Strings.get("st_sprites"));
        spritesStatus = line(c3, 16, Color.WHITE);
        TextView note = line(c3, 13, MUTED);
        note.setText(Strings.get("sprites_note"));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        bar.topMargin = dp(10);
        progress.setLayoutParams(bar);
        c3.addView(progress);
        spritesButton = button(Strings.get("sprites_btn"), GREEN, v -> startSprites());
        c3.addView(spritesButton);
        cancelButton = button(Strings.get("cancel"), 0xff7a2a2f, v -> Job.cancel = true);
        c3.addView(cancelButton);

        LinearLayout c4 = card(root, Strings.get("st_mod"));
        modStatus = line(c4, 15, MUTED);
        modLive = line(c4, 16, Color.WHITE);
        modButton = button(Strings.get("mod_btn"), GREEN, v -> sendMod());
        c4.addView(modButton);
        TextView openTl = button(Strings.get("open_tlpro"), BLUE, v -> openApp(TLPRO));
        c4.addView(openTl);

        LinearLayout c5 = card(root, Strings.get("st_start"));
        startNote = line(c5, 14, MUTED);
        startButton = button(Strings.get("start_btn"), GREEN, v -> startCompanion());
        c5.addView(startButton);
        setContentView(scroll);
    }

    // ---------------------------------------------------------------- refresh

    @Override protected void onResume() {
        super.onResume();
        if (terrariaStatus == null) return;
        startWatcher();
        refresh();
    }

    @Override protected void onPause() {
        watchGeneration++;
        main.removeCallbacks(tick);
        super.onPause();
    }

    /** Asks the game's mod in the background, so the screen can say whether the mod answers. */
    private void startWatcher() {
        final int generation = ++watchGeneration;
        Thread thread = new Thread(() -> {
            while (generation == watchGeneration) {
                modProtocol = ModLink.protocol();
                if (modProtocol >= TerraPanel.EXPECTED_PROTOCOL) prefs.edit().putInt("mod_ok_version", packVersion()).apply();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException error) {
                    return;
                }
            }
        }, "terraduo-mod-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void refresh() {
        if (terrariaStatus == null) return;
        PackageInfo terraria = info(GAME);
        PackageInfo tlpro = info(TLPRO);
        boolean unreadable = terraria != null && SpriteExtractor.findBundleApk(gameApks()) == null;
        terrariaStatus.setText(terraria == null ? Strings.get("inst_not")
            : unreadable ? Strings.get("game_unreadable") : String.format(Strings.get("inst_ver"), terraria.versionName));
        terrariaStatus.setTextColor(terraria != null && !unreadable ? OK : WARN);
        terrariaButton.setVisibility(terraria == null ? View.VISIBLE : View.GONE);
        tlproStatus.setText(tlpro == null ? Strings.get("inst_not") : String.format(Strings.get("inst_ver"), tlpro.versionName));
        tlproStatus.setTextColor(tlpro == null ? WARN : OK);
        tlproButton.setVisibility(tlpro == null ? View.VISIBLE : View.GONE);

        boolean ready = spritesReady();
        int pages = atlasPages();
        boolean canRun = terraria != null && !unreadable && !Job.running;
        if (Job.running) {
            spritesStatus.setText(Strings.get("sprites_working") + "  " + Job.what);
            spritesStatus.setTextColor(WARN);
        } else if (Job.error != null) {
            spritesStatus.setText(String.format(Strings.get("sprites_failed"), Job.error));
            spritesStatus.setTextColor(WARN);
        } else if (ready) {
            spritesStatus.setText(String.format(Strings.get("sprites_ready"), pages));
            spritesStatus.setTextColor(OK);
        } else if (pages > 50) {
            spritesStatus.setText(Strings.get("sprites_old"));
            spritesStatus.setTextColor(WARN);
        } else {
            spritesStatus.setText(Strings.get("sprites_none"));
            spritesStatus.setTextColor(WARN);
        }
        progress.setVisibility(Job.running ? View.VISIBLE : View.GONE);
        progress.setProgress(Job.done * 1000 / Math.max(1, Job.total));
        spritesButton.setText(Strings.get(ready ? "sprites_redo" : "sprites_btn"));
        spritesButton.setAlpha(canRun ? 1f : 0.4f);
        spritesButton.setVisibility(Job.running ? View.GONE : View.VISIBLE);
        cancelButton.setVisibility(Job.running ? View.VISIBLE : View.GONE);

        int pack = packVersion();
        int sent = prefs.getInt("mod_sent_version", 0);
        modStatus.setText(String.format(Strings.get("mod_in_app"), pack) + "\n"
            + (sent == 0 ? Strings.get("mod_never") : String.format(Strings.get("mod_sent"), sent)));
        int protocol = modProtocol;
        if (protocol >= TerraPanel.EXPECTED_PROTOCOL) {
            modLive.setText(String.format(Strings.get("mod_running"), protocol));
            modLive.setTextColor(OK);
        } else if (protocol >= 0) {
            modLive.setText(String.format(Strings.get("mod_old"), protocol));
            modLive.setTextColor(WARN);
        } else {
            modLive.setText(notice.isEmpty() ? Strings.get("mod_waiting") : notice);
            modLive.setTextColor(notice.isEmpty() ? MUTED : GOLD);
        }
        modButton.setAlpha(tlpro != null ? 1f : 0.4f);
        startNote.setText(Strings.get(ready ? "start_note" : "start_need"));
        startButton.setAlpha(ready ? 1f : 0.4f);
        main.removeCallbacks(tick);
        main.postDelayed(tick, Job.running ? 400 : 2000);
    }

    private final Runnable tick = this::refresh;

    // ---------------------------------------------------------------- actions

    private void openStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private void openApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch);
        else openStore(pkg);
    }

    private void startCompanion() {
        if (!spritesReady()) return;
        prefs.edit().putBoolean("started_once", true).apply();
        startActivity(new Intent(this, TlProActivity.class));
    }

    private void startSprites() {
        if (Job.running || info(GAME) == null) return;
        Job.running = true;
        Job.cancel = false;
        Job.error = null;
        Job.done = 0;
        Job.total = 1;
        Job.what = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Context app = getApplicationContext();
        final List<File> apks = gameApks();
        final File out = atlasDir();
        final File files = getFilesDir();
        final File tmp = new File(getCacheDir(), "extract");
        Thread thread = new Thread(() -> {
            try {
                if (out == null) throw new IOException("No storage for the sprites");
                SpriteExtractor.run(apks, out, tmp, new JobListener());
                // crops made from older pages must be redone
                File[] crops = files.listFiles((d, n) -> n.startsWith("sprites"));
                if (crops != null) for (File crop : crops) deleteTree(crop);
            } catch (Throwable error) {
                Job.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            } finally {
                Job.running = false;
            }
        }, "terraduo-sprites");
        thread.start();
        refresh();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Hands the pack to TL Pro, which registers for ".tl" files and opens its own import. */
    private void sendMod() {
        if (info(TLPRO) == null) {
            openStore(TLPRO);
            return;
        }
        int pack = packVersion();
        String name = "Terraduo-Companion-mod-v" + pack + ".tl";
        try {
            ModProvider.cached(this);
            Uri uri = ModProvider.uri(name);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/octet-stream");
            intent.setPackage(TLPRO);
            intent.setClipData(ClipData.newRawUri("mod", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            prefs.edit().putInt("mod_sent_version", pack).apply();
            notice = Strings.get("mod_help");
        } catch (ActivityNotFoundException | IOException error) {
            notice = saveToDownloads(name) ? Strings.get("mod_saved") : String.format(Strings.get("sprites_failed"), error.getMessage());
        }
        refresh();
    }

    private boolean saveToDownloads(String name) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) return false;
            try (InputStream in = getAssets().open(ModProvider.ASSET); OutputStream out = getContentResolver().openOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
