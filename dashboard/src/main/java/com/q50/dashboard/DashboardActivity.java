package com.q50.dashboard;

import android.app.Activity;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.q50.core.SensorCapture;
import com.q50.core.SensorState;

/**
 * One-screen dashboard. Capture runs on its own thread; values and texts refresh at 4 Hz
 * and, between refreshes, only the RPM ring is redrawn to sweep toward the last sample.
 */
public class DashboardActivity extends Activity {

    // 4 Hz: the refresh rate validated on the InTouch without ANRs.
    static final long REFRESH_INTERVAL_MS = 250L;
    // Ring-only frames per refresh when the smooth ring is on: 3 -> 12 Hz.
    static final int RING_FRAMES = 3;

    private final SensorState sensorState = new SensorState();
    private final SensorState.Snapshot[] snapshots = SensorState.newSnapshots();
    private final SensorState.DerivedRpm derivedRpm = new SensorState.DerivedRpm();
    private final DashModel model = new DashModel();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SensorCapture sensorCapture;
    private DashboardView dashboardView;
    private long copiedVersion = -1L;
    private boolean resumed;
    // Start of the current refresh period and the frame within it (0 = full refresh).
    private long refreshAt;
    private int frame;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (frame == 0) {
                copiedVersion = sensorState.copyInto(snapshots, derivedRpm, copiedVersion);
                // Update even without new events so stale readings turn into "--".
                model.update(snapshots, derivedRpm, SystemClock.elapsedRealtime());
                dashboardView.onModelUpdated(now);
                dashboardView.invalidate();
            } else {
                dashboardView.invalidateRing();
            }
            int frames = dashboardView.isSmoothRing() ? RING_FRAMES : 1;
            frame++;
            if (frame >= frames) {
                frame = 0;
                refreshAt += REFRESH_INTERVAL_MS;
            }
            long next = refreshAt + REFRESH_INTERVAL_MS * frame / frames;
            if (next < now) {
                // Fell behind (GC, busy main thread): drop the missed frames, never catch up.
                frame = 0;
                refreshAt = now;
                next = now;
            }
            // One pending refresh at most; sensor events never post UI work.
            uiHandler.postAtTime(this, next);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dashboardView = new DashboardView(this, model);
        setContentView(dashboardView);
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorCapture = new SensorCapture(sensorManager, sensorState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        sensorCapture.start();
        uiHandler.removeCallbacks(refreshTask);
        frame = 0;
        refreshAt = SystemClock.uptimeMillis();
        uiHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(refreshTask);
        sensorCapture.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        uiHandler.removeCallbacks(refreshTask);
        sensorCapture.close();
        super.onDestroy();
    }
}
