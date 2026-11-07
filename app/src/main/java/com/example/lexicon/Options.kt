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

/** Options per language, the word folder and best times, remembered between launches. */
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
