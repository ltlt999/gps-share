package com.gpsbt.transfer.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

/** 圆形脉冲主按钮：未启动为青色呼吸光环，传输中为橙色扩散波纹。 */
public class PulseButtonView extends View {

    public enum State { IDLE, RUNNING }

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density = getResources().getDisplayMetrics().density;
    private final int idleColor = ContextCompat.getColor(getContext(), R.color.color_primary);
    private final int runningColor = ContextCompat.getColor(getContext(), R.color.color_warning);
    private final int textColor = ContextCompat.getColor(getContext(), R.color.color_text_primary);

    private LinearGradient cachedGradient;
    private float cachedRadius;
    private int cachedColor;

    private ValueAnimator animator;
    private float phase;
    private State state = State.IDLE;
    private String idleText;
    private String runningText;

    public PulseButtonView(Context context) {
        super(context);
        init(null);
    }

    public PulseButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        idleText = getContext().getString(R.string.home_btn_start);
        runningText = getContext().getString(R.string.home_btn_stop);
        if (attrs != null) {
            TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.PulseButtonView);
            String customIdle = array.getString(R.styleable.PulseButtonView_pbIdleText);
            if (customIdle != null) {
                idleText = customIdle;
            }
            String customRunning = array.getString(R.styleable.PulseButtonView_pbRunningText);
            if (customRunning != null) {
                runningText = customRunning;
            }
            state = array.getInt(R.styleable.PulseButtonView_pbState, 0) == 1
                    ? State.RUNNING : State.IDLE;
            array.recycle();
        }

        corePaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * density);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(2f * density);
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(15f * density);
        setClickable(true);
        startAnimation();
    }

    public void setState(State value) {
        state = value == null ? State.IDLE : value;
        invalidate();
    }

    public State state() {
        return state;
    }

    private void startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = animation.getAnimatedFraction();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int size;
        if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
            size = Math.min(widthSize, heightSize);
        } else if (widthMode == MeasureSpec.EXACTLY) {
            size = widthSize;
        } else if (heightMode == MeasureSpec.EXACTLY) {
            size = heightSize;
        } else {
            size = (int) (140f * density);
        }
        if (size <= 0) {
            size = (int) (140f * density);
        }
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float outer = Math.min(getWidth(), getHeight()) / 2f - 4f * density;
        float radius = outer * 0.66f;
        int color = state == State.RUNNING ? runningColor : idleColor;

        if (state == State.RUNNING) {
            for (int i = 0; i < 2; i++) {
                float progress = (phase + i * 0.5f) % 1f;
                pulsePaint.setColor(withAlpha(color, (int) (180 * (1f - progress))));
                canvas.drawCircle(centerX, centerY, radius * (1f + 0.55f * progress), pulsePaint);
            }
        } else {
            float breathe = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2);
            pulsePaint.setColor(withAlpha(color, (int) (40 + 90 * (1f - breathe))));
            canvas.drawCircle(centerX, centerY, radius * (1f + 0.10f * breathe), pulsePaint);
        }

        if (cachedGradient == null || cachedRadius != radius || cachedColor != color) {
            cachedRadius = radius;
            cachedColor = color;
            cachedGradient = new LinearGradient(
                    -radius, -radius, radius, radius,
                    withAlpha(color, 255), withAlpha(mix(color, Color.BLACK, 0.45f), 255),
                    Shader.TileMode.CLAMP);
        }
        canvas.save();
        canvas.translate(centerX, centerY);
        corePaint.setShader(cachedGradient);
        canvas.drawCircle(0, 0, radius, corePaint);
        canvas.restore();
        corePaint.setShader(null);

        ringPaint.setColor(color);
        canvas.drawCircle(centerX, centerY, radius, ringPaint);

        String label = state == State.RUNNING ? runningText : idleText;
        canvas.drawText(label, centerX,
                centerY - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private static int mix(int first, int second, float ratio) {
        return Color.rgb(
                (int) (Color.red(first) + (Color.red(second) - Color.red(first)) * ratio),
                (int) (Color.green(first) + (Color.green(second) - Color.green(first)) * ratio),
                (int) (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * ratio));
    }
}
