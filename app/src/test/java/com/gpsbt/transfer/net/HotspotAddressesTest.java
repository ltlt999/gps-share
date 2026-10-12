package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HotspotAddressesTest {

    private static HotspotAddresses.Candidate candidate(String ifName, String ip) {
        return new HotspotAddresses.Candidate(ifName, ip);
    }

    @Test
    public void emptyListYieldsNull() {
        assertNull(HotspotAddresses.pickLikelyHotspot(
                Collections.<HotspotAddresses.Candidate>emptyList()));
        assertNull(HotspotAddresses.pickLikelyHotspot(null));
    }

    @Test
    public void accessPointInterfaceWins() {
        List<HotspotAddresses.Candidate> candidates = new ArrayList<>(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("ap0", "192.168.43.1")));

        assertEquals("192.168.43.1", HotspotAddresses.pickLikelyHotspot(candidates).ip);
    }

    @Test
    public void softApAndSwlanNamesAreRecognised() {
        assertEquals("10.0.0.1", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("softap0", "10.0.0.1"))).ip);
        assertEquals("10.0.0.2", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("swlan0", "10.0.0.2"))).ip);
    }

    @Test
    public void gatewayShapedAddressBeatsPlainWifi() {
        assertEquals("192.168.43.1", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("wlan1", "192.168.43.1"))).ip);
    }

    @Test
    public void fallsBackToFirstCandidateWhenNothingMatches() {
        HotspotAddresses.Candidate picked = HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("rmnet0", "10.1.2.3")));

        assertEquals("192.168.1.23", picked.ip);
    }

    @Test
    public void loopbackIsNeverPicked() {
        HotspotAddresses.Candidate picked = HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("lo", "127.0.0.1"),
                candidate("wlan0", "192.168.1.23")));

        assertEquals("192.168.1.23", picked.ip);
    }
}
