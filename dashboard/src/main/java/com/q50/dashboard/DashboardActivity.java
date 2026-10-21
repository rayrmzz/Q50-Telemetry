package com.q50.dashboard;

import android.app.Activity;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.q50.core.SensorCapture;
import com.q50.core.SensorState;

/** One-screen dashboard. Capture runs on its own thread; the UI refreshes at 4 Hz. */
public class DashboardActivity extends Activity {

    // 4 Hz: the refresh rate validated on the InTouch without ANRs.
    private static final long REFRESH_INTERVAL_MS = 250L;

    private final SensorState sensorState = new SensorState();
    private final SensorState.Snapshot[] snapshots = SensorState.newSnapshots();
    private final SensorState.DerivedRpm derivedRpm = new SensorState.DerivedRpm();
    private final DashModel model = new DashModel();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SensorCapture sensorCapture;
    private DashboardView dashboardView;
    private long copiedVersion = -1L;
    private boolean resumed;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            copiedVersion = sensorState.copyInto(snapshots, derivedRpm, copiedVersion);
            // Update even without new events so stale readings turn into "--".
            model.update(snapshots, derivedRpm, SystemClock.elapsedRealtime());
            dashboardView.invalidate();
            // One pending refresh at most; sensor events never post UI work.
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS);
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
