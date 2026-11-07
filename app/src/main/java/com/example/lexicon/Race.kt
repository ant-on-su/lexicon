package com.example.lexicon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

const val PENALTY_MS = 3_000L

data class RaceResult(
    val totalMs: Long,
    val mistakes: Int,
    val roundsMs: List<Long>,
    val bestMs: Long,
    val newBest: Boolean,
)

/**
 * The board of a race. It is one continuous board: when half of the pairs are matched,
 * new pairs fill the empty left slots and the right column is reshuffled.
 * A round is every [pairs] matches, a race is [pairs] × rounds matches.
 */
class Race(
    private val pool: List<Card>,
    val pairs: Int,
    rounds: Int,
    private val random: Random = Random.Default,
) {
    val total = pairs * rounds
    val left = mutableStateListOf<Card?>()
    val right = mutableStateListOf<Card?>()
    var matched by mutableIntStateOf(0)
        private set
    var mistakes by mutableIntStateOf(0)
        private set
    val finished get() = matched >= total

    private var dealt = 0
    private val shown = mutableMapOf<String, Int>()

    init {
        require(pool.isNotEmpty()) { "No cards to race with" }
        repeat(pairs) {
            left += null
            right += null
        }
        refill()
    }

    fun miss() {
        mistakes++
    }

    /** Removes a matched pair from the board. Returns true when this match ends a round. */
    fun solve(card: Card): Boolean {
        val i = left.indexOf(card)
        val j = right.indexOf(card)
        if (i < 0 || j < 0) return false
        left[i] = null
        right[j] = null
        matched++
        if (left.count { it != null } * 2 <= pairs) refill()
        return matched % pairs == 0
    }

    private fun refill() {
        val board = left.filterNotNull().toMutableList()
        val fresh = mutableListOf<Card>()
        while (board.size < pairs && dealt < total) {
            val card = pick(board) ?: break
            board += card
            fresh += card
            dealt++
            shown[card.id] = (shown[card.id] ?: 0) + 1
        }
        if (fresh.isEmpty()) return
        val emptyLeft = left.indices.filter { left[it] == null }.shuffled(random)
        fresh.forEachIndexed { k, card -> left[emptyLeft[k]] = card }
        val rightCards = right.filterNotNull() + fresh
        (rightCards + List(pairs - rightCards.size) { null })
            .shuffled(random)
            .forEachIndexed { k, card -> right[k] = card }
    }

    /** A card that is unambiguous next to [board], preferring the least shown ones. */
    private fun pick(board: List<Card>): Card? {
        val options = pool.filter { card -> board.none { it.conflictsWith(card) } }
        val least = options.minOfOrNull { shown[it.id] ?: 0 } ?: return null
        return options.filter { (shown[it.id] ?: 0) == least }.random(random)
    }
}
