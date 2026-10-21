package com.q50.core;

/** Event state shared by the capture thread and the UI. No Android dependencies. */
public final class SensorState {
    public static final int FIRST_TYPE = 12;
    public static final int LAST_TYPE = 53;
    public static final int TYPE_COUNT = LAST_TYPE - FIRST_TYPE + 1;

    // The UI owns separate, reusable copies. Never expose live mutable readings.
    public static final class Snapshot {
        public boolean registrationAttempted;
        public boolean registered;
        public float value;
        public float previousValue;
        public boolean hasFiniteValue;
        public float minValue;
        public float maxValue;
        public long eventCount;
        public long changeCount;
        public long zeroEventCount;
        public long nonZeroEventCount;
        public long zeroToNonZeroCount;
        public long nonZeroToZeroCount;
        public long lastEventTimestampNanos;
        public boolean hasNonZeroValue;
        public float lastNonZeroValue;
        public long lastNonZeroTimestampNanos;
        public long lastNonZeroReceivedElapsedRealtimeMs;
        public long lastReceivedElapsedRealtimeMs;

        public long nonZeroAgeMs(long nowElapsedRealtimeMs) {
            return hasNonZeroValue
                    ? Math.max(0L, nowElapsedRealtimeMs - lastNonZeroReceivedElapsedRealtimeMs)
                    : -1L;
        }
    }

    public static final int TORQUE_TYPE = 12;
    public static final int RPM_TYPE = 13;
    public static final int POWER_TYPE = 32;
    // The HAL's 0x180 range for RPM; outside it a power/torque pair is mismatched.
    public static final float MAX_RPM = 8191.75f;
    // Events of one HAL poll arrive back to back; a later torque is never reused.
    public static final long PAIR_WINDOW_MS = 50L;

    /**
     * Experimental RPM rebuilt as power(32) / torque(12): the HAL writes power as
     * RPM x torque from CAN 0x180, and the 0x1F9 frame that zeroes type 13 does not touch
     * either input. Pairing assumes the framework delivers each poll in HAL slot order
     * (12, 13, ..., 32); that is not confirmed on the vehicle. Arrivals that break the
     * 12 -> 13 -> 32 sequence are rejected and counted, but no arrival-order check can
     * prove that a pair came from one poll, so the value stays an estimate.
     */
    public static final class DerivedRpm {
        public boolean hasValue;
        public float value;
        public float minValue;
        public float maxValue;
        public long validCount;
        public long receivedElapsedRealtimeMs;
        public long unpairedCount;     // Power without a torque from the same poll.
        public long outOfOrderCount;   // A second torque before any power: the pair is dropped.
        public long zeroTorqueCount;   // Torque 0: RPM cannot be recovered.
        public long invalidCount;      // Non-finite inputs or result outside 0..MAX_RPM.
        public long reportedZeroCount; // Type 13 was 0 in the same poll while this was > 0.
        public long agreementCount;    // Type 13 and this were both > 0 in the same poll.
        public float maxAbsDifference; // Largest |this - type 13| among agreements.

        public long ageMs(long nowElapsedRealtimeMs) {
            return hasValue
                    ? Math.max(0L, nowElapsedRealtimeMs - receivedElapsedRealtimeMs)
                    : -1L;
        }

        void copyFrom(DerivedRpm source) {
            hasValue = source.hasValue;
            value = source.value;
            minValue = source.minValue;
            maxValue = source.maxValue;
            validCount = source.validCount;
            receivedElapsedRealtimeMs = source.receivedElapsedRealtimeMs;
            unpairedCount = source.unpairedCount;
            outOfOrderCount = source.outOfOrderCount;
            zeroTorqueCount = source.zeroTorqueCount;
            invalidCount = source.invalidCount;
            reportedZeroCount = source.reportedZeroCount;
            agreementCount = source.agreementCount;
            maxAbsDifference = source.maxAbsDifference;
        }
    }

