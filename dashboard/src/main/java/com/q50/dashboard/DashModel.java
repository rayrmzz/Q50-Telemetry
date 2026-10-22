package com.q50.dashboard;

import com.q50.core.SensorState;

/** Calibrates a coherent SensorState copy on the UI thread, without allocation. */
final class DashModel {
    static final long STALE_MS = 1500L;
    static final long EST_FRESH_MS = 1000L;
    static final int LEVEL_NORMAL = 0, LEVEL_COLD = 1, LEVEL_WARN = 2, LEVEL_DANGER = 3;
    static final float[] SHIFT_RPM = {6500f, 6750f, 7000f, 7250f, 7500f};

    boolean rpmValid;
    boolean rpmEstimated;
    float rpm;
    float rpmPeak;
    int shiftLights;
    boolean speedValid;
    float speedKmh;
    boolean gearValid;
    final char[] gearText = new char[4];
    int gearLength;
    boolean accValid;
    float accPercent;
    boolean brkValid;
    float brkPercent;
    boolean coolantValid;
    float coolantC;
    int coolantLevel;
    boolean oilValid;
    float oilC;
    int oilLevel;
    boolean torqueValid;
    float torqueNm;
    boolean powerValid;
    float powerKw;
    float powerPeak;
    final boolean[] tireValid = new boolean[4];
    final float[] tirePsi = new float[4];
    final boolean[] tireWarn = new boolean[4];
    boolean rangeValid;
    float rangeKm;
    boolean rangeWarn;
    boolean gValid;
    float gLat;
    float gLon;
    float gTotal;
    float gPeak;
    private boolean hasPowerPeak;
    public boolean held;

