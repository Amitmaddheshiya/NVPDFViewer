package com.nextview.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/** Local-network CMS bridge. The HTTP server never executes viewer actions itself;
 * it emits a command to React Native, where the active viewer owns all state. */
public class CmsServerModule extends ReactContextBaseJavaModule {
    private static final String MODULE_NAME = "CmsServerModule";
    private static final int PORT = 8081;
    private static CmsHttpServer server;
    private static final String PREFS_NAME = "cms_library";
    // This value intentionally lives in normal app-private storage. Android
    // clears it on either Clear data or uninstall, which makes the IP-derived
    // code the bootstrap password again on the next launch.
    private static final String APP_LOGIN_PASSWORD_KEY = "app_login_password";
    private static final String PLAYLIST_KEY = "playlist";
    private static final String TIMER_ENABLED_KEY = "timer_enabled";
    private static final String TIMER_SECONDS_KEY = "timer_seconds";
    private final ReactApplicationContext reactContext;
    // Commands stay in this outbox until JavaScript explicitly acknowledges
    // them. The old poll-and-remove queue could lose a button press if the
    // React bridge was busy or reloading at the instant it polled.
    private static final ConcurrentLinkedQueue<String> COMMAND_ORDER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, JSONObject> COMMAND_OUTBOX = new ConcurrentHashMap<>();
    private static final Set<String> SESSIONS = ConcurrentHashMap.newKeySet();

    public CmsServerModule(ReactApplicationContext context) {
        super(context);
        reactContext = context;
        startServer();
    }

    @NonNull
    @Override
    public String getName() {
        return MODULE_NAME;
    }

    private synchronized void startServer() {
        if (server != null) {
            // The React bridge can be recreated after a reload or an Activity
            // recreation. Keep the already-bound HTTP port, but always deliver
            // commands to this active module instance and its JS polling queue.
            server.setCommandListener(this::queueCommand);
            server.setAuthHandler(authHandler());
            return;
        }
        try {
            File uploadDirectory = new File(reactContext.getFilesDir(), "cms-uploads");
            if (!uploadDirectory.exists()) uploadDirectory.mkdirs();
            server = new CmsHttpServer(reactContext, PORT, uploadDirectory, authHandler(), this::queueCommand);
            server.start(5000, false);
        } catch (IOException ignored) {
            // JS can surface unavailable state through getStatus().
        }
    }

    private void queueCommand(JSONObject command) {
        String commandId = command.optString("id");
        if (commandId.isEmpty()) return;
        COMMAND_OUTBOX.put(commandId, command);
        COMMAND_ORDER.offer(commandId);
        emitCommandToJs(command);
    }

    private void emitCommandToJs(JSONObject command) {
        // Deliver immediately when the active React bridge is available. The
        // JS polling queue remains as a fallback for a bridge that is loading.
        try {
            WritableMap payload = Arguments.createMap();
            payload.putString("type", command.optString("type"));
            payload.putString("id", command.optString("id"));
            payload.putString("payload", command.optJSONObject("payload") == null
                    ? "{}" : command.optJSONObject("payload").toString());
            reactContext.runOnJSQueueThread(() -> reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("cmsCommand", payload));
        } catch (Exception ignored) {
            // Queue polling will handle this command after the bridge is ready.
        }
    }

    private CmsHttpServer.AuthHandler authHandler() {
        return new CmsHttpServer.AuthHandler() {
            @Override
            public String createSession(String password) {
                return CmsServerModule.this.createSession(password);
            }

            @Override
            public boolean isSessionValid(String token) {
                return CmsServerModule.this.isSessionValid(token);
            }
        };
    }

    private String createSession(String password) {
        try {
            if (!isLoginPasswordValid(password)) return null;
        } catch (IOException error) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        SESSIONS.add(token);
        return token;
    }

    private boolean isSessionValid(String token) {
        return SESSIONS.contains(token);
    }

    @ReactMethod
    public void updateState(ReadableMap state) {
        if (server != null) {
            // updateState is the heartbeat from the currently visible React
            // instance. Rebind here as well as at startup: on some TV
            // launchers the Activity/bridge is recreated while the static
            // HTTP server remains alive.
            server.setCommandListener(this::queueCommand);
            server.setAuthHandler(authHandler());
            server.setState(state.toHashMap());
            // A command submitted while Android was recreating the Activity
            // is re-pushed to this now-confirmed live bridge. It remains in
            // the outbox until completeCommand() acknowledges it.
            for (JSONObject command : COMMAND_OUTBOX.values()) emitCommandToJs(command);
        }
    }

