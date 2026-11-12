package com.terraduo.companion;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TlProActivity extends Activity {
    private static final int PORT = 38656;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private ExecutorService worker;
    private TerraPanel panel;
    private JSONObject nextAction;
    private boolean awaitingReply;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (moveToSecondDisplay()) {
            // keep this window for a moment so the system has the new task safely on the other screen
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        panel = new TerraPanel(this, this::queue, this::refresh);
        setContentView(panel);
        // Opening this window on Display 4 steals input focus from the game (the controller follows the
        // focused display), so hand it back to the game on Display 0 right after start.
        main.postDelayed(() -> FocusHelper.returnToGame(this, FocusHelper.TL_PRO), 700);
    }

    /** Started from the launcher on the main screen: reopen on the second (bottom) screen if the device has one. */
    @SuppressWarnings("deprecation")
    private boolean moveToSecondDisplay() {
        try {
            if (getWindowManager().getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return false;
            DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display target = null;
            for (Display display : manager.getDisplays()) {
                if (display.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                if (target == null || (display.getFlags() & Display.FLAG_PRESENTATION) != 0) target = display;
            }
            android.util.Log.w("Terraduo", "second display: " + (target == null ? "none" : target.getDisplayId()));
            if (target == null) return false;
            Intent intent = new Intent(this, TlProActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target.getDisplayId()).toBundle());
            return true;
        } catch (RuntimeException error) {
            android.util.Log.w("Terraduo", "second display failed", error);
            return false;   // stay where we are: single-screen testing
        }
    }

    @Override protected void onResume() {
        super.onResume();
        worker = Executors.newSingleThreadExecutor();
        panel.disconnected();
        refresh();
    }

    @Override protected void onPause() {
        main.removeCallbacks(poll);
        if (worker != null) worker.shutdownNow();
        worker = null;
        awaitingReply = false;
        nextAction = null;
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void queue(JSONObject action) {
        if (nextAction == null && worker != null) {
            nextAction = action;
            refresh();
        }
    }

    private void refresh() {
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private void poll() {
        ExecutorService running = worker;
        if (running == null || awaitingReply) return;
        boolean action = nextAction != null;
        JSONObject request = panel.request(nextAction);
        int revision = panel.revision();
        nextAction = null;
        awaitingReply = true;
        running.execute(() -> {
            JSONObject response = exchange(request);
            main.post(() -> {
                if (worker != running) return;
                awaitingReply = false;
                if (response == null) {
                    panel.disconnected();
                } else {
                    panel.accept(response, revision, action);
                }
                main.removeCallbacks(poll);
                main.postDelayed(poll, nextAction == null ? panel.pollInterval() : 0);
            });
        });
    }

    private JSONObject exchange(JSONObject request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            socket.setSoTimeout(1350);
            socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            BufferedReader input = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = input.readLine();
            return reply == null ? null : new JSONObject(reply);
        } catch (Exception error) {
            return null;
        }
    }
}
