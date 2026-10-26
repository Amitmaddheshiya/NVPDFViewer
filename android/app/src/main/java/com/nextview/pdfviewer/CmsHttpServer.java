package com.nextview.pdfviewer;

import android.net.Uri;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;
import org.json.JSONArray;
import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

class CmsHttpServer extends NanoHTTPD {
    interface CommandListener { void onCommand(JSONObject command); }
    interface AuthHandler {
        String createSession(String password);
        boolean isSessionValid(String token);
    }

    private final File uploadDirectory;
    private final Context context;
    private volatile AuthHandler authHandler;
    private volatile CommandListener commandListener;
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private volatile String stateJson = "{\"viewerOpen\":false}";

    CmsHttpServer(Context context, int port, File uploadDirectory, AuthHandler authHandler, CommandListener commandListener) {
        super(port);
        this.context = context;
        this.uploadDirectory = uploadDirectory;
        this.authHandler = authHandler;
        this.commandListener = commandListener;
    }

    void setCommandListener(CommandListener commandListener) {
        this.commandListener = commandListener;
    }

    void setAuthHandler(AuthHandler authHandler) {
        this.authHandler = authHandler;
    }

    void setState(Map<String, Object> state) {
        stateJson = new JSONObject(state).toString();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (Method.OPTIONS.equals(session.getMethod())) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            if ("/".equals(uri) || "/index.html".equals(uri)) return cors(asset("cms.html", "text/html; charset=utf-8"));
            if ("/api/qr.png".equals(uri)) return cors(qrCode(session));
            if ("/api/login".equals(uri) && Method.POST.equals(session.getMethod())) return cors(login(session));
            // Status is polled by the CMS every second. A restarted/updated
            // TV app clears its in-memory sessions, so report an explicit
            // 401 here instead of falling through to a misleading 404. The
            // browser can then discard its stale token and show Login again.
            if ("/api/status".equals(uri)) {
                if (!authorized(session)) return cors(json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}"));
                return cors(json(Response.Status.OK, stateJson));
            }
            if ("/api/upload".equals(uri) && Method.POST.equals(session.getMethod())) return cors(upload(session));
            if ("/api/ads-upload".equals(uri) && Method.POST.equals(session.getMethod())) return cors(uploadAds(session));
            if ("/api/ads-upload-chunk".equals(uri) && Method.POST.equals(session.getMethod())) return cors(uploadAdsChunk(session));
            if ("/api/storage/list".equals(uri) && Method.GET.equals(session.getMethod())) return cors(storageList(session));
            if ("/api/storage/folder".equals(uri) && Method.POST.equals(session.getMethod())) return cors(storageFolder(session));
            if ("/api/storage/file".equals(uri) && Method.POST.equals(session.getMethod())) return cors(storageFile(session));
            if ("/api/storage/upload".equals(uri) && Method.POST.equals(session.getMethod())) return cors(storageUpload(session));
            if ("/api/storage/move".equals(uri) && Method.POST.equals(session.getMethod())) return cors(storageMove(session));
            if ("/api/command".equals(uri) && Method.POST.equals(session.getMethod())) return cors(command(session));
            if ("/api/reload".equals(uri) && Method.POST.equals(session.getMethod())) return cors(reloadApp(session));
            return cors(json(Response.Status.NOT_FOUND, "{\"error\":\"Not found\"}"));
        } catch (Exception error) {
            return cors(json(Response.Status.INTERNAL_ERROR, "{\"error\":\"" + escape(error.getMessage()) + "\"}"));
        }
    }

    private Response upload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to parse upload: " + escape(e.getMessage()) + "\"}");
        }
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String temporaryPath = files.get("file");
        String requestedName = session.getParms().get("filename");
        if (temporaryPath == null || requestedName == null || !requestedName.toLowerCase().endsWith(".pdf")) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Upload a PDF file\"}");
        }
        String safeName = requestedName.replaceAll("[^a-zA-Z0-9._ -]", "_");
        File destination = new File(uploadDirectory, UUID.randomUUID() + "-" + safeName);
        // Copy then delete keeps the upload robust even if NanoHTTPD's
        // temporary file lives on a different Android filesystem provider.
        try {
            Files.copy(new File(temporaryPath).toPath(), destination.toPath());
            Files.deleteIfExists(new File(temporaryPath).toPath());
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to save file: " + escape(e.getMessage()) + "\"}");
        }
        JSONObject payload = new JSONObject();
        payload.put("uri", Uri.fromFile(destination).toString());
        payload.put("name", safeName);
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "uploadPdf");
        event.put("payload", payload);
        return dispatch(event, 12, "{\"name\":\"" + escape(safeName) + "\"}");
    }

    private Response uploadAds(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to parse upload: " + escape(e.getMessage()) + "\"}");
        }
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String temporaryPath = files.get("file");
        String requestedName = session.getParms().get("filename");
        if (temporaryPath == null || requestedName == null || !isSupportedAdName(requestedName)) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Upload a PDF, image, or supported video file\"}");
        }
        File directory = publicAdsDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Could not create TV Ads storage\"}");
        }
        String safeName = requestedName.replaceAll("[^a-zA-Z0-9._ -]", "_");
        // The marker lets Clear TV Ads remove only CMS uploads without
        // deleting any files the owner manually put in the public Ads folder.
        File destination = new File(directory, "cms-" + UUID.randomUUID() + "-" + safeName);
        // The temporary upload lives in app-private storage while this target
        // is public TV main storage, so use cross-filesystem-safe copy/delete.
        try {
            Files.copy(new File(temporaryPath).toPath(), destination.toPath());
            Files.deleteIfExists(new File(temporaryPath).toPath());
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to save file: " + escape(e.getMessage()) + "\"}");
        }
        JSONObject payload = new JSONObject();
        payload.put("uri", Uri.fromFile(destination).toString());
        payload.put("name", safeName);
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "uploadAds");
        event.put("payload", payload);
        return dispatch(event, 15, "{\"name\":\"" + escape(safeName) + "\"}");
    }

    private boolean isSupportedAdName(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".mp4")
                || lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".3gp")
                || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".mpeg") || lower.endsWith(".ts");
    }

    private Response uploadAdsChunk(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to parse upload: " + escape(e.getMessage()) + "\"}");
        }
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String temporaryPath = files.get("file");
        String uploadId = session.getParms().get("uploadId");
        String chunkIndexStr = session.getParms().get("chunkIndex");
        String totalChunksStr = session.getParms().get("totalChunks");
        String requestedName = session.getParms().get("filename");
        String fileSizeStr = session.getParms().get("fileSize");

        if (temporaryPath == null || uploadId == null || chunkIndexStr == null || totalChunksStr == null || requestedName == null || fileSizeStr == null) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Missing chunk upload parameters\"}");
        }

        int chunkIndex = Integer.parseInt(chunkIndexStr);
        int totalChunks = Integer.parseInt(totalChunksStr);
        long fileSize = Long.parseLong(fileSizeStr);

        File chunksDir = new File(context.getCacheDir(), "upload-chunks-" + uploadId);
        if (!chunksDir.exists()) chunksDir.mkdirs();

        File chunkFile = new File(chunksDir, chunkIndex + ".chunk");
        try {
            Files.copy(new File(temporaryPath).toPath(), chunkFile.toPath());
            Files.deleteIfExists(new File(temporaryPath).toPath());
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to save chunk: " + escape(e.getMessage()) + "\"}");
        }

        // Check if all chunks are received
        File[] chunkFiles = chunksDir.listFiles();
        if (chunkFiles != null && chunkFiles.length == totalChunks) {
            // Assemble file
            File directory = publicAdsDirectory();
            if (!directory.exists() && !directory.mkdirs()) {
                return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Could not create TV Ads storage\"}");
            }
            String safeName = requestedName.replaceAll("[^a-zA-Z0-9._ -]", "_");
            File destination = new File(directory, "cms-" + UUID.randomUUID() + "-" + safeName);

            try {
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(destination);
                for (int i = 0; i < totalChunks; i++) {
                    File chunk = new File(chunksDir, i + ".chunk");
                    if (!chunk.exists()) throw new IOException("Chunk " + i + " missing");
                    java.io.FileInputStream inputStream = new java.io.FileInputStream(chunk);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    inputStream.close();
                    chunk.delete();
                }
                outputStream.close();
                chunksDir.delete();
            } catch (Exception e) {
                return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to assemble file: " + escape(e.getMessage()) + "\"}");
            }

            JSONObject payload = new JSONObject();
            payload.put("uri", Uri.fromFile(destination).toString());
            payload.put("name", safeName);
            JSONObject event = new JSONObject();
            event.put("id", UUID.randomUUID().toString());
            event.put("type", "uploadAds");
            event.put("payload", payload);
            return dispatch(event, 15, "{\"name\":\"" + escape(safeName) + "\"}");
        }

        return json(Response.Status.OK, "{\"ok\":true,\"chunkReceived\":" + chunkIndex + ",\"totalChunks\":" + totalChunks + "}");
    }

    private File publicAdsDirectory() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw new IOException("Allow All files access for NV PDF Viewer on the TV, then upload Ads again.");
        }
        return new File(Environment.getExternalStorageDirectory(), "Ads");
    }

    /** Main TV shared-storage browser used by the authenticated CMS. Every
     * request is resolved below this root; canonical path checking prevents
     * paths such as ../ from reaching private app or system directories. */
    private File storageRoot() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw new IOException("Allow All files access for NV PDF Viewer on the TV, then use Storage Manager again.");
        }
        File root = Environment.getExternalStorageDirectory().getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) throw new IOException("TV main storage is unavailable.");
        return root;
    }

    private File storagePath(String relativePath, boolean allowRoot) throws IOException {
        File root = storageRoot();
        String relative = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);
        File target = relative.isEmpty() ? root : new File(root, relative);
        File canonical = target.getCanonicalFile();
        String rootPath = root.getPath();
        if (!canonical.getPath().equals(rootPath) && !canonical.getPath().startsWith(rootPath + File.separator)) {
            throw new IOException("Invalid storage path.");
        }
        if (!allowRoot && canonical.equals(root)) throw new IOException("TV storage root cannot be changed.");
        return canonical;
    }

    private String storageRelativePath(File file) throws IOException {
        File root = storageRoot();
        String rootPath = root.getPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) return "";
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private String safeStorageName(String value) throws IOException {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
            throw new IOException("Enter a valid name without / or \\.");
        }
        return name;
    }

    private Response storageList(IHTTPSession session) throws Exception {
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        File directory = storagePath(session.getParms().get("path"), true);
        if (!directory.exists() || !directory.isDirectory()) return json(Response.Status.NOT_FOUND, "{\"error\":\"Folder not found\"}");
        File[] entries = directory.listFiles();
        if (entries == null) entries = new File[0];
        Arrays.sort(entries, Comparator.comparing(File::isFile).thenComparing(file -> file.getName().toLowerCase()));
        JSONArray items = new JSONArray();
        int limit = Math.min(entries.length, 500);
        for (int index = 0; index < limit; index++) {
            File entry = entries[index];
            JSONObject item = new JSONObject();
            item.put("name", entry.getName());
            item.put("path", storageRelativePath(entry));
            item.put("folder", entry.isDirectory());
            item.put("size", entry.isFile() ? entry.length() : 0);
            item.put("modified", entry.lastModified());
            items.put(item);
        }
        JSONObject result = new JSONObject();
        result.put("path", storageRelativePath(directory));
        result.put("items", items);
        result.put("truncated", entries.length > limit);
        return json(Response.Status.OK, result.toString());
    }

    private JSONObject requestBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        return new JSONObject(files.getOrDefault("postData", "{}"));
    }

    private Response storageFolder(IHTTPSession session) throws Exception {
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        JSONObject body = requestBody(session);
        String action = body.optString("action");
        if ("create".equals(action)) {
            File parent = storagePath(body.optString("path"), true);
            if (!parent.isDirectory()) throw new IOException("Choose an existing folder.");
            File folder = new File(parent, safeStorageName(body.optString("name")));
            if (folder.exists()) throw new IOException("A file or folder with that name already exists.");
            if (!folder.mkdir()) throw new IOException("Could not create folder.");
            notifyAdsStorageChange(folder);
        } else if ("rename".equals(action)) {
            renameStorageItem(body.optString("path"), body.optString("name"));
        } else if ("delete".equals(action)) {
            deleteStorageItem(body.optString("path"));
        } else return json(Response.Status.BAD_REQUEST, "{\"error\":\"Unsupported folder action\"}");
        return json(Response.Status.OK, "{\"ok\":true}");
    }

    private Response storageFile(IHTTPSession session) throws Exception {
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        JSONObject body = requestBody(session);
        String action = body.optString("action");
        if ("rename".equals(action)) renameStorageItem(body.optString("path"), body.optString("name"));
        else if ("delete".equals(action)) deleteStorageItem(body.optString("path"));
        else return json(Response.Status.BAD_REQUEST, "{\"error\":\"Unsupported file action\"}");
        return json(Response.Status.OK, "{\"ok\":true}");
    }

    private Response storageUpload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to parse upload: " + escape(e.getMessage()) + "\"}");
        }
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String temporaryPath = files.get("file");
        String requestedName = session.getParms().get("filename");
        File directory = storagePath(session.getParms().get("path"), true);
        if (temporaryPath == null || !directory.isDirectory()) return json(Response.Status.BAD_REQUEST, "{\"error\":\"Choose an existing destination folder and file.\"}");
        File destination = new File(directory, safeStorageName(requestedName));
        if (destination.exists()) return json(Response.Status.BAD_REQUEST, "{\"error\":\"A file or folder with that name already exists.\"}");
        try {
            Files.copy(new File(temporaryPath).toPath(), destination.toPath());
            Files.deleteIfExists(new File(temporaryPath).toPath());
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Failed to save file: " + escape(e.getMessage()) + "\"}");
        }
        notifyAdsStorageChange(destination);
        return json(Response.Status.OK, "{\"ok\":true,\"path\":\"" + escape(storageRelativePath(destination)) + "\"}");
    }

    private Response storageMove(IHTTPSession session) throws Exception {
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        JSONObject body = requestBody(session);
        File source = storagePath(body.optString("sourcePath"), false);
        File destinationFolder = storagePath(body.optString("targetPath"), true);
        if (!source.exists()) throw new IOException("Source file or folder was not found.");
        if (!destinationFolder.isDirectory()) throw new IOException("Choose an existing destination folder.");
        if (source.isDirectory() && destinationFolder.getCanonicalPath().startsWith(source.getCanonicalPath() + File.separator)) {
            throw new IOException("A folder cannot be moved inside itself.");
        }
        File destination = new File(destinationFolder, source.getName()).getCanonicalFile();
        if (destination.exists()) throw new IOException("Destination already has a file or folder with that name.");
        boolean adsChanged = isAdsPath(source) || isAdsPath(destination);
        if (!source.renameTo(destination)) throw new IOException("Could not move item.");
        if (adsChanged) notifyAdsStorageChange(new File(storageRoot(), "Ads"));
        return json(Response.Status.OK, "{\"ok\":true}");
    }

    private void renameStorageItem(String path, String newName) throws Exception {
        File item = storagePath(path, false);
        if (!item.exists()) throw new IOException("File or folder not found.");
        File renamed = new File(item.getParentFile(), safeStorageName(newName));
        if (renamed.exists()) throw new IOException("A file or folder with that name already exists.");
        if (!item.renameTo(renamed)) throw new IOException("Could not rename item.");
        notifyAdsStorageChange(renamed);
    }

    private void deleteStorageItem(String path) throws Exception {
        File item = storagePath(path, false);
        if (!item.exists()) throw new IOException("File or folder not found.");
        boolean adsChanged = isAdsPath(item);
        deleteRecursively(item);
        if (adsChanged) notifyAdsStorageChange(new File(storageRoot(), "Ads"));
    }

    private void deleteRecursively(File item) throws IOException {
        if (item.isDirectory()) {
            File[] children = item.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!item.delete()) throw new IOException("Could not delete " + item.getName() + ".");
    }

    private boolean isAdsPath(File item) throws IOException {
        File ads = new File(storageRoot(), "Ads").getCanonicalFile();
        String adsPath = ads.getPath();
        String itemPath = item.getCanonicalPath();
        return itemPath.equals(adsPath) || itemPath.startsWith(adsPath + File.separator);
    }

    private void notifyAdsStorageChange(File item) {
        try {
            if (!isAdsPath(item) || commandListener == null) return;
            JSONObject payload = new JSONObject();
            payload.put("uri", Uri.fromFile(item).toString());
            JSONObject event = new JSONObject();
            event.put("id", UUID.randomUUID().toString());
            event.put("type", "uploadAds");
            event.put("payload", payload);
            commandListener.onCommand(event);
        } catch (Exception ignored) { }
    }

    private Response command(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        JSONObject body = new JSONObject(files.getOrDefault("postData", "{}"));
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String type = body.optString("type");
        if (!type.matches("previous|next|rotate|fullscreen|close|timer|timerSeconds|clearLibrary|openPdf|adsScan|adsStart|adsStop|adsNext|adsPrevious|adsRotate|adsSeconds|clearAds|reloadApp|adsForward|adsBackward")) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Unsupported command\"}");
        }
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("payload", body.optJSONObject("payload") == null ? new JSONObject() : body.getJSONObject("payload"));
        return dispatch(event, 5, "{}");
    }

    private Response reloadApp(IHTTPSession session) throws Exception {
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "reloadApp");
        event.put("payload", new JSONObject());
        return dispatch(event, 5, "{}");
    }

    void completeCommand(String id, boolean ok, String error) {
        PendingCommand pending = pendingCommands.get(id);
        if (pending != null) {
            pending.ok = ok;
            pending.error = error == null ? "" : error;
            pending.done.countDown();
        }
    }

    private Response dispatch(JSONObject event, int timeoutSeconds, String extraJsonFields) {
        String id = event.optString("id");
        PendingCommand pending = new PendingCommand();
        pendingCommands.put(id, pending);
        commandListener.onCommand(event);
        try {
            if (!pending.done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                return json(Response.Status.REQUEST_TIMEOUT, "{\"error\":\"TV did not confirm the command. Keep the TV app open and try again.\"}");
            }
            if (!pending.ok) {
                return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"" + escape(pending.error) + "\"}");
            }
            String suffix = "{}".equals(extraJsonFields) ? "" : "," + extraJsonFields.substring(1, extraJsonFields.length() - 1);
            return json(Response.Status.OK, "{\"ok\":true" + suffix + "}");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"Command interrupted\"}");
        } finally {
            pendingCommands.remove(id);
        }
    }

    private static class PendingCommand {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean ok;
        volatile String error = "Command was not applied";
    }

    private Response login(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        JSONObject body = new JSONObject(files.getOrDefault("postData", "{}"));
        String token = authHandler.createSession(body.optString("password"));
        if (token == null) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Invalid login code\"}");
        return json(Response.Status.OK, "{\"ok\":true,\"token\":\"" + escape(token) + "\"}");
    }

    private boolean authorized(IHTTPSession session) {
        String token = session.getHeaders().get("x-cms-token");
        return token != null && authHandler.isSessionValid(token);
    }

    private Response asset(String name, String mimeType) throws IOException {
        return newChunkedResponse(Response.Status.OK, mimeType, context.getAssets().open(name));
    }

    private Response qrCode(IHTTPSession session) throws Exception {
        String host = session.getHeaders().get("host");
        if (host == null || host.trim().isEmpty()) host = "TV-IP:8081";
        BitMatrix matrix = new MultiFormatWriter().encode("http://" + host, BarcodeFormat.QR_CODE, 180, 180);
        Bitmap bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++)
            bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(output.toByteArray()), output.size());
    }

    private Response json(Response.Status status, String value) { return newFixedLengthResponse(status, "application/json; charset=utf-8", value); }
    private Response cors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-CMS-TOKEN");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Cache-Control", "no-store, max-age=0");
        return response;
    }
    private String escape(String value) { return value == null ? "Unknown error" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