    @ReactMethod
    public void completeCommand(String id, boolean ok, String error) {
        // Remove only after the viewer has actually applied (or explicitly
        // rejected) the command. Until then polling can safely retry it.
        COMMAND_OUTBOX.remove(id);
        COMMAND_ORDER.remove(id);
        if (server != null) server.completeCommand(id, ok, error);
    }

    @ReactMethod
    public void getNextCommand(Promise promise) {
        JSONObject command = null;
        for (String commandId : COMMAND_ORDER) {
            JSONObject candidate = COMMAND_OUTBOX.get(commandId);
            if (candidate != null) {
                command = candidate;
                break;
            }
            // Remove stale order entries left after an acknowledgement.
            COMMAND_ORDER.remove(commandId);
        }
        if (command == null) { promise.resolve(null); return; }
        WritableMap payload = Arguments.createMap();
        payload.putString("type", command.optString("type"));
        payload.putString("id", command.optString("id"));
        payload.putString("payload", command.optJSONObject("payload") == null
                ? "{}" : command.optJSONObject("payload").toString());
        promise.resolve(payload);
    }

    @ReactMethod
    public void getStatus(Promise promise) {
        WritableMap result = Arguments.createMap();
        result.putBoolean("running", server != null && server.wasStarted());
        result.putInt("port", PORT);
        promise.resolve(result);
    }

    @ReactMethod
    public void getViewerSettings(Promise promise) {
        android.content.SharedPreferences prefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        WritableMap result = Arguments.createMap();
        result.putBoolean("timerEnabled", prefs.getBoolean(TIMER_ENABLED_KEY, false));
        result.putInt("timerSeconds", prefs.getInt(TIMER_SECONDS_KEY, 5));
        promise.resolve(result);
    }

    @ReactMethod
    public void saveViewerSettings(boolean timerEnabled, int timerSeconds) {
        reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(TIMER_ENABLED_KEY, timerEnabled)
                .putInt(TIMER_SECONDS_KEY, Math.max(1, Math.min(3600, timerSeconds)))
                .apply();
    }

