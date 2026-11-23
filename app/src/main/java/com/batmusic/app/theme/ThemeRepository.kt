package com.batmusic.app.theme

import android.content.Context
import org.json.JSONObject

data class BatTheme(
    val name: String = "DARK WINE",
    val background: Long = 0xFF10090D,
    val secondaryBackground: Long = 0xFF190C13,
    val surface: Long = 0xFF190C13,
    val primary: Long = 0xFF30101D,
    val secondary: Long = 0xFF702840,
    val accent: Long = 0xFF491529,
    val highlight: Long = 0xFFB77A90,
    val text: Long = 0xFFF4EAF0,
    val mutedText: Long = 0xFFB9A6AE,
    val border: Long = 0xFF59182B,
    val glitch: Boolean = true,
    val crt: Boolean = true,
    val animations: Boolean = true,
    val intensity: Float = 0.16f,
    val animationSpeed: Float = 1f,
    val monospace: Boolean = false,
    val textScale: Float = 1f,
    val cardRadiusDp: Float = 5f
) {
    fun toJson(): String = JSONObject().apply {
        put("schema", 1); put("name", name)
        put("background", color(background)); put("secondaryBackground", color(secondaryBackground))
        put("surface", color(surface)); put("primary", color(primary)); put("secondary", color(secondary))
        put("accent", color(accent)); put("highlight", color(highlight))
        put("text", color(text)); put("mutedText", color(mutedText)); put("border", color(border))
        put("glitch", glitch); put("crt", crt); put("animations", animations)
        put("intensity", intensity.toDouble()); put("animationSpeed", animationSpeed.toDouble()); put("monospace", monospace)
        put("textScale", textScale.toDouble()); put("cardRadiusDp", cardRadiusDp.toDouble())
    }.toString(2)

    companion object {
        val default = BatTheme()
        fun fromJson(json: String): BatTheme {
            require(json.length <= 16_384) { "Arquivo de tema excede 16 KB" }
            val value = JSONObject(json)
            require(value.optInt("schema", 1) == 1) { "Versão de tema não suportada" }
            fun readColor(key: String, fallback: Long): Long {
                val raw = value.optString(key, color(fallback))
                require(raw.matches(Regex("#[0-9A-Fa-f]{8}"))) { "Cor inválida: $key" }
                return raw.drop(1).toLong(16)
            }
            val imported = BatTheme(
                name = value.optString("name", "Tema importado").take(40).ifBlank { "Tema importado" },
                background = readColor("background", default.background),
                secondaryBackground = readColor("secondaryBackground", default.secondaryBackground),
                surface = readColor("surface", default.surface), primary = readColor("primary", default.primary),
                secondary = readColor("secondary", default.secondary), accent = readColor("accent", default.accent),
                highlight = readColor("highlight", default.highlight),
                text = readColor("text", default.text), mutedText = readColor("mutedText", default.mutedText),
                border = readColor("border", default.border), glitch = value.optBoolean("glitch", true),
                crt = value.optBoolean("crt", true), animations = value.optBoolean("animations", true),
                intensity = value.optDouble("intensity", .16).toFloat().coerceIn(0f, .5f),
                animationSpeed = value.optDouble("animationSpeed", 1.0).toFloat().coerceIn(.25f, 2f),
                monospace = value.optBoolean("monospace", false),
                textScale = value.optDouble("textScale", 1.0).toFloat().coerceIn(.75f, 1.5f),
                cardRadiusDp = value.optDouble("cardRadiusDp", 5.0).toFloat().coerceIn(0f, 28f)
            )
            return imported
        }
        private fun color(value: Long) = "#%08X".format(value and 0xFFFFFFFF)
    }
}

class ThemeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("batmusic_themes", Context.MODE_PRIVATE)
    fun active(): BatTheme {
        val raw = prefs.getString("active", null) ?: return BatTheme.default
        val loaded = runCatching { BatTheme.fromJson(raw) }.getOrDefault(BatTheme.default)
        // Migrate the shipped 2.2 default palette while keeping user-created profiles intact.
        val priorDefault = loaded.name == "DARK WINE" && loaded.background == 0xFF10090DL &&
            loaded.primary == 0xFF842D49L && loaded.surface == 0xFF30101DL
        return if (priorDefault || (loaded.name == "WIRED MINIMAL" && loaded.primary == 0xFFE32636L)) {
            activate(BatTheme.default)
            BatTheme.default
        } else loaded
    }
    fun themes(): Map<String, BatTheme> = runCatching {
        val json = JSONObject(prefs.getString("profiles", "{}") ?: "{}")
        buildMap { json.keys().forEach { key -> put(key, BatTheme.fromJson(json.getString(key))) } }
    }.getOrDefault(emptyMap()).toMutableMap().apply {
        // Shipped palettes are available immediately and remain editable/duplicable by the user.
        putIfAbsent(BatTheme.default.name, BatTheme.default)
        putIfAbsent("GOTHAM", BatTheme(
            name = "GOTHAM", background = 0xFF080B12, secondaryBackground = 0xFF111722,
            surface = 0xFF171F2B, primary = 0xFFF2C14E, secondary = 0xFF52647A,
            accent = 0xFF202B3A, highlight = 0xFFFFD76A, text = 0xFFF4F6FA,
            mutedText = 0xFFAAB5C4, border = 0xFF39485B, glitch = true, crt = true,
            intensity = .18f, animationSpeed = .82f, monospace = true, cardRadiusDp = 4f
        ))
        putIfAbsent("KRYPTON", BatTheme(
            name = "KRYPTON", background = 0xFF071426, secondaryBackground = 0xFF0B1E36,
            surface = 0xFF102A49, primary = 0xFF1769AA, secondary = 0xFFE33B36,
            accent = 0xFFB21F2D, highlight = 0xFFFFD65A, text = 0xFFF4F7FF,
            mutedText = 0xFFB6C7DC, border = 0xFF245B8C, glitch = false, crt = false,
            intensity = .08f, animationSpeed = 1.05f, cardRadiusDp = 10f
        ))
        putIfAbsent("METROPOLIS", BatTheme(
            name = "METROPOLIS", background = 0xFF080D18, secondaryBackground = 0xFF101A2B,
            surface = 0xFF14243A, primary = 0xFF1D5F9E, secondary = 0xFFB52D38,
            accent = 0xFFDBA83D, highlight = 0xFFE9F2FF, text = 0xFFF5F7FC,
            mutedText = 0xFFA9B8CA, border = 0xFF294B70, glitch = false, crt = true,
            intensity = .10f, cardRadiusDp = 8f
        ))
        putIfAbsent("SPEED FORCE", BatTheme(
            name = "SPEED FORCE", background = 0xFF170609, secondaryBackground = 0xFF260A10,
            surface = 0xFF321018, primary = 0xFFE32636, secondary = 0xFFFFC928,
            accent = 0xFF9F1524, highlight = 0xFFFFE17A, text = 0xFFFFF5F3,
            mutedText = 0xFFE0B9B7, border = 0xFF7A1B2A, glitch = true, crt = false,
            intensity = .22f, animationSpeed = 1.55f, monospace = false, textScale = 1.02f, cardRadiusDp = 6f
        ))
        putIfAbsent("EMERALD", BatTheme(
            name = "EMERALD", background = 0xFF04130D, secondaryBackground = 0xFF092217,
            surface = 0xFF0D2D1E, primary = 0xFF19A86B, secondary = 0xFF7CE0A7,
            accent = 0xFF087A4B, highlight = 0xFFB7FFD3, text = 0xFFF0FFF6,
            mutedText = 0xFFA7D6BC, border = 0xFF176B48, glitch = false, crt = true,
            intensity = .12f, animationSpeed = 1.1f, monospace = true, cardRadiusDp = 9f
        ))
        putIfAbsent("OBSIDIAN", BatTheme(
            name = "OBSIDIAN", background = 0xFF08090D, secondaryBackground = 0xFF101218,
            surface = 0xFF171B24, primary = 0xFF315C82, secondary = 0xFF5D87AD,
            accent = 0xFF243E59, highlight = 0xFFB9D8F4, text = 0xFFF1F4F8,
            mutedText = 0xFFA4AFBD, border = 0xFF263548, glitch = false, crt = false,
            intensity = .06f, cardRadiusDp = 12f
        ))
        putIfAbsent("NEON CIRCUIT", BatTheme(
            name = "NEON CIRCUIT", background = 0xFF0B0714, secondaryBackground = 0xFF150D22,
            surface = 0xFF1B102A, primary = 0xFF7137A8, secondary = 0xFF19A6A1,
            accent = 0xFFB52B83, highlight = 0xFF8DE8E0, text = 0xFFF8F1FF,
            mutedText = 0xFFC0B1CE, border = 0xFF512B70, glitch = true, crt = true,
            intensity = .14f, animationSpeed = 1.15f, cardRadiusDp = 7f
        ))
    }
    fun screenAssignments(): Map<String, String> = listOf("home", "library", "player", "queue", "audio", "settings", "customizer")
        .associateWith { screen ->
            val assigned = prefs.getString("screen_$screen", null)
            if (assigned == "WIRED MINIMAL") {
                prefs.edit().putString("screen_$screen", BatTheme.default.name).apply()
                BatTheme.default.name
            } else assigned ?: BatTheme.default.name
        }
    fun assignScreen(screen: String, themeName: String) {
        require(screen in setOf("home", "library", "player", "queue", "audio", "settings", "customizer"))
        require(themeName in themes())
        prefs.edit().putString("screen_$screen", themeName).apply()
    }
    fun activate(theme: BatTheme) {
        prefs.edit().putString("active", theme.toJson()).apply()
        val all = themes().toMutableMap().apply { put(theme.name, theme) }
        persist(all)
    }
    fun duplicate(theme: BatTheme, name: String): BatTheme {
        val copy = theme.copy(name = name.trim().take(40).ifBlank { "Tema" })
        require(copy.name !in themes()) { "Já existe um tema com esse nome" }
        activate(copy)
        return copy
    }
    fun delete(name: String) {
        require(name !in setOf(BatTheme.default.name, "GOTHAM", "KRYPTON", "SPEED FORCE", "EMERALD", "METROPOLIS", "OBSIDIAN", "NEON CIRCUIT")) { "Perfis integrados não podem ser excluídos" }
        val all = themes().toMutableMap().apply { remove(name) }
        persist(all)
        listOf("home", "library", "player", "queue", "audio", "settings", "customizer").forEach { screen ->
            if (prefs.getString("screen_$screen", null) == name) prefs.edit().putString("screen_$screen", BatTheme.default.name).apply()
        }
        if (active().name == name) activate(BatTheme.default)
    }
    fun reset() = activate(BatTheme.default)
    fun import(json: String): BatTheme = BatTheme.fromJson(json).also(::activate)
    fun export(theme: BatTheme = active()) = theme.toJson()
    private fun persist(themes: Map<String, BatTheme>) {
        prefs.edit().putString("profiles", JSONObject().apply { themes.forEach { (name, theme) -> put(name, theme.toJson()) } }.toString()).apply()
    }
}
