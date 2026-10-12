package com.gpsbt.transfer.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class PeerTest {

    @Test
    public void peersWithSameAddressAreEqual() {
        Peer first = new Peer("车机", "192.168.43.2:10110", 1000L);
        Peer second = new Peer("平板", "192.168.43.2:10110", 2000L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void peersWithDifferentAddressAreNotEqual() {
        Peer first = new Peer("车机", "192.168.43.2:10110", 1000L);
        Peer second = new Peer("车机", "192.168.43.3:10110", 1000L);

        assertNotEquals(first, second);
    }

    @Test
    public void nullFieldsAreNormalizedToEmpty() {
        Peer peer = new Peer(null, null, 0L);

        assertEquals("", peer.name);
        assertEquals("", peer.address);
        assertEquals(new Peer(null, null, 0L), peer);
    }

    @Test
    public void peerIsNotEqualToNullOrOtherTypes() {
        Peer peer = new Peer("车机", "AA:BB:CC:DD:EE:FF", 1L);

        assertEquals(peer, peer);
        assertFalse(peer.equals(null));
        assertFalse(peer.equals("不是 Peer"));
    }
}
