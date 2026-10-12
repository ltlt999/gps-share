package com.gpsbt.transfer.nmea;

import java.util.EnumSet;
import java.util.Set;

/** 用户可开关的 NMEA 语句类型。 */
public enum NmeaSentenceType {
    GGA, RMC, GSA, GSV, VTG;

    public static Set<NmeaSentenceType> all() {
        return EnumSet.allOf(NmeaSentenceType.class);
    }
}
