package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
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

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/** Four-camera Hikvision wall. Credentials are entered on the TV and stored in its private app preferences. */
public final class CameraWall extends LinearLayout {
    private static final String PREFS = "local_camera_wall";
    private static final String KEY_HOST = "host", KEY_USER = "user", KEY_PASS = "pass";
    private static final String[] CHANNELS = {"102", "302", "502", "702"};
    private final PlayerView[] surfaces = new PlayerView[4];
    private final ExoPlayer[] players = new ExoPlayer[4];
    private final TextView status;
    private boolean released;

    public CameraWall(Context context, AttributeSet attrs) {
        this(context);
    }

    public CameraWall(Context context) {
        super(context);
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
        heading.addView(title, new LayoutParams(0, dp(30), 1f));
        Button settings = new Button(context);
        settings.setText("CONFIGURAR DVR");
        settings.setTextSize(11);
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> showSetup());
        heading.addView(settings, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)));
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout row1 = new LinearLayout(context);
        LinearLayout row2 = new LinearLayout(context);
        row1.setOrientation(HORIZONTAL);
        row2.setOrientation(HORIZONTAL);
        addView(row1, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(row2, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < 4; i++) {
            PlayerView surface = new PlayerView(context);
            surface.setUseController(false);
            surface.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            surface.setKeepContentOnPlayerReset(true);
            surface.setBackgroundColor(0xFF080B10);
            surface.setFocusable(false);
            surfaces[i] = surface;
            LayoutParams cell = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            cell.setMargins(dp(3), dp(3), dp(3), dp(3));
            (i < 2 ? row1 : row2).addView(surface, cell);
        }

        status = new TextView(context);
        status.setText("Configurá la conexión local al DVR para mostrar las cuatro cámaras.");
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xAA080B10);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));
        updateStatus();
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
        EditText host = field("IP o nombre local del DVR", prefs().getString(KEY_HOST, "192.168.0.119"),
                InputType.TYPE_CLASS_TEXT);
        EditText user = field("Usuario del DVR", prefs().getString(KEY_USER, ""), InputType.TYPE_CLASS_TEXT);
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
                    prefs().edit().putString(KEY_HOST, h).putString(KEY_USER, u).putString(KEY_PASS, p).apply();
                    updateStatus();
                    start();
                }).show();
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

        for (int i = 0; i < 4; i++) {
            if (players[i] != null) continue;

            final int channel = i + 1;
            ExoPlayer player = null;

            try {
                Uri uri = new Uri.Builder().scheme("rtsp")
                        .encodedAuthority(Uri.encode(user) + ":" + Uri.encode(pass) + "@" + host + ":554")
                        .appendPath("Streaming")
                        .appendPath("Channels")
                        .appendPath(CHANNELS[i])
                        .build();

                player = new ExoPlayer.Builder(getContext()).build();

                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_READY) {
                            Log.i("CameraWall", "Canal " + channel + ": reproducción lista");
                        }
                    }

                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        Log.e("CameraWall", "Canal " + channel
                                + ": error Media3 " + error.errorCode
                                + " (código numérico)");

                        // Log the exception chain to identify the RTSP/I/O failure.
                        // Redact credentials in case a library message contains the URI.
                        Throwable cause = error;
                        int causeLevel = 0;
                        while (cause != null && causeLevel < 5) {
                            String detail = cause.getClass().getSimpleName();
                            String message = cause.getMessage();
                            if (message != null) {
                                message = message.replace(user, "[usuario]")
                                        .replace(pass, "[clave]")
                                        .replace(Uri.encode(user), "[usuario]")
                                        .replace(Uri.encode(pass), "[clave]");
                                detail += ": " + message;
                            }
                            Log.e("CameraWall", "Canal " + channel
                                    + ": causa " + causeLevel + " - " + detail);
                            cause = cause.getCause();
                            causeLevel++;
                        }

                        status.post(() -> {
                            status.setText("Error de reproducción en cámara " + channel
                                    + ". Código: " + error.errorCode);
                            status.setVisibility(VISIBLE);
                        });
                    }
                });

                player.setMediaSource(
                        new RtspMediaSource.Factory()
                                .setForceUseRtpTcp(true)
                                .createMediaSource(MediaItem.fromUri(uri))
                );

                surfaces[i].setPlayer(player);
                players[i] = player;

                Log.i("CameraWall", "Iniciando canal " + channel);

                player.setPlayWhenReady(true);
                player.prepare();

            } catch (RuntimeException error) {
                Log.e("CameraWall", "Canal " + channel
                        + ": error al crear el reproductor ("
                        + error.getClass().getSimpleName() + ")");

                if (player != null) {
                    surfaces[i].setPlayer(null);
                    player.release();
                }

                players[i] = null;

                status.setText("Error al iniciar la cámara " + channel);
                status.setVisibility(VISIBLE);
            }
        }
    }

    public void stop() {
        for (int i = 0; i < 4; i++) {
            if (players[i] != null) {
                surfaces[i].setPlayer(null);
                players[i].release();
                players[i] = null;
            }
        }
    }

    public void release() {
        stop();
        released = true;
    }
}
