package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class RaceTest {
    private fun pool(folder: File, lang: String) = File(folder, "$lang/words").listFiles()!!
        .flatMap { parseUnit(it.readText(), it.nameWithoutExtension).cards }

    private fun cards(n: Int) = List(n) { Card("c$it", "e$it", "term $it", "vertaling $it") }

    private fun board(race: Race) = race.left.filterNotNull()

    /** Plays a full race by always solving the first card on the board. */
    private fun play(race: Race, check: (Race) -> Unit = {}): Int {
        var rounds = 0
        while (!race.finished) {
            check(race)
            if (race.solve(board(race).first())) rounds++
        }
        return rounds
    }

    @Test
    fun bothColumnsHoldTheSameCards() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(1))
        play(race) { assertEquals(board(it).toSet(), it.right.filterNotNull().toSet()) }
    }

    @Test
    fun raceHasRoundsTimesPairsMatches() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(2))
        assertEquals(6, board(race).size)
        assertEquals(3, play(race))
        assertEquals(18, race.matched)
        assertTrue(race.left.all { it == null })
    }

    @Test
    fun refillsWhenHalfIsMatched() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(3))
        repeat(2) { race.solve(board(race).first()) }
        assertEquals(4, board(race).size)
        race.solve(board(race).first())
        assertEquals(6, board(race).size)
    }

    @Test
    fun refillMovesEveryRemainingTranslation() {
        repeat(20) { seed ->
            val race = Race(cards(30), pairs = 6, total = 18, random = Random(seed))
            while (!race.finished) {
                val before = race.right.toList()
                val filled = board(race).size
                race.solve(board(race).first())
                if (board(race).size >= filled) {
                    before.forEachIndexed { k, card -> if (card != null && card in race.right) assertFalse(race.right[k] == card) }
                }
            }
        }
    }

    @Test
    fun prefersCardsNotShownYet() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(4))
        val seen = mutableListOf<Card>()
        play(race) { seen += board(it).filter { c -> c !in seen } }
        assertEquals(18, seen.size)
        assertEquals(18, seen.toSet().size)
    }

    @Test
    fun boardIsNeverAmbiguous() {
        wordFolders.forEach { folder ->
            listOf("latin", "german", "french", "greek").forEach { lang ->
                repeat(20) { seed ->
                    val race = Race(pool(folder, lang), pairs = 10, total = 100, random = Random(seed))
                    play(race) {
                        val board = board(it)
                        board.forEach { a -> board.forEach { b -> if (a !== b) assertFalse(a.conflictsWith(b)) } }
                    }
                }
            }
        }
    }

    @Test
    fun smallPoolStillFinishes() {
        val race = Race(cards(2), pairs = 6, total = 18, random = Random(5))
        assertEquals(3, play(race))
    }

    @Test
    fun mistakenCardComesBackWhenOnePairIsLeft() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(6))
        val card = board(race).first()
        race.miss(card)
        race.solve(card)
        var backAt = -1
        val rounds = play(race) { if (backAt < 0 && card in board(it)) backAt = it.matched }
        assertEquals(17, backAt)
        assertEquals(19, race.total)
        assertEquals(19, race.matched)
        assertEquals(3, rounds)
        assertEquals(setOf(card), race.missed)
    }

    @Test
    fun retriesRepeatUntilNoMistakes() {
        val race = Race(cards(30), pairs = 6, total = 18, random = Random(7))
        val card = board(race).first()
        race.miss(card)
        race.solve(card)
        var missedAgain = false
        play(race) {
            if (!missedAgain && card in board(it)) {
                it.miss(card)
                missedAgain = true
            }
        }
        assertTrue(missedAgain)
        assertEquals(20, race.total)
        assertEquals(20, race.matched)
        assertEquals(2, race.mistakes)
    }

    @Test
    fun higherPriorityIsDealtFirst() {
        val race = Race(cards(10), pairs = 3, total = 10, random = Random(8), priority = mapOf("c7" to 5, "c2" to 3, "c4" to 1))
        assertEquals(setOf("c7", "c2", "c4"), board(race).map { it.id }.toSet())
    }
}
