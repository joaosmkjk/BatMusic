package com.batmusic.app.theme

/** Resolves a screen's assigned profile without mutating saved preferences. */
class ThemeManager {
    fun forScreen(screen: String, active: BatTheme, profiles: Map<String, BatTheme>, assignments: Map<String, String>): BatTheme {
        val assigned = assignments[screen]?.let(profiles::get)
        return assigned ?: profiles[active.name] ?: active
    }
}
