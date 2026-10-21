package com.q50.dashboard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * The whole dashboard in one View, laid out on an 800x480 reference grid and scaled to fit.
 *
 * Everything that never changes (rings, ticks, labels, units, unlit segments) is rendered
 * once into an opaque background bitmap. onDraw blits it and paints only live values, with
 * preallocated Paints, Paths and char buffers: no allocation per frame (API 10, software).
 */
final class DashboardView extends View {
    private static final float REF_W = 800f;
    private static final float REF_H = 480f;

    private static final int BG = 0xFF0D0F12;
    private static final int TRACK = 0xFF232931;
    private static final int DIVIDER = 0xFF1E232A;
    private static final int TICK = 0xFF3A414B;
    private static final int BOX = 0xFF2A3038;
    private static final int LABEL = 0xFF8C939E;
    private static final int VALUE = 0xFFECEFF3;
    private static final int DIM = 0xFF5E6570;
    private static final int BRAKE = 0xFFE5484D;
    private static final int COLD = 0xFF4DA8FF;
    private static final int WARN = 0xFFFFB21E;
    private static final int DANGER = 0xFFFF3D4D;
    private static final int REDZONE = 0xFF5A1E25;
    private static final int EST_ALPHA = 140;

    // RPM ring: 0..8000 over 270 degrees from 135, redline 7500 (VQ37VHR).
    private static final float RING_CX = 168f;
    private static final float RING_CY = 196f;
    private static final float RING_R = 132f;
    private static final float RPM_MAX = 8000f;
    private static final float RPM_RED = 7500f;
    private static final float SWEEP = 270f;
    private static final float START = 135f;

    // Segmented bars: horizontal (right column, range) and vertical (pedals).
    private static final int H_SEGMENTS = 20;
    private static final int V_SEGMENTS = 16;
    private static final float SEG_GAP = 2f;
    private static final float BAR_X = 580f;
    private static final float BAR_W = 204f;
    private static final float BAR_H = 10f;
    private static final float[] BAR_Y = {90f, 156f, 222f, 288f};
    private static final float[] LABEL_Y = {80f, 146f, 212f, 278f};
    private static final float VALUE_RIGHT = 784f;
    private static final float RANGE_X = 270f;
    private static final float RANGE_Y = 426f;
    private static final float RANGE_W = 272f;
    private static final float PEDAL_TOP = 96f;
    private static final float PEDAL_W = 12f;
    private static final float PEDAL_H = 196f;
    private static final float ACC_X = 318f;
    private static final float BRK_X = 344f;
    private static final float TORQUE_MIN = -100f;
    private static final float TORQUE_MAX = 400f;
    private static final int TORQUE_ZERO_SEGMENT = 4;
    private static final float POWER_MAX = 250f;
    private static final float RANGE_FULL = 500f;
    private static final float G_CX = 728f;
    private static final float G_CY = 410f;
    private static final float G_R = 52f;

    private static final float[] TIRE_X = {38f, 150f, 38f, 150f};
    private static final float[] TIRE_Y = {410f, 410f, 454f, 454f};
    private static final char[] NO_DATA = {'-', '-'};
    private static final long RESET_HOLD_MS = 800L;

    private final DashModel model;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF ringOval = new RectF(RING_CX - RING_R, RING_CY - RING_R,
            RING_CX + RING_R, RING_CY + RING_R);
    private final RectF estBox = new RectF(148f, 250f, 188f, 268f);
    // Segment shapes in local coordinates; each bar clips them to its lit span.
    private final Path hBarSegments = segments(BAR_W, BAR_H, H_SEGMENTS, true);
    private final Path rangeSegments = segments(RANGE_W, BAR_H, H_SEGMENTS, true);
    private final Path pedalSegments = segments(PEDAL_W, PEDAL_H, V_SEGMENTS, false);
    private final char[] buffer = new char[24];
    private final char[] number = new char[16];
    private final float hStep = (BAR_W + SEG_GAP) / H_SEGMENTS;
    private final float rangeStep = (RANGE_W + SEG_GAP) / H_SEGMENTS;
    private final float vStep = (PEDAL_H + SEG_GAP) / V_SEGMENTS;

