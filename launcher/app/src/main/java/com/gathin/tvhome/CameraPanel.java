package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The cameras on Home: up to four RTSP streams in a 1 x 1, 2 x 1 or 2 x 2 grid that fills the space it is given
 * (the left 70% of Home, see activity_main.xml). A header row above the video holds the title and the Edit button,
 * so nothing covers a picture.
 *
 * Lifecycle: MainActivity calls start() from onResume and stop() from onPause. stop() releases every player and
 * drops every surface, and start() builds the tiles from scratch, so coming back to Home from another app or from
 * a camera's full screen always shows the cameras again.
 */
public class CameraPanel extends LinearLayout {
    private final LinearLayout grid;
    private final TextView emptyView;
    private final View editButton;
    private final List<Tile> tiles = new ArrayList<>();
    private final float density;
    private boolean started;
    /** The camera that had focus when Home last stopped, so coming back puts focus on it again; -1 for none. */
    private int restoreIndex = -1;

    public CameraPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        density = context.getResources().getDisplayMetrics().density;
        LayoutInflater.from(context).inflate(R.layout.view_camera_panel, this, true);
        grid = findViewById(R.id.cameraGrid);
        emptyView = findViewById(R.id.cameraEmpty);
        editButton = findViewById(R.id.cameraEdit);
        editButton.setOnClickListener(v -> showEditDialog());
        emptyView.setOnClickListener(v -> showEditDialog());
    }

    void start() {
        started = true;
        buildTiles();
    }

    void stop() {
        started = false;
        restoreIndex = -1;
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).view.hasFocus()) restoreIndex = i;
        }
        releaseTiles();
        grid.removeAllViews();
    }

    boolean focusFirst() {
        if (!tiles.isEmpty()) return tiles.get(0).view.requestFocus();
        if (emptyView.getVisibility() == VISIBLE) return emptyView.requestFocus();
        return editButton.requestFocus();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (started) stop();
    }

    private void releaseTiles() {
        for (Tile tile : tiles) tile.release();
        tiles.clear();
    }

    private void buildTiles() {
        releaseTiles();
        grid.removeAllViews();
        List<String> urls = CameraStore.load(getContext());
        emptyView.setVisibility(urls.isEmpty() ? VISIBLE : GONE);
        int count = urls.size();
        if (count == 0) return;
        int columns = count == 1 ? 1 : 2;
        int rows = (count + columns - 1) / columns;
        int gap = Math.round(4 * density);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            grid.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(gap, gap, gap, gap);
                if (index >= count) {
                    row.addView(new View(getContext()), lp);
                    continue;
                }
                Tile tile = new Tile(index, urls);
                row.addView(tile.view, lp);
                tiles.add(tile);
                tile.player.start();
            }
        }
        if (restoreIndex >= 0 && restoreIndex < tiles.size()) {
            View wanted = tiles.get(restoreIndex).view;
            restoreIndex = -1;
            post(() -> wanted.requestFocus());
        }
        restoreIndex = -1;
    }

    private final class Tile {
        final FrameLayout view;
        final CameraPlayer player;

        Tile(int index, List<String> urls) {
            Context context = getContext();
            view = new FrameLayout(context);
            int pad = Math.round(3 * density);
            view.setPadding(pad, pad, pad, pad);
            view.setBackground(tileBackground());
            view.setFocusable(true);
            view.setClickable(true);

            AspectFrame frame = new AspectFrame(context);
            VLCVideoLayout video = new VLCVideoLayout(context);
            frame.addView(video, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.addView(frame, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(context);
            label.setText(context.getString(R.string.camera_n, index + 1));
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setSingleLine(true);
            label.setBackgroundColor(0x99000000);
            int h = Math.round(8 * density);
            int v = Math.round(3 * density);
            label.setPadding(h, v, h, v);
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            view.addView(label, labelLp);

            TextView status = new TextView(context);
            status.setTextColor(0xCCFFFFFF);
            status.setTextSize(14);
            status.setGravity(Gravity.CENTER);
            view.addView(status, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            player = new CameraPlayer(context, urls.get(index), true, video, frame, message -> {
                if (message == null) {
                    status.setVisibility(GONE);
                } else {
                    status.setText(message);
                    status.setVisibility(VISIBLE);
                }
            });
            view.setOnClickListener(x -> openFullscreen(urls, index));
        }

        void release() {
            player.release();
        }
    }

    private Drawable tileBackground() {
        float radius = 6 * density;
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.BLACK);
        normal.setCornerRadius(radius);
        normal.setStroke(Math.round(density), 0x33FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.BLACK);
        focused.setCornerRadius(radius);
        focused.setStroke(Math.round(3 * density), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private void openFullscreen(List<String> urls, int index) {
        Intent intent = new Intent(getContext(), CameraFullscreenActivity.class);
        intent.putStringArrayListExtra(CameraFullscreenActivity.EXTRA_URLS, new ArrayList<>(urls));
        intent.putExtra(CameraFullscreenActivity.EXTRA_INDEX, index);
        getContext().startActivity(intent);
    }

    private void showEditDialog() {
        Context context = getContext();
        List<String> current = CameraStore.load(context);
        int pad = Math.round(20 * density);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(VERTICAL);
        form.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(context);
        hint.setText(R.string.cameras_hint);
        hint.setTextSize(14);
        form.addView(hint);

        EditText[] fields = new EditText[CameraStore.MAX];
        for (int i = 0; i < fields.length; i++) {
            EditText field = new EditText(context);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            field.setImeOptions(i == fields.length - 1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            field.setHint(context.getString(R.string.camera_n, i + 1) + "  rtsp://user:pass@host:554/stream");
            field.setText(i < current.size() ? current.get(i) : "");
            form.addView(field, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            fields[i] = field;
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        new AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.cameras_edit_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> entered = new ArrayList<>();
                    for (EditText field : fields) entered.add(field.getText().toString());
                    CameraStore.save(context, entered);
                    if (started) buildTiles();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
