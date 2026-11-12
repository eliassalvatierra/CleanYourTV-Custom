package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import androidx.annotation.StringRes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * One RTSP camera playing into one TextureView. The owner (CameraPanel, CameraFullscreenActivity) creates it when
 * its screen comes to the front and calls release() when it leaves, so a screen that comes back always gets a
 * fresh player and a fresh surface instead of a player still pointing at a surface the system already destroyed.
 * A stream that fails or ends is retried every few seconds until release().
 */
@SuppressLint("UnsafeOptInUsageError")   // Media3's RTSP and load-control classes are marked unstable API
final class CameraPlayer {
    private static final long RETRY_MS = 6_000L;

    /** Reports what the stream is doing: a string for "connecting" or "no signal", 0 once the picture runs. */
    interface Status {
        void onStatus(@StringRes int message);
    }

    private final Context context;
    private final String url;
    private final boolean muted;
    private final TextureView texture;
    private final AspectFrame frame;
    private final Status status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean released;

    CameraPlayer(Context context, String url, boolean muted, TextureView texture, AspectFrame frame, Status status) {
        this.context = context.getApplicationContext();
        this.url = url;
        this.muted = muted;
        this.texture = texture;
        this.frame = frame;
        this.status = status;
        this.listener = createListener();
    }

    private final Player.Listener listener;

    private Player.Listener createListener() {
        return new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    status.onStatus(R.string.camera_connecting);
                } else if (state == Player.STATE_READY) {
                    status.onStatus(0);
                } else if (state == Player.STATE_ENDED) {
                    status.onStatus(R.string.camera_no_signal);
                    scheduleRetry();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                status.onStatus(0);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (frame != null && size.width > 0 && size.height > 0) {
                    frame.setAspect(size.width * size.pixelWidthHeightRatio / size.height);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.onStatus(R.string.camera_no_signal);
                scheduleRetry();
            }
        };
    }

    void start() {
        if (released || player != null) return;
        status.onStatus(R.string.camera_connecting);
        // Small buffers: these are live streams on a TV with little memory, and four of them may play at once.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 3000, 500, 1000)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(context).setLoadControl(loadControl).build();
        p.setVolume(muted ? 0f : 1f);
        p.setVideoTextureView(texture);
        p.addListener(listener);
        p.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)   // TCP: steadier over Wi-Fi than UDP, and works through most routers
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))));
        p.setPlayWhenReady(true);
        p.prepare();
        player = p;
    }

    private void scheduleRetry() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (released) return;
            if (player != null) {
                player.release();
                player = null;
            }
            start();
        }, RETRY_MS);
    }

    void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
