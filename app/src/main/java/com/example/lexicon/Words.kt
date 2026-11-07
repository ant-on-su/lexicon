package com.example.lexicon

import android.content.res.AssetManager
import android.util.Log
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.text.Normalizer
import java.util.Locale

/** One playable pair: a term and one Dutch sense. */
data class Card(
    val id: String,
    val entry: String,
    val term: String,
    val translation: String,
    val termForms: String? = null,
    val translationForms: String? = null,
    val gender: String? = null,
) {
    val meanings: Set<String> = meanings(translation)
    private val termKey = normalize(term)

    /** True when both cards on the board at once would make a match ambiguous. */
    fun conflictsWith(other: Card) =
        entry == other.entry || termKey == other.termKey || meanings.any { it in other.meanings }
}

data class WordUnit(
    val id: String,
    val title: String,
    val language: String?,
    val translationLanguage: String?,
    val cards: List<Card>,
)

data class Language(
    val folder: String,
    val name: String,
    val translationName: String,
    val units: List<WordUnit>,
) {
    val hasGender = units.any { unit -> unit.cards.any { it.gender != null } }

    fun activeUnits(options: RaceOptions) = units.filter { it.id !in options.excludedUnits }.ifEmpty { units }

    fun cards(options: RaceOptions) = activeUnits(options).flatMap { it.cards }
}

private val brackets = Regex("\\([^)]*\\)")
private val articles = Regex("^(de|het|een|'t)\\s+")
private val marks = Regex("\\p{Mn}+")
private val spaces = Regex("\\s+")

fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(marks, "")
        .replace(spaces, " ")
        .trim()

/**
 * The separate meanings of a Dutch sense: "de eerste, leider" -> {eerste, leider}.
 * Bracketed optional parts, case, accents and leading articles are ignored.
 */
fun meanings(translation: String): Set<String> =
    translation.replace(brackets, " ")
        .split(',', '/', ';')
        .map { normalize(it).replace(articles, "") }
        .filter { it.isNotEmpty() }
        .toSet()

@Suppress("UNCHECKED_CAST")
fun parseUnit(yaml: String, fallbackId: String): WordUnit {
    val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as? Map<String, Any?> ?: emptyMap()
    val meta = root["meta"] as? Map<String, Any?> ?: emptyMap()
    val unitId = meta["id"]?.toString() ?: fallbackId
    val entries = root["entries"] as? List<Map<String, Any?>> ?: emptyList()
    val cards = entries.flatMap { entry ->
        val term = entry["term"]?.toString() ?: return@flatMap emptyList()
        val senses = when (val t = entry["translation"]) {
            is List<*> -> t.mapNotNull { it?.toString() }
            null -> emptyList()
            else -> listOf(t.toString())
        }
        val forms = entry["forms"] as? Map<String, Any?> ?: emptyMap()
        val termForms = listOfNotNull(
            forms["plural"]?.toString(),
            forms["genitive"]?.let { "gen. $it" },
            forms["perfect"]?.let { "perf. $it" },
        ).joinToString(" · ").ifEmpty { null }
        val translationForms = (entry["translation_forms"] as? Map<String, Any?>)?.get("plural")?.toString()
        val entryId = "$unitId/${entry["id"] ?: term}"
        val used = if (entry["card_per_sense"] == true) senses else senses.take(1)
        used.mapIndexed { i, sense ->
            Card(
                id = "$entryId/$i",
                entry = entryId,
                term = term,
                translation = sense,
                termForms = termForms,
                translationForms = translationForms,
                gender = forms["gender"]?.toString(),
            )
        }
    }
    return WordUnit(
        id = unitId,
        title = meta["title"]?.toString() ?: fallbackId,
        language = meta["language"]?.toString(),
        translationLanguage = meta["translation_language"]?.toString(),
        cards = cards,
    )
}

/** Every asset folder with a `words/` folder of YAML units is a language. */
fun loadLanguages(assets: AssetManager): List<Language> =
    assets.list("").orEmpty().sorted().mapNotNull { folder ->
        val files = assets.list("$folder/words").orEmpty()
            .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
            .sorted()
        val units = files.mapNotNull { file ->
            runCatching {
                val yaml = assets.open("$folder/words/$file").bufferedReader().use { it.readText() }
                parseUnit(yaml, file.substringBeforeLast('.'))
            }.onFailure { Log.w("Lexicon", "Skipping $folder/words/$file", it) }.getOrNull()
        }.filter { it.cards.isNotEmpty() }
        if (units.isEmpty()) return@mapNotNull null
        val translationCode = units.first().translationLanguage ?: "nl"
        Language(
            folder = folder,
            name = languageName(units.first().language, folder),
            translationName = languageName(translationCode, translationCode),
            units = units,
        )
    }

/** The language's own name ("Deutsch"), or the folder name when unknown. */
private fun languageName(code: String?, fallback: String): String {
    val locale = code?.let { Locale.forLanguageTag(it) }
    val name = locale?.getDisplayLanguage(locale)
    return if (name.isNullOrBlank() || name == code) {
        fallback.replaceFirstChar { it.uppercase() }
    } else {
        name.replaceFirstChar { it.titlecase(locale) }
    }
}
