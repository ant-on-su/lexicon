package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class RaceTest {
    private fun pool(lang: String) = File("resources/$lang/words").listFiles()!!
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
        val race = Race(cards(30), pairs = 6, rounds = 3, random = Random(1))
        play(race) { assertEquals(board(it).toSet(), it.right.filterNotNull().toSet()) }
    }

    @Test
    fun raceHasRoundsTimesPairsMatches() {
        val race = Race(cards(30), pairs = 6, rounds = 3, random = Random(2))
        assertEquals(6, board(race).size)
        assertEquals(3, play(race))
        assertEquals(18, race.matched)
        assertTrue(race.left.all { it == null })
    }

    @Test
    fun refillsWhenHalfIsMatched() {
        val race = Race(cards(30), pairs = 6, rounds = 3, random = Random(3))
        repeat(2) { race.solve(board(race).first()) }
        assertEquals(4, board(race).size)
        race.solve(board(race).first())
        assertEquals(6, board(race).size)
    }

    @Test
    fun prefersCardsNotShownYet() {
        val race = Race(cards(30), pairs = 6, rounds = 3, random = Random(4))
        val seen = mutableListOf<Card>()
        play(race) { seen += board(it).filter { c -> c !in seen } }
        assertEquals(18, seen.size)
        assertEquals(18, seen.toSet().size)
    }

    @Test
    fun boardIsNeverAmbiguous() {
        listOf("latin", "german").forEach { lang ->
            repeat(20) { seed ->
                val race = Race(pool(lang), pairs = 10, rounds = 10, random = Random(seed))
                play(race) {
                    val board = board(it)
                    board.forEach { a -> board.forEach { b -> if (a !== b) assertFalse(a.conflictsWith(b)) } }
                }
            }
        }
    }

    @Test
    fun smallPoolStillFinishes() {
        val race = Race(cards(2), pairs = 6, rounds = 3, random = Random(5))
        assertEquals(3, play(race))
    }
}
