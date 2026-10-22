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
        movingRpmHold();
        resumeGrace();
        graceRejectsInvalidData();
        directSignalsAndGear();
        temperatureBoundaries();
        tiresAndRange();
        gAndPeaks();
        System.out.println("PASS: DashModel freshness, moving RPM hold, resume grace, held flag, "
                + "RPM priority, shift thresholds, gear, pedals, temperatures, power, "
                + "tires, range, G and peak reset.");
    }

    private static void freshnessAndInvalidValues() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        m.update(s, null, NOW, 0L);
        check(!m.rpmValid && !m.speedValid && !m.gearValid && !m.gValid
                && !m.accValid && !m.brkValid && !m.coolantValid && !m.oilValid
                && !m.torqueValid && !m.powerValid && !m.rangeValid, "No events means no data");
        for (int type = 12; type <= 39; type++) put(s, type, 70f, NOW);
        m.update(s, null, NOW + DashModel.STALE_MS, 0L);
        check(m.rpmValid && m.speedValid && m.gearValid && m.accValid && m.brkValid
                && m.coolantValid && m.oilValid && m.torqueValid && m.powerValid
                && m.rangeValid && m.gValid && m.tireValid[0] && m.tireWarn[0],
                "All outputs must accept the inclusive freshness boundary");
        m.update(s, null, NOW + DashModel.STALE_MS + 1L, 0L);
        check(!m.rpmValid && !m.speedValid && !m.gearValid && !m.accValid && !m.brkValid
                && !m.coolantValid && !m.oilValid && !m.torqueValid && !m.powerValid
                && !m.rangeValid && !m.gValid && !m.tireValid[0] && !m.tireWarn[0]
                && !m.rangeWarn && m.shiftLights == 0, "Expired readings and warnings must clear");
        put(s, 17, Float.NaN, NOW);
        put(s, 14, Float.POSITIVE_INFINITY, NOW);
        put(s, 15, Float.NEGATIVE_INFINITY, NOW);
        put(s, 23, 50f, NOW + 1L);
        m.update(s, null, NOW, 0L);
        check(!m.speedValid && !m.coolantValid && !m.oilValid && !m.accValid,
                "Non-finite values and future timestamps must not be displayed");
        m.update(null, null, NOW, 0L);
        check(!m.speedValid && !m.rpmValid, "Missing snapshot array is no data");
    }

    private static void rpmPriorityAndLights() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        DashModel m = new DashModel();
        d.hasValue = true; d.value = 850f; d.receivedElapsedRealtimeMs = NOW;
        put(s, 13, 900f, NOW);
        m.update(s, d, NOW, 0L);
        check(m.rpmValid && !m.rpmEstimated && m.rpm == 900f, "Positive real RPM has priority");
        put(s, 13, 0f, NOW);
        m.update(s, d, NOW + DashModel.EST_FRESH_MS, 0L);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 850f,
                "Estimate accepts its inclusive freshness limit over zero real RPM");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS + 1L, 0L);
        check(m.rpmValid && !m.rpmEstimated && m.rpm == 0f, "Expired estimate falls back to fresh zero");
        put(s, 13, -1f, NOW);
        m.update(s, null, NOW, 0L);
        check(m.rpmValid && m.rpm == 0f && !m.rpmEstimated, "Non-positive real fallback follows contract");
        put(s, 13, Float.NaN, NOW);
        m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpmEstimated, "Estimate can replace an invalid real reading");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS + 1L, 0L);
        check(!m.rpmValid && !m.rpmEstimated, "Without either fresh source RPM is invalid");
        d.value = Float.NaN; m.update(s, d, NOW, 0L);
        check(!m.rpmValid, "NaN estimate must not leak to the display");
        d.value = Float.POSITIVE_INFINITY; m.update(s, d, NOW, 0L);
        check(!m.rpmValid, "Infinite estimate must not leak to the display");
        d.value = -1f; m.update(s, d, NOW, 0L);
        check(!m.rpmValid, "Negative estimate must be rejected");
        d.value = SensorState.MAX_RPM + 1f; m.update(s, d, NOW, 0L);
        check(!m.rpmValid, "Out-of-range estimate must be rejected");
        d.value = SensorState.MAX_RPM; d.receivedElapsedRealtimeMs = NOW + 1L;
        m.update(s, d, NOW, 0L);
        check(!m.rpmValid, "Future estimate timestamp must be rejected");
        d.receivedElapsedRealtimeMs = NOW; m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpmEstimated && m.shiftLights == 5, "Upper valid estimate lights all shifts");
        d.value = 0f; m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 0f, "Finite zero estimate follows the contract");
        for (int i = 0; i < DashModel.SHIFT_RPM.length; i++) {
            put(s, 13, DashModel.SHIFT_RPM[i] - 0.125f, NOW); m.update(s, null, NOW, 0L);
            check(m.shiftLights == i, "Shift light must stay off immediately below its threshold");
            put(s, 13, DashModel.SHIFT_RPM[i], NOW); m.update(s, null, NOW, 0L);
            check(m.shiftLights == i + 1, "Shift thresholds must be inclusive");
        }
    }

    private static void movingRpmHold() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        DashModel m = new DashModel();
        d.hasValue = true; d.value = 720f;
        d.receivedElapsedRealtimeMs = NOW - DashModel.EST_FRESH_MS - 1L;
        put(s, 13, 0f, NOW); put(s, 17, 2.01f, NOW);
        m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 720f && !m.held,
                "Fresh motion over 2 km/h keeps the last estimate over fresh zero RPM");
        check(m.rpmPeak == 0f, "A held estimate cannot create a peak");
        d.value = 900f;
        m.update(s, d, NOW, 0L);
        check(m.rpm == 900f && m.rpmPeak == 0f && !m.held,
                "A higher moving estimate cannot raise the peak or dim all data");
        put(s, 13, -1f, NOW);
        m.update(s, d, NOW, 0L);
        check(m.rpmEstimated && m.rpm == 900f, "Fresh negative RPM follows moving hold too");
        put(s, 17, 2f, NOW); m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpm == 0f && !m.rpmEstimated && !m.held,
                "Exactly 2 km/h does not count as moving hold");
        put(s, 17, 0f, NOW); m.update(s, d, NOW, 0L);
        check(m.rpmValid && m.rpm == 0f && !m.rpmEstimated && !m.held,
                "Stopped car retains the engine-off fallback");
        put(s, 17, 10f, NOW - DashModel.STALE_MS - 1L);
        m.update(s, d, NOW, 0L);
        check(m.rpm == 0f && !m.rpmEstimated && !m.held,
                "Expired speed cannot justify an indefinite RPM hold");
        s[17 - SensorState.FIRST_TYPE].eventCount = 0L;
        m.update(s, d, NOW, 0L);
        check(m.rpm == 0f && !m.rpmEstimated, "Missing speed cannot justify moving hold");
        put(s, 17, Float.NaN, NOW); m.update(s, d, NOW, 0L);
        check(m.rpm == 0f && !m.rpmEstimated, "Invalid speed cannot justify moving hold");
        put(s, 17, 10f, NOW);
        m.update(s, null, NOW, 0L);
        check(m.rpmValid && m.rpm == 0f && !m.rpmEstimated && !m.held,
                "Motion must never invent an estimate");
        d.hasValue = false; m.update(s, d, NOW, 0L);
        check(!m.rpmEstimated && !m.held, "Absent estimate is not held");
        d.hasValue = true;
        float[] invalid = {Float.NaN, Float.POSITIVE_INFINITY, -1f, SensorState.MAX_RPM + 1f};
        for (int i = 0; i < invalid.length; i++) {
            d.value = invalid[i]; m.update(s, d, NOW, 0L);
            check(m.rpm == 0f && !m.rpmEstimated && !m.held, "Moving hold rejects invalid RPM");
        }
        d.value = 720f; d.receivedElapsedRealtimeMs = NOW + 1L;
        m.update(s, d, NOW, 0L);
        check(!m.rpmEstimated && !m.held, "Moving hold rejects a future estimate");
        d.receivedElapsedRealtimeMs = -1L; m.update(s, d, NOW, 0L);
        check(!m.rpmEstimated, "Moving hold rejects a negative receipt time");
        d.receivedElapsedRealtimeMs = 0L;
        put(s, 13, 750f, NOW); m.update(s, d, NOW, 0L);
        check(m.rpm == 750f && !m.rpmEstimated && !m.held && m.rpmPeak == 750f,
                "Fresh positive real RPM replaces the hold and updates the peak");
        put(s, 13, 0f, NOW); d.value = 900f; m.update(s, d, NOW, 0L);
        check(!m.held && m.rpmPeak == 750f, "Moving RPM preserves the peak without dimming all data");
        m.resetPeaks(); m.update(s, d, NOW, 0L);
        check(m.rpmPeak == 0f && !m.held, "Reset during moving hold does not restore an old peak");
        d.receivedElapsedRealtimeMs = NOW; m.update(s, d, NOW, 0L);
        check(!m.held && m.rpmPeak == 900f, "A new estimate clears held and can update the peak");
        d.receivedElapsedRealtimeMs = 0L;
        m.update(s, d, NOW, NOW);
        check(m.rpmEstimated && m.rpm == 900f && !m.held && m.rpmPeak == 900f,
                "At grace expiry moving hold continues without dimming or changing the peak");
        put(s, 13, 0f, NOW - DashModel.STALE_MS - 1L);
        m.update(s, d, NOW, 0L);
        check(!m.rpmValid && !m.held, "Stale real RPM cannot justify moving hold");
    }

    private static void resumeGrace() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        long deadline = NOW + 3000L;
        int[] types = {12, 13, 14, 15, 17, 18, 20, 21, 22, 23, 24, 36, 37, 38, 39};
        float[] values = {10f, 700f, 80f, 75f, 30f, 100f, 0.3f, -0.4f,
                4f, 20f, 0f, 32f, 33f, 34f, 35f};
        for (int i = 0; i < types.length; i++) {
            if (types[i] == 20 || types[i] == 21) {
                put(s, types[i] == 20 ? 21 : 20, 0f, NOW);
            }
            put(s, types[i], values[i], NOW - DashModel.STALE_MS - 1L);
            m.update(s, null, NOW, deadline);
            check(m.held, "Every accepted stale displayed signal must mark held");
            s[types[i] - SensorState.FIRST_TYPE].eventCount = 0L;
            m.update(s, null, NOW, deadline);
            check(!m.held, "held must clear once that signal is absent");
            if (types[i] == 20 || types[i] == 21) {
                s[(types[i] == 20 ? 21 : 20) - SensorState.FIRST_TYPE].eventCount = 0L;
            }
        }
        put(s, 32, 1000000f, NOW - DashModel.STALE_MS - 1L);
        m.update(s, null, NOW, deadline);
        check(m.powerValid && m.held, "Power also uses the grace window");
        for (int i = 0; i < types.length; i++) put(s, types[i], values[i], 0L);
        m.update(s, null, deadline - 1L, deadline);
        check(m.held && m.rpmValid && m.speedValid && m.gearValid && m.accValid
                && m.brkValid && m.coolantValid && m.oilValid && m.torqueValid
                && m.powerValid && m.rangeValid && m.gValid && m.tireValid[0]
                && m.tireValid[1] && m.tireValid[2] && m.tireValid[3],
                "Grace remains active immediately before the deadline for all outputs");
        check(m.rpmPeak == 0f, "Real RPM accepted only by grace must not create a peak");
        m.update(s, null, deadline, deadline);
        check(!m.held && !m.rpmValid && !m.speedValid && !m.gearValid && !m.accValid
                && !m.brkValid && !m.coolantValid && !m.oilValid && !m.torqueValid
                && !m.powerValid && !m.rangeValid && !m.gValid && !m.tireValid[0]
                && !m.tireValid[1] && !m.tireValid[2] && !m.tireValid[3],
                "At the deadline normal freshness resumes for all outputs");
        for (int i = 0; i < types.length; i++) put(s, types[i], values[i], NOW);
        put(s, 32, 1000000f, NOW);
        m.update(s, null, NOW, deadline);
        check(!m.held && m.rpmPeak == 700f,
                "An active grace window alone does not mark fresh readings held");

        s = SensorState.newSnapshots();
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        d.hasValue = true; d.value = 850f; d.receivedElapsedRealtimeMs = 0L;
        m.resetPeaks(); m.update(s, d, NOW, deadline);
        check(m.rpmValid && m.rpmEstimated && m.rpm == 850f && m.held && m.rpmPeak == 0f,
                "Grace extends the estimate window without requiring a real RPM sample");
        m.update(s, d, deadline, deadline);
        check(!m.rpmValid && !m.held, "The held estimate expires with the grace window");
        d.receivedElapsedRealtimeMs = NOW;
        m.update(s, d, NOW, deadline);
        check(m.rpmValid && m.rpmEstimated && !m.held && m.rpmPeak == 850f,
                "Fresh estimate during grace is current data");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS, deadline);
        check(!m.held, "Estimate freshness boundary remains inclusive during grace");
        m.update(s, d, NOW + DashModel.EST_FRESH_MS + 1L, deadline);
        check(m.held, "Only an estimate older than its normal limit marks held");
    }

    private static void graceRejectsInvalidData() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        long deadline = NOW + 3000L;
        m.update(s, null, NOW, deadline);
        check(!m.held && !m.rpmValid && !m.speedValid, "Grace never invents initial data");
        put(s, 13, Float.NaN, 0L); put(s, 14, Float.POSITIVE_INFINITY, 0L);
        put(s, 15, Float.NEGATIVE_INFINITY, 0L); put(s, 17, 30f, NOW + 1L);
        put(s, 23, 20f, -1L); put(s, 18, 901f, 0L); put(s, 37, 0.99f, 0L);
        put(s, 22, -1000f, 0L); put(s, 20, 0.3f, 0L);
        m.update(s, null, NOW, deadline);
        check(!m.rpmValid && !m.speedValid && !m.coolantValid && !m.oilValid
                && !m.accValid && !m.rangeValid && !m.tireValid[0] && !m.gearValid
                && !m.gValid && !m.held, "Rejected or undisplayed data cannot mark held");
        put(s, 20, Float.MAX_VALUE, 0L); put(s, 21, Float.MAX_VALUE, 0L);
        m.update(s, null, NOW, deadline);
        check(!m.gValid && !m.held, "Unrepresentable G vector is rejected even in grace");
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        d.hasValue = true; d.value = 800f; d.receivedElapsedRealtimeMs = NOW + 1L;
        m.update(s, d, NOW, deadline);
        check(!m.rpmValid && !m.held, "Grace rejects a future derived timestamp");
        d.receivedElapsedRealtimeMs = -1L; m.update(s, d, NOW, deadline);
        check(!m.rpmValid && !m.held, "Grace rejects a negative derived timestamp");
        d.receivedElapsedRealtimeMs = 0L; d.value = Float.NaN;
        m.update(s, d, NOW, deadline);
        check(!m.rpmValid && !m.held, "Grace rejects a non-finite estimate");
        d.value = SensorState.MAX_RPM + 1f; m.update(s, d, NOW, deadline);
        check(!m.rpmValid && !m.held, "Grace rejects an out-of-range estimate");
    }

    private static void directSignalsAndGear() {
        SensorState.Snapshot[] s = SensorState.newSnapshots();
        DashModel m = new DashModel();
        put(s, 17, 123.5f, NOW); put(s, 12, -17f, NOW);
        put(s, 32, 1000000f, NOW); put(s, 23, -1f, NOW); put(s, 24, 120f, NOW);
        m.update(s, null, NOW, 0L);
        check(m.speedValid && m.speedKmh == 123.5f && m.torqueValid && m.torqueNm == -17f,
                "Speed and signed torque are direct");
        near(m.powerKw, 104.7f, 0.0001f, "Power conversion is raw times 1.047e-4");
        check(m.accPercent == 0f && m.brkPercent == 100f, "Pedals clamp both endpoints");
        put(s, 23, 39.25f, NOW); put(s, 24, 68f, NOW); m.update(s, null, NOW, 0L);
        check(m.accPercent == 39.25f && m.brkPercent == 68f, "Pedals retain values within range");
        gear(s, m, 1f, "P"); gear(s, m, 2f, "R"); gear(s, m, 3f, "N"); gear(s, m, 4f, "D");
        gear(s, m, 16f, "M1"); gear(s, m, 22f, "M7"); gear(s, m, 15.6f, "M1");
        gear(s, m, 0f, "0"); gear(s, m, -12f, "-12"); gear(s, m, -1.5f, "-1");
        gear(s, m, 9999f, "9999");
        put(s, 22, -1000f, NOW); m.update(s, null, NOW, 0L);
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
        put(s, 39, 42f, NOW); put(s, 38, 42.25f, NOW); m.update(s, null, NOW, 0L);
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
                m.update(s, null, NOW, 0L);
                boolean valid = j >= 2 && j <= 4;
                check(m.tireValid[i] == valid, "TPMS minimum 1 psi and finite values required");
                check(m.tirePsi[i] == (valid ? value : 0f), "Invalid TPMS must clear its value");
                check(m.tireWarn[i] == valid, "Only these valid low/high samples warn");
            }
            put(s, tireTypes[i], 29.9f, NOW);
            m.update(s, null, NOW + DashModel.STALE_MS + 1L, 0L);
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
        put(s, 13, 1000f, NOW); put(s, 32, -1000000f, NOW); m.update(s, null, NOW, 0L);
        near(m.gTotal, 0.5f, 0.00001f, "G magnitude combines both signed axes");
        check(m.gLat == -0.3f && m.gLon == 0.4f, "G axes retain signs");
        near(m.powerPeak, -104.7f, 0.0001f, "First valid negative power is a valid maximum");
        SensorState.DerivedRpm d = new SensorState.DerivedRpm();
        d.hasValue = true; d.value = 2000f; d.receivedElapsedRealtimeMs = NOW;
        put(s, 13, 0f, NOW); put(s, 32, 2000000f, NOW); m.update(s, d, NOW, 0L);
        check(m.rpmPeak == 2000f, "Estimated RPM contributes to peak");
        float peakPower = m.powerPeak;
        put(s, 13, 900f, NOW); put(s, 32, 1000000f, NOW); put(s, 20, 0f, NOW);
        m.update(s, null, NOW, 0L);
        check(m.rpmPeak == 2000f && m.powerPeak == peakPower && m.gPeak >= 0.5f,
                "Lower samples cannot reduce peaks");
        m.update(s, null, NOW + DashModel.STALE_MS + 1L, 0L);
        check(!m.gValid && m.gPeak >= 0.5f && m.rpmPeak == 2000f, "Staleness preserves peaks");
        m.resetPeaks();
        check(m.rpmPeak == 0f && m.powerPeak == 0f && m.gPeak == 0f, "Reset clears all peaks");
        m.update(s, null, NOW, 0L);
        check(m.rpmPeak == 900f && m.powerPeak > 0f, "Peaks restart with next valid sample");
        s[21 - SensorState.FIRST_TYPE].eventCount = 0L;
        m.update(s, null, NOW, 0L);
        check(!m.gValid, "One missing G axis invalidates the combined vector");
        put(s, 21, Float.NaN, NOW); m.update(s, null, NOW, 0L);
        check(!m.gValid, "NaN G axis invalidates vector");
        put(s, 20, Float.MAX_VALUE, NOW); put(s, 21, Float.MAX_VALUE, NOW);
        m.update(s, null, NOW, 0L);
        check(!m.gValid, "Unrepresentable vector magnitude must not leak infinity");
    }

    private static void put(SensorState.Snapshot[] s, int type, float value, long received) {
        SensorState.Snapshot v = s[type - SensorState.FIRST_TYPE];
        v.value = value; v.eventCount = 1L; v.lastReceivedElapsedRealtimeMs = received;
    }
    private static void gear(SensorState.Snapshot[] s, DashModel m, float value, String expected) {
        put(s, 22, value, NOW); m.update(s, null, NOW, 0L);
        check(m.gearValid && expected.equals(new String(m.gearText, 0, m.gearLength)), "Gear mapping mismatch");
    }
    private static void temperature(SensorState.Snapshot[] s, DashModel m, int type, float value, int level) {
        put(s, type, value, NOW); m.update(s, null, NOW, 0L);
        check(type == 14 ? m.coolantValid && m.coolantC == value && m.coolantLevel == level
                : m.oilValid && m.oilC == value && m.oilLevel == level, "Temperature threshold mismatch");
    }
    private static void range(SensorState.Snapshot[] s, DashModel m, float value, boolean valid, boolean warn) {
        put(s, 18, value, NOW); m.update(s, null, NOW, 0L);
        check(m.rangeValid == valid && m.rangeWarn == warn, "Range filter or warning mismatch");
    }
    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
