package com.example.lexicon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.lexicon.ui.theme.BadRed
import com.example.lexicon.ui.theme.GoodGreen
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private enum class Look { Idle, Selected, Good, Bad }

private const val FEEDBACK_MS = 450L

/** Counts down 3-2-1, then runs the race. Reports matches and mistakes as they happen, then total time, mistakes and time per round. */
@Composable
fun RaceScreen(
    lang: Language,
    color: Color,
    options: RaceOptions,
    onCue: (Cue) -> Unit,
    onFinish: (totalMs: Long, mistakes: Int, roundsMs: List<Long>) -> Unit,
) {
    val race = remember { Race(lang.cards(options), options.pairs, options.rounds) }
    var countdown by remember { mutableIntStateOf(3) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val splits = remember { mutableListOf<Long>() }
    var selectedLeft by remember { mutableStateOf<String?>(null) }
    var selectedRight by remember { mutableStateOf<String?>(null) }
    val good = remember { mutableStateListOf<String>() }
    var bad by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            onCue(Cue.Countdown)
            delay(1000)
            countdown--
        }
        onCue(Cue.Go)
    }
    // The clock only ticks while the app is in the foreground.
    LaunchedEffect(countdown == 0) {
        if (countdown > 0) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    elapsed += now - last
                    last = now
                }
            }
        }
    }

    fun tap(card: Card, isLeft: Boolean) {
        if (card.id in good || bad != null) return
        if (isLeft) {
            selectedLeft = card.id.takeIf { it != selectedLeft }
        } else {
            selectedRight = card.id.takeIf { it != selectedRight }
        }
        val l = selectedLeft ?: return
        val r = selectedRight ?: return
        selectedLeft = null
        selectedRight = null
        if (l == r) {
            val at = elapsed + race.mistakes * PENALTY_MS
            good += l
            onCue(Cue.Correct)
            scope.launch {
                delay(FEEDBACK_MS)
                val roundDone = race.solve(card)
                good -= l
                if (roundDone) splits += at
                if (race.finished) {
                    onFinish(at, race.mistakes, splits.mapIndexed { i, t -> t - (splits.getOrNull(i - 1) ?: 0L) })
                }
            }
        } else {
            race.miss()
            bad = l to r
            onCue(Cue.Mistake)
            scope.launch {
                delay(FEEDBACK_MS)
                bad = null
            }
        }
    }

    if (countdown > 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            key(countdown) {
                val pop = remember { Animatable(2.2f) }
                LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
                Text(
                    "$countdown",
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    color = Ink,
                    fontSize = 160.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(elapsed + race.mistakes * PENALTY_MS), color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("${race.matched}/${race.total}", color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ✕ ${race.mistakes}", color = BadRed, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(true, false).forEach { isLeft ->
                val column = if (isLeft) race.left else race.right
                val showTerm = isLeft != options.reverse
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    column.forEach { card ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (card != null) key(card.id) {
                                val look = when {
                                    card.id in good -> Look.Good
                                    bad?.let { if (isLeft) it.first else it.second } == card.id -> Look.Bad
                                    (if (isLeft) selectedLeft else selectedRight) == card.id -> Look.Selected
                                    else -> Look.Idle
                                }
                                RaceCard(
                                    label = if (showTerm) termLabel(card, options) else AnnotatedString(card.translation),
                                    sub = (if (showTerm) card.termForms else card.translationForms).takeIf { options.showForms },
                                    look = look,
                                    accent = color,
                                    onTap = { tap(card, isLeft) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun termLabel(card: Card, options: RaceOptions): AnnotatedString = buildAnnotatedString {
    append(card.term)
    if (options.showGender && card.gender != null) {
        withStyle(SpanStyle(color = Muted)) { append("  ${card.gender}") }
    }
}

@Composable
private fun RaceCard(label: AnnotatedString, sub: String?, look: Look, accent: Color, onTap: () -> Unit) {
    val appear = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
    val shake = remember { Animatable(1f) }
    LaunchedEffect(look) {
        if (look == Look.Bad) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(400, easing = LinearEasing))
        }
    }
    val gone by animateFloatAsState(
        targetValue = if (look == Look.Good) 0f else 1f,
        animationSpec = tween(250, delayMillis = 180),
        label = "gone",
    )
    val face = when (look) {
        Look.Idle -> Color.White
        Look.Selected -> accent
        Look.Good -> GoodGreen
        Look.Bad -> BadRed
    }
    Chunky(
        face,
        onTap,
        Modifier.fillMaxSize().graphicsLayer {
            val scale = appear.value * gone
            scaleX = scale
            scaleY = scale
            val p = shake.value
            translationX = sin(p * 6 * PI).toFloat() * 14.dp.toPx() * (1 - p)
        },
        shape = RoundedCornerShape(18.dp),
        depth = 4.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FitText(label, maxSize = 22.sp, Modifier.weight(1f, fill = false), minSize = 9.sp)
            if (sub != null) {
                FitText(AnnotatedString(sub), maxSize = 13.sp, minSize = 8.sp, maxLines = 1, color = Muted, weight = FontWeight.Normal)
            }
        }
    }
}
