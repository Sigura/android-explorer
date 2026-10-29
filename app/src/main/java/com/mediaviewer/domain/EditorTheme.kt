package com.mediaviewer.domain

import kotlin.math.pow

/**
 * A colour theme of the text editor (todo §10.8): the colours of the 12 [Role]s and of the editor
 * itself, as ARGB. Pure Kotlin, so the contrast test in `EditorTest` can check every palette.
 * HEADING uses the TYPE colour, STRONG and EMPHASIS the text colour (they are bold / italic only).
 */
class EditorTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Long,
    val text: Long,
    val cursor: Long,
    /** Selected text, translucent over the background. */
    val selection: Long,
    /** The line of the cursor. */
    val currentLine: Long,
    /** Find matches (todo §10.6); the current one has its own background and text colour. */
    val match: Long,
    val currentMatch: Long,
    val currentMatchText: Long,
    /** Line numbers and whitespace marks. */
    val gutter: Long,
    /** The background of a matching bracket pair. */
    val bracket: Long,
    keyword: Long,
    string: Long,
    comment: Long,
    number: Long,
    type: Long,
    meta: Long,
    error: Long,
    warn: Long,
    added: Long,
) {
    private val roles = LongArray(Role.entries.size).also {
        it[Role.KEYWORD.ordinal] = keyword
        it[Role.STRING.ordinal] = string
        it[Role.COMMENT.ordinal] = comment
        it[Role.NUMBER.ordinal] = number
        it[Role.TYPE.ordinal] = type
        it[Role.META.ordinal] = meta
        it[Role.HEADING.ordinal] = type
        it[Role.STRONG.ordinal] = text
        it[Role.EMPHASIS.ordinal] = text
        it[Role.ERROR.ordinal] = error
        it[Role.WARN.ordinal] = warn
        it[Role.ADDED.ordinal] = added
    }

    fun colour(role: Role): Long = roles[role.ordinal]

    /** *Default* follows the app's day / night and draws on the app's own background. */
    val followsApp get() = id == EditorThemes.DEFAULT_ID
}

/**
 * The built-in themes. Classic palettes are kept where they reach WCAG AA (4.5 : 1 for every role
 * and the text against the background); colours that fell short (Solarized's accents, Monokai's and
 * Dracula's comments, …) are darkened or lightened just enough, keeping their hue.
 */
object EditorThemes {
    const val DEFAULT_ID = "default"

    /** *Default*, light: today's palette, with WARN (B26A00, 4.2 : 1) and COMMENT (757575, 4.49 : 1) darkened a step. */
    val DEFAULT_LIGHT = EditorTheme(
        DEFAULT_ID, "Default", dark = false,
        background = 0xFFFFFBFE, text = 0xFF1C1B1F, cursor = 0xFF2B5F96, selection = 0x662B5F96,
        currentLine = 0x0F000000, match = 0x66FFE082, currentMatch = 0xFFFFB300, currentMatchText = 0xFF000000,
        gutter = 0xFF8A8A8A, bracket = 0x4D2B5F96,
        keyword = 0xFF8E24AA, string = 0xFF2E7D32, comment = 0xFF717171, number = 0xFFC2410C,
        type = 0xFF1565C0, meta = 0xFF9C6A00, error = 0xFFC62828, warn = 0xFFA86400, added = 0xFF2E7D32,
    )

    /** *Default*, dark: today's palette. */
    val DEFAULT_DARK = EditorTheme(
        DEFAULT_ID, "Default", dark = true,
        background = 0xFF101418, text = 0xFFE2E2E6, cursor = 0xFF9CC3F5, selection = 0x669CC3F5,
        currentLine = 0x14FFFFFF, match = 0x664D6A2A, currentMatch = 0xFF8A6D00, currentMatchText = 0xFFFFFFFF,
        gutter = 0xFF6F7680, bracket = 0x559CC3F5,
        keyword = 0xFFC792EA, string = 0xFFC3E88D, comment = 0xFF8A94A6, number = 0xFFF78C6C,
        type = 0xFF82AAFF, meta = 0xFFFFCB6B, error = 0xFFFF6E6E, warn = 0xFFFFCB6B, added = 0xFFA5D6A7,
    )

    val ONE_DARK = EditorTheme(
        "one-dark", "One Dark", dark = true,
        background = 0xFF282C34, text = 0xFFABB2BF, cursor = 0xFF528BFF, selection = 0x803E4451,
        currentLine = 0xFF2C313C, match = 0x66314365, currentMatch = 0xFFD19A66, currentMatchText = 0xFF282C34,
        gutter = 0xFF636D83, bracket = 0x80515A6B,
        keyword = 0xFFC678DD, string = 0xFF98C379, comment = 0xFF92969F, number = 0xFFD19A66,
        type = 0xFFE5C07B, meta = 0xFF61AFEF, error = 0xFFE2747D, warn = 0xFFE5C07B, added = 0xFF98C379,
    )

    val SOLARIZED_LIGHT = EditorTheme(
        "solarized-light", "Solarized Light", dark = false,
        background = 0xFFFDF6E3, text = 0xFF586E75, cursor = 0xFF657B83, selection = 0xFFEEE8D5,
        currentLine = 0xFFF5EFDC, match = 0x80E9D9A6, currentMatch = 0xFFB58900, currentMatchText = 0xFFFDF6E3,
        gutter = 0xFF93A1A1, bracket = 0x6693A1A1,
        keyword = 0xFF667500, string = 0xFF1F7972, comment = 0xFF627171, number = 0xFFC92C78,
        type = 0xFF8C6A00, meta = 0xFFC24815, error = 0xFFD72724, warn = 0xFFC24815, added = 0xFF667500,
    )

