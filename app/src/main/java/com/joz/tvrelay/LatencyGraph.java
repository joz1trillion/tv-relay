package com.joz.tvrelay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Stacked area chart of the last minute of latency, one band per source of delay. */
public class LatencyGraph extends View {
    private static final int[] COLORS = {
            Color.rgb(0x4C, 0x9A, 0xFF), // network
            Color.rgb(0xB0, 0x7C, 0xFF), // codec
            Color.rgb(0x3D, 0xC9, 0x8A), // buffer
            Color.rgb(0xFF, 0xA8, 0x40), // output
    };
    private static final String[] NAMES = {"network", "codec", "buffer", "output"};

    private final float[][] data = new float[LatencyHistory.CAPACITY][LatencyHistory.PARTS];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    public LatencyGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        grid.setColor(Color.argb(60, 255, 255, 255));
        grid.setStrokeWidth(density);
        text.setColor(Color.argb(200, 255, 255, 255));
        text.setTextSize(12 * density);
        fill.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.rgb(0x1C, 0x1C, 0x1E)); // labels are white whatever the theme
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = ReceiverService.history.snapshot(data);
        float w = getWidth();
        float h = getHeight();
        float top = 20 * density;
        float left = 36 * density;
        float plotH = h - top;
        float plotW = w - left;

        float max = 40;
        for (int i = 0; i < n; i++) max = Math.max(max, total(data[i]));
        float scale = niceCeil(max * 1.15f);

        for (int g = 0; g <= 4; g++) {
            float y = top + plotH * (1 - g / 4f);
            canvas.drawLine(left, y, w, y, grid);
            canvas.drawText(Math.round(scale * g / 4) + "", 0, y + 4 * density, text);
        }

        if (n >= 2) {
            float dx = plotW / (LatencyHistory.CAPACITY - 1);
            float x0 = w - (n - 1) * dx; // newest sample on the right edge
            // Draw the bands from the top of the stack down so each covers the one above.
            for (int part = LatencyHistory.PARTS - 1; part >= 0; part--) {
                path.reset();
                path.moveTo(x0, top + plotH);
                for (int i = 0; i < n; i++) {
                    float cum = 0;
                    for (int k = 0; k <= part; k++) cum += data[i][k];
                    path.lineTo(x0 + i * dx, top + plotH * (1 - Math.min(cum / scale, 1)));
                }
                path.lineTo(x0 + (n - 1) * dx, top + plotH);
                path.close();
                fill.setColor(COLORS[part]);
                canvas.drawPath(path, fill);
            }
        }

        // Legend with the latest value of each band.
        float[] last = n > 0 ? data[n - 1] : null;
        float x = left;
        String head = last == null ? "no data yet  " : "total " + Math.round(total(last)) + " ms  ";
        canvas.drawText(head, x, 14 * density, text);
        x += text.measureText(head);
        for (int part = 0; part < LatencyHistory.PARTS; part++) {
            fill.setColor(COLORS[part]);
            canvas.drawRect(x, 5 * density, x + 9 * density, 14 * density, fill);
            x += 12 * density;
            String label = NAMES[part] + (last == null ? "" : " " + Math.round(last[part])) + "  ";
            canvas.drawText(label, x, 14 * density, text);
            x += text.measureText(label);
        }
    }

    private static float total(float[] s) {
        float t = 0;
        for (float v : s) t += v;
        return t;
    }

    private static float niceCeil(float v) {
        float[] steps = {40, 60, 80, 100, 150, 200, 300, 400, 600, 800, 1000};
        for (float s : steps) if (v <= s) return s;
        return v;
    }
}
