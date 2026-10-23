package com.nextview.pdfviewer;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

public class AdsVideoViewManager extends SimpleViewManager<AdsVideoView> {
    public static final String NAME = "AdsVideoView";
    @NonNull @Override public String getName() { return NAME; }
    @NonNull @Override protected AdsVideoView createViewInstance(@NonNull ThemedReactContext context) { return new AdsVideoView(context); }
    @ReactProp(name = "source") public void setSource(AdsVideoView view, String source) { view.setSource(source); }
    @ReactProp(name = "muted", defaultBoolean = false) public void setMuted(AdsVideoView view, boolean muted) { view.setMuted(muted); }
    @Override public void onDropViewInstance(@NonNull AdsVideoView view) { view.release(); super.onDropViewInstance(view); }
    @Override public Map getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.of("onAdComplete", MapBuilder.of("registrationName", "onAdComplete"), "onAdError", MapBuilder.of("registrationName", "onAdError"));
    }
}
