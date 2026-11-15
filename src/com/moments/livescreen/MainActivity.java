package com.moments.livescreen;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Full screen NDI program feed. Picks Moments Creator's feed on its own (or the one chosen last time),
 * shows a note while it's missing, and NDI reconnects by itself when the sender comes back.
 * OK opens the source list.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    /** Moments Creator's feed is "MAC (Moments Live – Clean)" unless renamed in its Live settings. */
    private static final String MOMENTS_FEED = "(Moments Live";
    private static final long STALE_MS = 3000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WifiManager.MulticastLock multicast;
    private boolean ndiOk;

    private SurfaceView video;
    private TextView status;
    private LinearLayout pickerBox;
    private TextView pickerInfo;
    private ListView picker;
    private final List<String> pickerItems = new ArrayList<>();

    private SurfaceHolder holder;
    private String source;
    private boolean low;
    private volatile String[] found = new String[0];
    private volatile boolean finding;
    private long shownW, shownH;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("screen", MODE_PRIVATE);
        source = prefs.getString("source", null);
        low = prefs.getBoolean("low", false);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicast = wifi.createMulticastLock("ndi");
            multicast.setReferenceCounted(false);
        }
        ndiOk = Ndi.init();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 24, 48, 24);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        pickerBox = new LinearLayout(this);
        pickerBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.setBackgroundColor(0xE6101418);
        pickerBox.setPadding(40, 32, 40, 32);
        TextView title = new TextView(this);
        title.setText("Live Screen");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        pickerBox.addView(title);
        pickerInfo = new TextView(this);
        pickerInfo.setTextColor(0xAAFFFFFF);
        pickerInfo.setTextSize(16);
        pickerInfo.setPadding(0, 6, 0, 18);
        pickerBox.addView(pickerInfo);
        picker = new ListView(this);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, pickerItems));
        picker.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) { onPick(i); }
        });
        pickerBox.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        pickerBox.setVisibility(View.GONE);
        root.addView(pickerBox, new FrameLayout.LayoutParams(900, -2, Gravity.CENTER));

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (multicast != null) multicast.acquire();
        if (!ndiOk) { status.setText("NDI couldn't start on this device."); return; }
        finding = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (finding) {
                    final String[] now = Ndi.sources(2000);
                    if (!Arrays.equals(now, found)) {
                        found = now;
                        ui.post(new Runnable() { @Override public void run() { onSources(); } });
                    }
                }
            }
        }, "ndi-find").start();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        finding = false;
        ui.removeCallbacks(tick);
        if (multicast != null) multicast.release();
        super.onStop();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; play(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hgt) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; Ndi.stop(); }

    private void play() {
        if (ndiOk && holder != null && source != null) Ndi.start(source, holder.getSurface(), low);
    }

    private void choose(String name, boolean remember) {
        source = name;
        if (remember) prefs.edit().putString("source", name).apply();
        shownW = shownH = 0;
        play();
    }

    /** First run: Moments Creator's feed as soon as it shows up. */
    private void onSources() {
        if (source == null) {
            for (String s : found) {
                if (s.contains(MOMENTS_FEED)) { choose(s, true); break; }
            }
        }
        if (pickerBox.getVisibility() == View.VISIBLE) fillPicker();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long[] s = Ndi.stats();
            boolean live = source != null && s[3] >= 0 && s[3] < STALE_MS;
            if (source == null) {
                status.setText(found.length == 0
                        ? "Looking for NDI sources on this network…"
                        : "Press OK to choose what to show.");
            } else if (!live) {
                status.setText("Waiting for " + shortName(source) + "…");
            }
            status.setVisibility(live ? View.GONE : View.VISIBLE);
            if (live && (s[1] != shownW || s[2] != shownH)) fit(s[1], s[2]);
            if (pickerBox.getVisibility() == View.VISIBLE) {
                pickerInfo.setText(live
                        ? s[1] + "×" + s[2] + " at " + Math.round(s[4] / 100.0) + " fps"
                        : found.length + " source" + (found.length == 1 ? "" : "s") + " found");
            }
            ui.postDelayed(this, 1000);
        }
    };

    /** Letterbox the picture to its own shape. */
    private void fit(long w, long h) {
        shownW = w;
        shownH = h;
        View root = (View) video.getParent();
        int rw = root.getWidth(), rh = root.getHeight();
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return;
        int vw = rw, vh = (int) (rw * h / w);
        if (vh > rh) { vh = rh; vw = (int) (rh * w / h); }
        video.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private void fillPicker() {
        pickerItems.clear();
        for (String s : found) pickerItems.add((s.equals(source) ? "●  " : "     ") + s);
        pickerItems.add(low ? "Quality: lower (for weak Wi‑Fi)" : "Quality: full");
        ((ArrayAdapter<?>) picker.getAdapter()).notifyDataSetChanged();
    }

    private void onPick(int i) {
        String[] now = found;
        if (i < now.length) {
            choose(now[i], true);
            pickerBox.setVisibility(View.GONE);
        } else {
            low = !low;
            prefs.edit().putBoolean("low", low).apply();
            play();
            fillPicker();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean open = pickerBox.getVisibility() == View.VISIBLE;
        if (!open && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            fillPicker();
            pickerBox.setVisibility(View.VISIBLE);
            picker.requestFocus();
            int at = Arrays.asList(found).indexOf(source);
            picker.setSelection(Math.max(at, 0));
            return true;
        }
        if (open && code == KeyEvent.KEYCODE_BACK) {
            pickerBox.setVisibility(View.GONE);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /** "MAC (Moments Live – Clean)" → "Moments Live – Clean". */
    private static String shortName(String s) {
        int a = s.indexOf('('), b = s.lastIndexOf(')');
        return a >= 0 && b > a ? s.substring(a + 1, b) : s;
    }
}
