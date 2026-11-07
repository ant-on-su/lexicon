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
    fun bundledWordListsParse() {
        val files = File("resources").walkTopDown().filter { it.extension == "yaml" }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val parsed = parseUnit(file.readText(), file.nameWithoutExtension)
            assertTrue("${file.name} has no cards", parsed.cards.isNotEmpty())
        }
    }
}
