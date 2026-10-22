package com.nextview.pdfviewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;

public class FilePickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final String MODULE_NAME = "FilePickerModule";
    private static final int PICK_PDF_REQUEST = 1001;
    private Promise mPickerPromise;

    public FilePickerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    @ReactMethod
    public void pickPDFs(Promise promise) {
        Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            promise.reject("NO_ACTIVITY", "Activity doesn't exist");
            return;
        }

        mPickerPromise = promise;

        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            currentActivity.startActivityForResult(intent, PICK_PDF_REQUEST);
        } catch (Exception e) {
            mPickerPromise.reject("PICK_ERROR", e.getMessage());
            mPickerPromise = null;
        }
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_PDF_REQUEST) {
            if (mPickerPromise == null) {
                return;
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null || data.getClipData() != null) {
                    int grantedFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    WritableArray uris = Arguments.createArray();
                    if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                        for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                            Uri selectedUri = data.getClipData().getItemAt(index).getUri();
                            if (selectedUri != null) {
                                persistReadPermission(activity, selectedUri, grantedFlags);
                                uris.pushString(selectedUri.toString());
                            }
                        }
                    } else if (uri != null) {
                        persistReadPermission(activity, uri, grantedFlags);
                        uris.pushString(uri.toString());
                    }
                    mPickerPromise.resolve(uris);
                } else {
                    mPickerPromise.reject("NO_URI", "No URI returned from picker");
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                mPickerPromise.reject("CANCELLED", "User cancelled the picker");
            } else {
                mPickerPromise.reject("ERROR", "Failed to pick file");
            }
            mPickerPromise = null;
        }
    }

    private void persistReadPermission(Activity activity, Uri uri, int grantedFlags) {
        if ((grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, grantedFlags);
        } catch (SecurityException ignored) {
            // Some document providers do not support persisted permissions. The
            // one-time read permission remains valid while this activity is open.
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        // Not needed for this implementation
    }
}
