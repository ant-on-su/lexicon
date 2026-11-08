package com.example.lexicon

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

val PairsRange = 5..10
val RoundsRange = 3..10

data class RaceOptions(
    /** Units are stored as excluded, so new units are practised by default. */
    val excludedUnits: Set<String> = emptySet(),
    val reverse: Boolean = false,
    val pairs: Int = 6,
    val rounds: Int = 3,
    val showForms: Boolean = false,
    val showGender: Boolean = false,
)

/** Sound and vibration, shared by all languages. A cue's sound is a [Tone] name, a phone sound's URI, or "" for none. */
data class FeedbackOptions(
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val sounds: Map<Cue, String> = Cue.entries.associateWith { it.defaultTone.name },
    val vibes: Map<Cue, Vibe> = Cue.entries.associateWith { it.defaultVibe },
)

/** Options per language, the word folder, sound and vibration, and best times, remembered between launches. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("lexicon", Context.MODE_PRIVATE)

    /** The folder with the user's own `<language>/words/` YAML files, shared by all languages. */
    var wordFolder: Uri?
        get() = prefs.getString("wordFolder", null)?.toUri()
        set(value) = prefs.edit { putString("wordFolder", value?.toString()) }

    fun options(lang: String) = RaceOptions(
        excludedUnits = prefs.getStringSet("$lang.excludedUnits", null).orEmpty().toSet(),
        reverse = prefs.getBoolean("$lang.reverse", false),
        pairs = prefs.getInt("$lang.pairs", 6).coerceIn(PairsRange),
        rounds = prefs.getInt("$lang.rounds", 3).coerceIn(RoundsRange),
        showForms = prefs.getBoolean("$lang.showForms", false),
        showGender = prefs.getBoolean("$lang.showGender", false),
    )

    fun saveOptions(lang: String, options: RaceOptions) = prefs.edit {
        putStringSet("$lang.excludedUnits", options.excludedUnits)
        putBoolean("$lang.reverse", options.reverse)
        putInt("$lang.pairs", options.pairs)
        putInt("$lang.rounds", options.rounds)
        putBoolean("$lang.showForms", options.showForms)
        putBoolean("$lang.showGender", options.showGender)
    }

    fun feedback() = FeedbackOptions(
        sound = prefs.getBoolean("sound", true),
        vibration = prefs.getBoolean("vibration", true),
        sounds = Cue.entries.associateWith { cue ->
            prefs.getString("sound.$cue", null)
                ?.takeIf { s -> s.isEmpty() || s.startsWith("content:") || Tone.entries.any { it.name == s } }
                ?: cue.defaultTone.name
        },
        vibes = Cue.entries.associateWith { cue ->
            Vibe.entries.find { it.name == prefs.getString("vibe.$cue", null) } ?: cue.defaultVibe
        },
    )

    fun saveFeedback(options: FeedbackOptions) = prefs.edit {
        putBoolean("sound", options.sound)
        putBoolean("vibration", options.vibration)
        options.sounds.forEach { (cue, sound) -> putString("sound.$cue", sound) }
        options.vibes.forEach { (cue, vibe) -> putString("vibe.$cue", vibe.name) }
    }

    fun best(key: String): Long? = prefs.getLong("best.$key", -1L).takeIf { it >= 0 }

    fun saveBest(key: String, ms: Long) = prefs.edit { putLong("best.$key", ms) }
}

/** Best times are kept per language and per combination of options. */
fun bestKey(lang: Language, options: RaceOptions) = listOf(
    lang.folder,
    lang.activeUnits(options).joinToString("+") { it.id },
    if (options.reverse) "reverse" else "direct",
    options.pairs,
    options.rounds,
    options.showForms,
    options.showGender,
).joinToString("|")
