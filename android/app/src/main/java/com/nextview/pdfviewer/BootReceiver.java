package com.nextview.pdfviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Restores the TV viewer after a reboot. It deliberately launches the
 * viewer first. It follows the same direct launch approach used by the
 * AWSPlayerTV reference app, including vendor quick-boot broadcasts. */
public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_REBOOT = "android.intent.action.REBOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !ACTION_QUICKBOOT_POWERON.equals(action)
                && !ACTION_HTC_QUICKBOOT_POWERON.equals(action)
                && !ACTION_REBOOT.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

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
            // Vendor firmware can block third-party boot launches. In that
            // case the user must enable the TV's vendor Auto-start permission.
        }
    }
}
