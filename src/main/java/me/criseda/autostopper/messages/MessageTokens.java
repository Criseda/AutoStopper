package me.criseda.autostopper.messages;

import net.kyori.adventure.text.format.TextColor;

/**
 * Central semantic tokens for AutoStopper's chat and command presentation.
 * Colors are derived from the official AutoStopper branding assets and downsample
 * cleanly to standard NamedTextColor values on legacy Minecraft clients.
 */
public final class MessageTokens {
    /** Primary brand accent (icy slate blue). */
    public static final TextColor BRAND = TextColor.color(0x8C, 0xB2, 0xC5);

    /** Neutral primary text for prose. */
    public static final TextColor TEXT_PRIMARY = TextColor.color(0xE5, 0xE9, 0xF0);

    /** Neutral muted text for secondary details, timestamps, and dividers. */
    public static final TextColor TEXT_MUTED = TextColor.color(0x88, 0x92, 0xB0);

    /** Bright action accent for commands, arguments, and clickable affordances. */
    public static final TextColor ACTION = TextColor.color(0x70, 0xD6, 0xFF);

    /** Semantic success accent for positive outcomes and ready badges. */
    public static final TextColor SUCCESS = TextColor.color(0xA3, 0xBE, 0x8C);

    /** Semantic progress and warning accent for in-flight transitions and unverified states. */
    public static final TextColor PROGRESS_WARNING = TextColor.color(0xEB, 0xCB, 0x8B);

    /** Semantic failure accent for errors, denials, and unavailable states. */
    public static final TextColor FAILURE = TextColor.color(0xBF, 0x61, 0x6A);

    /** Compact brand prompt divider. */
    public static final String PREFIX_DIVIDER = "›";

    /** Success checkmark. */
    public static final String MARK_SUCCESS = "✓";

    /** Warning and error marker. */
    public static final String MARK_ATTENTION = "!";

    /** Glyph for active and ready status. */
    public static final String DOT_READY = "●";

    /** Glyph for in-progress or unverified status. */
    public static final String DOT_PROGRESS = "◐";

    /** Glyph for stopped or sleeping status. */
    public static final String DOT_STOPPED = "○";

    /** Middle dot separator. */
    public static final String SEPARATOR = "·";

    private MessageTokens() {
    }
}
