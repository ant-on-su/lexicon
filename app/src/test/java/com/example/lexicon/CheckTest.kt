package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class CheckTest {
    private fun units(folder: File, lang: String) = File(folder, "$lang/words").listFiles()!!
        .map { parseUnit(it.readText(), it.nameWithoutExtension) }

    private val demo = units(File("demo"), "latin").first()

    private fun entry(id: String, examples: Int = 0) =
        Entry(id, "term $id", listOf("vertaling $id"), examples = List(examples) { Example("zin $it", null, "zin $it", "") })

    private fun unit(n: Int, examples: Int = 0): WordUnit {
        val entries = List(n) { entry("e$it", examples) }
        val cards = entries.map { Card("u/${it.id}/0", "u/${it.id}", it.term, it.translations.first()) }
        return WordUnit("u", "Unit", null, null, cards, entries = entries)
    }

    /** Answers every word right, after [before] on each question. */
    private fun play(check: Check, before: (Check) -> Unit = {}) {
        while (!check.finished) {
            before(check)
            assertTrue(check.answer(check.question!!.card))
            check.next()
        }
    }

    @Test
    fun everyWordOnce() {
        val check = Check(demo, emptyList(), choices = 4, repeatMistakes = false, random = Random(1))
        val asked = mutableListOf<Card>()
        play(check) { asked += it.question!!.card }
        assertEquals(demo.cards.toSet(), asked.toSet())
        assertEquals(demo.cards.size, asked.size)
        assertEquals(check.total, check.done)
    }

    @Test
    fun questionMatchesItsEntry() {
        val check = Check(demo, emptyList(), choices = 4, repeatMistakes = false, random = Random(2))
        play(check) { assertEquals(it.question!!.card.term, it.question!!.entry.term) }
    }

    @Test
    fun onlyOneOptionIsRight() {
        wordFolders.forEach { folder ->
            listOf("latin", "german", "french", "greek").forEach { lang ->
                val units = units(folder, lang)
                units.forEach { unit ->
                    val others = units.filter { it.id != unit.id }.flatMap { it.cards }
                    ChoicesRange.forEach { choices ->
                        play(Check(unit, others, choices, repeatMistakes = false, random = Random(choices))) {
                            val q = it.question!!
                            val multi = '\n' in q.card.translation
                            assertEquals(if (multi) minOf(choices, MULTI_SENSE_CHOICES) else choices, q.options.size)
                            assertEquals(1, q.options.count { o -> o == q.card })
                            q.options.forEach { a -> q.options.forEach { b -> if (a !== b) assertFalse(a.conflictsWith(b)) } }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun smallUnitTakesWrongOptionsFromOthers() {
        val others = List(10) { Card("o/$it/0", "o/$it", "other $it", "ander $it") }
        val check = Check(unit(2), others, choices = 5, repeatMistakes = false, random = Random(3))
        assertEquals(5, check.question!!.options.size)
    }

    @Test
    fun mistakesAreCounted() {
        val check = Check(unit(10), emptyList(), choices = 4, repeatMistakes = false, random = Random(4))
        val q = check.question!!
        val wrong = q.options.first { it != q.card }
        assertFalse(check.answer(wrong))
        assertFalse(check.answer(wrong))
        assertEquals(mapOf(q.card to 1), check.mistakes.toMap())
        assertTrue(check.answer(q.card))
        check.next()
        play(check)
        assertEquals(10, check.done)
    }

    @Test
    fun mistakenWordsComeBackWhenAsked() {
        val check = Check(unit(10), emptyList(), choices = 4, repeatMistakes = true, random = Random(5))
        val q = check.question!!
        check.answer(q.options.first { it != q.card })
        check.answer(q.card)
        check.next()
        assertEquals(11, check.total)
        var last: Card? = null
        play(check) { last = it.question!!.card }
        assertEquals(q.card, last)
        assertEquals(11, check.done)
    }

    @Test
    fun firstHintShowsTheExample() {
        val check = Check(unit(10, examples = 1), emptyList(), choices = 5, repeatMistakes = false, random = Random(6))
        check.hint()
        assertTrue(check.exampleShown)
        assertTrue(check.removed.isEmpty())
        assertTrue(check.hints.isEmpty())
        check.hint()
        assertEquals(1, check.removed.size)
        assertEquals(1, check.hints[check.question!!.card])
    }

    @Test
    fun hintsLeaveTheRightOptionAndOneWrong() {
        val check = Check(unit(10), emptyList(), choices = 5, repeatMistakes = false, random = Random(7))
        val q = check.question!!
        repeat(10) { check.hint() }
        assertFalse(check.canHint)
        assertFalse(check.exampleShown)
        assertEquals(3, check.removed.size)
        assertFalse(q.card in check.removed)
        assertEquals(3, check.hints[q.card])
    }

    @Test
    fun hintsNeverLeaveOnlyTappedWrongOnes() {
        val check = Check(unit(10), emptyList(), choices = 3, repeatMistakes = false, random = Random(8))
        val q = check.question!!
        check.answer(q.options.first { it != q.card })
        assertFalse(check.canHint)
        check.hint()
        assertTrue(check.removed.isEmpty())
    }

    @Test
    fun emptyUnitIsFinished() {
        val check = Check(unit(0), emptyList(), choices = 4, repeatMistakes = false)
        assertNull(check.question)
        assertTrue(check.finished)
    }
}
