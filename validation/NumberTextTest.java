package com.q50.dashboard;

/** Formatting tests allocate expected/result strings; production formatters do not. */
public final class NumberTextTest {
    public static void main(String[] args) {
        char[] text = new char[12];
        integer(0f, "0", text); integer(-0f, "0", text);
        integer(0.49f, "0", text); integer(-0.49f, "0", text);
        integer(0.5f, "1", text); integer(-0.5f, "-1", text);
        integer(1.5f, "2", text); integer(-1.5f, "-2", text);
        integer(8191.75f, "8192", text); integer(-8191.75f, "-8192", text);
        integer(1000000f, "1000000", text);
        one(35.5f, "35.5", text); one(-0.2f, "-0.2", text);
        one(-0.04f, "0.0", text); one(-0f, "0.0", text);
        one(1.25f, "1.3", text); one(-1.25f, "-1.3", text);
        one(9.99f, "10.0", text); one(99.99f, "100.0", text);
        one(8191.75f, "8191.8", text);
        two(0.32f, "0.32", text); two(-0.2f, "-0.20", text);
        two(-0.004f, "0.00", text); two(1.125f, "1.13", text);
        two(-1.125f, "-1.13", text); two(99.999f, "100.00", text);
        two(8191.75f, "8191.75", text); two(0f, "0.00", text);
        integer(Float.NaN, "--", text); one(Float.POSITIVE_INFINITY, "--", text);
        two(Float.NEGATIVE_INFINITY, "--", text); integer(Float.MAX_VALUE, "--", text);
        integer(-1000f, "--", new char[4]); integer(9999f, "9999", new char[4]);
        integer(123f, "--", new char[2]);
        check(NumberText.formatInt(123f, new char[0]) == 0, "Empty buffer must not overflow");
        // Reusing a buffer must not retain a minus sign or trailing digits in its length.
        one(-123.4f, "-123.4", text); one(1f, "1.0", text); integer(0f, "0", text);
        // A broad integer domain checks digit order, signs and returned lengths independently.
        for (int n = -10000; n <= 10000; n++) {
            int length = NumberText.formatInt((float) n, text);
            check(Integer.parseInt(new String(text, 0, length)) == n, "Integer round trip mismatch");
        }
        System.out.println("PASS: NumberText ties away from zero, decimals, negative zero, "
                + "carry, buffer reuse/bounds, non-finite inputs and 20001 integer round trips.");
    }
    private static void integer(float value, String expected, char[] text) {
        check(expected.equals(new String(text, 0, NumberText.formatInt(value, text))), "Integer format mismatch");
    }
    private static void one(float value, String expected, char[] text) {
        check(expected.equals(new String(text, 0, NumberText.formatOneDecimal(value, text))), "One decimal mismatch");
    }
    private static void two(float value, String expected, char[] text) {
        check(expected.equals(new String(text, 0, NumberText.formatTwoDecimals(value, text))), "Two decimals mismatch");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