    private final Snapshot[] readings = newSnapshots();
    private final DerivedRpm derivedRpm = new DerivedRpm();
    private long version;
    // Arrival order of accepted events, used to pair 12, 13 and 32 of one poll.
    private long sequence;
    private long torqueSequence;
    private long rpmSequence;
    private long powerSequence;
    private float pairTorque;
    private float pairRpm;
    private long pairTorqueReceivedMs;
    private boolean pairBroken;

    static boolean isSupportedType(int type) {
        return type >= FIRST_TYPE && type <= LAST_TYPE;
    }

    public static Snapshot[] newSnapshots() {
        Snapshot[] result = new Snapshot[TYPE_COUNT];
        for (int i = 0; i < TYPE_COUNT; i++) {
            result[i] = new Snapshot();
        }
        return result;
    }

    synchronized void setRegistration(int type, boolean registered) {
        Snapshot reading = readings[type - FIRST_TYPE];
        reading.registrationAttempted = true;
        reading.registered = registered;
        resetPairing();
        version++;
    }

    synchronized void clearRegistrations() {
        for (int i = 0; i < TYPE_COUNT; i++) {
            readings[i].registered = false;
            readings[i].registrationAttempted = false;
        }
        // A torque from before a pause must never pair with a power after it.
        resetPairing();
        // Keep observations across pause/resume; only registration state changes.
        version++;
    }

    synchronized void record(int type, float value, long timestampNanos,
            long receivedElapsedRealtimeMs) {
        if (!isSupportedType(type)) {
            return;
        }
        Snapshot reading = readings[type - FIRST_TYPE];
        if (!reading.registered) {
            return;
        }
        boolean firstEvent = reading.eventCount == 0L;
        if (!firstEvent) {
            reading.previousValue = reading.value;
            if (Float.floatToIntBits(reading.value) != Float.floatToIntBits(value)) {
                reading.changeCount++;
            }
        } else {
            reading.previousValue = value;
        }
        // All events count, but non-finite readings are not extrema or RPM states.
        if (!Float.isNaN(value) && !Float.isInfinite(value)) {
            if (!reading.hasFiniteValue) {
                reading.minValue = value;
                reading.maxValue = value;
                reading.hasFiniteValue = true;
            } else {
                if (value < reading.minValue) {
                    reading.minValue = value;
                }
                if (value > reading.maxValue) {
                    reading.maxValue = value;
                }
            }
            if (value == 0.0f) {
                reading.zeroEventCount++;
                if (!firstEvent && reading.previousValue != 0.0f
                        && !Float.isNaN(reading.previousValue)
                        && !Float.isInfinite(reading.previousValue)) {
                    reading.nonZeroToZeroCount++;
                }
            } else {
                reading.nonZeroEventCount++;
                if (!firstEvent && reading.previousValue == 0.0f) {
                    reading.zeroToNonZeroCount++;
                }
                reading.hasNonZeroValue = true;
                reading.lastNonZeroValue = value;
                reading.lastNonZeroTimestampNanos = timestampNanos;
                // Local monotonic receipt time also works with OEM sensor timestamps.
                reading.lastNonZeroReceivedElapsedRealtimeMs = receivedElapsedRealtimeMs;
            }
        }
        reading.value = value;
        reading.eventCount++;
        // Keep the sensor timestamp separately for diagnostics.
        reading.lastEventTimestampNanos = timestampNanos;
        reading.lastReceivedElapsedRealtimeMs = receivedElapsedRealtimeMs;
        trackDerivedRpm(type, value, receivedElapsedRealtimeMs);
        version++;
    }

    // Drops pending pair candidates; the derived statistics are kept.
    private void resetPairing() {
        torqueSequence = 0L;
        rpmSequence = 0L;
        pairBroken = false;
    }

