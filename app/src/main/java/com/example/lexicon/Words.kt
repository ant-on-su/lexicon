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

/** One playable pair: a term and its Dutch translation (one sense, or all senses numbered on separate lines). */
data class Card(
    val id: String,
    val entry: String,
    val term: String,
    val translation: String,
    val termForms: String? = null,
    val translationForms: String? = null,
    val gender: String? = null,
    val meanings: Set<String> = meanings(translation),
) {
    private val termKey = normalize(term)

    /** True when both cards on the board at once would make a match ambiguous. */
    fun conflictsWith(other: Card) =
        entry == other.entry || termKey == other.termKey || meanings.any { it in other.meanings }
}

/** An example phrase, with its Dutch translation when there is one. [audio] is a path inside the language's `audio/` folder. */
data class Example(val text: String, val translation: String?, val speak: String, val audio: String)

/** A whole entry, as Learn shows it: every sense, the forms and the example phrases. */
data class Entry(
    val id: String,
    val term: String,
    val translations: List<String>,
    val forms: String? = null,
    val gender: String? = null,
    val translationForms: String? = null,
    /** What is spoken: the `speak` field, or else the term as [spokenText] makes it. */
    val speak: String = spokenText(term),
    /** The `audio` field, or else `<unit id>/<entry id>.mp3`, inside the language's `audio/` folder. */
    val audio: String = "$id.mp3",
    val examples: List<Example> = emptyList(),
)

data class WordUnit(
    val id: String,
    val title: String,
    val language: String?,
    val translationLanguage: String?,
    /** Term to translation: one card per entry, with all senses of a `card_per_sense` entry on it. */
    val cards: List<Card>,
    /** Translation to term: one card per sense of a `card_per_sense` entry. */
    val senseCards: List<Card> = cards,
    val entries: List<Entry> = emptyList(),
)

