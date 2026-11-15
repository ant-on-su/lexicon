package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The demo words shipped with the app, and the full word lists kept in the repo's `Resources` folder. */
val wordFolders = listOf(File("demo"), File("../Resources"))

class WordsTest {
    private val unit = parseUnit(
        """
        meta:
          id: test
          title: Test unit
          language: la
          translation_language: nl
        entries:
          - id: princeps
            term: princeps
            forms: { genitive: principis, gender: m }
            translation:
              - de eerste, leider
              - keizer
          - id: urbs
            term: urbs
            translation:
              - stad
              - dé stad, Rome
            card_per_sense: true
          - id: dux
            term: dux
            translation: [leider]
          - id: dorf
            term: das Dorf
            forms: { plural: die Dörfer }
            translation: [het dorp]
            translation_forms: { plural: de dorpen }
          - id: im_dorf
            term: im Dorf
            translation: [in het dorp]
          - id: bild
            term: das Bild
            translation: [de foto]
          - id: foto
            term: das Foto
            translation: [de foto]
          - id: vobis
            term: vobis (dat.)
            translation: ["(aan/voor) jullie"]
        """.trimIndent(),
        "fallback",
    )

    private fun card(term: String) = unit.cards.first { it.term == term }

    @Test
    fun parsesUnitAndCards() {
        assertEquals("test", unit.id)
        assertEquals("Test unit", unit.title)
        assertEquals("la", unit.language)
        assertEquals("nl", unit.translationLanguage)
        // princeps uses only its first sense. urbs has all senses on one card, and one card per sense in reverse.
        assertEquals(listOf("de eerste, leider"), unit.cards.filter { it.term == "princeps" }.map { it.translation })
        assertEquals("1. stad\n2. dé stad, Rome", card("urbs").translation)
        assertEquals(setOf("stad", "rome"), card("urbs").meanings)
        assertEquals(listOf("stad", "dé stad, Rome"), unit.senseCards.filter { it.term == "urbs" }.map { it.translation })
        assertEquals(unit.cards.filter { it.term != "urbs" }, unit.senseCards.filter { it.term != "urbs" })
        assertEquals("gen. principis", card("princeps").termForms)
        assertEquals("m", card("princeps").gender)
        assertEquals("die Dörfer", card("das Dorf").termForms)
        assertEquals("de dorpen", card("das Dorf").translationForms)
        assertNull(card("dux").termForms)
    }

    @Test
    fun parsesEntriesForLearn() {
        val unit = parseUnit(
            """
            meta: { id: u1, title: Unit 1, language: de }
            entries:
              - id: haus
                term: das Haus
                forms: { plural: die Häuser, gender: o }
                translation: [het huis, de woning]
                examples:
                  - text: Das Haus ist groß.
                    translation: Het huis is groot.
                    source: generated
                  - text: Ein Haus.
                    audio: shared/ein-haus.mp3
              - id: amo
                term: amo (amare)
                speak: ahmo
                audio: amo-book.mp3
            """.trimIndent(),
            "fallback",
        )
        val (haus, amo) = unit.entries
        assertEquals(listOf("het huis", "de woning"), haus.translations)
        assertEquals("die Häuser", haus.forms)
        assertEquals("o", haus.gender)
        assertEquals("das Haus", haus.speak)
        assertEquals("u1/haus.mp3", haus.audio)
        assertEquals(
            listOf(
                Example("Das Haus ist groß.", "Het huis is groot.", "Das Haus ist groß.", "u1/haus_1.mp3"),
                Example("Ein Haus.", null, "Ein Haus.", "shared/ein-haus.mp3"),
            ),
            haus.examples,
        )
        assertEquals("ahmo", amo.speak)
        assertEquals("amo-book.mp3", amo.audio)
        assertTrue(amo.examples.isEmpty())
    }

    @Test
    fun spokenTextDropsBracketsAndCases() {
        assertEquals("accipio", spokenText("accipio (accipĕre)"))
        assertEquals("in", spokenText("in + acc."))
        assertEquals("das Haus", spokenText("das Haus"))
        assertEquals("frais, fraîche", spokenText("frais / fraîche"))
    }

