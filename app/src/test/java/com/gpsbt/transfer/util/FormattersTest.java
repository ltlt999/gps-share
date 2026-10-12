package com.gpsbt.transfer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FormattersTest {

    @Test
    public void latitudeHasHemisphereAndSymbol() {
        assertEquals("31.230416° N", Formatters.latitude(31.230416));
        assertEquals("33.856833° S", Formatters.latitude(-33.856833));
    }

    @Test
    public void longitudeHasHemisphereAndSymbol() {
        assertEquals("121.473701° E", Formatters.longitude(121.473701));
        assertEquals("118.210083° W", Formatters.longitude(-118.210083));
    }

    @Test
    public void speedConvertedToKmh() {
        assertEquals("18.5 km/h", Formatters.speedKmh(5.14444));
        assertEquals("0.0 km/h", Formatters.speedKmh(0));
        assertEquals("NaN", Formatters.speedKmh(Double.NaN));
    }

    @Test
    public void bearingAltitudeAndAccuracy() {
        assertEquals("87.0°", Formatters.bearing(87.0));
        assertEquals("12.5 m", Formatters.altitude(12.5));
        assertEquals("±3.0 m", Formatters.accuracy(3.0f));
        assertEquals("--", Formatters.accuracy(Float.NaN));
    }

    @Test
    public void durationFormatsAsClock() {
        assertEquals("00:00:00", Formatters.duration(0));
        assertEquals("00:01:23", Formatters.duration(83000));
        assertEquals("01:02:03", Formatters.duration(3723000));
        assertEquals("--", Formatters.duration(-1));
    }
}
