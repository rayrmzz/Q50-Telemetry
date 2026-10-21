package com.q50.dashboard;

import com.q50.core.SensorState;

/**
 * Dependency-free JVM checks. Run from the project root:
 * javac -d build/dash-tests core/src/main/java/com/q50/core/SensorState.java dashboard/src/main/java/com/q50/dashboard/DashModel.java dashboard/src/main/java/com/q50/dashboard/NumberText.java validation/DashModelTest.java
 * java -cp build/dash-tests com.q50.dashboard.DashModelTest
 */
public final class DashModelTest {
    private static final long NOW = 10000L;

    public static void main(String[] args) {
        freshnessAndInvalidValues();
        rpmPriorityAndLights();
        directSignalsAndGear();
        temperatureBoundaries();
        tiresAndRange();
        gAndPeaks();
        System.out.println("PASS: DashModel freshness, RPM priority, shift thresholds, gear, "
                + "pedals, temperatures, power, tires, range, G and peak reset.");
    }

    private static void freshnessAndInvalidValues() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        m.update(s, null, NOW);
        check(!m.rpmValid && !m.speedValid && !m.gearValid && !m.gValid
                && !m.accValid && !m.brkValid && !m.coolantValid && !m.oilValid
                && !m.torqueValid && !m.powerValid && !m.rangeValid, "No events means no data");
        for (int type = 12; type <= 39; type++) put(s, type, 70f, NOW);
        m.update(s, null, NOW + DashModel.STALE_MS);
        check(m.rpmValid && m.speedValid && m.gearValid && m.accValid && m.brkValid
                && m.coolantValid && m.oilValid && m.torqueValid && m.powerValid
                && m.rangeValid && m.gValid && m.tireValid[0] && m.tireWarn[0],
                "All outputs must accept the inclusive freshness boundary");
        m.update(s, null, NOW + DashModel.STALE_MS + 1L);
        check(!m.rpmValid && !m.speedValid && !m.gearValid && !m.accValid && !m.brkValid
                && !m.coolantValid && !m.oilValid && !m.torqueValid && !m.powerValid
                && !m.rangeValid && !m.gValid && !m.tireValid[0] && !m.tireWarn[0]
                && !m.rangeWarn && m.shiftLights == 0, "Expired readings and warnings must clear");
        put(s, 17, Float.NaN, NOW);
        put(s, 14, Float.POSITIVE_INFINITY, NOW);
        put(s, 15, Float.NEGATIVE_INFINITY, NOW);
        put(s, 23, 50f, NOW + 1L);
        m.update(s, null, NOW);
        check(!m.speedValid && !m.coolantValid && !m.oilValid && !m.accValid,
                "Non-finite values and future timestamps must not be displayed");
        m.update(null, null, NOW);
        check(!m.speedValid && !m.rpmValid, "Missing snapshot array is no data");
    }

    private static void rpmPriorityAndLights() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        DashModel m = new DashModel();
        d.hasValue = true; d.value = 850f; d.receivedElapsedRealtimeMs = NOW;
        put(s, 13, 900f, NOW);
        m.update(s, d, NOW);
        check(m.rpmValid && !m.rpmEstimated && m.rpm == 900f, "Positive real RPM has priority");
        put(s, 13, 0f, NOW);
        m.update(s, d, NOW + DashModel.EST_FRESH_MS);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 850f,
                "Estimate accepts its inclusive freshness limit over zero real RPM");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS + 1L);
        check(m.rpmValid && !m.rpmEstimated && m.rpm == 0f, "Expired estimate falls back to fresh zero");
        put(s, 13, -1f, NOW);
        m.update(s, null, NOW);
        check(m.rpmValid && m.rpm == 0f && !m.rpmEstimated, "Non-positive real fallback follows contract");
        put(s, 13, Float.NaN, NOW);
        m.update(s, d, NOW);
        check(m.rpmValid && m.rpmEstimated, "Estimate can replace an invalid real reading");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS + 1L);
        check(!m.rpmValid && !m.rpmEstimated, "Without either fresh source RPM is invalid");
        d.value = Float.NaN; m.update(s, d, NOW);
        check(!m.rpmValid, "NaN estimate must not leak to the display");
        d.value = Float.POSITIVE_INFINITY; m.update(s, d, NOW);
        check(!m.rpmValid, "Infinite estimate must not leak to the display");
        d.value = -1f; m.update(s, d, NOW);
        check(!m.rpmValid, "Negative estimate must be rejected");
        d.value = SensorState.MAX_RPM + 1f; m.update(s, d, NOW);
        check(!m.rpmValid, "Out-of-range estimate must be rejected");
        d.value = SensorState.MAX_RPM; d.receivedElapsedRealtimeMs = NOW + 1L;
        m.update(s, d, NOW);
        check(!m.rpmValid, "Future estimate timestamp must be rejected");
        d.receivedElapsedRealtimeMs = NOW; m.update(s, d, NOW);
        check(m.rpmValid && m.rpmEstimated && m.shiftLights == 5, "Upper valid estimate lights all shifts");
        d.value = 0f; m.update(s, d, NOW);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 0f, "Finite zero estimate follows the contract");
        for (int i = 0; i < DashModel.SHIFT_RPM.length; i++) {
            put(s, 13, DashModel.SHIFT_RPM[i] - 0.125f, NOW); m.update(s, null, NOW);
            check(m.shiftLights == i, "Shift light must stay off immediately below its threshold");
            put(s, 13, DashModel.SHIFT_RPM[i], NOW); m.update(s, null, NOW);
            check(m.shiftLights == i + 1, "Shift thresholds must be inclusive");
        }
    }

    private static void directSignalsAndGear() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        put(s, 17, 123.5f, NOW); put(s, 12, -17f, NOW);
        put(s, 32, 1000000f, NOW); put(s, 23, -1f, NOW); put(s, 24, 120f, NOW);
        m.update(s, null, NOW);
        check(m.speedValid && m.speedKmh == 123.5f && m.torqueValid && m.torqueNm == -17f,
                "Speed and signed torque are direct");
        near(m.powerKw, 104.7f, 0.0001f, "Power conversion is raw times 1.047e-4");
        check(m.accPercent == 0f && m.brkPercent == 100f, "Pedals clamp both endpoints");
        put(s, 23, 39.25f, NOW); put(s, 24, 68f, NOW); m.update(s, null, NOW);
        check(m.accPercent == 39.25f && m.brkPercent == 68f, "Pedals retain values within range");
        gear(s, m, 1f, "P"); gear(s, m, 2f, "R"); gear(s, m, 3f, "N"); gear(s, m, 4f, "D");
        gear(s, m, 16f, "M1"); gear(s, m, 22f, "M7"); gear(s, m, 15.6f, "M1");
        gear(s, m, 0f, "0"); gear(s, m, -12f, "-12"); gear(s, m, -1.5f, "-1");
        gear(s, m, 9999f, "9999");
        put(s, 22, -1000f, NOW); m.update(s, null, NOW);
        check(!m.gearValid && "--".equals(new String(m.gearText, 0, m.gearLength)),
                "Oversized unknown gear must not be truncated or overflow its buffer");
    }

    private static void temperatureBoundaries() {
        SensorState.Snapshot[] s = SensorState.newSnapshots(); DashModel m = new DashModel();
        temperature(s, m, 14, 59.99f, DashModel.LEVEL_COLD);
        temperature(s, m, 14, 60f, DashModel.LEVEL_NORMAL);
        temperature(s, m, 14, 105f, DashModel.LEVEL_NORMAL);
        temperature(s, m, 14, 105.01f, DashModel.LEVEL_WARN);
        temperature(s, m, 14, 115f, DashModel.LEVEL_WARN);
        temperature(s, m, 14, 115.01f, DashModel.LEVEL_DANGER);
        temperature(s, m, 15, 59.99f, DashModel.LEVEL_COLD);
        temperature(s, m, 15, 60f, DashModel.LEVEL_NORMAL);
        temperature(s, m, 15, 125f, DashModel.LEVEL_NORMAL);
        temperature(s, m, 15, 125.01f, DashModel.LEVEL_WARN);
        temperature(s, m, 15, 135f, DashModel.LEVEL_WARN);
        temperature(s, m, 15, 135.01f, DashModel.LEVEL_DANGER);
    }

    private static void tiresAndRange() {
        SensorState.Snapshot[] s = SensorState.newSnapshots(); DashModel m = new DashModel();
        put(s, 37, 29.75f, NOW); put(s, 36, 30f, NOW);
        put(s, 39, 42f, NOW); put(s, 38, 42.25f, NOW); m.update(s, null, NOW);
        check(m.tirePsi[0] == 29.75f && m.tirePsi[1] == 30f && m.tirePsi[2] == 42f
                && m.tirePsi[3] == 42.25f, "Tires must be FL37 FR36 RL39 RR38");
        check(m.tireWarn[0] && !m.tireWarn[1] && !m.tireWarn[2] && m.tireWarn[3],
                "Tire warnings must exclude exactly 30 and 42 psi");
        // Exercise every wheel so validity cannot depend on its mapped position.
        int[] tireTypes = {37, 36, 39, 38};
        float[] values = {0f, 0.99f, 1f, 29.9f, 42.1f, -1f,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < tireTypes.length; i++) {
            for (int j = 0; j < values.length; j++) {
                float value = values[j];
                put(s, tireTypes[i], value, NOW);
                m.update(s, null, NOW);
                boolean valid = j >= 2 && j <= 4;
                check(m.tireValid[i] == valid, "TPMS minimum 1 psi and finite values required");
                check(m.tirePsi[i] == (valid ? value : 0f), "Invalid TPMS must clear its value");
                check(m.tireWarn[i] == valid, "Only these valid low/high samples warn");
            }
            put(s, tireTypes[i], 29.9f, NOW);
            m.update(s, null, NOW + DashModel.STALE_MS + 1L);
            check(!m.tireValid[i] && m.tirePsi[i] == 0f && !m.tireWarn[i],
                    "Stale TPMS must clear validity, value and warning");
        }
        range(s, m, 49.99f, false, false); range(s, m, 50f, true, true);
        range(s, m, 79.99f, true, true); range(s, m, 80f, true, false);
        range(s, m, 900f, true, false); range(s, m, 900.01f, false, false);
        range(s, m, Float.NaN, false, false);
    }

    private static void gAndPeaks() {
        SensorState.Snapshot[] s = SensorState.newSnapshots(); DashModel m = new DashModel();
        put(s, 20, -0.3f, NOW); put(s, 21, 0.4f, NOW);
        put(s, 13, 1000f, NOW); put(s, 32, -1000000f, NOW); m.update(s, null, NOW);
        near(m.gTotal, 0.5f, 0.00001f, "G magnitude combines both signed axes");
        check(m.gLat == -0.3f && m.gLon == 0.4f, "G axes retain signs");
        near(m.powerPeak, -104.7f, 0.0001f, "First valid negative power is a valid maximum");
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        d.hasValue = true; d.value = 2000f; d.receivedElapsedRealtimeMs = NOW;
        put(s, 13, 0f, NOW); put(s, 32, 2000000f, NOW); m.update(s, d, NOW);
        check(m.rpmPeak == 2000f, "Estimated RPM contributes to peak");
        float peakPower = m.powerPeak;
        put(s, 13, 900f, NOW); put(s, 32, 1000000f, NOW); put(s, 20, 0f, NOW);
        m.update(s, null, NOW);
        check(m.rpmPeak == 2000f && m.powerPeak == peakPower && m.gPeak >= 0.5f,
                "Lower samples cannot reduce peaks");
        m.update(s, null, NOW + DashModel.STALE_MS + 1L);
        check(!m.gValid && m.gPeak >= 0.5f && m.rpmPeak == 2000f, "Staleness preserves peaks");
        m.resetPeaks();
        check(m.rpmPeak == 0f && m.powerPeak == 0f && m.gPeak == 0f, "Reset clears all peaks");
        m.update(s, null, NOW);
        check(m.rpmPeak == 900f && m.powerPeak > 0f, "Peaks restart with next valid sample");
        s[21 - SensorState.FIRST_TYPE].eventCount = 0L;
        m.update(s, null, NOW);
        check(!m.gValid, "One missing G axis invalidates the combined vector");
        put(s, 21, Float.NaN, NOW); m.update(s, null, NOW);
        check(!m.gValid, "NaN G axis invalidates vector");
        put(s, 20, Float.MAX_VALUE, NOW); put(s, 21, Float.MAX_VALUE, NOW);
        m.update(s, null, NOW);
        check(!m.gValid, "Unrepresentable vector magnitude must not leak infinity");
    }

    private static void put(SensorState.Snapshot[] s, int type, float value, long received) {
        SensorState.Snapshot v = s[type - SensorState.FIRST_TYPE];
        v.value = value; v.eventCount = 1L; v.lastReceivedElapsedRealtimeMs = received;
    }
    private static void gear(SensorState.Snapshot[] s, DashModel m, float value, String expected) {
        put(s, 22, value, NOW); m.update(s, null, NOW);
        check(m.gearValid && expected.equals(new String(m.gearText, 0, m.gearLength)), "Gear mapping mismatch");
    }
    private static void temperature(SensorState.Snapshot[] s, DashModel m, int type, float value, int level) {
        put(s, type, value, NOW); m.update(s, null, NOW);
        check(type == 14 ? m.coolantValid && m.coolantC == value && m.coolantLevel == level
                : m.oilValid && m.oilC == value && m.oilLevel == level, "Temperature threshold mismatch");
    }
    private static void range(SensorState.Snapshot[] s, DashModel m, float value, boolean valid, boolean warn) {
        put(s, 18, value, NOW); m.update(s, null, NOW);
        check(m.rangeValid == valid && m.rangeWarn == warn, "Range filter or warning mismatch");
    }
    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
