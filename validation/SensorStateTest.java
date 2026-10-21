package com.q50.core;

/**
 * Dependency-free JVM checks. Run from the project root:
 * javac -d build/state-tests core/src/main/java/com/q50/core/SensorState.java validation/SensorStateTest.java
 * java -cp build/state-tests com.q50.core.SensorStateTest
 */
public final class SensorStateTest {
    public static void main(String[] args) throws Exception {
        rpmBurstBetweenRenders();
        registrationAndTypeIsolation();
        extremaAndRpmAge();
        nonFiniteReadings();
        concurrentCaptureAndSnapshots();
        derivedRpmFromPowerAndTorque();
        derivedRpmRejectsBadPairs();
        derivedRpmAcrossPauseAndOrder();
        System.out.println("PASS: RPM burst, registration/resume, type isolation, "
                + "extrema, zero/non-zero counters and transitions, monotonic RPM age, "
                + "non-finite readings, 420000 events with concurrent coherent snapshots, "
                + "derived RPM pairing, agreement, zeroed type 13 and rejected pairs.");
    }

    /** Power exactly as the HAL computes it for CAN 0x180: rpm_raw * torque * 0.125. */
    private static float halPower(int rpmRaw, float torque) {
        return (float) (rpmRaw * (double) torque * 0.125);
    }

    /** One HAL poll in slot order: torque 12, RPM 13, power 32. */
    private static void poll(SensorState state, float torque, float rpm, float power, long ms) {
        state.record(12, torque, 0L, ms);
        state.record(13, rpm, 0L, ms);
        state.record(32, power, 0L, ms);
    }

    private static SensorState registeredForDerivedRpm() {
        SensorState state = new SensorState();
        state.setRegistration(12, true);
        state.setRegistration(13, true);
        state.setRegistration(32, true);
        return state;
    }

    private static void derivedRpmFromPowerAndTorque() {
        SensorState state = registeredForDerivedRpm();
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        SensorState.DerivedRpm derived = new SensorState.DerivedRpm();
        state.copyInto(ui, derived, -1L);
        check(!derived.hasValue && derived.ageMs(1000L) == -1L,
                "No power/torque pair must show no derived RPM");

        // Good session: type 13 is valid and must agree with power / torque.
        poll(state, 16.5f, 812.5f, halPower(6500, 16.5f), 1000L);
        // Engine braking: negative torque and power still give a positive RPM.
        poll(state, -17.0f, 940.625f, halPower(7525, -17.0f), 1200L);
        state.copyInto(ui, derived, -1L);
        check(derived.hasValue && derived.validCount == 2L
                && derived.value == 940.625f && derived.minValue == 812.5f
                && derived.maxValue == 940.625f && derived.agreementCount == 2L
                && derived.maxAbsDifference == 0.0f && derived.reportedZeroCount == 0L,
                "Derived RPM must match type 13 for positive and negative torque");

        // Failing session: 0x1F9 zeroes type 13, power and torque still carry 0x180.
        poll(state, 55.5f, 0.0f, halPower(6800, 55.5f), 1400L);
        state.copyInto(ui, derived, -1L);
        check(derived.value == 850.0f && derived.reportedZeroCount == 1L
                && derived.agreementCount == 2L && ui[1].value == 0.0f,
                "A zeroed type 13 must be counted while the derived RPM survives");
        check(derived.ageMs(1400L) == 0L && derived.ageMs(2400L) == 1000L,
                "Derived RPM age must use the local receipt clock");

        // High RPM and torque: float power keeps the error far below one RPM.
        poll(state, 380.5f, 7000.0f, halPower(56000, 380.5f), 1600L);
        state.copyInto(ui, derived, -1L);
        check(Math.abs(derived.value - 7000.0f) < 0.01f && derived.maxValue == derived.value,
                "Derived RPM must stay precise at high RPM and torque");

        // Torque and power without a type 13 between them are not a real 0x180 poll.
        state.record(12, 20.0f, 0L, 1800L);
        state.record(32, halPower(8000, 20.0f), 0L, 1800L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 4L && derived.unpairedCount == 1L
                && derived.agreementCount == 3L && derived.reportedZeroCount == 1L,
                "A pair without type 13 between torque and power must be rejected");
    }

