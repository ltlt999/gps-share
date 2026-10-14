package com.gpsbt.transfer.ui.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

/** 数据卡片：图标 + 标题 + 等宽数值 + 单位。 */
public class StatCardView extends LinearLayout {

    private final TextView titleView = new TextView(getContext());
    private final TextView valueView = new TextView(getContext());
    private final TextView unitView = new TextView(getContext());
    private final ImageView iconView = new ImageView(getContext());
    private int accent = ContextCompat.getColor(getContext(), R.color.color_primary);

    public StatCardView(Context context) {
        super(context);
        init(null);
    }

    public StatCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.card_background);
        int padding = dp(16);
        setPadding(padding, padding, padding, padding);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconParams.setMargins(0, 0, dp(6), 0);
        iconView.setLayoutParams(iconParams);

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(iconView);
        titleRow.addView(titleView);
        addView(titleRow);

        LinearLayout valueRow = new LinearLayout(getContext());
        valueRow.setOrientation(HORIZONTAL);
        valueRow.setGravity(Gravity.BOTTOM);
        valueView.setLayoutParams(
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        valueRow.addView(valueView);
        valueRow.addView(unitView);
        LinearLayout.LayoutParams valueRowParams =
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        valueRowParams.setMargins(0, dp(8), 0, 0);
        addView(valueRow, valueRowParams);

        titleView.setTextSize(12);
        titleView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        valueView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_primary));
        valueView.setTypeface(android.graphics.Typeface.MONOSPACE);
        valueView.setTextSize(17);
        valueView.setMaxLines(1);
        unitView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        unitView.setTextSize(11);
        unitView.setPadding(dp(3), 0, 0, dp(2));

        if (attrs != null) {
            TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.StatCardView);
            setTitle(array.getString(R.styleable.StatCardView_cardTitle));
            setValue(array.getString(R.styleable.StatCardView_cardValue));
            setUnit(array.getString(R.styleable.StatCardView_cardUnit));
            setIcon(array.getResourceId(R.styleable.StatCardView_cardIcon, 0));
            array.recycle();
        }
    }

    public void setTitle(String text) {
        titleView.setText(text == null ? "" : text);
    }

    public void setValue(String text) {
        valueView.setText(text == null ? getContext().getString(R.string.value_unknown) : text);
    }

    /**
     * 允许数值换行。
     *
     * 默认是单行——数值（坐标、速度之类）本来就短，单行不会跳行更稳。
     * 但「连接设备」这类卡片要列出多台设备的 IP，单行且无省略号时会被
     * **硬裁掉**（表现为 192.168.1.100:60432 显示成 192.168.1.1）。
     */
    public void setValueMultiline(boolean multiline) {
        valueView.setSingleLine(!multiline);
        valueView.setMaxLines(multiline ? 6 : 1);
    }

    public void setUnit(String text) {
        unitView.setText(text == null ? "" : text);
    }

    public void setIcon(int resId) {
        if (resId == 0) {
            iconView.setVisibility(GONE);
            return;
        }
        iconView.setVisibility(VISIBLE);
        iconView.setImageResource(resId);
        iconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
    }

    public void setAccent(int color) {
        accent = color;
        titleView.setTextColor(color);
        iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
