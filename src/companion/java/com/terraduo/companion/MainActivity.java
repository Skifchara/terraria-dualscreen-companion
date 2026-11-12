package com.terraduo.companion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * STAGE 1 tester. Shows the REAL inventory read from the in-game bridge (127.0.0.1:38655) and sends
 * two actions: tap a hotbar slot -> SELECT, tap a slot then another -> MOVE. Nothing is faked: when the
 * bridge is not reachable the grid stays empty and the status line says so.
 * Items are shown by numeric type id (names/icons come later, from the game itself).
 */
public class MainActivity extends Activity {
    static final int COLS = 10, ROWS = 5;

    volatile int[][] inv = new int[50][3];
    volatile int sel = -1;
    volatile boolean dead;
    volatile String status = "connecting...";
    volatile boolean running = true;
    volatile int pendingFrom = -1;
    int nextId = 1;
    Socket sock;
    GridView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Keep input focus on the game's display: gamepad key events are routed to the focused display
        // (the controller has no display association on Thor), so this window must never take focus.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        view = new GridView(this);
        setContentView(view);
        // Launching on Display 4 makes it the focused display; hand focus back to the game on Display 0
        // (the already-running singleTask Activity is just brought to front, not restarted).
        view.postDelayed(this::returnFocusToGame, 600);
    }

    void returnFocusToGame() {
        if (!FocusHelper.returnToGame(this, FocusHelper.TERRARIA)) status = "refocus failed";
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        new Thread(this::pollLoop, "terraduo-poll").start();
    }

    @Override protected void onPause() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        super.onPause();
    }

    void pollLoop() {
        while (running) {
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress("127.0.0.1", 38655), 1000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                OutputStream out = sock.getOutputStream();
                status = "connected";
                while (running) {
                    out.write("SNAP\n".getBytes());
                    out.flush();
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("ACK ")) { status = line; continue; }
                        parse(line);
                        break;
                    }
                    if (line == null) throw new java.io.IOException("closed");
                    view.postInvalidate();
                    Thread.sleep(150);
                }
            } catch (Exception e) {
                status = "bridge unreachable: " + e.getClass().getSimpleName();
                view.postInvalidate();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    // {"rev":1,"dead":false,"sel":0,"inv":[[type,stack,prefix],...]}
    void parse(String s) {
        try {
            dead = s.contains("\"dead\":true");
            int i = s.indexOf("\"sel\":") + 6;
            sel = Integer.parseInt(s.substring(i, s.indexOf(',', i)));
            int p = s.indexOf("\"inv\":[") + 7;
            int[][] n = new int[50][3];
            int slot = 0;
            while (slot < 50) {
                p = s.indexOf('[', p);
                if (p < 0) break;
                int e = s.indexOf(']', p);
                String[] f = s.substring(p + 1, e).split(",");
                n[slot][0] = Integer.parseInt(f[0]);
                n[slot][1] = Integer.parseInt(f[1]);
                n[slot][2] = Integer.parseInt(f[2]);
                slot++;
                p = e + 1;
            }
            inv = n;
        } catch (Exception e) {
            status = "parse error";
        }
    }

    void send(final String cmd) {
        new Thread(() -> {
            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", 38655), 1000);
                c.getOutputStream().write((cmd + "\n").getBytes());
                c.getOutputStream().flush();
                new BufferedReader(new InputStreamReader(c.getInputStream())).readLine();
            } catch (Exception e) {
                status = "send failed: " + e.getClass().getSimpleName();
            }
        }).start();
    }

    void tap(int i) {
        if (pendingFrom < 0) {
            if (i < 10) send("SELECT " + (nextId++) + " " + i);  // a hotbar tap selects the slot
            if (inv[i][0] != 0) pendingFrom = i;                 // and arms a possible move
        } else {
            if (pendingFrom != i) send("MOVE " + (nextId++) + " " + pendingFrom + " " + i);
            pendingFrom = -1;
        }
    }
}