    private Bitmap background;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;
    // Unit widths measured once, so values sit right before their unit.
    private float unitDegreesW;
    private float unitTorqueW;
    private float unitPowerW;
    private float unitKmW;
    // Labels follow the system language (res/values-xx); loaded once, drawn into the
    // static bitmap. The only dynamic ones are the MAX prefix and EST, kept as char[].
    private final String labelRpm, labelMax, labelAccelerator, labelBrake, labelSpeedUnit,
            labelGear, labelTires, labelRange, labelEmpty, labelFull, labelGForce;
    private final String[] barLabels;
    private final String[] tireLabels;
    private final char[] maxPrefix;
    private final char[] est;
    private boolean holdingRing;
    private long downTime;

    DashboardView(Context context, DashModel model) {
        super(context);
        this.model = model;
        Resources res = context.getResources();
        labelRpm = res.getString(R.string.label_rpm);
        labelMax = res.getString(R.string.label_max);
        labelAccelerator = res.getString(R.string.label_accelerator);
        labelBrake = res.getString(R.string.label_brake);
        labelSpeedUnit = res.getString(R.string.label_speed_unit);
        labelGear = res.getString(R.string.label_gear);
        labelTires = res.getString(R.string.label_tires);
        labelRange = res.getString(R.string.label_range);
        labelEmpty = res.getString(R.string.label_empty);
        labelFull = res.getString(R.string.label_full);
        labelGForce = res.getString(R.string.label_g_force);
        barLabels = new String[] {res.getString(R.string.label_coolant),
                res.getString(R.string.label_oil), res.getString(R.string.label_torque),
                res.getString(R.string.label_power)};
        tireLabels = new String[] {res.getString(R.string.label_front_left),
                res.getString(R.string.label_front_right), res.getString(R.string.label_rear_left),
                res.getString(R.string.label_rear_right)};
        maxPrefix = (labelMax + " ").toCharArray();
        est = res.getString(R.string.label_est).toCharArray();
        Typeface face;
        try {
            face = Typeface.createFromAsset(context.getAssets(), "fonts/BarlowCondensed-SemiBold.ttf");
        } catch (RuntimeException e) {
            face = Typeface.DEFAULT_BOLD;
        }
        text.setTypeface(face);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        text.setTextSize(14f);
        unitDegreesW = text.measureText(" °C");
        unitTorqueW = text.measureText(" N·m");
        unitPowerW = text.measureText(" kW");
        text.setTextSize(16f);
        unitKmW = text.measureText(" km");
        // No View background: the bitmap covers every pixel, so one full fill per frame.
        setWillNotDraw(false);
    }

    // Opaque once the background exists, so nothing behind the View is redrawn.
    @Override
    public boolean isOpaque() {
        return background != null;
    }

    /** n rectangles with SEG_GAP between them, laid out along x (horizontal) or y. */
    private static Path segments(float w, float h, int n, boolean horizontal) {
        Path path = new Path();
        float length = horizontal ? w : h;
        float size = (length - SEG_GAP * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float start = i * (size + SEG_GAP);
            if (horizontal) {
                path.addRect(start, 0f, start + size, h, Path.Direction.CW);
            } else {
                path.addRect(0f, start, w, start + size, Path.Direction.CW);
            }
        }
        return path;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildBackground(w, h);
    }

    // The bitmap's pixels are native memory on API 10: free them as soon as the View leaves.
    @Override
    protected void onDetachedFromWindow() {
        releaseBackground();
        super.onDetachedFromWindow();
    }

    private void releaseBackground() {
        if (background != null) {
            background.recycle();
            background = null;
        }
    }

