package com.gpsbt.transfer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SatelliteOrderTest {

    private static SatelliteRadarView.Sat sat(int prn, int snr, boolean used) {
        return new SatelliteRadarView.Sat(prn, 45, 180, snr, used, "GP");
    }

    private static List<Integer> prnsOf(List<SatelliteRadarView.Sat> list) {
        List<Integer> prns = new ArrayList<>();
        for (SatelliteRadarView.Sat sat : list) {
            prns.add(sat.prn);
        }
        return prns;
    }

    @Test
    public void usedSatellitesComeFirst() {
        List<SatelliteRadarView.Sat> list = new ArrayList<>();
        list.add(sat(1, 45, false));   // 信噪比最高但没在用
        list.add(sat(2, 20, true));    // 在用的信号更弱
        list.add(sat(3, 30, true));

        SatelliteOrder.sort(list);

        assertEquals(java.util.Arrays.asList(3, 2, 1), prnsOf(list));
    }

    @Test
    public void withinEachGroupHigherSnrComesFirst() {
        List<SatelliteRadarView.Sat> list = new ArrayList<>();
        list.add(sat(1, 20, true));
        list.add(sat(2, 45, true));
        list.add(sat(3, 30, true));
        list.add(sat(4, 12, false));
        list.add(sat(5, 38, false));

        SatelliteOrder.sort(list);

        assertEquals(java.util.Arrays.asList(2, 3, 1, 5, 4), prnsOf(list));
    }

    @Test
    public void equalSnrKeepsOriginalArrivalOrder() {
        List<SatelliteRadarView.Sat> list = new ArrayList<>();
        list.add(sat(10, 30, true));
        list.add(sat(11, 30, true));
        list.add(sat(12, 30, true));

        SatelliteOrder.sort(list);

        // 稳定排序：完全相同时不能因为每次刷新就重新洗牌
        assertEquals(java.util.Arrays.asList(10, 11, 12), prnsOf(list));
    }

    @Test
    public void satellitesWithoutSignalSinkToTheBottom() {
        List<SatelliteRadarView.Sat> list = new ArrayList<>();
        list.add(sat(1, 0, false));
        list.add(sat(2, 25, false));
        list.add(sat(3, 0, true));

        SatelliteOrder.sort(list);

        assertEquals(java.util.Arrays.asList(3, 2, 1), prnsOf(list));
    }

    @Test
    public void emptyAndSingleElementListsAreSafe() {
        SatelliteOrder.sort(null);

        List<SatelliteRadarView.Sat> empty = new ArrayList<>();
        SatelliteOrder.sort(empty);
        assertTrue(empty.isEmpty());

        List<SatelliteRadarView.Sat> one = new ArrayList<>(Collections.singletonList(sat(7, 10, true)));
        SatelliteOrder.sort(one);
        assertEquals(1, one.size());
        assertEquals(7, one.get(0).prn);
    }
}
