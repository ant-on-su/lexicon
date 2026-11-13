package com.example.lexicon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** Fewer mistaken words than this are only shown, not raced. */
const val MIN_CORRECTION_WORDS = 3

data class RaceResult(
    val totalMs: Long,
    val mistakes: Int,
    val roundsMs: List<Long>,
    /** Time spent on retries of mistaken cards after the last round, 0 when there were none. */
    val retryMs: Long,
    val bestMs: Long,
    val newBest: Boolean,
)

/**
 * The board of a race. It is one continuous board: when half of the pairs are matched,
 * new pairs fill the empty left slots and the right column is reshuffled.
 * A round is every [pairs] matches; [total] starts at the number of cards to deal.
 * Mistaken cards come back when one pair is left after the last card is dealt, until none are mistaken again.
 * [priority] (higher first) breaks ties between equally shown cards.
 */
class Race(
    private val pool: List<Card>,
    val pairs: Int,
    total: Int,
    private val random: Random = Random.Default,
    private val priority: Map<String, Int> = emptyMap(),
) {
    /** Cards dealt from the pool, without retries. */
    val regular = total
    var total by mutableIntStateOf(total)
        private set
    val left = mutableStateListOf<Card?>()
    val right = mutableStateListOf<Card?>()
    var matched by mutableIntStateOf(0)
        private set
    var mistakes by mutableIntStateOf(0)
        private set
    val finished get() = matched >= total

    /** Left cards that were mistaken in this race. */
    val missed: Set<Card> get() = missedCards
    /** Cards matched in this race. */
    val solved: Set<Card> get() = solvedCards

    private val missedCards = mutableSetOf<Card>()
    private val solvedCards = mutableSetOf<Card>()
    private val retries = mutableSetOf<Card>()
    private val again = mutableListOf<Card>()
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

    /** A wrong pair was tapped with [card] on the left: it comes back at the end. */
    fun miss(card: Card) {
        mistakes++
        missedCards += card
        retries += card
    }

    /** Removes a matched pair from the board. Returns true when this match ends a round. */
    fun solve(card: Card): Boolean {
        val i = left.indexOf(card)
        val j = right.indexOf(card)
        if (i < 0 || j < 0) return false
        left[i] = null
        right[j] = null
        matched++
        solvedCards += card
        val onBoard = left.count { it != null }
        if (dealt >= regular && again.isEmpty() && onBoard <= 1 && retries.isNotEmpty()) {
            again += retries.shuffled(random)
            total += retries.size
            retries.clear()
        }
        if (onBoard * 2 <= pairs) refill()
        return matched % pairs == 0 && matched <= regular
    }

    private fun refill() {
        val board = left.filterNotNull().toMutableList()
        val fresh = mutableListOf<Card>()
        while (board.size < pairs) {
            val card = if (dealt < regular) {
                pick(board)?.also {
                    dealt++
                    shown[it.id] = (shown[it.id] ?: 0) + 1
                }
            } else {
                again.firstOrNull { card -> board.none { it.conflictsWith(card) } }?.also { again -= it }
            } ?: break
            board += card
            fresh += card
        }
        if (fresh.isEmpty()) return
        val emptyLeft = left.indices.filter { left[it] == null }.shuffled(random)
        fresh.forEachIndexed { k, card -> left[emptyLeft[k]] = card }
        val before = right.toList()
        val rightCards = right.filterNotNull() + fresh
        val slots = rightCards + List(pairs - rightCards.size) { null }
        // Every card already on the right moves, so its old position gives nothing away.
        var order: List<Card?>
        do order = slots.shuffled(random) while (order.indices.any { before[it] != null && order[it] == before[it] })
        order.forEachIndexed { k, card -> right[k] = card }
    }

    /** A card that is unambiguous next to [board], preferring the least shown ones, then the highest priority. */
    private fun pick(board: List<Card>): Card? {
        val options = pool.filter { card -> board.none { it.conflictsWith(card) } }
        val least = options.minOfOrNull { shown[it.id] ?: 0 } ?: return null
        val fewest = options.filter { (shown[it.id] ?: 0) == least }
        val top = fewest.maxOf { priority[it.id] ?: 0 }
        return fewest.filter { (priority[it.id] ?: 0) == top }.random(random)
    }
}
