package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * One RTSP camera played by LibVLC. The owner creates the player when its screen comes to the front
 * and releases it when it leaves. A failed stream is retried until release().
 *
 * All camera players share one LibVLC engine; each camera has its own MediaPlayer.
 */
final class CameraPlayer {
    private static final String TAG = "CameraPlayer";
    private static final long RETRY_MS = 6_000L;

    private static LibVLC sharedLibVlc;
    private static int engineUsers;

    interface Status {
        void onStatus(@Nullable CharSequence message);
    }

    private final Context context;
    private final String url;
    private final boolean muted;
    private final VLCVideoLayout videoLayout;
    private final Status status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LibVLC libVlc;
    private MediaPlayer player;
    private boolean released;
    // Once playback has started, later buffering events must not cover an already visible image.
    private boolean streamStarted;

    CameraPlayer(Context context, String url, boolean muted, VLCVideoLayout videoLayout,
                 AspectFrame frame, Status status) {
        this.context = context.getApplicationContext();
        this.url = url;
        this.muted = muted;
        this.videoLayout = videoLayout;
        this.status = status;
    }

    private static synchronized LibVLC acquireEngine(Context context) {
        if (sharedLibVlc == null) {
            ArrayList<String> options = new ArrayList<>();
            options.add("--rtsp-tcp");
            options.add("--network-caching=500");
            sharedLibVlc = new LibVLC(context, options);
        }
        engineUsers++;
        return sharedLibVlc;
    }

    private static synchronized void releaseEngine() {
        if (engineUsers > 0) engineUsers--;
        if (engineUsers == 0 && sharedLibVlc != null) {
            sharedLibVlc.release();
            sharedLibVlc = null;
        }
    }

    void start() {
        if (released || player != null) return;

        streamStarted = false;
        status.onStatus(context.getString(R.string.camera_connecting));
        try {
            libVlc = acquireEngine(context);
            MediaPlayer next = new MediaPlayer(libVlc);
            next.setEventListener(event -> {
                if (released || player != next) return;
                switch (event.type) {
                    case MediaPlayer.Event.Opening:
                    case MediaPlayer.Event.Buffering:
                        if (!streamStarted) {
                            status.onStatus(context.getString(R.string.camera_connecting));
                        }
                        break;
                    case MediaPlayer.Event.Playing:
                        streamStarted = true;
                        status.onStatus(null);
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        streamStarted = false;
                        Log.w(TAG, "LibVLC could not play camera stream (RTSP/H.264)");
                        status.onStatus(context.getString(R.string.camera_no_signal));
                        scheduleRetry();
                        break;
                    case MediaPlayer.Event.Stopped:
                        if (!released) {
                            streamStarted = false;
                            status.onStatus(context.getString(R.string.camera_no_signal));
                            scheduleRetry();
                        }
                        break;
                    default:
                        break;
                }
            });

            next.attachViews(videoLayout, null, false, true);
            // Fill each camera tile while preserving the source aspect ratio.
            // LibVLC crops the excess image instead of leaving black side bars.
            next.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL);

            Media media = new Media(libVlc, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":rtsp-tcp");
            media.addOption(":network-caching=500");
            next.setMedia(media);
            media.release();
            next.setVolume(muted ? 0 : 100);
            player = next;
            next.play();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to initialize LibVLC camera player", error);
            status.onStatus(context.getString(R.string.camera_no_signal) + "\n" + error.getClass().getSimpleName());
            releaseCurrentPlayer();
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (released) return;
            releaseCurrentPlayer();
            start();
        }, RETRY_MS);
    }

    private void releaseCurrentPlayer() {
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            try {
                current.stop();
                current.detachViews();
            } catch (RuntimeException error) {
                Log.w(TAG, "Error detaching LibVLC video output", error);
            }
            current.release();
        }
        if (libVlc != null) {
            libVlc = null;
            releaseEngine();
        }
    }

    void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releaseCurrentPlayer();
    }
}
