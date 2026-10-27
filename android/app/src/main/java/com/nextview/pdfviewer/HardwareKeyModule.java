package com.nextview.pdfviewer;

import android.view.KeyEvent;
import androidx.annotation.NonNull;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

/** Module to emit hardware key events from TV remote to React Native */
public class HardwareKeyModule extends ReactContextBaseJavaModule {
    private static final String MODULE_NAME = "HardwareKeyModule";
    private static HardwareKeyModule instance;
    private final ReactApplicationContext reactContext;

    public HardwareKeyModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        instance = this;
    }

    @NonNull
    @Override
    public String getName() {
        return MODULE_NAME;
    }

    public static HardwareKeyModule getInstance() {
        return instance;
    }

    public void emitKeyEvent(int keyCode, int action) {
        String eventType = null;
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                eventType = "left";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                eventType = "right";
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                eventType = "up";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                eventType = "down";
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                eventType = "center";
                break;
            case KeyEvent.KEYCODE_BACK:
                eventType = "back";
                break;
            default:
                return;
        }

        try {
            WritableMap event = Arguments.createMap();
            event.putString("eventType", eventType);
            event.putInt("eventKeyAction", action);
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("onHWKeyEvent", event);
        } catch (Exception e) {
            // Ignore if JS bridge is not ready
        }
    }

    @Override
    public void initialize() {
        super.initialize();
    }
}
