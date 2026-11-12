package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/** One camera at a time, full screen. Left/Right switch between the four Hikvision channels. */
@SuppressWarnings("UnsafeOptInUsageError")
public final class CameraFullscreenActivity extends Activity {
    static final String EXTRA_INDEX = "camera_index";
    private static final String PREFS = "local_camera_wall";
    private static final String[] CHANNELS = {"102", "302", "502", "702"};
    private static final long RETRY_MS = 6000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int index;
    private AspectFrame frame;
    private TextView label;
    private TextView status;
    private ExoPlayer player;
    private TextureView texture;
    private Runnable retry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        index = Math.max(0, Math.min(3, getIntent().getIntExtra(EXTRA_INDEX, 0)));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        frame = new AspectFrame(this);
        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setBackgroundColor(0x99000000);
        int pad = dp(14);
        label.setPadding(pad, dp(6), pad, dp(6));
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        labelLp.setMargins(dp(24), dp(24), dp(24), dp(24));
        root.addView(label, labelLp);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(20);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        hideSystemBars(root);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        show(index);
    }

    @Override
    protected void onStop() {
        releasePlayer();
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            show((index + 1) % CHANNELS.length);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            show((index + CHANNELS.length - 1) % CHANNELS.length);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private String streamUrl(int cameraIndex) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = prefs.getString("host", "").trim();
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) return null;
        String authority = Uri.encode(user) + ":" + Uri.encode(pass) + "@" + host + ":554";
        return new Uri.Builder().scheme("rtsp").encodedAuthority(authority)
                .appendPath("Streaming").appendPath("Channels").appendPath(CHANNELS[cameraIndex])
                .build().toString();
    }

    private void show(int nextIndex) {
        releasePlayer();
        index = nextIndex;
        label.setText(getString(R.string.camera_n, index + 1));
        frame.setAspect(0);
        frame.removeAllViews();
        texture = new TextureView(this);
        frame.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String url = streamUrl(index);
        if (url == null) {
            status.setText("Configurá primero la conexión al DVR desde Home.");
            status.setVisibility(View.VISIBLE);
            return;
        }
        status.setText("Conectando…");
        status.setVisibility(View.VISIBLE);
        player = new ExoPlayer.Builder(getApplicationContext()).build();
        player.setVideoTextureView(texture);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setVisibility(View.GONE);
            }

            @Override
            public void onRenderedFirstFrame() {
                status.setVisibility(View.GONE);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    frame.setAspect(size.width * size.pixelWidthHeightRatio / size.height);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("Sin señal. Reintentando…");
                status.setVisibility(View.VISIBLE);
                scheduleRetry();
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void scheduleRetry() {
        if (retry != null) handler.removeCallbacks(retry);
        retry = () -> {
            retry = null;
            if (!isFinishing() && !isDestroyed()) show(index);
        };
        handler.postDelayed(retry, RETRY_MS);
    }

    private void releasePlayer() {
        if (retry != null) {
            handler.removeCallbacks(retry);
            retry = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(View root) {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
