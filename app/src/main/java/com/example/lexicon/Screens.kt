package com.example.lexicon

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lexicon.ui.theme.Cream
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.Muted
import com.example.lexicon.ui.theme.SoonGrey

@Composable
private fun CenterColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    Text(text, color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
}

@Composable
fun LanguageScreen(languages: List<Language>?, colorOf: (Language) -> Color, onPick: (Language) -> Unit) {
    CenterColumn {
        Title("Lexicon")
        when {
            languages == null -> Unit
            languages.isEmpty() -> Text("No word lists found", color = Muted)
            else -> languages.forEach { lang -> ChunkyButton(lang.name, colorOf(lang), { onPick(lang) }) }
        }
    }
}

@Composable
fun ModeScreen(lang: Language, color: Color, onRace: () -> Unit) {
    CenterColumn {
        Title(lang.name)
        ChunkyButton("Learn", SoonGrey, {}, note = "soon", enabled = false)
        ChunkyButton("Test", SoonGrey, {}, note = "soon", enabled = false)
        ChunkyButton("Race!", color, onRace)
    }
}

@Composable
fun StartScreen(
    lang: Language,
    color: Color,
    options: RaceOptions,
    bestMs: Long?,
    onOptions: (RaceOptions) -> Unit,
    onStart: () -> Unit,
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Chunky(
            Color.White,
            { showOptions = true },
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(56.dp),
            shape = CircleShape,
            depth = 4.dp,
        ) { Text("⚙", fontSize = 26.sp, color = Muted) }

        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Title(lang.name)
            Chunky(color, onStart, Modifier.size(240.dp), shape = CircleShape, depth = 10.dp) {
                Text("START", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }
            val (from, to) = if (options.reverse) lang.translationName to lang.name else lang.name to lang.translationName
            Text("$from → $to · ${options.pairs} pairs · ${options.rounds} rounds", color = Muted, textAlign = TextAlign.Center)
            if (bestMs != null) Text("Best: ${formatTime(bestMs)}", color = Ink, fontWeight = FontWeight.Bold)
        }
    }
    if (showOptions) OptionsSheet(lang, color, options, onOptions) { showOptions = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(
    lang: Language,
    color: Color,
    options: RaceOptions,
    onChange: (RaceOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cream) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Units")
            val included = lang.activeUnits(options).map { it.id }.toSet()
            lang.units.forEach { unit ->
                val on = unit.id in included
                val isLast = on && included.size == 1
                Row(
                    Modifier.fillMaxWidth().toggleable(on, enabled = !isLast) {
                        val excluded = if (it) lang.units.map { u -> u.id } - included - unit.id else lang.units.map { u -> u.id } - included + unit.id
                        onChange(options.copy(excludedUnits = excluded.toSet()))
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, onCheckedChange = null, enabled = !isLast)
                    Text(unit.title, Modifier.padding(start = 8.dp), color = Ink)
                }
            }

            Heading("Direction")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false, true).forEach { reverse ->
                    val (from, to) = if (reverse) lang.translationName to lang.name else lang.name to lang.translationName
                    Chunky(
                        if (options.reverse == reverse) color else Color.White,
                        { onChange(options.copy(reverse = reverse)) },
                        Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        depth = 4.dp,
                    ) { FitText(AnnotatedString("$from → $to"), maxSize = 16.sp, Modifier.padding(8.dp), maxLines = 1) }
                }
            }

            Stepper("Pairs per round", options.pairs, PairsRange) { onChange(options.copy(pairs = it)) }
            Stepper("Rounds", options.rounds, RoundsRange) { onChange(options.copy(rounds = it)) }
            SwitchRow("Show forms", options.showForms) { onChange(options.copy(showForms = it)) }
            if (lang.hasGender) {
                SwitchRow("Show gender (m/v/o)", options.showGender) { onChange(options.copy(showGender = it)) }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Chunky(Color.White, { onChange(value - 1) }, Modifier.size(52.dp), value > range.first, CircleShape, 4.dp) {
            Text("−", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Text("$value", Modifier.width(52.dp), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Chunky(Color.White, { onChange(value + 1) }, Modifier.size(52.dp), value < range.last, CircleShape, 4.dp) {
            Text("+", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Switch(checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(color: Color, result: RaceResult, onAgain: () -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CenterColumn {
            if (result.newBest) NewBest(color)
            Text(formatTime(result.totalMs), color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Black)
            val penalty = if (result.mistakes > 0) "  (+${result.mistakes * PENALTY_MS / 1000} s)" else ""
            Text("Mistakes: ${result.mistakes}$penalty", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                result.roundsMs.forEachIndexed { i, ms ->
                    Text(
                        "R${i + 1}  ${formatTime(ms)}",
                        Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Ink,
                    )
                }
            }
            Text("Best: ${formatTime(result.bestMs)}", color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ChunkyButton("Again", color, onAgain)
            ChunkyButton("Menu", Color.White, onMenu)
        }
        Confetti(pieces = if (result.newBest) 220 else 110)
    }
}

@Composable
private fun NewBest(color: Color) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse",
    )
    Text(
        "🏆 New best!",
        Modifier
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        color = Ink,
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
    )
}