    void update(SensorState.Snapshot[] s, SensorState.DerivedRpm d, long nowElapsedMs,
            long holdUntilElapsedMs) {
        held = false;
        SensorState.Snapshot v = reading(s, 17);
        boolean speedFresh = fresh(v, nowElapsedMs);
        speedValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        speedKmh = speedValid ? v.value : 0f;
        held = speedValid && !speedFresh;

        v = reading(s, 13);
        boolean realFresh = fresh(v, nowElapsedMs);
        boolean realAvailable = available(v, nowElapsedMs, holdUntilElapsedMs);
        boolean estimateValid = d != null && d.hasValue && finite(d.value)
                && d.value >= 0f && d.value <= SensorState.MAX_RPM
                && d.receivedElapsedRealtimeMs >= 0L
                && nowElapsedMs >= d.receivedElapsedRealtimeMs;
        boolean estimateFresh = estimateValid
                && recent(d.receivedElapsedRealtimeMs, nowElapsedMs, EST_FRESH_MS);
        boolean rpmStale = false;
        rpmValid = false;
        rpmEstimated = false;
        rpm = 0f;
        if (realAvailable && v.value > 0f) {
            rpm = v.value;
            rpmValid = true;
            rpmStale = !realFresh;
        } else if (estimateFresh || (estimateValid && nowElapsedMs < holdUntilElapsedMs)) {
            rpm = d.value;
            rpmValid = true;
            rpmEstimated = true;
            rpmStale = !estimateFresh;
        } else if (realFresh && v.value <= 0f && speedFresh && speedKmh > 2f
                && estimateValid) {
            // A moving car must not look switched off when the last estimate ages out.
            rpm = d.value;
            rpmValid = true;
            rpmEstimated = true;
            rpmStale = true;
        } else if (realAvailable) {
            rpmValid = true; // Fresh zero/non-positive input is the engine-off fallback.
            rpmStale = !realFresh;
        }
        held |= rpmStale && nowElapsedMs < holdUntilElapsedMs;
        shiftLights = 0;
        if (rpmValid) {
            for (int i = 0; i < SHIFT_RPM.length; i++) {
                if (rpm >= SHIFT_RPM[i]) {
                    shiftLights++;
                }
            }
            if (!rpmStale && rpm > rpmPeak) {
                rpmPeak = rpm;
            }
        }

        v = reading(s, 22);
        gearValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        gearLength = 0;
        if (gearValid) {
            int gear = Math.round(v.value);
            if (gear >= 1 && gear <= 4) {
                gearText[0] = gear == 1 ? 'P' : gear == 2 ? 'R' : gear == 3 ? 'N' : 'D';
                gearLength = 1;
            } else if (gear >= 16 && gear <= 22) {
                gearText[0] = 'M';
                gearText[1] = (char) ('1' + gear - 16);
                gearLength = 2;
            } else {
                gearLength = NumberText.formatInt((float) gear, gearText);
                // The fixed four-character contract cannot show every possible int.
                gearValid = !(gearLength == 2 && gearText[0] == '-' && gearText[1] == '-');
            }
        }
        held |= gearValid && !fresh(v, nowElapsedMs);
        v = reading(s, 23);
        accValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        accPercent = accValid ? percent(v.value) : 0f;
        held |= accValid && !fresh(v, nowElapsedMs);
        v = reading(s, 24);
        brkValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        brkPercent = brkValid ? percent(v.value) : 0f;
        held |= brkValid && !fresh(v, nowElapsedMs);

        v = reading(s, 14);
        coolantValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        coolantC = coolantValid ? v.value : 0f;
        coolantLevel = coolantValid ? temperatureLevel(coolantC, 105f, 115f) : LEVEL_NORMAL;
        held |= coolantValid && !fresh(v, nowElapsedMs);
        v = reading(s, 15);
        oilValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        oilC = oilValid ? v.value : 0f;
        oilLevel = oilValid ? temperatureLevel(oilC, 125f, 135f) : LEVEL_NORMAL;
        held |= oilValid && !fresh(v, nowElapsedMs);
        v = reading(s, 12);
        torqueValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        torqueNm = torqueValid ? v.value : 0f;
        held |= torqueValid && !fresh(v, nowElapsedMs);
        v = reading(s, 32);
        powerValid = available(v, nowElapsedMs, holdUntilElapsedMs);
        powerKw = powerValid ? v.value * 1.047e-4f : 0f;
        held |= powerValid && !fresh(v, nowElapsedMs);
        if (powerValid && (!hasPowerPeak || powerKw > powerPeak)) {
            powerPeak = powerKw;
            hasPowerPeak = true;
        }

        for (int i = 0; i < 4; i++) {
            int type = i == 0 ? 37 : i == 1 ? 36 : i == 2 ? 39 : 38;
            v = reading(s, type);
            tireValid[i] = available(v, nowElapsedMs, holdUntilElapsedMs) && v.value >= 1f;
            tirePsi[i] = tireValid[i] ? v.value : 0f;
            tireWarn[i] = tireValid[i] && (v.value < 30f || v.value > 42f);
            held |= tireValid[i] && !fresh(v, nowElapsedMs);
        }
        v = reading(s, 18);
        rangeValid = available(v, nowElapsedMs, holdUntilElapsedMs)
                && v.value >= 50f && v.value <= 900f;
        rangeKm = rangeValid ? v.value : 0f;
        rangeWarn = rangeValid && rangeKm < 80f;
        held |= rangeValid && !fresh(v, nowElapsedMs);

        SensorState.Snapshot lateral = reading(s, 20);
        SensorState.Snapshot longitudinal = reading(s, 21);
        gValid = available(lateral, nowElapsedMs, holdUntilElapsedMs)
                && available(longitudinal, nowElapsedMs, holdUntilElapsedMs);
        gLat = gValid ? lateral.value : 0f;
        gLon = gValid ? longitudinal.value : 0f;
        // Double intermediates avoid overflowing float squares before the square root.
        gTotal = gValid ? (float) Math.sqrt((double) gLat * gLat + (double) gLon * gLon) : 0f;
        if (!finite(gTotal)) {
            gValid = false;
            gLat = gLon = gTotal = 0f;
        }
        held |= gValid && (!fresh(lateral, nowElapsedMs) || !fresh(longitudinal, nowElapsedMs));
        if (gValid && gTotal > gPeak) {
            gPeak = gTotal;
        }
    }

    void resetPeaks() {
        rpmPeak = 0f;
        powerPeak = 0f;
        gPeak = 0f;
        hasPowerPeak = false;
    }

    private static SensorState.Snapshot reading(SensorState.Snapshot[] s, int type) {
        int index = type - SensorState.FIRST_TYPE;
        return s != null && index < s.length ? s[index] : null;
    }

    private static boolean fresh(SensorState.Snapshot s, long now) {
        return s != null && s.eventCount > 0L && finite(s.value)
                && recent(s.lastReceivedElapsedRealtimeMs, now, STALE_MS);
    }

    private static boolean available(SensorState.Snapshot s, long now, long holdUntil) {
        return s != null && s.eventCount > 0L && finite(s.value)
                && s.lastReceivedElapsedRealtimeMs >= 0L
                && now >= s.lastReceivedElapsedRealtimeMs
                && (now < holdUntil || recent(s.lastReceivedElapsedRealtimeMs, now, STALE_MS));
    }

    private static boolean recent(long received, long now, long maximumAge) {
        return received >= 0L && now >= received && now - received <= maximumAge;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float percent(float value) {
        return value < 0f ? 0f : value > 100f ? 100f : value;
    }

    private static int temperatureLevel(float value, float warn, float danger) {
        return value > danger ? LEVEL_DANGER : value > warn ? LEVEL_WARN
                : value < 60f ? LEVEL_COLD : LEVEL_NORMAL;
    }
}
