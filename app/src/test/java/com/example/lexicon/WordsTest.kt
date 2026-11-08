package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // princeps uses only its first sense, urbs one card per sense.
        assertEquals(listOf("de eerste, leider"), unit.cards.filter { it.term == "princeps" }.map { it.translation })
        assertEquals(2, unit.cards.count { it.term == "urbs" })
        assertEquals("gen. principis", card("princeps").termForms)
        assertEquals("m", card("princeps").gender)
        assertEquals("die Dörfer", card("das Dorf").termForms)
        assertEquals("de dorpen", card("das Dorf").translationForms)
        assertNull(card("dux").termForms)
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
        val (urbs1, urbs2) = unit.cards.filter { it.term == "urbs" }
        assertTrue(urbs1.conflictsWith(urbs2))
        assertFalse(card("das Dorf").conflictsWith(card("im Dorf")))
        assertFalse(card("dux").conflictsWith(card("das Dorf")))
    }

    @Test
    fun wordFolderAddsAndReplacesUnits() {
        fun yaml(id: String, term: String) =
            "meta: { id: $id, title: $id, language: de }\nentries:\n  - { id: x, term: $term, translation: [iets] }"
        val files = listOf(
            UnitFile("german", "b.yaml") { yaml("b", "bundled") },
            UnitFile("german", "a.yaml") { yaml("a", "bundled") },
            // From the word folder, after the bundled files.
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
        val files = File("resources").walkTopDown().filter { it.extension == "yaml" }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val parsed = parseUnit(file.readText(), file.nameWithoutExtension)
            assertTrue("${file.name} has no cards", parsed.cards.isNotEmpty())
        }
    }

    @Test
    fun languagesUseTheirOwnNames() {
        val files = File("resources").walkTopDown().filter { it.extension == "yaml" }.map { file ->
            UnitFile(file.parentFile!!.parentFile!!.name, file.name) { file.readText() }
        }.toList()
        val names = loadLanguages(files).associate { it.folder to it.name }
        assertEquals("Français", names["french"])
        assertEquals("Ελληνικά", names["greek"])
    }
}
