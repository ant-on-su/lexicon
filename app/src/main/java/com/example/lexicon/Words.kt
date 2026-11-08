package com.example.lexicon

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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

/** A YAML file in `<language>/words/`, read on demand. */
class UnitFile(val language: String, val name: String, val read: () -> String)

/** The languages found, the word folder's name, and the files that couldn't be used. */
class Library(val languages: List<Language>, val folderName: String?, val problems: List<String>)

private fun isYaml(name: String) = name.endsWith(".yaml") || name.endsWith(".yml")

/** Every asset folder with a `words/` folder of YAML units is a language. */
fun assetFiles(assets: AssetManager): List<UnitFile> =
    assets.list("").orEmpty().flatMap { folder ->
        assets.list("$folder/words").orEmpty().filter(::isYaml).map { file ->
            UnitFile(folder, file) { assets.open("$folder/words/$file").bufferedReader().use { it.readText() } }
        }
    }

/** The same `<language>/words/` layout of YAML files, inside a folder picked with the system picker. */
fun folderFiles(resolver: ContentResolver, tree: Uri): List<UnitFile> {
    class Doc(val id: String, val name: String, val isDir: Boolean)

    fun children(parent: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return checkNotNull(resolver.query(uri, columns, null, null, null)).use { c ->
            buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
        }
    }

    return children(DocumentsContract.getTreeDocumentId(tree)).filter { it.isDir }.flatMap { lang ->
        children(lang.id).filter { it.isDir && it.name == "words" }.flatMap { words ->
            children(words.id).filter { !it.isDir && isYaml(it.name) }.map { file ->
                UnitFile(lang.name, file.name) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.id)
                    checkNotNull(resolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                }
            }
        }
    }
}

private fun folderName(resolver: ContentResolver, tree: Uri): String? {
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}

/** The bundled units plus those in the word folder, if one is set. */
fun loadLibrary(context: Context, folder: Uri?): Library {
    val problems = mutableListOf<String>()
    val folderFiles = folder?.let {
        runCatching { folderFiles(context.contentResolver, it) }
            .onFailure { e -> Log.w("Lexicon", "Can't read the word folder", e); problems += "The word folder can't be opened" }
            .getOrNull()
    }.orEmpty()
    val name = folder?.let { runCatching { folderName(context.contentResolver, it) }.getOrNull() ?: it.lastPathSegment }
    val languages = loadLanguages(assetFiles(context.assets) + folderFiles, problems)
    return Library(languages, name, problems)
}

private val lineNumber = Regex("line \\d+")

/**
 * Groups unit files into languages by folder. A later file replaces an earlier unit with the same id,
 * so put the word folder after the bundled files. Unusable files are added to `problems`.
 */
fun loadLanguages(files: List<UnitFile>, problems: MutableList<String> = mutableListOf()): List<Language> =
    files.groupBy { it.language }.toSortedMap().mapNotNull { (folder, group) ->
        val units = group.mapNotNull { file ->
            val path = "$folder/words/${file.name}"
            val unit = runCatching { parseUnit(file.read(), file.name.substringBeforeLast('.')) }.getOrElse { e ->
                val line = e.message?.let { lineNumber.find(it) }
                problems += "$path: " + (line?.let { "YAML error at ${it.value}" } ?: "can't be read")
                return@mapNotNull null
            }
            if (unit.cards.isEmpty()) {
                problems += "$path: no cards"
                return@mapNotNull null
            }
            file.name to unit
        }
            .associateBy { it.second.id }.values
            .sortedBy { it.first }
            .map { it.second }
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
