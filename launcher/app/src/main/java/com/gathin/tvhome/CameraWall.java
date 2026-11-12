package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Four-camera Hikvision wall using LibVLC. This replaces Media3 RTSP because
 * some Hikvision SDP responses omit fmtp parameters that Media3 rejects.
 * Credentials remain in the app's private SharedPreferences.
 */
public final class CameraWall extends LinearLayout {
    private static final String PREFS = "local_camera_wall";
    private static final String KEY_HOST = "host", KEY_USER = "user", KEY_PASS = "pass";
    private static final String[] CHANNELS = {"102", "302", "502", "702"};

    private final VLCVideoLayout[] surfaces = new VLCVideoLayout[4];
    private LinearLayout firstRow;
    private LinearLayout secondRow;
    private final MediaPlayer[] players = new MediaPlayer[4];
    private final TextView status;
    private LibVLC libVLC;
    private boolean released;
    private final boolean fullScreen;
    private final Runnable startRunnable = this::start;

    public CameraWall(Context context, AttributeSet attrs) {
        this(context, false);
    }

    public CameraWall(Context context) {
        this(context, false);
    }

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

        LinearLayout row1 = new LinearLayout(context);
        LinearLayout row2 = new LinearLayout(context);
        firstRow = row1;
        secondRow = row2;
        row1.setGravity(Gravity.CENTER);
        row2.setGravity(Gravity.CENTER);
        row1.setOrientation(HORIZONTAL);
        row2.setOrientation(HORIZONTAL);
        addView(row1, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(row2, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < 4; i++) {
            VLCVideoLayout surface = new VLCVideoLayout(context);
            surface.setBackgroundColor(0xFF080B10);
            surface.setFocusable(false);
            surfaces[i] = surface;
            LayoutParams cell = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            cell.setMargins(dp(2), dp(2), dp(2), dp(2));
            (i < 2 ? row1 : row2).addView(surface, cell);
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
            // A 16:9 TV split into four equal cells gives each camera its native 16:9 viewport.
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
            VLCVideoLayout surface = surfaces[i];
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) surface.getLayoutParams();
            if (params.width != cellWidth || params.height != cellHeight) {
                params.width = cellWidth;
                params.height = cellHeight;
                surface.setLayoutParams(params);
            }
        }
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

    private void ensureVlc() {
        if (libVLC != null) return;
        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--network-caching=500");
        options.add("--rtsp-tcp");
        libVLC = new LibVLC(getContext().getApplicationContext(), options);
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

        try {
            ensureVlc();
        } catch (RuntimeException error) {
            Log.e("CameraWall", "No se pudo inicializar LibVLC: " + error.getClass().getSimpleName());
            status.setText("No se pudo iniciar el motor de video.");
            status.setVisibility(VISIBLE);
            return;
        }

        for (int i = 0; i < 4; i++) {
            if (players[i] != null) continue;
            final int channel = i + 1;
            MediaPlayer player = null;
            Media media = null;
            try {
                Uri uri = new Uri.Builder().scheme("rtsp")
                        .encodedAuthority(Uri.encode(user) + ":" + Uri.encode(pass) + "@" + host + ":554")
                        .appendPath("Streaming").appendPath("Channels")
                        .appendPath(CHANNELS[i]).build();

                player = new MediaPlayer(libVLC);
                player.attachViews(surfaces[i], null, false, false);
                player.setEventListener(event -> {
                    if (event.type == MediaPlayer.Event.Playing) {
                        Log.i("CameraWall", "Canal " + channel + ": reproducción lista");
                        status.post(() -> {
                            status.setText("Cámaras conectadas mediante RTSP.");
                            status.setVisibility(VISIBLE);
                        });
                    } else if (event.type == MediaPlayer.Event.EncounteredError) {
                        Log.e("CameraWall", "Canal " + channel + ": LibVLC informó un error RTSP/decodificación");
                        status.post(() -> {
                            status.setText("No se pudo reproducir la cámara " + channel + ". Revisá el stream del DVR.");
                            status.setVisibility(VISIBLE);
                        });
                    }
                });

                media = new Media(libVLC, uri);
                media.addOption(":rtsp-tcp");
                media.addOption(":network-caching=500");
                media.addOption(":no-audio");
                media.addOption(":avcodec-hw=any");
                player.setMedia(media);
                media.release();
                media = null;

                players[i] = player;
                Log.i("CameraWall", "Iniciando canal físico " + (i * 2 + 1));
                player.play();
            } catch (RuntimeException error) {
                Log.e("CameraWall", "Canal " + channel + ": error al iniciar LibVLC ("
                        + error.getClass().getSimpleName() + ")");
                if (media != null) media.release();
                if (player != null) {
                    try { player.stop(); } catch (RuntimeException ignored) {}
                    try { player.detachViews(); } catch (RuntimeException ignored) {}
                    player.release();
                }
                players[i] = null;
                status.setText("Error al iniciar la cámara " + channel + ".");
                status.setVisibility(VISIBLE);
            }
        }
    }

    public void restart() {
        stop();
        if (libVLC != null) {
            try { libVLC.release(); } catch (RuntimeException ignored) {}
            libVLC = null;
        }
        // Recreate LibVLC after another app has covered Home: TV devices can invalidate
        // the native video surfaces while the activity is paused.
        removeCallbacks(startRunnable);
        postDelayed(startRunnable, 650);
    }

    public void stop() {
        removeCallbacks(startRunnable);
        for (int i = 0; i < 4; i++) {
            if (players[i] != null) {
                MediaPlayer player = players[i];
                players[i] = null;
                try { player.stop(); } catch (RuntimeException ignored) {}
                try { player.detachViews(); } catch (RuntimeException ignored) {}
                player.release();
            }
        }
    }

    public void release() {
        stop();
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        released = true;
    }
}