data class Language(
    val folder: String,
    val name: String,
    val translationName: String,
    val units: List<WordUnit>,
    /** The words' language code, such as "fr". */
    val code: String? = null,
    /** The files in the language's `audio/` folder, by path inside it ("unit/entry.mp3"), as content URIs. */
    val audio: Map<String, String> = emptyMap(),
) {
    val hasGender = units.any { unit -> unit.cards.any { it.gender != null } }

    fun activeUnits(options: RaceOptions) = units.filter { it.id !in options.excludedUnits }

    fun cards(options: RaceOptions) = activeUnits(options).flatMap { if (options.reverse) it.senseCards else it.cards }

    /** The cards in [mistakes], from all units and both directions. Ids no longer in the word files are left out. */
    fun mistakenCards(mistakes: Map<String, Int>) =
        units.flatMap { it.cards + it.senseCards }.distinctBy { it.id }.filter { it.id in mistakes }
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

private val unspoken = Regex("\\([^)]*\\)|\\+\\s*\\p{L}+\\.")

/** The term as spoken: without bracketed parts ("(amare)") or cases ("+ acc."), and "a / b" as "a, b". Articles stay. */
fun spokenText(term: String): String =
    term.replace(unspoken, " ").replace(" / ", ", ").replace(spaces, " ").trim()

@Suppress("UNCHECKED_CAST")
fun parseUnit(yaml: String, fallbackId: String): WordUnit {
    val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as? Map<String, Any?> ?: emptyMap()
    val meta = root["meta"] as? Map<String, Any?> ?: emptyMap()
    val unitId = meta["id"]?.toString() ?: fallbackId
    val entries = root["entries"] as? List<Map<String, Any?>> ?: emptyList()
    val cards = mutableListOf<Card>()
    val senseCards = mutableListOf<Card>()
    val learnEntries = mutableListOf<Entry>()
    entries.forEach { entry ->
        val term = entry["term"]?.toString() ?: return@forEach
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
        val id = entry["id"]?.toString() ?: term
        val entryId = "$unitId/$id"
        val examples = (entry["examples"] as? List<*>).orEmpty().mapIndexedNotNull { i, example ->
            val fields = example as? Map<String, Any?> ?: return@mapIndexedNotNull null
            val text = fields["text"]?.toString() ?: return@mapIndexedNotNull null
            Example(
                text = text,
                translation = fields["translation"]?.toString(),
                speak = fields["speak"]?.toString() ?: text,
                audio = fields["audio"]?.toString() ?: "${entryId}_${i + 1}.mp3",
            )
        }
        learnEntries += Entry(
            id = id,
            term = term,
            translations = senses,
            forms = termForms,
            gender = forms["gender"]?.toString(),
            translationForms = translationForms,
            speak = entry["speak"]?.toString() ?: spokenText(term),
            audio = entry["audio"]?.toString() ?: "$entryId.mp3",
            examples = examples,
        )
        val used = if (entry["card_per_sense"] == true) senses else senses.take(1)
        val perSense = used.mapIndexed { i, sense ->
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
        senseCards += perSense
        // Term to translation shows every sense on one card, so the card is complete.
        cards += if (perSense.size > 1) {
            listOf(
                perSense.first().copy(
                    id = "$entryId/senses",
                    translation = perSense.mapIndexed { i, card -> "${i + 1}. ${card.translation}" }.joinToString("\n"),
                    meanings = perSense.flatMap { it.meanings }.toSet(),
                ),
            )
        } else {
            perSense
        }
    }
    return WordUnit(
        id = unitId,
        title = meta["title"]?.toString() ?: fallbackId,
        language = meta["language"]?.toString(),
        translationLanguage = meta["translation_language"]?.toString(),
        cards = cards,
        senseCards = senseCards,
        entries = learnEntries,
    )
}

/** A YAML file in `<language>/words/`, read on demand. */
class UnitFile(val language: String, val name: String, val read: () -> String)

/** The languages found, the word folder's name, and the files that couldn't be used. */
class Library(val languages: List<Language>, val folderName: String?, val problems: List<String>)

private fun isYaml(name: String) = name.endsWith(".yaml") || name.endsWith(".yml")

/** Every asset folder with a `words/` folder of YAML units is a language: the demo words shipped with the app. */
fun assetFiles(assets: AssetManager): List<UnitFile> =
    assets.list("").orEmpty().flatMap { folder ->
        assets.list("$folder/words").orEmpty().filter(::isYaml).map { file ->
            UnitFile(folder, file) { assets.open("$folder/words/$file").bufferedReader().use { it.readText() } }
        }
    }

private class Doc(val id: String, val name: String, val isDir: Boolean)

private fun ContentResolver.children(tree: Uri, parent: String = DocumentsContract.getTreeDocumentId(tree)): List<Doc> {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    return checkNotNull(query(uri, columns, null, null, null)).use { c ->
        buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
    }
}

/** The same `<language>/words/` layout of YAML files, inside a folder picked with the system picker. */
fun folderFiles(resolver: ContentResolver, tree: Uri): List<UnitFile> {
    fun children(parent: String) = resolver.children(tree, parent)

    return resolver.children(tree).filter { it.isDir }.flatMap { lang ->
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

/** Every file in each `<language>/audio/` folder, by language folder and path inside `audio/`, as content URIs. */
fun folderAudio(resolver: ContentResolver, tree: Uri): Map<String, Map<String, String>> {
    fun files(dir: String, path: String): List<Pair<String, String>> = resolver.children(tree, dir).flatMap { doc ->
        if (doc.isDir) {
            files(doc.id, "$path${doc.name}/")
        } else {
            listOf("$path${doc.name}" to DocumentsContract.buildDocumentUriUsingTree(tree, doc.id).toString())
        }
    }

    return resolver.children(tree).filter { it.isDir }.associate { lang ->
        lang.name to resolver.children(tree, lang.id).filter { it.isDir && it.name == "audio" }.flatMap { files(it.id, "") }.toMap()
    }
}

private fun folderName(resolver: ContentResolver, tree: Uri): String? {
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}

/** The units in the word folder, or the demo units while no folder is set or it has no usable words. */
fun loadLibrary(context: Context, folder: Uri?): Library {
    val problems = mutableListOf<String>()
    val folderFiles = folder?.let {
        runCatching { folderFiles(context.contentResolver, it) }
            .onFailure { e -> Log.w("Lexicon", "Can't read the word folder", e); problems += "The word folder can't be opened" }
            .getOrNull()
    }.orEmpty()
    val name = folder?.let { runCatching { folderName(context.contentResolver, it) }.getOrNull() ?: it.lastPathSegment }
    val audio = folder?.takeIf { folderFiles.isNotEmpty() }?.let {
        runCatching { folderAudio(context.contentResolver, it) }
            .onFailure { e -> Log.w("Lexicon", "Can't read the audio files", e) }
            .getOrNull()
    }.orEmpty()
    val languages = loadLanguages(folderFiles, problems).map { it.copy(audio = audio[it.folder].orEmpty()) }.ifEmpty {
        if (folder != null) problems += "No usable <language>/words/*.yaml files in the word folder: showing the demo words"
        loadLanguages(assetFiles(context.assets), problems)
    }
    return Library(languages, name, problems)
}

private val lineNumber = Regex("line \\d+")

/**
 * Groups unit files into languages by folder. A later file replaces an earlier unit with the same id.
 * Unusable files are added to `problems`.
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
            code = units.first().language,
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