    private static void derivedRpmRejectsBadPairs() {
        SensorState state = registeredForDerivedRpm();
        SensorState.DerivedRpm derived = new SensorState.DerivedRpm();
        SensorState.Snapshot[] ui = SensorState.newSnapshots();

        poll(state, 0.0f, 800.0f, 0.0f, 1000L);
        state.copyInto(ui, derived, -1L);
        check(!derived.hasValue && derived.zeroTorqueCount == 1L,
                "Zero torque must not produce an RPM");

        poll(state, 16.5f, 812.5f, halPower(6500, 16.5f), 1200L);
        state.record(32, halPower(6600, 16.5f), 0L, 1210L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.value == 812.5f
                && derived.unpairedCount == 1L,
                "A torque must pair with only one power event");

        state.record(12, 16.5f, 0L, 2000L);
        state.record(13, 812.5f, 0L, 2000L);
        state.record(32, halPower(6500, 16.5f), 0L, 2000L + SensorState.PAIR_WINDOW_MS + 1L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.unpairedCount == 2L,
                "A stale torque must not pair with a later power event");

        state.record(32, halPower(6500, 16.5f), 0L, 2100L);
        state.copyInto(ui, derived, -1L);
        check(derived.unpairedCount == 3L, "Power before any new torque must be unpaired");

        poll(state, 16.5f, 812.5f, Float.NaN, 2200L);
        poll(state, Float.POSITIVE_INFINITY, 812.5f, 1000.0f, 2400L);
        poll(state, 10.0f, 812.5f, -5000.0f, 2600L);
        poll(state, 1.0f, 812.5f, 9000.0f, 2800L);
        state.copyInto(ui, derived, -1L);
        check(derived.invalidCount == 4L && derived.validCount == 1L
                && derived.value == 812.5f && derived.minValue == 812.5f
                && derived.maxValue == 812.5f && derived.agreementCount == 1L,
                "Non-finite inputs and RPM outside 0..8191.75 must be rejected");

        // Pause keeps observations; events while unregistered do not pair.
        state.clearRegistrations();
        poll(state, 16.5f, 0.0f, halPower(7000, 16.5f), 3000L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.reportedZeroCount == 0L,
                "Unregistered events must not feed the derived RPM");
    }

    private static void derivedRpmAcrossPauseAndOrder() {
        SensorState state = registeredForDerivedRpm();
        SensorState.DerivedRpm derived = new SensorState.DerivedRpm();
        SensorState.Snapshot[] ui = SensorState.newSnapshots();

        // A torque from before a pause must not pair with a power after resume.
        state.record(12, 10.0f, 0L, 1000L);
        state.clearRegistrations();
        state.setRegistration(12, true);
        state.setRegistration(13, true);
        state.setRegistration(32, true);
        state.record(32, 8000.0f, 0L, 1020L);
        state.copyInto(ui, derived, -1L);
        check(!derived.hasValue && derived.unpairedCount == 1L,
                "Pause/resume must drop pending pair candidates");
        // Partial unregistration also drops candidates.
        state.record(12, 10.0f, 0L, 1100L);
        state.setRegistration(13, false);
        state.record(32, 8000.0f, 0L, 1110L);
        state.copyInto(ui, derived, -1L);
        check(!derived.hasValue && derived.unpairedCount == 2L,
                "A registration change must drop pending pair candidates");
        state.setRegistration(13, true);

        // Negative type 13 is neither a zero report nor an agreement.
        poll(state, 16.5f, -1.0f, halPower(6500, 16.5f), 1200L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.agreementCount == 0L
                && derived.reportedZeroCount == 0L,
                "Negative type 13 must not count as agreement");

        // Mixed polls 12A,13A,12B,13B,32A,32B: every pair is rejected.
        state.record(12, 10.0f, 0L, 1400L);
        state.record(13, 800.0f, 0L, 1400L);
        state.record(12, 20.0f, 0L, 1401L);
        state.record(13, 900.0f, 0L, 1401L);
        state.record(32, 8000.0f, 0L, 1402L);
        state.record(32, 18000.0f, 0L, 1403L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.outOfOrderCount == 1L
                && derived.unpairedCount == 4L && derived.value == 812.5f,
                "Interleaved polls must be rejected, never paired across polls");

        // Compressed reversed delivery (32, 13, 12 per poll, 1 ms apart): power B must
        // not pair with torque A even inside the time window.
        state.record(32, halPower(6500, 16.5f), 0L, 1600L);
        state.record(13, 812.5f, 0L, 1600L);
        state.record(12, 16.5f, 0L, 1601L);
        state.record(32, 26250.0f, 0L, 1602L);
        state.record(13, 875.0f, 0L, 1602L);
        state.record(12, 30.0f, 0L, 1603L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.unpairedCount == 6L
                && derived.value == 812.5f,
                "Reversed delivery must not pair power with the previous poll's torque");

        // The reversed stream left a torque pending: the first normal poll is dropped
        // as out of order, then pairing recovers.
        poll(state, 30.0f, 875.0f, halPower(7000, 30.0f), 2000L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 1L && derived.outOfOrderCount == 2L
                && derived.unpairedCount == 7L,
                "A torque arriving over a pending one must drop the next pair");
        poll(state, 30.0f, 875.0f, halPower(7000, 30.0f), 2200L);
        state.copyInto(ui, derived, -1L);
        check(derived.validCount == 2L && derived.value == 875.0f
                && derived.agreementCount == 1L,
                "Normal order must pair again after rejected arrivals");
    }