    // The HAL emits one poll in slot order: torque 12, then RPM 13, ..., then power 32.
    private void trackDerivedRpm(int type, float value, long receivedElapsedRealtimeMs) {
        sequence++;
        if (type == TORQUE_TYPE) {
            if (torqueSequence > powerSequence) {
                // Two torques without a power between them: polls are mixed or reordered.
                derivedRpm.outOfOrderCount++;
                pairBroken = true;
            }
            torqueSequence = sequence;
            pairTorque = value;
            pairTorqueReceivedMs = receivedElapsedRealtimeMs;
            return;
        }
        if (type == RPM_TYPE) {
            rpmSequence = sequence;
            pairRpm = value;
            return;
        }
        if (type != POWER_TYPE) {
            return;
        }
        long previousPowerSequence = powerSequence;
        powerSequence = sequence;
        DerivedRpm derived = derivedRpm;
        if (pairBroken) {
            pairBroken = false;
            derived.unpairedCount++;
            return;
        }
        // 0x180 writes slots 12, 13 and 32 in one packet, so a real poll always has a
        // type 13 between its torque and its power; reversed delivery does not.
        if (torqueSequence <= previousPowerSequence || rpmSequence <= torqueSequence
                || receivedElapsedRealtimeMs - pairTorqueReceivedMs > PAIR_WINDOW_MS) {
            derived.unpairedCount++;
            return;
        }
        if (!isFinite(value) || !isFinite(pairTorque)) {
            derived.invalidCount++;
            return;
        }
        if (pairTorque == 0.0f) {
            derived.zeroTorqueCount++;
            return;
        }
        float rpm = value / pairTorque;
        if (!isFinite(rpm) || rpm < 0.0f || rpm > MAX_RPM) {
            derived.invalidCount++;
            return;
        }
        if (!derived.hasValue) {
            derived.minValue = rpm;
            derived.maxValue = rpm;
            derived.hasValue = true;
        } else {
            if (rpm < derived.minValue) {
                derived.minValue = rpm;
            }
            if (rpm > derived.maxValue) {
                derived.maxValue = rpm;
            }
        }
        derived.value = rpm;
        derived.validCount++;
        derived.receivedElapsedRealtimeMs = receivedElapsedRealtimeMs;
        // The type 13 between this torque and power is compared as the same poll.
        if (rpm > 0.0f) {
            if (pairRpm == 0.0f) {
                derived.reportedZeroCount++;
            } else if (pairRpm > 0.0f && pairRpm <= MAX_RPM) {
                derived.agreementCount++;
                float difference = Math.abs(rpm - pairRpm);
                if (difference > derived.maxAbsDifference) {
                    derived.maxAbsDifference = difference;
                }
            }
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public synchronized long copyInto(Snapshot[] target, long knownVersion) {
        return copyInto(target, null, knownVersion);
    }

    /** Copies sensor readings and, when given, the derived RPM in one consistent step. */
    public synchronized long copyInto(Snapshot[] target, DerivedRpm derivedTarget, long knownVersion) {
        if (version == knownVersion) {
            return version;
        }
        if (derivedTarget != null) {
            derivedTarget.copyFrom(derivedRpm);
        }
        for (int i = 0; i < TYPE_COUNT; i++) {
            Snapshot source = readings[i];
            Snapshot copy = target[i];
            copy.registrationAttempted = source.registrationAttempted;
            copy.registered = source.registered;
            copy.value = source.value;
            copy.previousValue = source.previousValue;
            copy.hasFiniteValue = source.hasFiniteValue;
            copy.minValue = source.minValue;
            copy.maxValue = source.maxValue;
            copy.eventCount = source.eventCount;
            copy.changeCount = source.changeCount;
            copy.zeroEventCount = source.zeroEventCount;
            copy.nonZeroEventCount = source.nonZeroEventCount;
            copy.zeroToNonZeroCount = source.zeroToNonZeroCount;
            copy.nonZeroToZeroCount = source.nonZeroToZeroCount;
            copy.lastEventTimestampNanos = source.lastEventTimestampNanos;
            copy.hasNonZeroValue = source.hasNonZeroValue;
            copy.lastNonZeroValue = source.lastNonZeroValue;
            copy.lastNonZeroTimestampNanos = source.lastNonZeroTimestampNanos;
            copy.lastNonZeroReceivedElapsedRealtimeMs = source.lastNonZeroReceivedElapsedRealtimeMs;
            copy.lastReceivedElapsedRealtimeMs = source.lastReceivedElapsedRealtimeMs;
        }
        return version;
    }
}