    // Runs on size changes and once after a re-attach, never per frame.
    private void buildBackground(int w, int h) {
        releaseBackground();
        if (w <= 0 || h <= 0) {
            return;
        }
        scale = Math.min(w / REF_W, h / REF_H);
        offsetX = (w - REF_W * scale) / 2f;
        offsetY = (h - REF_H * scale) / 2f;
        // Opaque flat colors: RGB565 halves memory and copy cost versus ARGB8888.
        background = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(background);
        canvas.drawColor(BG);
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawStatic(canvas);
    }

    // ---------------------------------------------------------------- static layer

    private void drawStatic(Canvas c) {
        // RPM ring track, redline zone, ticks every 500 and numbers every 1000.
        stroke.setStrokeWidth(16f);
        stroke.setColor(TRACK);
        c.drawArc(ringOval, START, SWEEP, false, stroke);
        stroke.setColor(REDZONE);
        c.drawArc(ringOval, START + SWEEP * RPM_RED / RPM_MAX,
                SWEEP * (1f - RPM_RED / RPM_MAX), false, stroke);
        stroke.setStrokeWidth(2f);
        stroke.setColor(TICK);
        for (int k = 0; k <= 16; k++) {
            double a = Math.toRadians(START + SWEEP * k / 16f);
            float inner = k % 2 == 0 ? 104f : 109f;
            c.drawLine(RING_CX + inner * (float) Math.cos(a), RING_CY + inner * (float) Math.sin(a),
                    RING_CX + 116f * (float) Math.cos(a), RING_CY + 116f * (float) Math.sin(a), stroke);
        }
        for (int k = 0; k <= 8; k++) {
            double a = Math.toRadians(START + SWEEP * k / 8f);
            buffer[0] = (char) ('0' + k);
            label(c, buffer, 1, RING_CX + 90f * (float) Math.cos(a),
                    RING_CY + 90f * (float) Math.sin(a) + 5f, 15f, LABEL, Paint.Align.CENTER);
        }
        label(c, labelRpm, 168f, 240f, 14f, LABEL, Paint.Align.CENTER);
        label(c, labelMax, 164f, 304f, 14f, LABEL, Paint.Align.RIGHT);
        fill.setColor(TRACK);
        for (int i = 0; i < 5; i++) {
            c.drawCircle(128f + 20f * i, 140f, 5f, fill);
        }

        // Pedals: unlit segments and labels.
        fill.setColor(TRACK);
        drawSegments(c, pedalSegments, ACC_X, PEDAL_TOP);
        drawSegments(c, pedalSegments, BRK_X, PEDAL_TOP);
        label(c, labelAccelerator, ACC_X + 6f, 312f, 12f, LABEL, Paint.Align.CENTER);
        label(c, labelBrake, BRK_X + 6f, 312f, 12f, LABEL, Paint.Align.CENTER);

        // Speed and gear.
        label(c, labelSpeedUnit, 474f, 196f, 16f, LABEL, Paint.Align.CENTER);
        stroke.setColor(BOX);
        RectF gearBox = new RectF(430f, 220f, 518f, 292f); // Static layer only.
        c.drawRoundRect(gearBox, 6f, 6f, stroke);
        label(c, labelGear, 474f, 312f, 12f, LABEL, Paint.Align.CENTER);
        label(c, "Q50 · TELEMETRY", 780f, 34f, 13f, LABEL, Paint.Align.RIGHT);

        // Right column: labels, units, unlit segments, threshold marks.
        String[] units = {" °C", " °C", " N·m", " kW"};
        fill.setColor(TRACK);
        for (int i = 0; i < 4; i++) {
            label(c, barLabels[i], BAR_X, LABEL_Y[i], 13f, LABEL, Paint.Align.LEFT);
            label(c, units[i], VALUE_RIGHT, LABEL_Y[i] + 2f, 14f, LABEL, Paint.Align.RIGHT);
            drawSegments(c, hBarSegments, BAR_X, BAR_Y[i]);
        }
        stroke.setColor(DIM);
        mark(c, barX(105f, 40f, 130f), BAR_Y[0] + 13f, BAR_Y[0] + 19f);
        mark(c, barX(115f, 40f, 130f), BAR_Y[0] + 13f, BAR_Y[0] + 19f);
        mark(c, barX(125f, 40f, 150f), BAR_Y[1] + 13f, BAR_Y[1] + 19f);
        mark(c, barX(135f, 40f, 150f), BAR_Y[1] + 13f, BAR_Y[1] + 19f);
        stroke.setColor(LABEL);
        mark(c, BAR_X + hStep * TORQUE_ZERO_SEGMENT - SEG_GAP / 2f, BAR_Y[2] - 4f, BAR_Y[2] + 14f);

        // Bottom strip.
        stroke.setColor(DIVIDER);
        c.drawLine(16f, 340f, 784f, 340f, stroke);
        c.drawLine(250f, 356f, 250f, 464f, stroke);
        c.drawLine(562f, 356f, 562f, 464f, stroke);

        label(c, labelTires, 16f, 370f, 13f, LABEL, Paint.Align.LEFT);
        label(c, tireLabels[0], 16f, 408f, 12f, LABEL, Paint.Align.LEFT);
        label(c, tireLabels[1], 128f, 408f, 12f, LABEL, Paint.Align.LEFT);
        label(c, tireLabels[2], 16f, 452f, 12f, LABEL, Paint.Align.LEFT);
        label(c, tireLabels[3], 128f, 452f, 12f, LABEL, Paint.Align.LEFT);

        label(c, labelRange, RANGE_X, 370f, 13f, LABEL, Paint.Align.LEFT);
        label(c, " km", 542f, 410f, 16f, LABEL, Paint.Align.RIGHT);
        fill.setColor(TRACK);
        drawSegments(c, rangeSegments, RANGE_X, RANGE_Y);
        label(c, labelEmpty, RANGE_X, 452f, 13f, LABEL, Paint.Align.LEFT);
        label(c, labelFull, RANGE_X + RANGE_W, 452f, 13f, LABEL, Paint.Align.RIGHT);

        label(c, labelGForce, 578f, 370f, 13f, LABEL, Paint.Align.LEFT);
        stroke.setColor(TRACK);
        c.drawCircle(G_CX, G_CY, G_R, stroke);
        c.drawCircle(G_CX, G_CY, G_R / 2f, stroke);
        c.drawLine(G_CX - G_R, G_CY, G_CX + G_R, G_CY, stroke);
        c.drawLine(G_CX, G_CY - G_R, G_CX, G_CY + G_R, stroke);
    }

