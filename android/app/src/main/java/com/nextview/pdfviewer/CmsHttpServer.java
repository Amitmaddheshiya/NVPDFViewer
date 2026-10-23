package com.nextview.pdfviewer;

import android.net.Uri;
import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
            if ("/api/command".equals(uri) && Method.POST.equals(session.getMethod())) return cors(command(session));
            return cors(json(Response.Status.NOT_FOUND, "{\"error\":\"Not found\"}"));
        } catch (Exception error) {
            return cors(json(Response.Status.INTERNAL_ERROR, "{\"error\":\"" + escape(error.getMessage()) + "\"}"));
        }
    }

    private Response upload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String temporaryPath = files.get("file");
        String requestedName = session.getParms().get("filename");
        if (temporaryPath == null || requestedName == null || !requestedName.toLowerCase().endsWith(".pdf")) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Upload a PDF file\"}");
        }
        String safeName = requestedName.replaceAll("[^a-zA-Z0-9._ -]", "_");
        File destination = new File(uploadDirectory, UUID.randomUUID() + "-" + safeName);
        Files.move(new File(temporaryPath).toPath(), destination.toPath());
        JSONObject payload = new JSONObject();
        payload.put("uri", Uri.fromFile(destination).toString());
        payload.put("name", safeName);
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "uploadPdf");
        event.put("payload", payload);
        return dispatch(event, 12, "{\"name\":\"" + escape(safeName) + "\"}");
    }

    private Response command(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        JSONObject body = new JSONObject(files.getOrDefault("postData", "{}"));
        if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, "{\"error\":\"Please login again\"}");
        String type = body.optString("type");
        if (!type.matches("previous|next|rotate|fullscreen|close|timer|timerSeconds|clearLibrary|adsScan|adsStart|adsStop|adsNext|adsPrevious|adsSeconds")) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"Unsupported command\"}");
        }
        JSONObject event = new JSONObject();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("payload", body.optJSONObject("payload") == null ? new JSONObject() : body.getJSONObject("payload"));
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
