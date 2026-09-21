package ist.solo.notifications;

import android.graphics.Color;

/**
 * Measured against the real LightOS toolbox on 582-release-lp3
 * (1080x1240 @ 480dpi, scale 3.0), captured 2026-09-19.
 *
 * Observed geometry:
 *   row pitch        190px → 63dp
 *   text line box    150px → 50dp
 *   six rows visible per page, text CENTRED horizontally (all rows share
 *   centre x = 540 = screen middle)
 *   page dots in a right-hand column, centre x ≈ 1016, pitch ≈ 80px
 *
 * Typeface: LightOS maps the system "sans-serif" family to Akkurat LL in
 * /system/etc/fonts.xml, so asking for sans-serif-light yields
 * AkkuratLLTT-Light — the toolbox's actual font, with nothing to ship.
 */
final class Style {
    private Style() {}

    static final int BACKGROUND = Color.BLACK;
    static final int FOREGROUND = Color.WHITE;
    /** The add-row and other secondary text. */
    static final int MUTED = Color.parseColor("#6E6E6E");

    /**
     * Resolves to AkkuratLLTT-Regular (weight 400) on LightOS — the system
     * sans-serif family is Akkurat LL. "sans-serif-light" (weight 300) was
     * visibly thinner than the toolbox: 6px stems against its 10px.
     */
    static final String FONT_FAMILY = "sans-serif";

    /**
     * Matched by measurement against a screenshot of the real toolbox,
     * excluding the page-dot rail from the scan. Reference "Calculator"
     * renders an 80px ascender-to-baseline band with 10px stems. At
     * Regular weight: 34sp gave 76px, 38sp gave 85px, so 36sp lands on 80.
     *
     * Compare glyph height, not per-character width — width varies too much
     * between words to be a reliable axis.
     */
    static final float ROW_TEXT_SP = 36f;
    static final int ROW_PITCH_DP = 63;
    static final int ROWS_PER_PAGE = 6;

    static final int DOT_DIAMETER_DP = 9;
    static final int DOT_PITCH_DP = 27;
    static final int DOT_MARGIN_END_DP = 16;

    /**
     * Page-turn haptic, matched to LightOS. Read off the real toolbox via
     * `dumpsys vibrator_manager`: com.lightos plays
     * Composed{segments=[Step{amplitude=1.0, duration=40}]} with
     * originalEffect amplitude=-1.0 and Usage=TOUCH — i.e. a plain
     * createOneShot(40, DEFAULT_AMPLITUDE), not a prebaked CLICK.
     */
    static final long HAPTIC_MS = 40L;

    /** Keeps long labels clear of the dot rail while staying screen-centred. */
    static final int ROW_PAD_HORIZONTAL_DP = 32;
}
