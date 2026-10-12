package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NmeaChecksumTest {

    @Test
    public void knownGgaExample_checksumIs47() {
        String body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
        assertEquals(0x47, NmeaChecksum.xor(body));
        assertEquals("47", NmeaChecksum.toHex(0x47));
    }

    @Test
    public void knownRmcExample_checksumIs6A() {
        String body = "GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W";
        assertEquals("6A", NmeaChecksum.toHex(NmeaChecksum.xor(body)));
    }

    @Test
    public void hexIsAlwaysTwoUpperDigits() {
        assertEquals("00", NmeaChecksum.toHex(0));
        assertEquals("0F", NmeaChecksum.toHex(15));
        assertEquals("FF", NmeaChecksum.toHex(0x1FF));
        assertEquals("A0", NmeaChecksum.toHex(-96));
    }

    @Test
    public void withChecksum_appendsStarAndCrLf() {
        String sentence = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
        assertEquals(sentence + "*47\r\n", NmeaChecksum.withChecksum(sentence));
    }
}
