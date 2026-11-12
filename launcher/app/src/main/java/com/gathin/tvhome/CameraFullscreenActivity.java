package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows one camera across the whole screen, or all configured cameras together in a 2 x 2 wall.
 * Back returns to Home, whose cameras start again by themselves (CameraPanel starts in onResume).
 */
public class CameraFullscreenActivity extends Activity {
    static final String EXTRA_URLS = "urls";
    static final String EXTRA_INDEX = "index";
    static final String EXTRA_ALL_CAMERAS = "all_cameras";

    private final List<CameraPlayer> wallPlayers = new ArrayList<>();
    private ArrayList<String> urls = new ArrayList<>();
    private int index;
    private boolean allCameras;
    private FrameLayout root;
    private AspectFrame frame;
    private VLCVideoLayout video;
    private TextView label;
    private TextView status;
    private CameraPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ArrayList<String> given = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (given != null) urls = given;
        if (urls.isEmpty()) {
            finish();
            return;
        }
        allCameras = getIntent().getBooleanExtra(EXTRA_ALL_CAMERAS, false);
        index = Math.max(0, Math.min(urls.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));

        float density = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        if (!allCameras) {
            frame = new AspectFrame(this);
            video = new VLCVideoLayout(this);
            frame.addView(video, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(frame, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(16);
            label.setBackgroundColor(0x99000000);
            int h = Math.round(14 * density);
            int v = Math.round(6 * density);
            label.setPadding(h, v, h, v);
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            int margin = Math.round(24 * density);
            labelLp.setMargins(margin, margin, margin, margin);
            root.addView(label, labelLp);

            status = new TextView(this);
            status.setTextColor(0xCCFFFFFF);
            status.setTextSize(20);
            root.addView(status, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        setContentView(root);
        hideSystemBars(root);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onStart() {
        super.onStart();
        if (urls.isEmpty()) return;
        if (allCameras) showAllCameras();
        else show(index);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
        releaseWallPlayers();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!allCameras && urls.size() > 1) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                show((index + 1) % urls.size());
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                show((index + urls.size() - 1) % urls.size());
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void show(int i) {
        releasePlayer();
        releaseWallPlayers();
        index = i;
        label.setText(getString(R.string.camera_n, i + 1));
        frame.setAspect(0);
        player = new CameraPlayer(this, urls.get(i), false, video, frame, message -> {
            if (message == null) {
                status.setVisibility(View.GONE);
            } else {
                status.setText(message);
                status.setVisibility(View.VISIBLE);
            }
        });
        player.start();
    }

    /** Opens every configured stream simultaneously, arranged in a 2 x 2 grid. */
    private void showAllCameras() {
        releasePlayer();
        releaseWallPlayers();
        root.removeAllViews();

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int columns = 2;
        int rows = (urls.size() + columns - 1) / columns;
        int gap = Math.max(2, Math.round(getResources().getDisplayMetrics().density * 3));
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            for (int column = 0; column < columns; column++) {
                int cameraIndex = rowIndex * columns + column;
                FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (cameraIndex < urls.size()) {
                    FrameLayout tile = new FrameLayout(this);
                    tile.setBackgroundColor(Color.BLACK);
                    LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    cellParams.setMargins(gap, gap, gap, gap);
                    row.addView(tile, cellParams);

                    VLCVideoLayout videoLayout = new VLCVideoLayout(this);
                    tile.addView(videoLayout, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                    TextView name = new TextView(this);
                    name.setText(getString(R.string.camera_n, cameraIndex + 1));
                    name.setTextColor(Color.WHITE);
                    name.setTextSize(13);
                    name.setBackgroundColor(0x99000000);
                    name.setPadding(gap * 3, gap * 2, gap * 3, gap * 2);
                    FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.START);
                    tile.addView(name, nameParams);

                    TextView cameraStatus = new TextView(this);
                    cameraStatus.setTextColor(0xCCFFFFFF);
                    cameraStatus.setTextSize(16);
                    cameraStatus.setGravity(Gravity.CENTER);
                    tile.addView(cameraStatus, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER));

                    CameraPlayer cameraPlayer = new CameraPlayer(this, urls.get(cameraIndex), true,
                            videoLayout, null, message -> {
                        if (message == null) {
                            cameraStatus.setVisibility(View.GONE);
                        } else {
                            cameraStatus.setText(message);
                            cameraStatus.setVisibility(View.VISIBLE);
                        }
                    });
                    wallPlayers.add(cameraPlayer);
                    cameraPlayer.start();
                } else {
                    View empty = new View(this);
                    LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    cellParams.setMargins(gap, gap, gap, gap);
                    row.addView(empty, cellParams);
                }
            }
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void releaseWallPlayers() {
        for (CameraPlayer cameraPlayer : wallPlayers) cameraPlayer.release();
        wallPlayers.clear();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(View root) {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