    @Test
    fun splitsMeanings() {
        assertEquals(setOf("eerste", "leider"), meanings("de eerste, leider"))
        assertEquals(setOf("stad", "rome"), meanings("dé stad, Rome"))
        assertEquals(setOf("hij", "zij", "het"), meanings("hij/zij/het"))
        assertEquals(setOf("jullie"), meanings("(aan/voor) jullie"))
        assertEquals(setOf("in het dorp"), meanings("in het dorp"))
    }

    @Test
    fun detectsAmbiguousPairs() {
        assertTrue(card("princeps").conflictsWith(card("dux")))
        assertTrue(card("das Bild").conflictsWith(card("das Foto")))
        val (urbs1, urbs2) = unit.senseCards.filter { it.term == "urbs" }
        assertTrue(urbs1.conflictsWith(urbs2))
        assertFalse(card("das Dorf").conflictsWith(card("im Dorf")))
        assertFalse(card("dux").conflictsWith(card("das Dorf")))
    }

    @Test
    fun cardsFollowTheDirection() {
        val lang = Language("latin", "Latina", "Nederlands", listOf(unit))
        assertEquals(1, lang.cards(RaceOptions()).count { it.term == "urbs" })
        assertEquals(2, lang.cards(RaceOptions(reverse = true)).count { it.term == "urbs" })
        // Mistakes from either direction are found.
        val mistakes = mapOf("test/urbs/senses" to 1, "test/urbs/1" to 3, "test/gone/0" to 5)
        assertEquals(listOf("test/urbs/senses", "test/urbs/1"), lang.mistakenCards(mistakes).map { it.id })
        // Only the ones mistaken at least POORLY_LEARNED times are added to races.
        assertEquals(listOf("test/urbs/1"), lang.poorlyLearned(mistakes).map { it.id })
    }

    @Test
    fun wordFolderAddsAndReplacesUnits() {
        fun yaml(id: String, term: String) =
            "meta: { id: $id, title: $id, language: de }\nentries:\n  - { id: x, term: $term, translation: [iets] }"
        val files = listOf(
            UnitFile("german", "b.yaml") { yaml("b", "bundled") },
            UnitFile("german", "a.yaml") { yaml("a", "bundled") },
            // A later file with the same id replaces the earlier unit.
            UnitFile("german", "b-edited.yaml") { yaml("b", "edited") },
            UnitFile("german", "c.yaml") { yaml("c", "new") },
            UnitFile("german", "broken.yaml") { "meta: [\n  id: oops" },
            UnitFile("german", "empty.yaml") { "meta: { id: empty }" },
            UnitFile("french", "f.yaml") { yaml("f", "mot") },
        )
        val problems = mutableListOf<String>()
        val languages = loadLanguages(files, problems)

        assertEquals(listOf("french", "german"), languages.map { it.folder })
        val german = languages.last()
        assertEquals(listOf("a", "b", "c"), german.units.map { it.id })
        assertEquals("edited", german.units.first { it.id == "b" }.cards.single().term)
        assertEquals(2, problems.size)
        assertTrue(problems[0], problems[0].startsWith("german/words/broken.yaml: YAML error at line"))
        assertEquals("german/words/empty.yaml: no cards", problems[1])
    }

    @Test
    fun bundledWordListsParse() {
        wordFolders.forEach { folder -> assertTrue("$folder has no words", folder.walkTopDown().any { it.extension == "yaml" }) }
        wordFolders.flatMap { folder -> folder.walkTopDown().filter { it.extension == "yaml" } }.forEach { file ->
            val parsed = parseUnit(file.readText(), file.nameWithoutExtension)
            assertTrue("${file.name} has no cards", parsed.cards.isNotEmpty())
        }
    }

    @Test
    fun languagesUseTheirOwnNames() {
        val files = File("demo").walkTopDown().filter { it.extension == "yaml" }.map { file ->
            UnitFile(file.parentFile!!.parentFile!!.name, file.name) { file.readText() }
        }.toList()
        val names = loadLanguages(files).associate { it.folder to it.name }
        assertEquals("Français", names["french"])
        assertEquals("Ελληνικά", names["greek"])
    }
}
