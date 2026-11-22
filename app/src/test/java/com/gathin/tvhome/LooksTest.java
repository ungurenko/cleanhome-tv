package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the Looks screen: which look the saved choices make and how motion maps to two keys. */
public class LooksTest {

    @Test
    public void everyLookIsRecognisedFromItsOwnChoices() {
        for (int i = 0; i < Looks.ALL.length; i++) {
            Looks.Look look = Looks.ALL[i];
            assertEquals(i, Looks.match(look.scene, look.columns, look.rows, look.wallpaper,
                    Looks.animations(look.motion), Looks.advanced(look.motion)));
        }
    }

    @Test
    public void oneChoiceChangedByHandMatchesNoLook() {
        Looks.Look cinema = Looks.ALL[0];
        assertEquals(-1, Looks.match(cinema.scene, cinema.columns + 1, cinema.rows, cinema.wallpaper, true, false));
        assertEquals(-1, Looks.match(cinema.scene, cinema.columns, cinema.rows, "picture-3.jpg", true, false));
        assertEquals(-1, Looks.match(!cinema.scene, cinema.columns, cinema.rows, cinema.wallpaper, true, false));
        assertEquals(-1, Looks.match(cinema.scene, cinema.columns, cinema.rows, cinema.wallpaper, true, true));
    }

    @Test
    public void motionRoundTripsThroughTheTwoKeys() {
        for (int motion = Looks.MOTION_CALM; motion <= Looks.MOTION_LIVE; motion++) {
            assertEquals(motion, Looks.motion(Looks.animations(motion), Looks.advanced(motion)));
        }
        assertFalse(Looks.animations(Looks.MOTION_CALM));
        assertTrue(Looks.advanced(Looks.MOTION_LIVE));
    }

    @Test
    public void effectsWithoutAnimationsReadAsCalm() {
        assertEquals(Looks.MOTION_CALM, Looks.motion(false, true));
    }
}
