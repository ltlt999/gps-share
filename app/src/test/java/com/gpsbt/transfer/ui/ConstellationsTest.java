package com.gpsbt.transfer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.gpsbt.transfer.R;

import org.junit.Test;

public class ConstellationsTest {

    @Test
    public void knownTalkersMapToTheirOwnFlags() {
        assertEquals(R.drawable.ic_flag_gp, Constellations.flagResOf("GP"));
        assertEquals(R.drawable.ic_flag_bd, Constellations.flagResOf("BD"));
        assertEquals(R.drawable.ic_flag_gl, Constellations.flagResOf("GL"));
        assertEquals(R.drawable.ic_flag_ga, Constellations.flagResOf("GA"));
        assertEquals(R.drawable.ic_flag_gq, Constellations.flagResOf("GQ"));
        assertEquals(R.drawable.ic_flag_gi, Constellations.flagResOf("GI"));
    }

    @Test
    public void sixConstellationsHaveSixDistinctFlags() {
        int[] flags = {
                Constellations.flagResOf("GP"),
                Constellations.flagResOf("BD"),
                Constellations.flagResOf("GL"),
                Constellations.flagResOf("GA"),
                Constellations.flagResOf("GQ"),
                Constellations.flagResOf("GI"),
        };
        for (int i = 0; i < flags.length; i++) {
            for (int j = i + 1; j < flags.length; j++) {
                assertNotEquals("第 " + i + " 与第 " + j + " 个星座的旗标不能相同",
                        flags[i], flags[j]);
            }
        }
    }

    @Test
    public void talkerIsCaseInsensitiveAndTrimmed() {
        assertEquals(R.drawable.ic_flag_gp, Constellations.flagResOf("gp"));
        assertEquals(R.drawable.ic_flag_bd, Constellations.flagResOf(" Bd "));
    }

    @Test
    public void unknownOrMissingTalkerFallsBackToNeutralFlag() {
        assertEquals(R.drawable.ic_flag_unknown, Constellations.flagResOf(null));
        assertEquals(R.drawable.ic_flag_unknown, Constellations.flagResOf(""));
        assertEquals(R.drawable.ic_flag_unknown, Constellations.flagResOf("XX"));
        assertEquals(R.drawable.ic_flag_unknown, Constellations.flagResOf("SBAS"));
    }
}