    private static void rpmBurstBetweenRenders() {
        SensorState state = new SensorState();
        state.setRegistration(13, true);
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        long version = state.copyInto(ui, -1L);
        float[] rpm = {812.0f, 0.0f, 0.0f, 814.0f, 813.0f, 0.0f};
        for (int i = 0; i < rpm.length; i++) {
            state.record(13, rpm[i], i + 1L, 1000L + i);
        }
        check(ui[1].eventCount == 0L, "Capture must not mutate the UI snapshot");
        long nextVersion = state.copyInto(ui, version);
        SensorState.Snapshot reading = ui[1];
        check(nextVersion != version, "Burst must invalidate the rendered snapshot");
        check(reading.eventCount == 6L && reading.changeCount == 4L,
                "All RPM events and transitions must survive slow rendering");
        check(reading.value == 0.0f && reading.previousValue == 813.0f,
                "Current and immediately preceding values must be preserved");
        check(reading.hasNonZeroValue && reading.lastNonZeroValue == 813.0f
                && reading.lastNonZeroTimestampNanos == 5L
                && reading.lastEventTimestampNanos == 6L,
                "Zero RPM must not erase the most recent nonzero observation");
        check(reading.hasFiniteValue && reading.minValue == 0.0f
                && reading.maxValue == 814.0f && reading.zeroEventCount == 3L
                && reading.nonZeroEventCount == 3L && reading.zeroToNonZeroCount == 1L
                && reading.nonZeroToZeroCount == 2L,
                "RPM extrema and transitions must include events between UI renders");
        check(state.copyInto(ui, nextVersion) == nextVersion,
                "Unchanged state must allow the UI to skip rendering");
        // A caller's snapshot must never be the live state.
        reading.value = 999.0f;
        reading.minValue = 999.0f;
        reading.zeroEventCount = 999L;
        state.record(13, 0.0f, 7L, 1006L);
        state.copyInto(ui, nextVersion);
        check(reading.eventCount == 7L && reading.changeCount == 4L
                && reading.previousValue == 0.0f && reading.minValue == 0.0f
                && reading.maxValue == 814.0f && reading.zeroEventCount == 4L,
                "Snapshots must be independent copies");
    }

