package com.example.lexicon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

val ChoicesRange = 3..5

/** Options for a card with several senses: they're long, so there are fewer of them. */
const val MULTI_SENSE_CHOICES = 3

/** One word to check: its entry, its term-to-translation card, and the translations to choose from, in random order. */
class Question(val entry: Entry, val card: Card, val options: List<Card>)

private val Card.multiSense get() = '\n' in translation

/**
 * A check of one unit: every entry once, in random order, with [choices] translations to choose from.
 * One is right; the wrong ones are random cards of the unit that share no meaning with it or with each other,
 * and from the [others] (the other units) when the unit is too small.
 * A card with several senses shows them all, so it gets at most [MULTI_SENSE_CHOICES] options. Cards with several senses
 * are its preferred wrong ones and avoided for the others, so the shape of an option gives nothing away.
 * With [repeatMistakes], a word answered with a mistake comes back at the end, until it's answered without one.
 */
class Check(
    unit: WordUnit,
    private val others: List<Card>,
    private val choices: Int,
    private val repeatMistakes: Boolean,
    private val random: Random = Random.Default,
) {
    private val pool = unit.cards
    private val queue = unit.cards.mapNotNull { card ->
        unit.entries.firstOrNull { "${unit.id}/${it.id}" == card.entry && it.term == card.term }?.let { card to it }
    }.shuffled(random).toMutableList()

    /** Words to answer, with the repeats added so far. */
    var total by mutableIntStateOf(queue.size)
        private set
    /** Words answered. */
    var done by mutableIntStateOf(0)
        private set
    /** The current word, null once all are answered. */
    var question by mutableStateOf<Question?>(null)
        private set
    /** Wrong options of the current word that were tapped, and those taken away by hints. */
    val wrong = mutableStateListOf<Card>()
    val removed = mutableStateListOf<Card>()
    var exampleShown by mutableStateOf(false)
        private set
    /** The current word was answered right. */
    var solved by mutableStateOf(false)
        private set
    val finished get() = question == null

    /** Mistakes, and hints that took away an option, per card. */
    val mistakes = mutableMapOf<Card, Int>()
    val hints = mutableMapOf<Card, Int>()
    /** Every card asked, without the repeats. */
    val cards = queue.map { it.first }

    val example: Example? get() = question?.entry?.examples?.firstOrNull()

    /** Wrong options a hint can take away. The last one not tapped yet stays, so the answer isn't given away. */
    private val removable: List<Card>
        get() = question?.let { q -> q.options.filter { it != q.card && it !in wrong && it !in removed } }.orEmpty()

    val canHint get() = question != null && !solved && ((example != null && !exampleShown) || removable.size >= 2)

    init {
        ask()
    }

    /** Taps an option: true when it's the right one. A wrong one counts a mistake. */
    fun answer(option: Card): Boolean {
        val q = question ?: return false
        if (solved || option in wrong || option in removed) return false
        if (option == q.card) {
            solved = true
            return true
        }
        wrong += option
        mistakes[q.card] = (mistakes[q.card] ?: 0) + 1
        return false
    }

    /** The first hint shows the example phrase, when there is one. Each next one takes away a wrong option and is counted. */
    fun hint() {
        val q = question ?: return
        if (!canHint) return
        if (example != null && !exampleShown) {
            exampleShown = true
            return
        }
        removed += removable.random(random)
        hints[q.card] = (hints[q.card] ?: 0) + 1
    }

    /** On to the next word, once the current one is answered right. */
    fun next() {
        val q = question ?: return
        if (!solved) return
        if (repeatMistakes && wrong.isNotEmpty()) {
            queue += q.card to q.entry
            total++
        }
        done++
        ask()
    }

    private fun ask() {
        wrong.clear()
        removed.clear()
        exampleShown = false
        solved = false
        question = queue.removeFirstOrNull()?.let { (card, entry) -> Question(entry, card, options(card)) }
    }

    private fun options(card: Card): List<Card> {
        val count = if (card.multiSense) minOf(choices, MULTI_SENSE_CHOICES) else choices
        val picked = mutableListOf<Card>()
        fun pick(from: List<Card>) {
            // Shuffled, then the same shape first: the sort keeps the shuffled order within each shape.
            from.shuffled(random).sortedBy { it.multiSense != card.multiSense }.forEach { c ->
                if (picked.size < count - 1 && !c.conflictsWith(card) && picked.none { it.conflictsWith(c) }) picked += c
            }
        }
        pick(pool)
        if (picked.size < count - 1) pick(others)
        return (picked + card).shuffled(random)
    }
}
