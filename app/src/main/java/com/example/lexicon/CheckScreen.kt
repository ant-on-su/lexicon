package com.example.lexicon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lexicon.ui.theme.BadRed
import com.example.lexicon.ui.theme.Cream
import com.example.lexicon.ui.theme.GoodGreen
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.Muted
import com.example.lexicon.ui.theme.SoonGrey
import kotlinx.coroutines.delay

/** How long the right answer stays before the next word. */
private const val NEXT_MS = 1800L

private enum class OptionLook { Idle, Wrong, Right, Gone }

/**
 * Check: the words of one unit in random order, each with translations to choose from, then the unit's stats.
 * Starts with [start]; a unit picked from the drawer is reported through [onUnit].
 * [onMiss] gets the card of each mistake.
 */
@Composable
fun CheckScreen(
    lang: Language,
    color: Color,
    speech: Speech,
    options: RaceOptions,
    start: WordUnit,
    onUnit: (WordUnit) -> Unit,
    onCue: (Cue) -> Unit,
    onMiss: (Card) -> Unit,
    onDone: () -> Unit,
) {
    var unit by remember(lang) { mutableStateOf(start) }
    var round by remember { mutableIntStateOf(0) }
    val check = remember(unit, round) {
        Check(unit, lang.units.filter { it.id != unit.id }.flatMap { it.cards }, options.choices, options.checkRepeat)
    }
    val pick = { it: WordUnit ->
        unit = it
        onUnit(it)
    }
    // The phone's voice starts up meanwhile.
    LaunchedEffect(Unit) { speech.prepare() }

    UnitDrawer(lang, color, unit, onPick = { pick(lang.units[it]) }) {
        val question = check.question
        if (question == null) {
            Learned(check, unit, color, onAgain = { round++ }, next = lang.units.getOrNull(lang.units.indexOf(unit) + 1), onNext = pick, onDone)
        } else {
            key(question) { Asking(check, question, lang, speech, onCue, onMiss) }
        }
    }
}