    private static void registrationAndTypeIsolation() {
        SensorState state = new SensorState();
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        state.record(13, 900.0f, 1L, 1L); // No registration: cannot invent an observation.
        state.setRegistration(13, false);
        state.record(13, 900.0f, 2L, 2L);
        state.record(11, 1.0f, 3L, 3L);
        state.record(54, 1.0f, 4L, 4L);
        state.record(Integer.MAX_VALUE, 1.0f, 5L, 5L);
        state.setRegistration(12, true);
        state.setRegistration(53, true);
        state.record(12, 16.5f, 6L, 6L);
        state.record(53, 42.0f, 7L, 7L);
        state.copyInto(ui, -1L);
        check(ui[0].value == 16.5f && ui[0].eventCount == 1L
                && ui[41].value == 42.0f && ui[41].eventCount == 1L,
                "Both supported boundaries must map to separate sensor state");
        check(ui[1].registrationAttempted && !ui[1].registered
                && ui[1].eventCount == 0L && !ui[1].hasFiniteValue
                && ui[1].zeroEventCount == 0L && ui[1].nonZeroEventCount == 0L,
                "Failed registration must be visible without inventing statistics");
        state.setRegistration(13, true);
        state.record(13, 812.0f, 8L, 8L);
        state.clearRegistrations();
        state.record(13, 1000.0f, 9L, 9L);
        state.copyInto(ui, -1L);
        check(!ui[1].registered && !ui[1].registrationAttempted
                && ui[1].eventCount == 1L && ui[1].minValue == 812.0f
                && ui[1].maxValue == 812.0f && ui[1].nonZeroEventCount == 1L,
                "Pause must stop capture and retain observations");
        state.setRegistration(13, true);
        state.record(13, 0.0f, 10L, 10L);
        state.copyInto(ui, -1L);
        check(ui[1].eventCount == 2L && ui[1].changeCount == 1L
                && ui[1].lastNonZeroValue == 812.0f && ui[1].minValue == 0.0f
                && ui[1].maxValue == 812.0f && ui[1].zeroEventCount == 1L
                && ui[1].nonZeroEventCount == 1L && ui[1].nonZeroToZeroCount == 1L,
                "Resume must continue counters and nonzero history");
    }

    private static void extremaAndRpmAge() {
        SensorState state = new SensorState();
        state.setRegistration(13, true);
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        state.copyInto(ui, -1L);
        check(!ui[1].hasFiniteValue && ui[1].nonZeroAgeMs(1000L) == -1L,
                "No events must display unknown extrema and nonzero age");
        state.record(13, 0.0f, 0L, 1000L);
        state.record(13, -0.0f, 1L, 1500L);
        state.record(13, 0.0f, 2L, 2000L);
        state.copyInto(ui, -1L);
        check(ui[1].hasFiniteValue && ui[1].minValue == 0.0f && ui[1].maxValue == 0.0f
                && ui[1].zeroEventCount == 3L && ui[1].nonZeroEventCount == 0L
                && ui[1].zeroToNonZeroCount == 0L && ui[1].nonZeroToZeroCount == 0L
                && ui[1].nonZeroAgeMs(2500L) == -1L,
                "An all-zero stream, including negative zero, must not invent nonzero history");
        state.record(13, 750.0f, 9L, 3000L);
        state.record(13, 750.0f, 10L, 3500L);
        state.record(13, 800.0f, 11L, 4000L);
        state.record(13, 0.0f, 12L, 4500L);
        long version = state.copyInto(ui, -1L);
        check(ui[1].minValue == 0.0f && ui[1].maxValue == 800.0f
                && ui[1].zeroEventCount == 4L && ui[1].nonZeroEventCount == 3L
                && ui[1].zeroToNonZeroCount == 1L && ui[1].nonZeroToZeroCount == 1L,
                "Repeated nonzero values must count as events without adding transitions");
        check(ui[1].lastNonZeroValue == 800.0f && ui[1].lastNonZeroTimestampNanos == 11L
                && ui[1].nonZeroAgeMs(4500L) == 500L
                && ui[1].nonZeroAgeMs(10000L) == 6000L
                && ui[1].nonZeroAgeMs(3999L) == 0L
                && state.copyInto(ui, version) == version,
                "RPM age must advance without events using the local monotonic clock");
        state.setRegistration(14, true);
        state.record(14, 67.0f, 1L, 1000L);
        state.copyInto(ui, -1L);
        check(ui[2].minValue == 67.0f && ui[2].maxValue == 67.0f,
                "The first positive reading must initialize both extrema, not zero");
        state.record(14, 72.0f, 2L, 2000L);
        state.record(14, 65.0f, 3L, 3000L);
        state.record(14, 65.0f, 4L, 4000L);
        state.setRegistration(12, true);
        state.record(12, -10.0f, 1L, 1000L);
        state.record(12, 2.0f, 2L, 2000L);
        state.record(12, -20.0f, 3L, 3000L);
        state.copyInto(ui, -1L);
        check(ui[2].minValue == 65.0f && ui[2].maxValue == 72.0f
                && ui[2].eventCount == 4L && ui[2].changeCount == 2L
                && ui[0].minValue == -20.0f && ui[0].maxValue == 2.0f,
                "Each sensor must independently track repeated, positive and negative readings");
    }

