package com.nextview.pdfviewer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

/** Small native player used by USB Ads mode. Video completion is reported to
 * JS so the common ad playlist can advance to its next item. Uses ExoPlayer
 * StyledPlayerView with TextureView. Rotation is handled by React Native stage. */
public class AdsVideoView extends FrameLayout {
    private final StyledPlayerView playerView;
    private ExoPlayer player;
    private String source;
    private boolean muted = false;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private boolean attached = false;

    public AdsVideoView(ReactContext context) {
        super(context);
        this.setBackgroundColor(0x00000000);
        LayoutInflater.from(context).inflate(R.layout.ads_video_view, this, true);
        this.playerView = findViewById(R.id.ads_video_player_surface);
        this.playerView.setLayoutParams(new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        this.playerView.setKeepScreenOn(true);
        this.playerView.setKeepContentOnPlayerReset(false);
        this.playerView.setUseController(false);
    }

    private void ensurePlayer() {
        if (player != null) return;

        player = new ExoPlayer.Builder(getContext()).build();
        playerView.setPlayer(player);
        player.setVolume(muted ? 0f : 1f);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    emit("onAdReady");
                }
                if (playbackState == Player.STATE_ENDED) {
                    emit("onAdComplete");
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                emit("onAdError");
            }
        });
    }

    public void setSource(String value) {
        if (value == null || value.equals(source)) {
            if (player != null && !source.isEmpty()) {
                try {
                    player.setPlayWhenReady(true);
                    player.play();
                } catch (Exception ignored) {}
            }
            return;
        }
        source = value;
        prepareIfPossible();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
    }

    private void prepareIfPossible() {
        if (!attached || source.isEmpty()) return;
        ensurePlayer();
        if (player != null) {
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(source));
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
            player.play();
        }
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        source = "";
    }

    public void seekTo(int milliseconds) {
        if (player != null) {
            try {
                player.seekTo(milliseconds);
            } catch (Exception ignored) {}
        }
    }

    public void seekForward(int milliseconds) {
        if (player != null) {
            try {
                long current = player.getCurrentPosition();
                long duration = player.getDuration();
                long newPosition = Math.min(duration, current + milliseconds);
                player.seekTo(newPosition);
            } catch (Exception ignored) {}
        }
    }

    public void seekBackward(int milliseconds) {
        if (player != null) {
            try {
                long current = player.getCurrentPosition();
                long newPosition = Math.max(0, current - milliseconds);
                player.seekTo(newPosition);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        prepareIfPossible();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        if (player != null) {
            try {
                player.pause();
            } catch (Exception ignored) {}
        }
        super.onDetachedFromWindow();
    }

    private void emit(String event) {
        ((ReactContext) getContext()).getJSModule(RCTEventEmitter.class).receiveEvent(getId(), event, null);
    }
}
