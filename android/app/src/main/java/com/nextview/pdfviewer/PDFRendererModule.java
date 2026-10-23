package com.nextview.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PDFRendererModule extends ReactContextBaseJavaModule {

    private static final String MODULE_NAME = "PDFRendererModule";
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor parcelFileDescriptor;

    public PDFRendererModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    @ReactMethod
    public void openDocument(String uriString, Promise promise) {
        try {
            Context context = getReactApplicationContext();
            Uri uri = Uri.parse(uriString);
            
            if ("file".equals(uri.getScheme())) {
                parcelFileDescriptor = ParcelFileDescriptor.open(
                    new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY
                );
            } else {
                parcelFileDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            }
            if (parcelFileDescriptor == null) {
                promise.reject("OPEN_ERROR", "Could not open file descriptor");
                return;
            }

            pdfRenderer = new PdfRenderer(parcelFileDescriptor);
            
            WritableMap result = Arguments.createMap();
            result.putInt("pageCount", pdfRenderer.getPageCount());
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("OPEN_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void renderPage(int pageIndex, int width, int height, int rotation, Promise promise) {
        if (pdfRenderer == null) {
            promise.reject("NO_DOCUMENT", "No document opened");
            return;
        }

        if (pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) {
            promise.reject("INVALID_PAGE", "Page index out of range");
            return;
        }

        try {
            PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
            
            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();
            
            float scale = Math.min(
                (float) width / pageWidth,
                (float) height / pageHeight
            );
            
            int scaledWidth = (int) (pageWidth * scale);
            int scaledHeight = (int) (pageHeight * scale);
            
            Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
            // Engineering/blueprint PDFs often use transparency. PdfRenderer leaves those
            // pixels transparent, which appear black against the TV viewer background.
            new Canvas(bitmap).drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            
            page.close();
            
            Bitmap outputBitmap = bitmap;
            int normalizedRotation = ((rotation % 360) + 360) % 360;
            if (normalizedRotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(normalizedRotation);
                outputBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                );
                bitmap.recycle();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            String base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP);
            
            outputBitmap.recycle();
            
            WritableMap result = Arguments.createMap();
            result.putString("base64", "data:image/png;base64," + base64);
            result.putInt("originalWidth", pageWidth);
            result.putInt("originalHeight", pageHeight);
            result.putInt("renderedWidth", scaledWidth);
            result.putInt("renderedHeight", scaledHeight);
            
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("RENDER_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getPageCount(Promise promise) {
        if (pdfRenderer == null) {
            promise.reject("NO_DOCUMENT", "No document opened");
            return;
        }
        promise.resolve(pdfRenderer.getPageCount());
    }

    @ReactMethod
    public void closeDocument(Promise promise) {
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
                pdfRenderer = null;
            }
            if (parcelFileDescriptor != null) {
                parcelFileDescriptor.close();
                parcelFileDescriptor = null;
            }
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("CLOSE_ERROR", e.getMessage());
        }
    }

    @Override
    public void onCatalystInstanceDestroy() {
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
            }
            if (parcelFileDescriptor != null) {
                parcelFileDescriptor.close();
            }
        } catch (IOException e) {
            // Ignore cleanup errors
        }
        super.onCatalystInstanceDestroy();
    }
}
