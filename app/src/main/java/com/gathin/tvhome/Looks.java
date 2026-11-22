package com.gathin.tvhome;

import android.content.SharedPreferences;

/**
 * The three ready-made looks of Home shown on the Looks screen. Each look is a full set of the choices that
 * really change the screen: the scene or the classic grid, the tile size, rows under the dock, the wallpaper
 * and how much it moves. The clock is not part of a look. Kept apart from the views so it can be tested.
 */
final class Looks {
    /** Motion levels: no animations; animations; animations with the advanced effects. */
    static final int MOTION_CALM = 0, MOTION_SMOOTH = 1, MOTION_LIVE = 2;

    static final class Look {
        final int name;
        final boolean scene;
        final int columns;
        final int rows;
        /** Config.WALLPAPER_CHOICE_KEY: null leaves it to the theme (rotation), "none" switches it off. */
        final String wallpaper;
        final int motion;

        Look(int name, boolean scene, int columns, int rows, String wallpaper, int motion) {
            this.name = name;
            this.scene = scene;
            this.columns = columns;
            this.rows = rows;
            this.wallpaper = wallpaper;
            this.motion = motion;
        }

        void apply(SharedPreferences.Editor editor) {
            editor.putBoolean(Config.SCENE_HOME_KEY, scene)
                    .putInt("columns", columns)
                    .putInt(Config.ROWS_BELOW_DOCK_KEY, rows)
                    .putBoolean("animations", animations(motion))
                    .putBoolean("advanced_effects", advanced(motion));
            if (wallpaper == null) editor.remove(Config.WALLPAPER_CHOICE_KEY);
            else editor.putString(Config.WALLPAPER_CHOICE_KEY, wallpaper);
        }
    }

    static final Look[] ALL = {
            new Look(R.string.look_cinema, true, 4, 0, null, MOTION_SMOOTH),
            new Look(R.string.look_quiet, true, 5, 0, "none", MOTION_CALM),
            new Look(R.string.look_everything, false, 6, 2, null, MOTION_SMOOTH),
    };

    private Looks() {}

    /** The look these choices make, or -1 when something was changed by hand. */
    static int match(boolean scene, int columns, int rows, String wallpaper, boolean animations, boolean advanced) {
        int motion = motion(animations, advanced);
        for (int i = 0; i < ALL.length; i++) {
            Look look = ALL[i];
            if (look.scene == scene && look.columns == columns && look.rows == rows && look.motion == motion
                    && (look.wallpaper == null ? wallpaper == null : look.wallpaper.equals(wallpaper))) return i;
        }
        return -1;
    }

    /** The advanced effects only run with animations on, so effects without animations read as calm. */
    static int motion(boolean animations, boolean advanced) {
        if (!animations) return MOTION_CALM;
        return advanced ? MOTION_LIVE : MOTION_SMOOTH;
    }

    static boolean animations(int motion) {
        return motion != MOTION_CALM;
    }

    static boolean advanced(int motion) {
        return motion == MOTION_LIVE;
    }
}