    @ReactMethod
    public void getCmsQrCode(Promise promise) {
        try {
            String url = "http://" + localIpAddress() + ":" + PORT;
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 180, 180);
            Bitmap bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++)
                bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            WritableMap result = Arguments.createMap();
            result.putString("url", url);
            result.putString("base64", "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            promise.resolve(result);
        } catch (Exception error) {
            promise.reject("QR_ERROR", error.getMessage(), error);
        }
    }

    @ReactMethod
    public void validateLogin(String password, Promise promise) {
        try {
            promise.resolve(isLoginPasswordValid(password));
        } catch (Exception error) {
            promise.reject("LOGIN_ERROR", error.getMessage(), error);
        }
    }

    @ReactMethod
    public void getSavedLoginPassword(Promise promise) {
        try {
            String savedPassword = reactContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(APP_LOGIN_PASSWORD_KEY, null);
            promise.resolve(savedPassword);
        } catch (Exception error) {
            promise.reject("LOGIN_PASSWORD_ERROR", error.getMessage(), error);
        }
    }

    private boolean isLoginPasswordValid(String password) throws IOException {
        String enteredPassword = password == null ? "" : password;
        android.content.SharedPreferences prefs = reactContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedPassword = prefs.getString(APP_LOGIN_PASSWORD_KEY, null);

        // The first login uses the current TV IP-derived code. Both the TV UI
        // and browser CMS then retain that same code for future logins.
        if (savedPassword != null) return savedPassword.equals(enteredPassword);

        boolean valid = loginPassword().equals(enteredPassword);
        if (valid) prefs.edit().putString(APP_LOGIN_PASSWORD_KEY, enteredPassword).apply();
        return valid;
    }

    private String loginPassword() throws IOException {
        String digits = localIpAddress().replace(".", "");
        if (digits.length() <= 3) throw new IOException("Unable to create login password from TV IP");
        return new StringBuilder(digits.substring(3)).reverse().toString();
    }

    private String localIpAddress() throws IOException {
        WifiManager wifi = (WifiManager) reactContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && wifi.getConnectionInfo() != null) {
            int address = wifi.getConnectionInfo().getIpAddress();
            if (address != 0) return (address & 0xff) + "." + ((address >> 8) & 0xff) + "." + ((address >> 16) & 0xff) + "." + ((address >> 24) & 0xff);
        }
        throw new IOException("TV is not connected to Wi-Fi");
    }

    @ReactMethod
    public void importPdf(String sourceUri, String displayName, Promise promise) {
        try {
            File directory = getUploadDirectory();
            String safeName = displayName.replaceAll("[^a-zA-Z0-9._ -]", "_");
            if (!safeName.toLowerCase().endsWith(".pdf")) safeName += ".pdf";
            File destination = new File(directory, UUID.randomUUID() + "-" + safeName);
            Uri uri = Uri.parse(sourceUri);
            InputStream input = "file".equals(uri.getScheme())
                    ? new FileInputStream(new File(uri.getPath()))
                    : reactContext.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Unable to read selected PDF");
            copy(input, new FileOutputStream(destination));
            WritableMap result = Arguments.createMap();
            result.putString("uri", Uri.fromFile(destination).toString());
            result.putString("name", safeName);
            promise.resolve(result);
        } catch (Exception error) {
            promise.reject("IMPORT_ERROR", error.getMessage(), error);
        }
    }

    @ReactMethod
    public void savePlaylist(ReadableArray items) {
        try {
            org.json.JSONArray saved = new org.json.JSONArray();
            for (int index = 0; index < items.size(); index++) {
                ReadableMap item = items.getMap(index);
                if (item != null) {
                    String uri = item.getString("uri");
                    if (uri != null && "file".equals(Uri.parse(uri).getScheme())
                            && new File(Uri.parse(uri).getPath()).exists()) {
                        org.json.JSONObject entry = new org.json.JSONObject();
                        entry.put("uri", uri);
                        entry.put("name", item.hasKey("name") ? item.getString("name") : "PDF document");
                        saved.put(entry);
                    }
                }
            }
            reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PLAYLIST_KEY, saved.toString()).apply();
        } catch (Exception ignored) { }
    }

    @ReactMethod
    public void getSavedPlaylist(Promise promise) {
        try {
            String raw = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PLAYLIST_KEY, "[]");
            org.json.JSONArray saved = new org.json.JSONArray(raw);
            WritableArray result = Arguments.createArray();
            for (int index = 0; index < saved.length(); index++) {
                org.json.JSONObject entry = saved.getJSONObject(index);
                String uri = entry.optString("uri");
                if ("file".equals(Uri.parse(uri).getScheme()) && new File(Uri.parse(uri).getPath()).exists()) {
                    WritableMap item = Arguments.createMap();
                    item.putString("uri", uri);
                    item.putString("name", entry.optString("name", "PDF document"));
                    result.pushMap(item);
                }
            }
            promise.resolve(result);
        } catch (Exception error) {
            promise.reject("LIBRARY_ERROR", error.getMessage(), error);
        }
    }

    @ReactMethod
    public void clearLibrary(Promise promise) {
        try {
            File directory = getUploadDirectory();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) if (!file.delete()) throw new IOException("Could not delete " + file.getName());
            reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(PLAYLIST_KEY).apply();
            promise.resolve(null);
        } catch (Exception error) {
            promise.reject("CLEAR_ERROR", error.getMessage(), error);
        }
    }

    private File getUploadDirectory() throws IOException {
        File directory = new File(reactContext.getFilesDir(), "cms-uploads");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create PDF library");
        return directory;
    }

    private void copy(InputStream input, FileOutputStream output) throws IOException {
        try (InputStream source = input; FileOutputStream target = output) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = source.read(buffer)) != -1) target.write(buffer, 0, length);
        }
    }

    // Required by React Native's EventEmitter contract.
    @ReactMethod public void addListener(String eventName) { }
    @ReactMethod public void removeListeners(double count) { }

    @ReactMethod
    public void reloadApp() {
        // Reload the React Native bundle and restart the app
        reactContext.runOnJSQueueThread(() -> {
            try {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit("reloadApp", null);
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }
}
