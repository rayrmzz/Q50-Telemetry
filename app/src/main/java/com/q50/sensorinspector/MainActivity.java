package com.q50.sensorinspector;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import com.q50.core.SensorCapture;
import com.q50.core.SensorState;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final long UI_REFRESH_INTERVAL_MS = 250L;
    private static final int RPM_INDEX = SensorState.RPM_TYPE - SensorState.FIRST_TYPE;
    private static final int TORQUE_INDEX = SensorState.TORQUE_TYPE - SensorState.FIRST_TYPE;
    private static final int POWER_INDEX = SensorState.POWER_TYPE - SensorState.FIRST_TYPE;

    private SensorCapture sensorCapture;
    private TextView outputView;
    private final SensorState sensorState = new SensorState();
    private final SensorState.Snapshot[] snapshots = SensorState.newSnapshots();
    private final SensorState.DerivedRpm derivedRpm = new SensorState.DerivedRpm();
    private final String[] sensorDetails = new String[SensorState.TYPE_COUNT];
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder displayText = new StringBuilder(12288);
    private final DecimalFormat valueFormat =
            new DecimalFormat("0.000", new DecimalFormatSymbols(Locale.US));
    private long renderedVersion = -1L;
    private boolean resumed;
    private String sensorStatus;

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refreshDisplay();
            // One pending render at most; event delivery never posts UI work.
            uiHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorCapture = new SensorCapture(sensorManager, sensorState);
        prepareSensorDetails(sensorManager);
        refreshDisplay();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);

        outputView = new TextView(this);
        outputView.setTextSize(16.0f);
        outputView.setGravity(Gravity.LEFT);
        outputView.setPadding(18, 12, 18, 12);

        scrollView.addView(outputView);

        setContentView(scrollView);
    }

    private void prepareSensorDetails(SensorManager sensorManager) {
        if (sensorManager == null) {
            sensorStatus = "SensorManager unavailable\n";
            return;
        }

        int sensorCount = 0;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < SensorState.TYPE_COUNT; i++) {
            Sensor sensor = sensorCapture.getSensor(i);
            if (sensor != null) {
                sensorCount++;
                // Metadata does not change with telemetry; format it only once.
                sensorDetails[i] = "    OEM: " + sensor.getName() + " / "
                        + sensor.getVendor() + "\n";
            } else {
                missing.append(missing.length() == 0 ? "" : ", ")
                        .append(SensorState.FIRST_TYPE + i).append(' ')
                        .append(getSignalName(SensorState.FIRST_TYPE + i));
            }
        }
        sensorStatus = sensorCount == 0 ? "No OEM sensors (types 12..53) found\n"
                : "OEM sensors: " + sensorCount + " | UI: max 4 Hz\n"
                        + "Missing types: " + (missing.length() == 0 ? "none" : missing)
                        + "\n\n";
    }

    @Override
    protected void onResume() {
        super.onResume();

        resumed = true;
        sensorCapture.start();
        uiHandler.removeCallbacks(renderTask);
        uiHandler.post(renderTask);
    }

    @Override
    protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(renderTask);
        sensorCapture.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        uiHandler.removeCallbacks(renderTask);
        sensorCapture.close();
        super.onDestroy();
    }

    private void refreshDisplay() {
        long version = sensorState.copyInto(snapshots, derivedRpm, renderedVersion);
        SensorState.Snapshot rpm = snapshots[RPM_INDEX];
        // RPM ages must keep advancing even when no new events arrive.
        if (version == renderedVersion && !rpm.hasNonZeroValue && !derivedRpm.hasValue) {
            return;
        }
        renderedVersion = version;
        StringBuilder builder = displayText;
        builder.setLength(0);

        builder.append("Q50 SENSOR INSPECTOR\n");
        builder.append("====================\n\n");
        builder.append(sensorStatus);
        long now = SystemClock.elapsedRealtime();
        appendRpmDiagnostics(builder, rpm, now);
        appendDerivedRpm(builder, derivedRpm, now);

        for (int i = 0; i < SensorState.TYPE_COUNT; i++) {
            if (sensorDetails[i] == null) {
                continue;
            }
            int type = SensorState.FIRST_TYPE + i;
            SensorState.Snapshot snapshot = snapshots[i];
            builder.append(type).append("  ").append(getSignalName(type));
            builder.append("  Current: ");
            if (snapshot.eventCount == 0L) {
                builder.append("--");
            } else {
                builder.append(valueFormat.format(snapshot.value));
            }
            builder.append('\n');
            builder.append("    Min: ");
            if (snapshot.hasFiniteValue) {
                builder.append(valueFormat.format(snapshot.minValue));
                builder.append("  Max: ").append(valueFormat.format(snapshot.maxValue));
            } else {
                builder.append("--  Max: --");
            }
            builder.append('\n');
            builder.append("    Events: ").append(snapshot.eventCount);
            builder.append("  Changes: ").append(snapshot.changeCount);
            if (!snapshot.registered) {
                builder.append(snapshot.registrationAttempted
                        ? "  [registration failed]" : "  [waiting for registration]");
            }
            builder.append('\n').append(sensorDetails[i]).append('\n');
        }

        outputView.setText(builder.toString());
    }

    private void appendRpmDiagnostics(StringBuilder builder, SensorState.Snapshot rpm,
            long nowElapsedRealtimeMs) {
        builder.append("ENGINE RPM (type 13) - DIAGNOSTICS\n");
        if (sensorDetails[RPM_INDEX] == null) {
            builder.append("    Sensor unavailable\n\n");
            return;
        }
        builder.append("    Current: ");
        builder.append(rpm.eventCount == 0L ? "--" : valueFormat.format(rpm.value));
        builder.append("  Last non-zero: ");
        if (rpm.hasNonZeroValue) {
            builder.append(valueFormat.format(rpm.lastNonZeroValue));
            builder.append("  Age: ");
            builder.append(valueFormat.format(rpm.nonZeroAgeMs(nowElapsedRealtimeMs) / 1000.0));
            builder.append(" s");
        } else {
            builder.append("--  Age: --");
        }
        builder.append("\n    Zero events: ").append(rpm.zeroEventCount);
        builder.append("  Non-zero events: ").append(rpm.nonZeroEventCount);
        builder.append("\n    0 -> non-zero: ").append(rpm.zeroToNonZeroCount);
        builder.append("  Non-zero -> 0: ").append(rpm.nonZeroToZeroCount);
        builder.append("\n\n");
    }

    private void appendDerivedRpm(StringBuilder builder, SensorState.DerivedRpm derived,
            long nowElapsedRealtimeMs) {
        builder.append("DERIVED RPM = power (32) / torque (12) - EXPERIMENTAL\n");
        if (sensorDetails[TORQUE_INDEX] == null || sensorDetails[POWER_INDEX] == null) {
            builder.append("    Torque or power sensor unavailable\n\n");
            return;
        }
        builder.append("    Estimate; poll pairing by arrival order is unconfirmed\n");
        // The value is held after rejected pairs, so it is never shown as current.
        builder.append("    Last valid: ");
        if (derived.hasValue) {
            builder.append(valueFormat.format(derived.value));
            builder.append("  Age: ");
            builder.append(valueFormat.format(derived.ageMs(nowElapsedRealtimeMs) / 1000.0));
            builder.append(" s\n    Min: ").append(valueFormat.format(derived.minValue));
            builder.append("  Max: ").append(valueFormat.format(derived.maxValue));
        } else {
            builder.append("--  Age: --\n    Min: --  Max: --");
        }
        builder.append("\n    Valid: ").append(derived.validCount);
        builder.append("  Torque 0: ").append(derived.zeroTorqueCount);
        builder.append("  Invalid: ").append(derived.invalidCount);
        builder.append("\n    Unpaired: ").append(derived.unpairedCount);
        builder.append("  Out of order: ").append(derived.outOfOrderCount);
        builder.append("\n    Type 13 = 0 while derived > 0: ").append(derived.reportedZeroCount);
        builder.append("\n    Both > 0: ").append(derived.agreementCount);
        builder.append("  Max |derived - type 13|: ");
        builder.append(derived.agreementCount == 0L ? "--"
                : valueFormat.format(derived.maxAbsDifference));
        builder.append("\n\n");
    }

    private String getSignalName(int type) {
        switch (type) {
            case 12:
                return "Effective Torque";
            case 13:
                return "Engine RPM";
            case 14:
                return "Coolant Temperature";
            case 15:
                return "Engine Oil Temperature";
            case 16:
                return "Engine Oil Pressure";
            case 17:
                return "Vehicle Speed";
            case 18:
                return "Distance To Empty";
            case 19:
                return "Fuel Consumption Fine";
            case 20:
                return "Transversal Acceleration";
            case 21:
                return "Longitudinal Acceleration";
            case 22:
                return "Gear Position";
            case 23:
                return "Accelerator Pedal";
            case 24:
                return "Brake Pedal";
            case 25:
                return "Steering Angle";
            case 26:
                return "Regeneration";
            case 27:
                return "Illumination";
            case 28:
                return "Eco Mode";
            case 29:
                return "Fuel Consumption History";
            case 30:
                return "Average Fuel Consumption";
            case 31:
                return "Moment Fuel Consumption";
            case 32:
                return "Engine Power";
            case 33:
                return "Display Style";
            case 34:
                return "Flat Tire";
            case 35:
                return "Low Tire Pressure";
            case 36:
                return "Tire Pressure FR";
            case 37:
                return "Tire Pressure FL";
            case 38:
                return "Tire Pressure RR";
            case 39:
                return "Tire Pressure RL";
            case 40:
                return "Transmitter Status";
            case 41:
                return "Setting Pressure Front";
            case 42:
                return "Setting Pressure Rear";
            case 43:
                return "Distance Totalizer";
            case 44:
                return "Fuel Low";
            case 45:
                return "Hand Brake";
            case 46:
                return "Select Pressure Support";
            case 47:
                return "Reset TPMS";
            case 48:
                return "ICC Existence";
            case 49:
                return "Market";
            case 50:
                return "Door Switch";
            case 51:
                return "Flash Indicator";
            case 52:
                return "DGA Generation";
            case 53:
                return "IOP Illumination";
            default:
                return "Sensor " + type;
        }
    }
}