@Composable
private fun ColumnScope.Asking(
    check: Check,
    question: Question,
    lang: Language,
    speech: Speech,
    onCue: (Cue) -> Unit,
    onMiss: (Card) -> Unit,
) {
    val entry = question.entry
    var showGrammar by remember { mutableStateOf(false) }
    val canSpeak = lang.audio[entry.audio] != null || remember(speech.ready, lang.code) { speech.canSpeak(lang.code) }
    val advance = {
        if (check.solved) {
            speech.stop()
            check.next()
            if (check.finished) onCue(Cue.Record)
        }
    }
    LaunchedEffect(check.solved) {
        if (check.solved) {
            delay(NEXT_MS)
            advance()
        }
    }

    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Chunky(Color.White, { speech.say(entry.speak, lang.code, lang.audio[entry.audio]) }, Modifier.weight(0.8f).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(AnnotatedString(entry.term), maxSize = 44.sp, Modifier.weight(1f, fill = false), minSize = 16.sp, maxLines = 3)
                if (canSpeak) Text("🔊", fontSize = 20.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("💡", check.canHint) { check.hint() }
            Text(
                "${check.done + 1} / ${check.total}",
                Modifier.weight(1f),
                color = Muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (entry.gender != null || entry.forms != null) SmallButton("Aa", true) { showGrammar = true } else Spacer(Modifier.size(56.dp))
        }

        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            question.options.forEach { option ->
                val look = when {
                    check.solved && option == question.card -> OptionLook.Right
                    check.solved || option in check.removed -> OptionLook.Gone
                    option in check.wrong -> OptionLook.Wrong
                    else -> OptionLook.Idle
                }
                Option(option, look, Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        check.solved -> advance()
                        check.answer(option) -> onCue(Cue.Correct)
                        else -> {
                            onCue(Cue.Mistake)
                            onMiss(question.card)
                        }
                    }
                }
            }
        }

        // Kept free for the example phrase, so nothing moves when a hint shows it.
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            val example = check.example
            if (example != null && check.exampleShown) {
                Chunky(
                    Cream,
                    { speech.say(example.speak, lang.code, lang.audio[example.audio]) },
                    Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    depth = 4.dp,
                ) {
                    val text = buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(example.text) } }
                    FitText(text, maxSize = 20.sp, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 3, weight = FontWeight.Medium)
                }
            }
        }
    }

    if (showGrammar) {
        AlertDialog(
            onDismissRequest = { showGrammar = false },
            confirmButton = { TextButton({ showGrammar = false }) { Text("Done", color = Ink, fontWeight = FontWeight.Black) } },
            title = { Text(entry.term, color = Ink, fontWeight = FontWeight.Black) },
            // The forms of the term only: the translation's would give the answer away.
            text = { Text(listOfNotNull(entry.gender, entry.forms).joinToString(" · "), color = Ink, fontSize = 20.sp) },
            containerColor = Cream,
        )
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Chunky(if (enabled) Color.White else SoonGrey, onClick, Modifier.size(56.dp), enabled, CircleShape, 4.dp) {
        Text(text, color = if (enabled) Ink else Muted, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

/** A translation to choose. Wrong turns it red with a shake, right turns it green and bigger; the others dissolve. */
@Composable
private fun Option(option: Card, look: OptionLook, modifier: Modifier, onTap: () -> Unit) {
    val shake = remember { Animatable(1f) }
    LaunchedEffect(look) {
        if (look == OptionLook.Wrong) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(400, easing = LinearEasing))
        }
    }
    val grow by animateFloatAsState(
        targetValue = if (look == OptionLook.Right) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "grow",
    )
    val fade by animateFloatAsState(if (look == OptionLook.Gone) 0f else 1f, tween(350), label = "fade")
    val face = when (look) {
        OptionLook.Idle, OptionLook.Gone -> Color.White
        OptionLook.Wrong -> BadRed
        OptionLook.Right -> GoodGreen
    }
    Box(modifier) {
        Chunky(
            face,
            onTap,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = grow
                scaleY = grow
                alpha = fade
                translationX = shakeOffset(shake.value) * 14.dp.toPx()
            },
            enabled = look == OptionLook.Idle || look == OptionLook.Right,
            shape = RoundedCornerShape(22.dp),
            depth = 5.dp,
        ) {
            // One extra line on top of the senses, so a long one can still wrap.
            val lines = option.translation.lines().size + 1
            FitText(AnnotatedString(option.translation), maxSize = 26.sp, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), maxLines = lines)
        }
        if (look == OptionLook.Gone) Dissolve(Muted, Modifier.matchParentSize())
    }
}

/** The end of a unit: applause, then how many words went without a mistake, and the mistakes and hints per word. */
@Composable
private fun ColumnScope.Learned(
    check: Check,
    unit: WordUnit,
    color: Color,
    onAgain: () -> Unit,
    next: WordUnit?,
    onNext: (WordUnit) -> Unit,
    onDone: () -> Unit,
) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Applause()
            Text("${unit.title} is learned!", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            val perfect = check.cards.count { it !in check.mistakes }
            Text(
                "$perfect of ${check.cards.size} words without mistakes",
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Counts("Mistakes", check.mistakes)
            Counts("Hints", check.hints)
            ChunkyButton("Again", color, onAgain)
            if (next != null) ChunkyButton("Next unit", Color.White, { onNext(next) }, note = next.title)
            ChunkyButton("Done", Color.White, onDone)
        }
        Confetti(pieces = 260)
    }
}

/** Words with their count, highest first. Nothing when there are none. */
@Composable
private fun Counts(title: String, counts: Map<Card, Int>) {
    if (counts.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        counts.entries.sortedByDescending { it.value }.forEach { (card, count) ->
            Row {
                Text(card.term, Modifier.weight(1f), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$count", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
