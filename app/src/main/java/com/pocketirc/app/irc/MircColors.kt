package com.pocketirc.app.irc

/**
 * The 99 mIRC palette colors (indices 0-98). Index 99 is "default" (no
 * specific color) and is not in this table — emit it as the bare digits "99".
 *
 * Ported from qtpyrc/models.py.
 */
object MircColors {
    /** RGB triples for color indices 0..98. */
    val rgb: List<Triple<Int, Int, Int>> = listOf(
        Triple(255,255,255), Triple(0,0,0), Triple(0,0,127), Triple(0,147,0),
        Triple(255,0,0), Triple(127,0,0), Triple(156,0,156), Triple(252,127,0),
        Triple(255,255,0), Triple(0,252,0), Triple(0,147,147), Triple(0,255,255),
        Triple(0,0,252), Triple(255,0,255), Triple(127,127,127), Triple(210,210,210),
        Triple(71,0,0), Triple(71,33,0), Triple(71,71,0), Triple(50,71,0),
        Triple(0,71,0), Triple(0,71,44), Triple(0,71,71), Triple(0,39,71),
        Triple(0,0,71), Triple(46,0,71), Triple(71,0,71), Triple(71,0,42),
        Triple(116,0,0), Triple(116,58,0), Triple(116,116,0), Triple(81,116,0),
        Triple(0,116,0), Triple(0,116,73), Triple(0,116,116), Triple(0,64,116),
        Triple(0,0,116), Triple(75,0,116), Triple(116,0,116), Triple(116,0,69),
        Triple(181,0,0), Triple(181,99,0), Triple(181,181,0), Triple(125,181,0),
        Triple(0,181,0), Triple(0,181,113), Triple(0,181,181), Triple(0,99,181),
        Triple(0,0,181), Triple(117,0,181), Triple(181,0,181), Triple(181,0,107),
        Triple(255,0,0), Triple(255,140,0), Triple(255,255,0), Triple(178,255,0),
        Triple(0,255,0), Triple(0,255,160), Triple(0,255,255), Triple(0,140,255),
        Triple(0,0,255), Triple(165,0,255), Triple(255,0,255), Triple(255,0,152),
        Triple(255,89,89), Triple(255,180,89), Triple(255,255,113), Triple(207,255,96),
        Triple(111,255,111), Triple(111,255,201), Triple(109,255,255), Triple(89,180,255),
        Triple(89,89,255), Triple(196,89,255), Triple(255,102,255), Triple(255,89,188),
        Triple(255,156,156), Triple(255,211,156), Triple(255,255,156), Triple(226,255,156),
        Triple(156,255,156), Triple(156,255,219), Triple(156,255,255), Triple(156,211,255),
        Triple(156,156,255), Triple(220,156,255), Triple(255,156,255), Triple(255,148,211),
        Triple(0,0,0), Triple(19,19,19), Triple(40,40,40), Triple(54,54,54),
        Triple(77,77,77), Triple(101,101,101), Triple(129,129,129), Triple(159,159,159),
        Triple(188,188,188), Triple(226,226,226), Triple(255,255,255),
    )

    /** Returns true if the swatch at [index] is dark enough that white text reads better. */
    fun isDark(index: Int): Boolean {
        val (r, g, b) = rgb[index]
        // Rec. 601 luma
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140
    }

    /** Build the inline mIRC color control sequence for [fg]/[bg] (0..99 each, nullable). */
    fun colorCode(fg: Int?, bg: Int?): String {
        return when {
            fg != null && bg != null -> "\u0003%02d,%02d".format(fg, bg)
            fg != null -> "\u0003%02d".format(fg)
            // bg only -> default fg=1 (black), matches qtpyrc behavior
            bg != null -> "\u00031,%02d".format(bg)
            else -> "\u0003"
        }
    }

    const val BOLD = "\u0002"
    const val ITALIC = "\u001d"
    const val UNDERLINE = "\u001f"
    const val RESET = "\u000f"
}
