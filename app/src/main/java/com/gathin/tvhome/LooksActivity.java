package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * The first settings screen: three ready-made looks of Home as live miniatures, applied with one OK, and a row
 * of chips for the wallpaper, the clock, motion, the fine-tuning screen (LauncherSettingsActivity) and the TV's
 * own settings. Every choice is saved at once and counted in Config.SETTINGS_REVISION_KEY, so Home rebuilds
 * however this screen is left.
 */
public final class LooksActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private static final int FINE_TUNE_REQUEST = 1;
    private static final String[] CLOCK_FORMATS = {"system", "24", "12"};
    private static final int[] CLOCK_LABELS = {
            R.string.looks_clock_auto, R.string.looks_clock_24, R.string.looks_clock_12};
    private static final int[] MOTION_LABELS = {R.string.motion_calm, R.string.motion_smooth, R.string.motion_live};

    private SharedPreferences choices;
    private final View[] cards = new View[Looks.ALL.length];
    private final TextView[] checks = new TextView[Looks.ALL.length];
    private final FrameLayout[] minis = new FrameLayout[Looks.ALL.length];
    private TextView chipWallpaper, chipClock, chipMotion;
    private Bitmap wallpaper;
    /** Config.animations as of the last refresh: the focus zoom follows it without reading the config per key. */
    private boolean animate;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_looks);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);

        LinearLayout row = findViewById(R.id.lookCards);
        LayoutInflater inflater = LayoutInflater.from(this);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Looks.ALL.length; i++) {
            final int index = i;
            View card = inflater.inflate(R.layout.item_look, row, false);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
            if (i > 0) params.setMarginStart((int) (24 * d));
            row.addView(card, params);
            ((TextView) card.findViewById(R.id.lookName)).setText(Looks.ALL[i].name);
            checks[i] = card.findViewById(R.id.lookCheck);
            minis[i] = card.findViewById(R.id.lookScreen);
            card.setOnClickListener(v -> apply(index));
            card.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.04f : 1f;
                if (animate) v.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
                else { v.setScaleX(scale); v.setScaleY(scale); }
            });
            cards[i] = card;
        }

        chipWallpaper = findViewById(R.id.chipWallpaper);
        chipClock = findViewById(R.id.chipClock);
        chipMotion = findViewById(R.id.chipMotion);
        chipWallpaper.setOnClickListener(v -> open(new Intent(this, WallpaperPickerActivity.class)));
        chipClock.setOnClickListener(v -> nextClock());
        chipMotion.setOnClickListener(v -> nextMotion());
        findViewById(R.id.chipFineTune).setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(this, LauncherSettingsActivity.class), FINE_TUNE_REQUEST);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.chipTv).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        // Up from any chip lands on the middle card; Down from a card on the first chip.
        for (View card : cards) card.setNextFocusDownId(R.id.chipWallpaper);

        refresh();
        int current = currentLook();
        cards[Math.max(0, current)].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (choices != null) refresh();
    }

    /** Fine-tuning asked to reorder apps on Home: pass that on and leave, Home enters reorder mode. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FINE_TUNE_REQUEST || resultCode != RESULT_OK) return;
        setResult(RESULT_OK, data);
        if (data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) finish();
    }

    private void apply(int index) {
        Looks.Look look = Looks.ALL[index];
        SharedPreferences.Editor editor = choices.edit();
        look.apply(editor);
        editor.apply();
        changed();
        refresh();
        Toast.makeText(this, getString(R.string.look_applied, getString(look.name)), Toast.LENGTH_SHORT).show();
    }

    private void nextClock() {
        String current = Config.load(this).clockFormat;
        int i = 0;
        while (i < CLOCK_FORMATS.length && !CLOCK_FORMATS[i].equals(current)) i++;
        choices.edit().putString(Config.CLOCK_FORMAT_KEY, CLOCK_FORMATS[(i + 1) % CLOCK_FORMATS.length]).apply();
        changed();
        refresh();
    }

    private void nextMotion() {
        Config config = Config.load(this);
        int motion = (Looks.motion(config.animations, config.advancedEffects) + 1) % MOTION_LABELS.length;
        choices.edit().putBoolean("animations", Looks.animations(motion))
                .putBoolean("advanced_effects", Looks.advanced(motion)).apply();
        changed();
        refresh();
        if (motion == Looks.MOTION_LIVE) {
            Toast.makeText(this, R.string.advanced_effects_warning, Toast.LENGTH_LONG).show();
        }
    }

    /** Same as in LauncherSettingsActivity: count the change so Home rebuilds on its next resume. */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private int currentLook() {
        Config config = Config.load(this);
        return Looks.match(config.sceneHome, config.columns, config.rowsBelowDock,
                choices.getString(Config.WALLPAPER_CHOICE_KEY, null), config.animations, config.advancedEffects);
    }

    private void refresh() {
        Config config = Config.load(this);
        animate = config.animations;
        int current = currentLook();
        for (int i = 0; i < checks.length; i++) checks[i].setVisibility(i == current ? View.VISIBLE : View.INVISIBLE);
        chipWallpaper.setText(wallpaperChip());
        int clock = 0;
        while (clock < CLOCK_FORMATS.length - 1 && !CLOCK_FORMATS[clock].equals(config.clockFormat)) clock++;
        if (!CLOCK_FORMATS[clock].equals(config.clockFormat)) clock = 0;
        chipClock.setText(getString(R.string.looks_clock, getString(CLOCK_LABELS[clock])));
        chipMotion.setText(getString(R.string.looks_motion,
                getString(MOTION_LABELS[Looks.motion(config.animations, config.advancedEffects)])));
        wallpaper = LauncherSettingsActivity.decode(LauncherSettingsActivity.currentWallpaperFile(this, choices), 480);
        for (int i = 0; i < minis.length; i++) {
            final int index = i;
            FrameLayout screen = minis[i];
            if (screen.getWidth() > 0) buildMini(screen, Looks.ALL[index], config.clockFormat);
            else screen.post(() -> buildMini(screen, Looks.ALL[index], config.clockFormat));
        }
    }

    /** The wallpaper in a few words for its chip: rotating, off, or which picture. */
    private CharSequence wallpaperChip() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (current == null) return getString(R.string.looks_wallpaper_rotation);
        if ("none".equals(current)) return getString(R.string.looks_wallpaper_none);
        java.io.File dir = Config.dir(this);
        java.util.List<java.io.File> images = dir == null ? java.util.Collections.emptyList()
                : WallpaperRotation.images(new java.io.File(dir, "wallpapers"));
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(current)) return getString(R.string.looks_wallpaper_picture, i + 1);
        }
        return LauncherSettingsActivity.wallpaperState(this, choices);
    }

    /**
     * A miniature of Home in this look, sized from the card's width: the scene's single row of cards under the
     * app's name, or the classic dock band with the rows under it.
     */
    private void buildMini(FrameLayout screen, Looks.Look look, String clockFormat) {
        screen.removeAllViews();
        int w = screen.getWidth();
        if (w <= 0) return;
        int h = w * 9 / 16;
        float pad = w * 0.06f;

        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (look.wallpaper == null && wallpaper != null) {
            back.setImageBitmap(wallpaper);
            back.setColorFilter(0x33000000);
        } else {
            back.setBackgroundColor(0xFF0A0D11);
        }
        screen.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView clock = new TextView(this);
        Date now = new Date();
        String time = "12".equals(clockFormat) ? new java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(now)
                : "24".equals(clockFormat) ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
                : DateFormat.getTimeFormat(this).format(now);
        clock.setText(time);
        clock.setTextColor(0xFFF4F8FB);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.075f);
        clock.setIncludeFontPadding(false);
        place(screen, clock, (int) pad, (int) pad, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        int n = look.columns;
        float gap = w * 0.02f;
        int tileW = (int) ((w - 2 * pad - (n - 1) * gap) / n);
        int tileH = tileW * 9 / 16;
        int[] colors = LauncherSettingsActivity.PREVIEW_COLORS;
        if (look.scene) {
            int y = (int) (h - pad - tileH);
            // The glow of the focused app's colour behind the row, as the scene floods the background.
            View glow = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setColors(new int[]{(colors[0] & 0x00FFFFFF) | 0x66000000, 0x00000000});
            g.setGradientRadius(w * 0.45f);
            g.setGradientCenter(0.3f, 0.8f);
            glow.setBackground(g);
            place(screen, glow, 0, 0, w, h);
            View title = rounded(0xE6FFFFFF, w * 0.01f);
            place(screen, title, (int) pad, (int) (y - pad * 0.6f - w * 0.035f), (int) (w * 0.3f), (int) (w * 0.035f));
            for (int i = 0; i < n; i++) {
                View tile = rounded(colors[i % colors.length], w * 0.012f);
                if (i == 0) ((GradientDrawable) tile.getBackground()).setStroke(Math.max(2, w / 160), 0xFFFFFFFF);
                else tile.setAlpha(0.55f);
                place(screen, tile, (int) (pad + i * (tileW + gap)), y, tileW, tileH);
            }
        } else {
            float rowGap = gap * 1.4f;
            int dockY = (int) (h - pad - (look.rows + 1) * tileH - look.rows * rowGap);
            View band = rounded(0x33FFFFFF, w * 0.02f);
            place(screen, band, (int) (pad - gap), (int) (dockY - gap), (int) (w - 2 * pad + 2 * gap), (int) (tileH + 2 * gap));
            for (int r = 0; r <= look.rows; r++) {
                int y = (int) (dockY + r * (tileH + rowGap));
                for (int i = 0; i < n; i++) {
                    View tile = rounded(r == 0 ? colors[i % colors.length] : 0x33FFFFFF, w * 0.01f);
                    place(screen, tile, (int) (pad + i * (tileW + gap)), y, tileW, tileH);
                }
            }
        }
    }

    private View rounded(int color, float radius) {
        View view = new View(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        view.setBackground(background);
        return view;
    }

    private static void place(FrameLayout parent, View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = x;
        params.topMargin = y;
        parent.addView(view, params);
    }

    /** The miniature's frame: always 16:9 of its width, with rounded corners that clip the picture. */
    public static final class Screen extends FrameLayout {
        public Screen(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
            final float radius = 10 * context.getResources().getDisplayMetrics().density;
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
