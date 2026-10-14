package com.gpsbt.transfer.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class NmeaLogTest {

    @Before
    public void setUp() {
        LogBus.get().clear();
    }

    @Test
    public void gsvAndGsaAreLoggedAsSatellite() {
        NmeaLog.sentence("$GPGSV,1,1,01,03,45,120,42*7B");
        NmeaLog.sentence("$GPGSA,A,3,03,,,,,,,,,,,1.8,1.0,1.5*33");
        NmeaLog.sentence("$BDGSV,1,1,01,10,30,200,35*5A");

        for (LogBus.Entry entry : LogBus.get().entries()) {
            assertEquals(LogBus.Level.SAT, entry.level);
        }
    }

    @Test
    public void positionSentencesAreLoggedAsData() {
        NmeaLog.sentence("$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47");
        NmeaLog.sentence("$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,,,*6A");
        NmeaLog.sentence("$GPVTG,054.7,T,,000.0,N,000.0,K*XX");

        List<LogBus.Entry> entries = LogBus.get().entries();
        assertEquals(3, entries.size());
        for (LogBus.Entry entry : entries) {
            assertEquals(LogBus.Level.DATA, entry.level);
        }
    }

    @Test
    public void entriesKeepTheirArrivalOrder() {
        NmeaLog.sentence("$GPGGA,1*XX");
        NmeaLog.sentence("$GPGSV,1*XX");
        NmeaLog.sentence("$GPRMC,1*XX");

        List<LogBus.Entry> entries = LogBus.get().entries();
        assertEquals(LogBus.Level.DATA, entries.get(0).level);
        assertEquals(LogBus.Level.SAT, entries.get(1).level);
        assertEquals(LogBus.Level.DATA, entries.get(2).level);
    }

    @Test
    public void sentenceIsTrimmedBeforeLogging() {
        NmeaLog.sentence("  $GPGGA,1*XX  ");

        assertEquals("$GPGGA,1*XX", LogBus.get().entries().get(0).message);
    }

    @Test
    public void nullAndBlankLinesAreIgnored() {
        NmeaLog.sentence(null);
        NmeaLog.sentence("");
        NmeaLog.sentence("   ");

        assertEquals(0, LogBus.get().entries().size());
    }

    @Test
    public void isSatelliteRecognisesOnlyGsvAndGsa() {
        assertTrue(NmeaLog.isSatellite("$GPGSV,1,1,01*XX"));
        assertTrue(NmeaLog.isSatellite("$GPGSA,A,3*XX"));
        assertFalse(NmeaLog.isSatellite("$GPGGA,1*XX"));
        assertFalse(NmeaLog.isSatellite("$GPRMC,1*XX"));
        assertFalse(NmeaLog.isSatellite("$GPZDA,1*XX"));
    }
}
