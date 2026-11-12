package com.terraduo.companion;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

/**
 * Launching an Activity on the bottom display makes that display the focused one. The built-in controller has
 * no display association, so its key events follow the focused display: without this the gamepad would stop
 * working in the game. Bringing the game's task to front on display 0 returns the focus (verified on Thor:
 * FocusedDisplayId goes back to 0 and the game process is not restarted).
 */
final class FocusHelper {
    static final String TL_PRO = "com.pixelcurves.terlauncher";
    static final String TERRARIA = "com.and.games505.TerrariaPaid";

    private FocusHelper() { }

    /** @return false when the game package is not installed or the launch was refused. */
    static boolean returnToGame(Activity from, String gamePackage) {
        try {
            Intent launch = from.getPackageManager().getLaunchIntentForPackage(gamePackage);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            from.startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
