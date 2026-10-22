package com.q50.core;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/** Owns sensor registration and event delivery on one background looper. */
public final class SensorCapture implements SensorEventListener {
    private static final int SENSOR_PERIOD_US = 200000;

    private final SensorManager sensorManager;
    private final SensorState state;
    private final Sensor[] sensors = new Sensor[SensorState.TYPE_COUNT];
    private final HandlerThread thread = new HandlerThread("Q50SensorCapture");
    private final Handler handler;
    private boolean active; // Accessed only on the capture looper.
    private boolean closed; // Accessed only by the Activity on the main looper.
    private final Runnable stopTask = new Runnable() {
        @Override
        public void run() {
            stopOnCaptureThread();
        }
    };

    public SensorCapture(SensorManager sensorManager, SensorState state) {
        this.sensorManager = sensorManager;
        this.state = state;
        if (sensorManager != null) {
            List<Sensor> available = sensorManager.getSensorList(Sensor.TYPE_ALL);
            if (available != null) {
                for (Sensor sensor : available) {
                    if (sensor != null && SensorState.isSupportedType(sensor.getType())) {
                        int index = sensor.getType() - SensorState.FIRST_TYPE;
                        // One source per type, matching the OEM's 42-sensor mapping.
                        if (sensors[index] == null) {
                            sensors[index] = sensor;
                        }
                    }
                }
            }
        }
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public Sensor getSensor(int index) {
        return sensors[index];
    }

    public void start() {
        if (closed) {
            return;
        }
        // A resume inside the stopAfter() window keeps the registration it never lost.
        handler.removeCallbacks(stopTask);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (active || sensorManager == null) {
                    return;
                }
                active = true;
                for (int i = 0; i < sensors.length; i++) {
                    Sensor sensor = sensors[i];
                    if (sensor == null) {
                        continue;
                    }
                    boolean registered = false;
                    try {
                        registered = sensorManager.registerListener(
                                SensorCapture.this, sensor, SENSOR_PERIOD_US, handler);
                    } catch (SecurityException e) {
                        Log.w("Q50SensorCapture", "Read permission denied for type "
                                + sensor.getType(), e);
                    } catch (IllegalArgumentException e) {
                        Log.w("Q50SensorCapture", "Cannot register type " + sensor.getType(), e);
                    }
                    state.setRegistration(sensor.getType(), registered);
                }
            }
        });
    }

    public void stop() {
        stopAfter(0L);
    }

    /**
     * Unregisters after delayMs unless start() comes first. A short system pause then never
     * leaves the HAL without listeners, where its poll loop sleeps a full second per pass.
     */
    public void stopAfter(long delayMs) {
        if (closed) {
            return;
        }
        handler.removeCallbacks(stopTask);
        handler.postDelayed(stopTask, delayMs);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacks(stopTask);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    stopOnCaptureThread();
                } finally {
                    // quitSafely() is newer than API 10; do not block the UI with join().
                    thread.quit();
                }
            }
        });
    }

    private void stopOnCaptureThread() {
        if (!active) {
            return;
        }
        active = false;
        sensorManager.unregisterListener(this);
        state.clearRegistrations();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active || event == null || event.sensor == null
                || event.values == null || event.values.length == 0) {
            return;
        }
        // No formatting, UI work, allocations or per-event messages.
        state.record(event.sensor.getType(), event.values[0], event.timestamp,
                SystemClock.elapsedRealtime());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
