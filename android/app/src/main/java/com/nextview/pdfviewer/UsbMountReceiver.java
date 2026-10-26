package com.nextview.pdfviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Launches the viewer when Android reports that USB storage is mounting. The
 * manifest also has a USB-host Activity filter for firmware which skips this
 * media broadcast. */
public class UsbMountReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_MEDIA_CHECKING.equals(action)
                && !Intent.ACTION_MEDIA_MOUNTED.equals(action)) return;
        launchViewer(context);
    }

    private void launchViewer(Context context) {
        try {
            Intent launchIntent;
            try {
                PackageManager packageManager = context.getPackageManager();
                launchIntent = packageManager.getLaunchIntentForPackage(context.getPackageName());
            } catch (Exception ignored) {
                launchIntent = null;
            }
            if (launchIntent == null) {
                launchIntent = new Intent(context, MainActivity.class);
                launchIntent.setAction(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(launchIntent);
        } catch (Exception ignored) {
            // TV firmware may still reject a background activity launch.
        }
    }
}
