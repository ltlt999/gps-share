package com.gpsbt.transfer.ui;

import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 卫星明细的显示顺序。
 *
 * 规则：**正在用于定位的排在前面**，同组内按信噪比从高到低。
 *
 * 理由：用户最关心的是「现在哪几颗星在给我定位、信号好不好」，而不是
 * 一份按星座或编号排列的完整清单——好用的先看到，没在用的沉下去。
 */
public final class SatelliteOrder {

    /** 用于定位的优先；其次信噪比高的优先；都相同则保持原顺序。 */
    public static final Comparator<SatelliteRadarView.Sat> BY_USED_THEN_SNR =
            new Comparator<SatelliteRadarView.Sat>() {
                @Override
                public int compare(SatelliteRadarView.Sat left, SatelliteRadarView.Sat right) {
                    if (left.used != right.used) {
                        return left.used ? -1 : 1;
                    }
                    return Integer.compare(right.snr, left.snr);
                }
            };

    private SatelliteOrder() {
    }

    /**
     * 就地排序。
     *
     * 用 {@link Collections#sort}（稳定排序），因此信噪比与 used 都相同的卫星
     * 会保持原来的到达顺序，不会在每次刷新时乱跳。
     */
    public static void sort(List<SatelliteRadarView.Sat> satellites) {
        if (satellites == null || satellites.size() < 2) {
            return;
        }
        Collections.sort(satellites, BY_USED_THEN_SNR);
    }
}
