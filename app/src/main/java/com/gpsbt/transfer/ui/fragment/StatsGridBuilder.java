package com.gpsbt.transfer.ui.fragment;

import android.view.ViewGroup;
import android.widget.GridLayout;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.view.StatCardView;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在主页 GridLayout 中填充 10 张数据卡片。 */
final class StatsGridBuilder {

    static final String KEY_FIX = "fix";
    static final String KEY_SATELLITES = "satellites";
    static final String KEY_LATITUDE = "latitude";
    static final String KEY_LONGITUDE = "longitude";
    static final String KEY_SPEED = "speed";
    static final String KEY_BEARING = "bearing";
    static final String KEY_ALTITUDE = "altitude";
    static final String KEY_SENTENCES = "sentences";
    static final String KEY_RATE = "rate";
    static final String KEY_DEVICE = "device";
    static final String KEY_UPTIME = "uptime";

    private StatsGridBuilder() {
    }

    static void build(GridLayout grid, Map<String, StatCardView> target) {
        // 每次重建视图都会调到这里，而 cards 是 Fragment 的字段、跨视图存活。
        // 先清干净：目前键固定所以 put 会覆盖，但键集合一旦变化就会残留上一轮
        // 的卡片，且旧的 View 已经不在布局里，表现为「某张卡永远不更新」。
        target.clear();
        grid.removeAllViews();
        add(grid, target, KEY_FIX, R.string.stat_fix, R.drawable.ic_stat_location,
                R.string.value_no_fix);
        add(grid, target, KEY_SATELLITES, R.string.stat_satellites, R.drawable.ic_nav_satellite,
                R.string.value_unknown);
        add(grid, target, KEY_LATITUDE, R.string.stat_latitude, R.drawable.ic_stat_location,
                R.string.value_unknown);
        add(grid, target, KEY_LONGITUDE, R.string.stat_longitude, R.drawable.ic_stat_location,
                R.string.value_unknown);
        add(grid, target, KEY_SPEED, R.string.stat_speed, R.drawable.ic_stat_speed,
                R.string.value_unknown);
        add(grid, target, KEY_BEARING, R.string.stat_bearing, R.drawable.ic_stat_compass,
                R.string.value_unknown);
        add(grid, target, KEY_ALTITUDE, R.string.stat_altitude, R.drawable.ic_stat_altitude,
                R.string.value_unknown);
        add(grid, target, KEY_SENTENCES, R.string.stat_sentences, R.drawable.ic_stat_send, "0");
        add(grid, target, KEY_RATE, R.string.stat_rate, R.drawable.ic_stat_speed, "0");
        add(grid, target, KEY_DEVICE, R.string.stat_device, R.drawable.ic_stat_bluetooth,
                R.string.value_unknown);
        add(grid, target, KEY_UPTIME, R.string.stat_uptime, R.drawable.ic_stat_uptime,
                R.string.value_unknown);
    }

    private static void add(GridLayout grid, Map<String, StatCardView> target, String key,
                            int titleRes, int iconRes, Object value) {
        StatCardView card = new StatCardView(grid.getContext());
        card.setTitle(grid.getContext().getString(titleRes));
        card.setIcon(iconRes);
        if (value instanceof Integer) {
            card.setValue(grid.getContext().getString((Integer) value));
        } else {
            card.setValue(String.valueOf(value));
        }
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(6, 6, 6, 6);
        grid.addView(card, params);
        target.put(key, card);
    }
}
