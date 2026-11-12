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
import android.widget.TextView;

import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * One camera across the whole screen, with sound. Left and Right switch to the previous or next camera; Back
 * returns to Home, whose cameras start again by themselves (CameraPanel starts in onResume).
 *
 * The player lives between onStart and onStop and is always built new, never kept across them.
 */
public class CameraFullscreenActivity extends Activity {
    static final String EXTRA_URLS = "urls";
    static final String EXTRA_INDEX = "index";

    private ArrayList<String> urls = new ArrayList<>();
    private int index;
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
        index = Math.max(0, Math.min(urls.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));

        float density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
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

        setContentView(root);
        hideSystemBars(root);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onStart() {
        super.onStart();
        if (urls.isEmpty()) return;
        show(index);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (urls.size() > 1) {
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

    private void releasePlayer() {
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
