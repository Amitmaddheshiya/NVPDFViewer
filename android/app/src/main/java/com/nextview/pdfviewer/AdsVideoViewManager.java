package com.nextview.pdfviewer;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

public class AdsVideoViewManager extends SimpleViewManager<AdsVideoView> {
    public static final String NAME = "AdsVideoView";
    public static final int COMMAND_SEEK_FORWARD = 1;
    public static final int COMMAND_SEEK_BACKWARD = 2;

    @NonNull @Override public String getName() { return NAME; }
    @NonNull @Override protected AdsVideoView createViewInstance(@NonNull ThemedReactContext context) { return new AdsVideoView(context); }
    @ReactProp(name = "source") public void setSource(AdsVideoView view, String source) { view.setSource(source); }
    @ReactProp(name = "muted", defaultBoolean = false) public void setMuted(AdsVideoView view, boolean muted) { view.setMuted(muted); }
    @ReactProp(name = "rotation", defaultInt = 0) public void setRotation(AdsVideoView view, int rotation) { view.setRotation(rotation); }
    @Override public void onDropViewInstance(@NonNull AdsVideoView view) { view.release(); super.onDropViewInstance(view); }
    @Override public Map getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.of(
            "onAdComplete", MapBuilder.of("registrationName", "onAdComplete"),
            "onAdError", MapBuilder.of("registrationName", "onAdError"),
            "onAdReady", MapBuilder.of("registrationName", "onAdReady")
        );
    }

    @Override
    public void receiveCommand(@NonNull AdsVideoView view, int commandId, @androidx.annotation.Nullable ReadableArray args) {
        switch (commandId) {
            case COMMAND_SEEK_FORWARD:
                int seekForwardMs = (args != null && args.size() > 0) ? args.getInt(0) : 10000;
                view.seekForward(seekForwardMs);
                break;
            case COMMAND_SEEK_BACKWARD:
                int seekBackwardMs = (args != null && args.size() > 0) ? args.getInt(0) : 10000;
                view.seekBackward(seekBackwardMs);
                break;
            default:
                // Do nothing for unknown commands
                break;
        }
    }

    @Override
    public Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
            "seekForward", COMMAND_SEEK_FORWARD,
            "seekBackward", COMMAND_SEEK_BACKWARD
        );
    }
}
