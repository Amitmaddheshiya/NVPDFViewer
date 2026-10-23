package com.nextview.pdfviewer;

import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.VideoView;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

/** Small native player used by USB Ads mode. Video completion is reported to
 * JS so the common ad playlist can advance to its next item. */
public class AdsVideoView extends FrameLayout {
    private final VideoView video;
    private String source;

    public AdsVideoView(ReactContext context) {
        super(context);
        setBackgroundColor(0xFF000000);
        video = new VideoView(context);
        addView(video, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        video.setOnCompletionListener(player -> emit("onAdComplete"));
        video.setOnErrorListener((player, what, extra) -> { emit("onAdError"); return true; });
    }

    public void setSource(String value) {
        if (value == null || value.equals(source)) return;
        source = value;
        try {
            video.setVideoURI(Uri.parse(value));
            video.start();
        } catch (Exception error) { emit("onAdError"); }
    }

    public void setMuted(boolean muted) {
        video.setOnPreparedListener(player -> {
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            player.start();
        });
    }

    public void release() { video.stopPlayback(); }

    private void emit(String event) {
        ((ReactContext) getContext()).getJSModule(RCTEventEmitter.class).receiveEvent(getId(), event, null);
    }
}
