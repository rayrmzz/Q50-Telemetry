package com.q50.dashboard;

/** Decimal text in caller-owned buffers; no allocation and no Android dependencies. */
final class NumberText {
    private NumberText() {}

    static int formatInt(float value, char[] dst) {
        return format(value, dst, 0, 1);
    }

    static int formatOneDecimal(float value, char[] dst) {
        return format(value, dst, 1, 10);
    }

    static int formatTwoDecimals(float value, char[] dst) {
        return format(value, dst, 2, 100);
    }

    private static int format(float value, char[] dst, int decimals, int scale) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return unavailable(dst);
        }
        double absolute = Math.abs((double) value);
        double rounded = Math.floor(absolute * scale + 0.5d);
        // A cast outside long's range would silently saturate to an unrelated number.
        if (rounded >= 9223372036854775807d) {
            return unavailable(dst);
        }
        long magnitude = (long) rounded;
        boolean negative = value < 0f && magnitude != 0L;
        long integer = magnitude / scale;
        int digits = 1;
        for (long n = integer; n >= 10L; n /= 10L) {
            digits++;
        }
        int length = digits + (negative ? 1 : 0) + (decimals == 0 ? 0 : decimals + 1);
        if (length > dst.length) {
            return unavailable(dst);
        }
        int at = length;
        for (int i = 0; i < decimals; i++) {
            dst[--at] = (char) ('0' + magnitude % 10L);
            magnitude /= 10L;
        }
        if (decimals != 0) {
            dst[--at] = '.';
        }
        do {
            dst[--at] = (char) ('0' + integer % 10L);
            integer /= 10L;
        } while (integer != 0L);
        if (negative) {
            dst[0] = '-';
        }
        return length;
    }

    private static int unavailable(char[] dst) {
        if (dst.length == 0) {
            return 0;
        }
        dst[0] = '-';
        if (dst.length == 1) {
            return 1;
        }
        dst[1] = '-';
        return 2;
    }
}
