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

    /**
     * 按角色填充数据卡片。
     *
     * 接收端**不创建**「发送速率」卡（它不发送，那张卡没有意义）。注意不能
     * 建了再 setVisibility(GONE)——GridLayout 仍会给 GONE 的孩子留格子，
     * 结果是在网格中间留一个洞，后面的卡被挤得七零八落。
     *
     * 「连接设备」放在**最后**且**占满两列**：WiFi 模式下要列出每台设备的
     * IP，半宽卡片放不下（单行且无省略号会被硬裁）。
     */
    static void build(GridLayout grid, Map<String, StatCardView> target, boolean receive) {
        // 每次重建视图都会调到这里，而 cards 是 Fragment 的字段、跨视图存活。
        // 先清干净：键集合会随角色变化，不清就会残留上一轮的卡片。
        target.clear();
        grid.removeAllViews();
        add(grid, target, KEY_FIX, R.string.stat_fix, R.drawable.ic_stat_location,
                R.string.value_no_fix, 1);
        add(grid, target, KEY_SATELLITES, R.string.stat_satellites, R.drawable.ic_nav_satellite,
                R.string.value_unknown, 1);
        add(grid, target, KEY_LATITUDE, R.string.stat_latitude, R.drawable.ic_stat_location,
                R.string.value_unknown, 1);
        add(grid, target, KEY_LONGITUDE, R.string.stat_longitude, R.drawable.ic_stat_location,
                R.string.value_unknown, 1);
        add(grid, target, KEY_SPEED, R.string.stat_speed, R.drawable.ic_stat_speed,
                R.string.value_unknown, 1);
        add(grid, target, KEY_BEARING, R.string.stat_bearing, R.drawable.ic_stat_compass,
                R.string.value_unknown, 1);
        add(grid, target, KEY_ALTITUDE, R.string.stat_altitude, R.drawable.ic_stat_altitude,
                R.string.value_unknown, 1);
        // 接收端这里显示的是「已接收条数」，与发送端语义不同，标题跟着换
        add(grid, target, KEY_SENTENCES,
                receive ? R.string.stat_received : R.string.stat_sentences,
                R.drawable.ic_stat_send, "0", 1);
        // 两个角色都有速率卡，只是量的是不同的东西：发送端发出去的语句，
        // 接收端收进来的定位。少了它半宽卡片会变成奇数张，末尾留空一格。
        add(grid, target, KEY_RATE,
                receive ? R.string.stat_rate_received : R.string.stat_rate,
                R.drawable.ic_stat_speed, "0", 1);
        add(grid, target, KEY_UPTIME, R.string.stat_uptime, R.drawable.ic_stat_uptime,
                R.string.value_unknown, 1);
        // 放最后、占满两列：可能列出多台设备，需要整行宽度
        StatCardView device = add(grid, target, KEY_DEVICE, R.string.stat_device,
                R.drawable.ic_stat_bluetooth, R.string.value_unknown, 2);
        device.setValueMultiline(true);
    }

    private static StatCardView add(GridLayout grid, Map<String, StatCardView> target,
                                    String key, int titleRes, int iconRes, Object value,
                                    int span) {
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
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f);
        params.setMargins(6, 6, 6, 6);
        grid.addView(card, params);
        target.put(key, card);
        return card;
    }
}
