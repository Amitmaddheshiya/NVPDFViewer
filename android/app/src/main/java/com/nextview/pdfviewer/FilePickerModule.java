package com.nextview.pdfviewer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collection;
import java.util.Locale;

public class FilePickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final String MODULE_NAME = "FilePickerModule";
    private static final int PICK_PDF_REQUEST = 1001;
    private static final int PICK_ADS_FOLDER_REQUEST = 1002;
    private static final String ADS_PREFS = "usb_ads";
    private static final String ADS_TREE_URI = "ads_tree_uri";
    private Promise mPickerPromise;
    private Promise mAdsFolderPromise;
    private BroadcastReceiver usbReceiver;

    public FilePickerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
        registerUsbReceiver();
    }

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    if (getReactApplicationContext().hasActiveReactInstance()) {
                        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit("usbAdsStorageChanged", null);
                    }
                } catch (Exception ignored) { }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                getReactApplicationContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else getReactApplicationContext().registerReceiver(usbReceiver, filter);
        } catch (Exception ignored) { }
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

    /** User selects the USB Ads folder once. Android grants durable access to
     * that exact folder even on scoped-storage Android TV devices. */
    @ReactMethod
    public void pickAdsFolder(Promise promise) {
        Activity activity = getCurrentActivity();
        if (activity == null) { promise.reject("NO_ACTIVITY", "TV activity is unavailable"); return; }
        mAdsFolderPromise = promise;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivityForResult(intent, PICK_ADS_FOLDER_REQUEST);
        } catch (Exception error) {
            mAdsFolderPromise = null;
            promise.reject("ADS_FOLDER_ERROR", error.getMessage(), error);
        }
    }

    /** Opens Android's owner-controlled storage page. Unlike ACTION_OPEN_DOCUMENT_TREE
     * it works on TV launchers that do not ship a DocumentsUI/file-manager app. */
    @ReactMethod
    public void requestUsbAdsAccess(Promise promise) {
        try {
            Activity activity = getCurrentActivity();
            if (activity == null) { promise.reject("NO_ACTIVITY", "TV activity is unavailable"); return; }
            // MediaStore fallback needs its normal media grants on Android 13+
            // even when a vendor exposes an All Files toggle.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(new String[]{
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO"
                }, 2001);
            }
            if (hasUsbStorageAccess()) { promise.resolve(true); return; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (Exception ignored) {
                    // Some TV firmware only exposes the generic All files page.
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                promise.resolve(false);
            } else {
                activity.requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, 2001);
                promise.resolve(false);
            }
        } catch (Exception error) { promise.reject("USB_PERMISSION_ERROR", error.getMessage(), error); }
    }

    @ReactMethod
    public void scanAdsFolder(Promise promise) {
        try {
            List<File> usbFiles = scanMountedUsbAds();
            if (!usbFiles.isEmpty()) { promise.resolve(toAdsArray(usbFiles)); return; }
        } catch (Exception ignored) {
            // The internal-storage and MediaStore fallbacks follow below.
        }
        try {
            // Only try this after removable storage. This keeps a pendrive's
            // Ads folder authoritative whenever it is connected.
            List<File> internalFiles = scanInternalStorageAds();
            if (!internalFiles.isEmpty()) { promise.resolve(toAdsArray(internalFiles)); return; }
        } catch (Exception ignored) {
            // MediaStore and document-tree fallbacks follow below.
        }
        try {
            List<AdEntry> mediaStoreFiles = scanMediaStoreAds();
            if (!mediaStoreFiles.isEmpty()) { promise.resolve(toAdsEntryArray(mediaStoreFiles)); return; }
        } catch (Exception ignored) { }
        String raw = getReactApplicationContext().getSharedPreferences(ADS_PREFS, 0).getString(ADS_TREE_URI, null);
        if (raw == null) {
            String message = hasUsbStorageAccess()
                    ? "No Ads folder was found. Put files in Pendrive/Ads, or in the TV internal storage Ads folder."
                    : "Allow All files access for NV PDF Viewer, then use Pendrive/Ads or the TV internal storage Ads folder.";
            promise.reject("NO_ADS_FOLDER", message);
            return;
        }
        try {
            DocumentFile root = DocumentFile.fromTreeUri(getReactApplicationContext(), Uri.parse(raw));
            if (root == null || !root.canRead()) throw new IllegalStateException("USB Ads folder is unavailable. Reconnect the pendrive and select Ads folder again.");
            List<DocumentFile> files = new ArrayList<>();
            collectAdFiles(root, files);
            Collections.sort(files, Comparator.comparing(file -> file.getName() == null ? "" : file.getName().toLowerCase()));
            WritableArray result = Arguments.createArray();
            for (DocumentFile file : files) result.pushMap(adItem(file.getUri().toString(), file.getName()));
            promise.resolve(result);
        } catch (Exception error) { promise.reject("ADS_SCAN_ERROR", error.getMessage(), error); }
    }

    private boolean hasUsbStorageAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private List<File> scanMountedUsbAds() {
        if (!hasUsbStorageAccess()) return Collections.emptyList();
        Set<File> roots = new LinkedHashSet<>();
        addVolumeChildren(roots, new File("/storage"));
        addVolumeChildren(roots, new File("/mnt/media_rw"));
        // Android's StorageManager is authoritative on newer TV firmware;
        // manufacturers frequently do not expose USB at /storage/<UUID>.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StorageManager manager = getReactApplicationContext().getSystemService(StorageManager.class);
            if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) {
                if (volume.isPrimary()) continue;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && volume.getDirectory() != null) roots.add(volume.getDirectory());
            }
        }
        List<File> output = new ArrayList<>();
        Set<String> scannedAdsFolders = new LinkedHashSet<>();
        for (File volume : roots) {
            String volumeName = volume.getName().toLowerCase();
            if ("emulated".equals(volumeName) || "self".equals(volumeName)) continue;
            File ads = findAdsDirectory(volume);
            if (ads.isDirectory() && ads.canRead()) {
                String path;
                try { path = ads.getCanonicalPath(); } catch (IOException ignored) { path = ads.getAbsolutePath(); }
                if (scannedAdsFolders.add(path)) collectRawAdFiles(ads, output);
            }
        }
        Collections.sort(output, Comparator.comparing(file -> file.getName().toLowerCase()));
        return output;
    }

    /** TV main storage fallback: /storage/emulated/0/Ads (often also visible
     * as /sdcard/Ads). It is deliberately checked only after all USB volumes. */
    private List<File> scanInternalStorageAds() {
        if (!hasUsbStorageAccess()) return Collections.emptyList();
        File ads = findAdsDirectory(Environment.getExternalStorageDirectory());
        if (!ads.isDirectory() || !ads.canRead()) return Collections.emptyList();
        List<File> output = new ArrayList<>();
        collectRawAdFiles(ads, output);
        Collections.sort(output, Comparator.comparing(file -> file.getName().toLowerCase()));
        return output;
    }

    private void addVolumeChildren(Set<File> output, File parent) {
        File[] volumes = parent.listFiles();
        if (volumes == null) return;
        Collections.addAll(output, volumes);
    }

    private File findAdsDirectory(File volume) {
        File direct = new File(volume, "Ads");
        if (direct.isDirectory()) return direct;
        File[] children = volume.listFiles();
        if (children != null) for (File child : children)
            if (child.isDirectory() && "ads".equalsIgnoreCase(child.getName())) return child;
        return direct;
    }

    private void collectRawAdFiles(File directory, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File file : children) {
            if (file.isDirectory()) collectRawAdFiles(file, output);
            else if (file.isFile() && adType(file.getName()) != null) output.add(file);
        }
    }

    private WritableArray toAdsArray(List<File> files) {
        WritableArray result = Arguments.createArray();
        for (File file : files) result.pushMap(adItem(Uri.fromFile(file).toString(), file.getName()));
        return result;
    }

    /** Some Android TV vendors hide removable mounts from java.io.File even
     * with All Files enabled, while still indexing USB media in MediaStore.
     * Return content:// URIs so Image, VideoView, and PdfRenderer can open
     * them without relying on a private mount-path implementation. */
    private List<AdEntry> scanMediaStoreAds() {
        List<AdEntry> results = new ArrayList<>();
        ContentResolver resolver = getReactApplicationContext().getContentResolver();
        Set<String> discoveredVolumeNames = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Collection<String> discovered = MediaStore.getExternalVolumeNames(getReactApplicationContext());
                if (discovered != null) discoveredVolumeNames.addAll(discovered);
            } catch (Exception ignored) { }
        }
        Set<String> volumeNames = new LinkedHashSet<>();
        // A UUID-style volume is removable. Keep it ahead of primary storage
        // even when the TV returns its primary volume first.
        for (String volume : discoveredVolumeNames)
            if (!isPrimaryMediaVolume(volume)) volumeNames.add(volume);
        // Query removable volumes before primary storage. On Android 10+ the
        // aggregate "external" volume mixes both, so avoid it whenever the
        // per-volume list is available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volumeNames.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else volumeNames.add("external");

        String[] projection = new String[] {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA
        };
        String selection = "(LOWER(COALESCE(" + MediaStore.Files.FileColumns.RELATIVE_PATH + ", '')) LIKE ? OR "
                + "LOWER(COALESCE(" + MediaStore.MediaColumns.DATA + ", '')) LIKE ?)";
        String[] args = new String[] { "ads/%", "%/ads/%" };
        Set<String> seen = new LinkedHashSet<>();
        for (String volume : volumeNames) {
            Cursor cursor = null;
            try {
                Uri collection = MediaStore.Files.getContentUri(volume);
                cursor = resolver.query(collection, projection, selection, args,
                        MediaStore.Files.FileColumns.DISPLAY_NAME + " COLLATE NOCASE ASC");
                if (cursor == null) continue;
                int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameIndex);
                    if (adType(name) == null || cursor.getLong(sizeIndex) <= 0L) continue;
                    long id = cursor.getLong(idIndex);
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    if (seen.add(uri.toString())) results.add(new AdEntry(uri.toString(), name));
                }
            } catch (Exception ignored) {
                // A vendor may reject its aggregate volume; other discovered
                // removable volumes are still attempted.
            } finally { if (cursor != null) cursor.close(); }
        }
        return results;
    }

    private boolean isPrimaryMediaVolume(String volume) {
        return MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume) || "external".equals(volume);
    }

    private WritableArray toAdsEntryArray(List<AdEntry> entries) {
        WritableArray result = Arguments.createArray();
        for (AdEntry entry : entries) result.pushMap(adItem(entry.uri, entry.name));
        return result;
    }

    private WritableMap adItem(String uri, String name) {
        WritableMap item = Arguments.createMap();
        item.putString("uri", uri);
        item.putString("name", name == null ? "Advertisement" : name);
        item.putString("type", adType(name));
        return item;
    }

    private static class AdEntry {
        final String uri;
        final String name;
        AdEntry(String uri, String name) { this.uri = uri; this.name = name; }
    }

    private void collectAdFiles(DocumentFile directory, List<DocumentFile> output) {
        for (DocumentFile file : directory.listFiles()) {
            if (file.isDirectory()) collectAdFiles(file, output);
            else if (file.isFile() && adType(file.getName()) != null) output.add(file);
        }
    }

    private String adType(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".heic")) return "image";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".3gp")
                || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".mpeg")
                || lower.endsWith(".ts")) return "video";
        return null;
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_ADS_FOLDER_REQUEST) {
            Promise folderPromise = mAdsFolderPromise;
            mAdsFolderPromise = null;
            if (folderPromise == null) return;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                folderPromise.reject("CANCELLED", "USB Ads folder selection cancelled");
                return;
            }
            Uri treeUri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { activity.getContentResolver().takePersistableUriPermission(treeUri, flags); } catch (SecurityException ignored) { }
            getReactApplicationContext().getSharedPreferences(ADS_PREFS, 0).edit().putString(ADS_TREE_URI, treeUri.toString()).apply();
            WritableMap result = Arguments.createMap();
            result.putString("uri", treeUri.toString());
            result.putString("name", "Ads folder selected");
            folderPromise.resolve(result);
            return;
        }
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

    @Override public void invalidate() {
        try { if (usbReceiver != null) getReactApplicationContext().unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.invalidate();
    }
}