    private void drawSegments(Canvas c, Path segments, float x, float y) {
        c.save();
        c.translate(x, y);
        c.drawPath(segments, fill);
        c.restore();
    }

    private void mark(Canvas c, float x, float y0, float y1) {
        c.drawLine(x, y0, x, y1, stroke);
    }

    private static float barX(float value, float min, float max) {
        return BAR_X + BAR_W * (value - min) / (max - min);
    }

    private void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align a) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, x, y, text);
    }

    private void label(Canvas c, char[] s, int length, float x, float y, float size, int color,
            Paint.Align a) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, 0, length, x, y, text);
    }

    // ---------------------------------------------------------------- live layer

    @Override
    protected void onDraw(Canvas canvas) {
        if (background == null) {
            // Re-attached with the same size: onSizeChanged will not run again.
            buildBackground(getWidth(), getHeight());
            if (background == null) {
                return;
            }
        }
        canvas.drawBitmap(background, 0f, 0f, null);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawRpm(canvas);
        drawPedalsSpeedGear(canvas);
        drawBars(canvas);
        drawBottom(canvas);
        canvas.restore();
    }

    private void drawRpm(Canvas c) {
        DashModel m = model;
        if (m.rpmValid) {
            float frac = clamp(m.rpm / RPM_MAX, 0f, 1f);
            stroke.setStrokeWidth(16f);
            stroke.setColor(m.rpm >= RPM_RED ? DANGER : VALUE);
            if (m.rpmEstimated) {
                stroke.setAlpha(EST_ALPHA);
            }
            if (frac > 0f) {
                c.drawArc(ringOval, START, SWEEP * frac, false, stroke);
            }
            stroke.setAlpha(255);
            for (int i = 0; i < m.shiftLights && i < 5; i++) {
                fill.setColor(i < 2 ? VALUE : i == 2 ? WARN : DANGER);
                c.drawCircle(128f + 20f * i, 140f, 5f, fill);
            }
            int n = NumberText.formatInt(m.rpm, number);
            label(c, number, n, RING_CX, 216f, 68f, VALUE, Paint.Align.CENTER);
            if (m.rpmEstimated) {
                stroke.setStrokeWidth(1.5f);
                stroke.setColor(VALUE);
                c.drawRoundRect(estBox, 3f, 3f, stroke);
                label(c, est, est.length, RING_CX, 264f, 13f, VALUE, Paint.Align.CENTER);
            }
        } else {
            label(c, NO_DATA, 2, RING_CX, 216f, 68f, DIM, Paint.Align.CENTER);
        }
        if (m.rpmPeak > 0f) {
            double a = Math.toRadians(START + SWEEP * clamp(m.rpmPeak / RPM_MAX, 0f, 1f));
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            stroke.setStrokeWidth(3f);
            stroke.setColor(VALUE);
            c.drawLine(RING_CX + 122f * cos, RING_CY + 122f * sin,
                    RING_CX + 142f * cos, RING_CY + 142f * sin, stroke);
            int n = NumberText.formatInt(m.rpmPeak, number);
            label(c, number, n, 168f, 304f, 14f, LABEL, Paint.Align.LEFT);
        } else {
            label(c, NO_DATA, 2, 168f, 304f, 14f, LABEL, Paint.Align.LEFT);
        }
    }

    private void drawPedalsSpeedGear(Canvas c) {
        DashModel m = model;
        // A missing pedal shows "--" above its bar so it never looks like 0 %.
        if (m.accValid) {
            fill.setColor(VALUE);
            drawPedal(c, ACC_X, m.accPercent);
        } else {
            label(c, NO_DATA, 2, ACC_X + PEDAL_W / 2f, PEDAL_TOP - 6f, 12f, DIM, Paint.Align.CENTER);
        }
        if (m.brkValid) {
            fill.setColor(BRAKE);
            drawPedal(c, BRK_X, m.brkPercent);
        } else {
            label(c, NO_DATA, 2, BRK_X + PEDAL_W / 2f, PEDAL_TOP - 6f, 12f, DIM, Paint.Align.CENTER);
        }
        if (m.speedValid) {
            int n = NumberText.formatInt(m.speedKmh, number);
            label(c, number, n, 474f, 170f, 104f, VALUE, Paint.Align.CENTER);
        } else {
            label(c, NO_DATA, 2, 474f, 170f, 104f, DIM, Paint.Align.CENTER);
        }
        if (m.gearValid) {
            label(c, m.gearText, m.gearLength, 474f, 276f, 60f, VALUE, Paint.Align.CENTER);
        } else {
            label(c, NO_DATA, 2, 474f, 276f, 60f, DIM, Paint.Align.CENTER);
        }
    }

    // Lights the bottom segments up to percent (0..100).
    private void drawPedal(Canvas c, float x, float percent) {
        int lit = Math.round(V_SEGMENTS * clamp(percent, 0f, 100f) / 100f);
        if (lit <= 0) {
            return;
        }
        // The lit segments are the bottom ones; their span starts in the gap above them.
        c.save();
        c.translate(x, PEDAL_TOP);
        c.clipRect(0f, PEDAL_H - lit * vStep, PEDAL_W, PEDAL_H);
        c.drawPath(pedalSegments, fill);
        c.restore();
    }

    private void drawBars(Canvas c) {
        DashModel m = model;
        temperature(c, 0, m.coolantValid, m.coolantC, m.coolantLevel, 40f, 130f);
        temperature(c, 1, m.oilValid, m.oilC, m.oilLevel, 40f, 150f);

        // Torque: bipolar around the zero segment; negative (engine braking) in grey.
        if (m.torqueValid) {
            int k = segmentsFor(m.torqueNm, TORQUE_MIN, TORQUE_MAX);
            if (m.torqueNm >= 0f) {
                fill.setColor(VALUE);
                litSpan(c, hBarSegments, BAR_X, BAR_Y[2], hStep, TORQUE_ZERO_SEGMENT,
                        Math.max(k, TORQUE_ZERO_SEGMENT));
            } else {
                fill.setColor(LABEL);
                litSpan(c, hBarSegments, BAR_X, BAR_Y[2], hStep, Math.min(k, TORQUE_ZERO_SEGMENT),
                        TORQUE_ZERO_SEGMENT);
            }
            value(c, m.torqueNm, unitTorqueW, 2, VALUE);
        } else {
            noValue(c, unitTorqueW, 2);
        }

        if (m.powerValid) {
            fill.setColor(VALUE);
            litSpan(c, hBarSegments, BAR_X, BAR_Y[3], hStep, 0, segmentsFor(m.powerKw, 0f, POWER_MAX));
            value(c, m.powerKw, unitPowerW, 3, VALUE);
        } else {
            noValue(c, unitPowerW, 3);
        }
        if (m.powerPeak > 0f) {
            stroke.setStrokeWidth(2f);
            stroke.setColor(VALUE);
            float x = BAR_X + BAR_W * clamp(m.powerPeak / POWER_MAX, 0f, 1f);
            c.drawLine(x, BAR_Y[3] - 4f, x, BAR_Y[3] + BAR_H + 4f, stroke);
        }
    }

    private void temperature(Canvas c, int row, boolean valid, float celsius, int level,
            float min, float max) {
        if (!valid) {
            noValue(c, unitDegreesW, row);
            return;
        }
        int color = levelColor(level);
        fill.setColor(color);
        litSpan(c, hBarSegments, BAR_X, BAR_Y[row], hStep, 0, segmentsFor(celsius, min, max));
        value(c, celsius, unitDegreesW, row, color);
    }

    private void drawBottom(Canvas c) {
        DashModel m = model;
        for (int i = 0; i < 4; i++) {
            if (m.tireValid[i]) {
                int n = NumberText.formatOneDecimal(m.tirePsi[i], number);
                label(c, number, n, TIRE_X[i], TIRE_Y[i], 28f, m.tireWarn[i] ? WARN : VALUE,
                        Paint.Align.LEFT);
            } else {
                label(c, NO_DATA, 2, TIRE_X[i], TIRE_Y[i], 28f, DIM, Paint.Align.LEFT);
            }
        }

        if (m.rangeValid) {
            int color = m.rangeWarn ? WARN : VALUE;
            fill.setColor(color);
            litSpan(c, rangeSegments, RANGE_X, RANGE_Y, rangeStep, 0,
                    segmentsFor(m.rangeKm, 0f, RANGE_FULL));
            int n = NumberText.formatInt(m.rangeKm, number);
            label(c, number, n, 542f - unitKmW, 410f, 32f, color, Paint.Align.RIGHT);
        } else {
            label(c, NO_DATA, 2, 542f - unitKmW, 410f, 32f, DIM, Paint.Align.RIGHT);
        }

        if (m.gValid) {
            int n = NumberText.formatTwoDecimals(m.gTotal, buffer);
            buffer[n++] = ' ';
            buffer[n++] = 'g';
            label(c, buffer, n, 578f, 412f, 32f, VALUE, Paint.Align.LEFT);
            fill.setColor(VALUE);
            c.drawCircle(G_CX + clamp(m.gLat, -1f, 1f) * G_R, G_CY - clamp(m.gLon, -1f, 1f) * G_R,
                    7f, fill);
        } else {
            label(c, NO_DATA, 2, 578f, 412f, 32f, DIM, Paint.Align.LEFT);
        }
        int n = copy(maxPrefix, buffer, 0);
        if (m.gPeak > 0f) {
            n += NumberText.formatTwoDecimals(m.gPeak, number);
            System.arraycopy(number, 0, buffer, maxPrefix.length, n - maxPrefix.length);
            buffer[n++] = ' ';
            buffer[n++] = 'g';
            if (m.gPeak > 0.02f) {
                stroke.setStrokeWidth(2f);
                stroke.setColor(DIM);
                c.drawCircle(G_CX, G_CY, G_R * clamp(m.gPeak, 0f, 1f), stroke);
            }
        } else {
            buffer[n++] = '-';
            buffer[n++] = '-';
        }
        label(c, buffer, n, 578f, 438f, 14f, LABEL, Paint.Align.LEFT);
    }

    private void value(Canvas c, float v, float unitWidth, int row, int color) {
        int n = NumberText.formatInt(v, number);
        label(c, number, n, VALUE_RIGHT - unitWidth, LABEL_Y[row] + 2f, 24f, color, Paint.Align.RIGHT);
    }

    private void noValue(Canvas c, float unitWidth, int row) {
        label(c, NO_DATA, 2, VALUE_RIGHT - unitWidth, LABEL_Y[row] + 2f, 24f, DIM, Paint.Align.RIGHT);
    }

    // Draws the precomputed segments of a bar clipped to [from, to) segments.
    private void litSpan(Canvas c, Path segments, float x, float y, float step, int from, int to) {
        if (to <= from) {
            return;
        }
        c.save();
        c.translate(x, y);
        c.clipRect(from * step, 0f, to * step - SEG_GAP, BAR_H);
        c.drawPath(segments, fill);
        c.restore();
    }

    private static int segmentsFor(float v, float min, float max) {
        return Math.round(H_SEGMENTS * clamp((v - min) / (max - min), 0f, 1f));
    }

    private static int levelColor(int level) {
        switch (level) {
            case DashModel.LEVEL_COLD:
                return COLD;
            case DashModel.LEVEL_WARN:
                return WARN;
            case DashModel.LEVEL_DANGER:
                return DANGER;
            default:
                return VALUE;
        }
    }

    private static int copy(char[] src, char[] dst, int at) {
        System.arraycopy(src, 0, dst, at, src.length);
        return at + src.length;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }

    // ---------------------------------------------------------------- touch

    // Holding the RPM ring for RESET_HOLD_MS clears the peaks. Leaving the ring or a
    // cancelled gesture abandons the hold.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                holdingRing = insideRing(event);
                downTime = event.getEventTime();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (holdingRing && !insideRing(event)) {
                    holdingRing = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (holdingRing && insideRing(event)
                        && event.getEventTime() - downTime >= RESET_HOLD_MS) {
                    performClick();
                }
                holdingRing = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                holdingRing = false;
                return true;
            default:
                return true;
        }
    }

    private boolean insideRing(MotionEvent event) {
        float dx = (event.getX() - offsetX) / scale - RING_CX;
        float dy = (event.getY() - offsetY) / scale - RING_CY;
        return dx * dx + dy * dy <= (RING_R + 10f) * (RING_R + 10f);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        model.resetPeaks();
        invalidate();
        return true;
    }
}
