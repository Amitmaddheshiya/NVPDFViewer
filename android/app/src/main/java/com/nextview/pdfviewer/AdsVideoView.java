package com.nextview.pdfviewer;

import android.content.Context;
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
    private boolean muted;
    private int rotation = 0;

    public AdsVideoView(ReactContext context) {
        super(context);
        // The React layer keeps the previous ad behind this view until this
        // player is prepared, so this container must stay transparent.
        setBackgroundColor(0x00000000);
        // Android's stock VideoView may preserve the source aspect ratio and
        // leave bars. Ads mode is explicitly full-screen stretch, including
        // after a 90/270-degree stage rotation.
        video = new StretchVideoView(context);
        addView(video, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        video.setOnCompletionListener(player -> emit("onAdComplete"));
        video.setOnErrorListener((player, what, extra) -> { emit("onAdError"); return true; });
        video.setOnPreparedListener(player -> {
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            player.start();
            emit("onAdReady");
        });
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
        this.muted = muted;
    }

    public void setRotation(int rotation) {
        this.rotation = rotation;
        video.setRotation(rotation);
        // For 90/270 degree rotations, we need to adjust the VideoView dimensions
        // to match the rotated layout. The parent container handles the stage rotation.
        post(() -> {
            if (rotation == 90 || rotation == 270) {
                // Swap dimensions for quarter turns
                int width = getWidth();
                int height = getHeight();
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) video.getLayoutParams();
                params.width = height;
                params.height = width;
                video.setLayoutParams(params);
                // Set pivot to center
                video.setPivotX(height / 2f);
                video.setPivotY(width / 2f);
            } else {
                // Reset dimensions for 0/180 degree rotations
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) video.getLayoutParams();
                params.width = LayoutParams.MATCH_PARENT;
                params.height = LayoutParams.MATCH_PARENT;
                video.setLayoutParams(params);
                video.setPivotX(getWidth() / 2f);
                video.setPivotY(getHeight() / 2f);
            }
        });
    }

    public void release() { video.stopPlayback(); }

    public void seekTo(int milliseconds) {
        post(() -> {
            try {
                if (video.isPlaying() || video.getCurrentPosition() > 0) {
                    video.seekTo(milliseconds);
                }
            } catch (Exception ignored) { }
        });
    }

    public void seekForward(int milliseconds) {
        post(() -> {
            try {
                int current = video.getCurrentPosition();
                int duration = video.getDuration();
                int newPosition = Math.min(duration, current + milliseconds);
                video.seekTo(newPosition);
            } catch (Exception ignored) { }
        });
    }

    public void seekBackward(int milliseconds) {
        post(() -> {
            try {
                int current = video.getCurrentPosition();
                int newPosition = Math.max(0, current - milliseconds);
                video.seekTo(newPosition);
            } catch (Exception ignored) { }
        });
    }

    private static class StretchVideoView extends VideoView {
        StretchVideoView(Context context) { super(context); }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec)
            );
        }
    }

    private void emit(String event) {
        ((ReactContext) getContext()).getJSModule(RCTEventEmitter.class).receiveEvent(getId(), event, null);
    }
}