    val SOLARIZED_DARK = EditorTheme(
        "solarized-dark", "Solarized Dark", dark = true,
        background = 0xFF002B36, text = 0xFF93A1A1, cursor = 0xFF839496, selection = 0xFF073642,
        currentLine = 0xFF04313C, match = 0x80365A3A, currentMatch = 0xFFB58900, currentMatchText = 0xFF002B36,
        gutter = 0xFF586E75, bracket = 0x80586E75,
        keyword = 0xFF859900, string = 0xFF2AA198, comment = 0xFF7A939B, number = 0xFFDE68A1,
        type = 0xFFB58900, meta = 0xFFE96A35, error = 0xFFE66A68, warn = 0xFFE96A35, added = 0xFF859900,
    )

    val GITHUB_LIGHT = EditorTheme(
        "github-light", "GitHub Light", dark = false,
        background = 0xFFFFFFFF, text = 0xFF1F2328, cursor = 0xFF0969DA, selection = 0x4D54AEFF,
        currentLine = 0xFFF6F8FA, match = 0x66FFF8C5, currentMatch = 0xFFFFD33D, currentMatchText = 0xFF1F2328,
        gutter = 0xFF8C959F, bracket = 0x4D4AC26B,
        keyword = 0xFFCF222E, string = 0xFF0A3069, comment = 0xFF6E7781, number = 0xFF0550AE,
        type = 0xFF953800, meta = 0xFF8250DF, error = 0xFFCF222E, warn = 0xFF9A6700, added = 0xFF116329,
    )

    val GITHUB_DARK = EditorTheme(
        "github-dark", "GitHub Dark", dark = true,
        background = 0xFF0D1117, text = 0xFFE6EDF3, cursor = 0xFF2F81F7, selection = 0x4D388BFD,
        currentLine = 0xFF161B22, match = 0x66BB800A, currentMatch = 0xFFD29922, currentMatchText = 0xFF0D1117,
        gutter = 0xFF6E7681, bracket = 0x663FB950,
        keyword = 0xFFFF7B72, string = 0xFFA5D6FF, comment = 0xFF8B949E, number = 0xFF79C0FF,
        type = 0xFFFFA657, meta = 0xFFD2A8FF, error = 0xFFFFA198, warn = 0xFFD29922, added = 0xFF7EE787,
    )

    val MONOKAI = EditorTheme(
        "monokai", "Monokai", dark = true,
        background = 0xFF272822, text = 0xFFF8F8F2, cursor = 0xFFF8F8F0, selection = 0xFF49483E,
        currentLine = 0xFF3E3D32, match = 0x66FFE792, currentMatch = 0xFFE6DB74, currentMatchText = 0xFF272822,
        gutter = 0xFF90908A, bracket = 0x8075715E,
        keyword = 0xFFFA4E8C, string = 0xFFE6DB74, comment = 0xFF95917B, number = 0xFFAE81FF,
        type = 0xFF66D9EF, meta = 0xFFA6E22E, error = 0xFFFA4E8C, warn = 0xFFFD971F, added = 0xFFA6E22E,
    )

    val DRACULA = EditorTheme(
        "dracula", "Dracula", dark = true,
        background = 0xFF282A36, text = 0xFFF8F8F2, cursor = 0xFFF8F8F2, selection = 0xFF44475A,
        currentLine = 0xFF313442, match = 0x66FFB86C, currentMatch = 0xFFF1FA8C, currentMatchText = 0xFF282A36,
        gutter = 0xFF6272A4, bracket = 0x806272A4,
        keyword = 0xFFFF79C6, string = 0xFFF1FA8C, comment = 0xFF8692B9, number = 0xFFBD93F9,
        type = 0xFF8BE9FD, meta = 0xFF50FA7B, error = 0xFFFF5555, warn = 0xFFFFB86C, added = 0xFF50FA7B,
    )

    val HIGH_CONTRAST = EditorTheme(
        "high-contrast", "High contrast", dark = true,
        background = 0xFF000000, text = 0xFFFFFFFF, cursor = 0xFFFFFFFF, selection = 0xFF264F78,
        currentLine = 0xFF1A1A1A, match = 0x80806000, currentMatch = 0xFFFFD700, currentMatchText = 0xFF000000,
        gutter = 0xFFB0B0B0, bracket = 0xFF505050,
        keyword = 0xFFFFD700, string = 0xFF7CFC00, comment = 0xFFB0B0B0, number = 0xFF00FFFF,
        type = 0xFF87CEFA, meta = 0xFFFF9DFF, error = 0xFFFF6B6B, warn = 0xFFFFA500, added = 0xFF7CFC00,
    )

    /** Every palette, both *Default* variants included (for the contrast test). */
    val PALETTES = listOf(
        DEFAULT_LIGHT, DEFAULT_DARK, ONE_DARK, SOLARIZED_LIGHT, SOLARIZED_DARK,
        GITHUB_LIGHT, GITHUB_DARK, MONOKAI, DRACULA, HIGH_CONTRAST,
    )

    /** The choices, in the order shown: *Default* once. */
    val CHOICES = PALETTES.filter { it !== DEFAULT_DARK }

    /** The theme for a setting; *Default* (and an unknown id) by the app's day / night. */
    fun resolve(id: String, appDark: Boolean): EditorTheme =
        PALETTES.firstOrNull { it.id == id && !it.followsApp } ?: if (appDark) DEFAULT_DARK else DEFAULT_LIGHT
}

/** WCAG 2 contrast (https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio), for opaque ARGB colours. */
object Contrast {
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