    private static void nonFiniteReadings() {
        SensorState state = new SensorState();
        state.setRegistration(13, true);
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        state.record(13, Float.NaN, 1L, 1000L);
        state.record(13, Float.POSITIVE_INFINITY, 2L, 2000L);
        state.record(13, Float.NEGATIVE_INFINITY, 3L, 3000L);
        state.copyInto(ui, -1L);
        check(ui[1].eventCount == 3L && !ui[1].hasFiniteValue
                && !ui[1].hasNonZeroValue && ui[1].zeroEventCount == 0L
                && ui[1].nonZeroEventCount == 0L,
                "Non-finite events must count without poisoning extrema or RPM history");
        state.record(13, 812.0f, 4L, 4000L);
        state.record(13, Float.NaN, 5L, 5000L);
        state.record(13, 0.0f, 6L, 6000L);
        state.record(13, -10.0f, 7L, 7000L);
        state.record(13, Float.POSITIVE_INFINITY, 8L, 8000L);
        state.record(13, 0.0f, 9L, 9000L);
        state.record(13, 0.0f, 10L, 10000L);
        state.copyInto(ui, -1L);
        check(ui[1].eventCount == 10L && ui[1].minValue == -10.0f
                && ui[1].maxValue == 812.0f && ui[1].zeroEventCount == 3L
                && ui[1].nonZeroEventCount == 2L && ui[1].zeroToNonZeroCount == 1L
                && ui[1].nonZeroToZeroCount == 0L && ui[1].lastNonZeroValue == -10.0f
                && ui[1].nonZeroAgeMs(11000L) == 4000L,
                "Invalid observations must not invent transitions or refresh nonzero age");
    }

    private static void concurrentCaptureAndSnapshots() throws Exception {
        final SensorState state = new SensorState();
        final int cycles = 10000;
        for (int type = SensorState.FIRST_TYPE; type <= SensorState.LAST_TYPE; type++) {
            state.setRegistration(type, true);
        }
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    for (int type = SensorState.FIRST_TYPE; type <= SensorState.LAST_TYPE;
                            type++) {
                        state.record(type, (float) (cycle & 1), cycle + 1L, cycle + 1L);
                    }
                }
            }
        }, "test-capture");
        SensorState.Snapshot[] ui = SensorState.newSnapshots();
        long[] previousCounts = new long[SensorState.TYPE_COUNT];
        long version = -1L;
        producer.start();
        do {
            version = state.copyInto(ui, version);
            for (int i = 0; i < ui.length; i++) {
                SensorState.Snapshot reading = ui[i];
                check(reading.eventCount >= previousCounts[i], "Counts must be monotonic");
                previousCounts[i] = reading.eventCount;
                if (reading.eventCount > 0L) {
                    check(reading.value == (float) ((reading.eventCount - 1L) & 1L)
                            && reading.changeCount == reading.eventCount - 1L
                            && reading.lastEventTimestampNanos == reading.eventCount
                            && reading.hasFiniteValue && reading.minValue == 0.0f
                            && reading.maxValue == (reading.eventCount > 1L ? 1.0f : 0.0f)
                            && reading.zeroEventCount == (reading.eventCount + 1L) / 2L
                            && reading.nonZeroEventCount == reading.eventCount / 2L
                            && reading.zeroToNonZeroCount == reading.eventCount / 2L
                            && reading.nonZeroToZeroCount == (reading.eventCount - 1L) / 2L,
                            "Snapshots must contain coherent counts, timestamps and extrema");
                }
            }
            Thread.yield();
        } while (producer.isAlive());
        producer.join();
        state.copyInto(ui, version);
        for (int i = 0; i < ui.length; i++) {
            check(ui[i].eventCount == cycles && ui[i].changeCount == cycles - 1L
                    && ui[i].zeroEventCount == cycles / 2L
                    && ui[i].nonZeroEventCount == cycles / 2L
                    && ui[i].zeroToNonZeroCount == cycles / 2L
                    && ui[i].nonZeroToZeroCount == cycles / 2L - 1L,
                    "Concurrent rendering must not drop any captured event");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
