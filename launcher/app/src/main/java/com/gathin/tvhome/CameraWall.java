package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.util.ArrayList;

/**
 * Four-camera Hikvision wall using Media3 RTSP. This is an intentional retry of Media3:
 * the previous implementation rejected some Hikvision SDP responses (missing fmtp).
 * Existing DVR credentials and channel mapping are retained.
 */
@SuppressWarnings("UnsafeOptInUsageError")
public final class CameraWall extends LinearLayout {
    private static final String PREFS = "local_camera_wall";
    private static final String KEY_HOST = "host", KEY_USER = "user", KEY_PASS = "pass";
    private static final String[] CHANNELS = {"102", "302", "502", "702"};
    private static final long RETRY_MS = 6000L;

    private final AspectFrame[] frames = new AspectFrame[4];
    private final TextureView[] textures = new TextureView[4];
    private final ExoPlayer[] players = new ExoPlayer[4];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable[] retryRunnables = new Runnable[4];
    private LinearLayout firstRow;
    private LinearLayout secondRow;
    private final TextView status;
    private boolean released;
    private final boolean fullScreen;

    public CameraWall(Context context, AttributeSet attrs) { this(context, false); }
    public CameraWall(Context context) { this(context, false); }

    public CameraWall(Context context, boolean fullScreen) {
        super(context);
        this.fullScreen = fullScreen;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("CÁMARAS EN VIVO");
        title.setTextColor(0xCCFFFFFF);
        title.setTextSize(12);
        title.setLetterSpacing(0.16f);
        heading.addView(title, new LayoutParams(0, dp(26), 1f));
        Button settings = new Button(context);
        settings.setText("CONFIGURAR DVR");
        settings.setTextSize(11);
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> showSetup());
        settings.setMinHeight(0);
        settings.setMinWidth(0);
        settings.setPadding(dp(10), 0, dp(10), 0);
        settings.setIncludeFontPadding(false);
        heading.addView(settings, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)));
        settings.setTranslationY(-dp(3));
        Button fullScreenButton = new Button(context);
        fullScreenButton.setText("PANTALLA COMPLETA");
        fullScreenButton.setTextSize(10);
        fullScreenButton.setAllCaps(false);
        fullScreenButton.setMinHeight(0);
        fullScreenButton.setMinWidth(0);
        fullScreenButton.setPadding(dp(8), 0, dp(8), 0);
        fullScreenButton.setOnClickListener(v ->
                context.startActivity(new Intent(context, CameraFullscreenActivity.class)));
        heading.addView(fullScreenButton, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)));
        heading.setVisibility(fullScreen ? GONE : VISIBLE);
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        firstRow = new LinearLayout(context);
        secondRow = new LinearLayout(context);
        firstRow.setGravity(Gravity.CENTER);
        secondRow.setGravity(Gravity.CENTER);
        firstRow.setOrientation(HORIZONTAL);
        secondRow.setOrientation(HORIZONTAL);
        addView(firstRow, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(secondRow, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < 4; i++) {
            AspectFrame frame = new AspectFrame(context);
            frame.setBackground(cameraBackground());
            TextureView texture = new TextureView(context);
            frame.addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            frame.setFocusable(!fullScreen);
            frame.setClickable(!fullScreen);
            frames[i] = frame;
            textures[i] = texture;
            final int cameraIndex = i;
            frame.setOnClickListener(v -> openFullscreen(cameraIndex));
            LayoutParams cell = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            cell.setMargins(dp(2), dp(2), dp(2), dp(2));
            (i < 2 ? firstRow : secondRow).addView(frame, cell);
        }

        status = new TextView(context);
        status.setText("Configurá la conexión local al DVR para mostrar las cuatro cámaras.");
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xAA080B10);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, dp(24)));
        if (fullScreen) status.setVisibility(GONE);
        updateStatus();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        sizeSquareCells();
    }

    private void sizeSquareCells() {
        if (firstRow == null || secondRow == null || getWidth() == 0 || getHeight() == 0) return;
        int cellWidth;
        int cellHeight;
        if (fullScreen) {
            cellWidth = Math.max(0, (getWidth() - dp(8)) / 2);
            cellHeight = Math.max(0, (getHeight() - dp(8)) / 2);
        } else {
            int availableWidth = Math.max(0, (getWidth() - dp(16)) / 2);
            int side = Math.min(availableWidth, Math.min(firstRow.getHeight(), secondRow.getHeight()) - dp(4));
            cellWidth = side;
            cellHeight = side;
        }
        if (cellWidth <= 0 || cellHeight <= 0) return;
        for (int i = 0; i < 4; i++) {
            LayoutParams params = (LayoutParams) frames[i].getLayoutParams();
            if (params.width != cellWidth || params.height != cellHeight) {
                params.width = cellWidth;
                params.height = cellHeight;
                frames[i].setLayoutParams(params);
            }
        }
    }

    private StateListDrawable cameraBackground() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF080B10);
        normal.setStroke(dp(1), 0x55777777);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF080B10);
        focused.setStroke(dp(3), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void updateStatus() {
        boolean configured = !prefs().getString(KEY_HOST, "").isEmpty()
                && !prefs().getString(KEY_USER, "").isEmpty()
                && !prefs().getString(KEY_PASS, "").isEmpty();
        status.setVisibility(configured ? GONE : VISIBLE);
    }

    private EditText field(String hint, String value, int type) {
        EditText edit = new EditText(getContext());
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setText(value);
        edit.setInputType(type);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0xFF888888);
        return edit;
    }

    private void showSetup() {
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText host = field("IP o nombre local del DVR",
                prefs().getString(KEY_HOST, "192.168.0.119"), InputType.TYPE_CLASS_TEXT);
        EditText user = field("Usuario del DVR", prefs().getString(KEY_USER, ""),
                InputType.TYPE_CLASS_TEXT);
        EditText pass = field("Contraseña del DVR", prefs().getString(KEY_PASS, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(host);
        form.addView(user);
        form.addView(pass);
        new AlertDialog.Builder(getContext())
                .setTitle("Conexión Hikvision")
                .setMessage("Se usarán los substreams RTSP de los canales 1, 3, 5 y 7 por la red local.")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Borrar", (dialog, which) -> {
                    prefs().edit().clear().apply();
                    stop();
                    updateStatus();
                })
                .setPositiveButton("Guardar y conectar", (dialog, which) -> {
                    String h = host.getText().toString().trim();
                    String u = user.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                        status.setText("Faltan datos. Completá IP, usuario y contraseña del DVR.");
                        status.setVisibility(VISIBLE);
                        return;
                    }
                    prefs().edit().putString(KEY_HOST, h).putString(KEY_USER, u)
                            .putString(KEY_PASS, p).apply();
                    updateStatus();
                    start();
                }).show();
    }

    public boolean focusFirst() {
        return !fullScreen && frames[0] != null && frames[0].requestFocus();
    }

    private void openFullscreen(int index) {
        Intent intent = new Intent(getContext(), CameraFullscreenActivity.class);
        intent.putExtra(CameraFullscreenActivity.EXTRA_INDEX, index);
        getContext().startActivity(intent);
    }

    private String streamUrl(String host, String user, String pass, int index) {
        String authority = Uri.encode(user) + ":" + Uri.encode(pass) + "@" + host + ":554";
        return new Uri.Builder().scheme("rtsp").encodedAuthority(authority)
                .appendPath("Streaming").appendPath("Channels").appendPath(CHANNELS[index])
                .build().toString();
    }

    private void startPlayer(int index, String url) {
        if (released || players[index] != null) return;
        status.setText("Conectando cámara " + (index + 1) + "…");
        status.setVisibility(fullScreen ? GONE : VISIBLE);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 3000, 500, 1000).build();
        ExoPlayer player = new ExoPlayer.Builder(getContext().getApplicationContext())
                .setLoadControl(loadControl).build();
        players[index] = player;
        player.setVolume(0f);
        player.setVideoTextureView(textures[index]);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.post(() -> {
                        if (!fullScreen) status.setVisibility(GONE);
                    });
                } else if (state == Player.STATE_BUFFERING) {
                    status.post(() -> {
                        if (!fullScreen) {
                            status.setText("Conectando cámara " + (index + 1) + "…");
                            status.setVisibility(VISIBLE);
                        }
                    });
                } else if (state == Player.STATE_ENDED) {
                    scheduleRetry(index, url);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    float aspect = size.width * size.pixelWidthHeightRatio / size.height;
                    frames[index].setAspect(aspect);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                status.post(() -> {
                    if (!fullScreen) status.setVisibility(GONE);
                });
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e("CameraWall", "Canal " + (index + 1) + ": Media3 RTSP falló: "
                        + error.getErrorCodeName());
                status.post(() -> {
                    if (!fullScreen) {
                        status.setText("Sin señal en cámara " + (index + 1) + ". Reintentando…");
                        status.setVisibility(VISIBLE);
                    }
                });
                scheduleRetry(index, url);
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void scheduleRetry(int index, String url) {
        if (retryRunnables[index] != null) handler.removeCallbacks(retryRunnables[index]);
        Runnable retry = () -> {
            retryRunnables[index] = null;
            if (released) return;
            ExoPlayer old = players[index];
            players[index] = null;
            if (old != null) old.release();
            startPlayer(index, url);
        };
        retryRunnables[index] = retry;
        handler.postDelayed(retry, RETRY_MS);
    }

    public void start() {
        if (released) return;
        String host = prefs().getString(KEY_HOST, "").trim();
        String user = prefs().getString(KEY_USER, "");
        String pass = prefs().getString(KEY_PASS, "");
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            updateStatus();
            return;
        }
        for (int i = 0; i < 4; i++) startPlayer(i, streamUrl(host, user, pass, i));
    }

    public void restart() {
        if (released) return;
        stop();
        for (int i = 0; i < 4; i++) {
            LinearLayout parent = (LinearLayout) frames[i].getParent();
            if (parent != null) parent.removeView(frames[i]);
            AspectFrame frame = new AspectFrame(getContext());
            frame.setBackgroundColor(0xFF080B10);
            TextureView texture = new TextureView(getContext());
            frame.addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            frames[i] = frame;
            textures[i] = texture;
            frame.setFocusable(!fullScreen);
            frame.setClickable(!fullScreen);
            final int cameraIndex = i;
            frame.setOnClickListener(v -> openFullscreen(cameraIndex));
            LayoutParams cell = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            cell.setMargins(dp(2), dp(2), dp(2), dp(2));
            (i < 2 ? firstRow : secondRow).addView(frame, cell);
        }
        requestLayout();
        removeCallbacks(restartRunnable);
        postDelayed(restartRunnable, 900);
    }

    private final Runnable restartRunnable = this::start;

    public void stop() {
        removeCallbacks(restartRunnable);
        for (int i = 0; i < 4; i++) {
            if (retryRunnables[i] != null) {
                handler.removeCallbacks(retryRunnables[i]);
                retryRunnables[i] = null;
            }
            if (players[i] != null) {
                ExoPlayer player = players[i];
                players[i] = null;
                player.stop();
                player.release();
            }
        }
    }

    public void release() {
        stop();
        released = true;
    }
}
