package com.gpsbt.transfer.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

import java.util.ArrayList;
import java.util.List;

/** 卫星天空雷达：同心圆表示仰角，卫星按方位/仰角定位，柱高表示信噪比。 */
public class SatelliteRadarView extends View {

    public static final class Sat {
        public final int prn;
        public final int elevationDeg;
        public final int azimuthDeg;
        public final int snr;
        public final boolean used;
        /** 星座 talker（GP/BD/GL/GA/GQ/GI），用于在明细里显示对应国旗；可能为 null。 */
        public final String talker;

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used) {
            this(prn, elevationDeg, azimuthDeg, snr, used, null);
        }

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used,
                   String talker) {
            this.prn = prn;
            this.elevationDeg = elevationDeg;
            this.azimuthDeg = azimuthDeg;
            this.snr = snr;
            this.used = used;
            this.talker = talker;
        }
    }

    private final List<Sat> satellites = new ArrayList<Sat>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint usedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unusedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density = getResources().getDisplayMetrics().density;

    public SatelliteRadarView(Context context) {
        super(context);
        init();
    }

    public SatelliteRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_card_stroke));
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1f * density);
        crossPaint.setColor(0x33FFFFFF);
        usedPaint.setStyle(Paint.Style.FILL);
        usedPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_primary));
        unusedPaint.setStyle(Paint.Style.FILL);
        unusedPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_accent_blue));
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeWidth(3f * density);
        barPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_primary));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(9f * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        emptyPaint.setTextSize(13f * density);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void submit(List<Sat> incoming) {
        satellites.clear();
        if (incoming != null) {
            satellites.addAll(incoming);
        }
        postInvalidate();
    }

    public void clear() {
        satellites.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - 14f * density;
        if (radius <= 0) {
            return;
        }

        for (int ring = 1; ring <= 3; ring++) {
            canvas.drawCircle(centerX, centerY, radius * ring / 3f, gridPaint);
        }
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, crossPaint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, crossPaint);
        canvas.drawText("北", centerX, centerY - radius - 3f * density, textPaint);

        if (satellites.isEmpty()) {
            canvas.drawText(getContext().getString(R.string.satellite_radar_empty),
                    centerX, centerY + emptyPaint.getTextSize() / 2f, emptyPaint);
            return;
        }

        for (Sat sat : satellites) {
            double azimuth = Math.toRadians(sat.azimuthDeg);
            float distance = radius * (1f - Math.max(0, Math.min(90, sat.elevationDeg)) / 90f) * 0.96f;
            float x = centerX + (float) (distance * Math.sin(azimuth));
            float y = centerY - (float) (distance * Math.cos(azimuth));
            float barLength = 6f * density + (sat.snr / 50f) * 18f * density;
            barPaint.setColor(sat.used
                    ? ContextCompat.getColor(getContext(), R.color.color_primary)
                    : ContextCompat.getColor(getContext(), R.color.color_accent_blue));
            canvas.drawLine(x, y, x, y - barLength, barPaint);
            canvas.drawCircle(x, y, 4f * density, sat.used ? usedPaint : unusedPaint);
        }
    }
}
