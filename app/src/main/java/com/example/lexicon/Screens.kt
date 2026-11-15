package com.example.lexicon

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.lexicon.ui.theme.Cream
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.MistakePink
import com.example.lexicon.ui.theme.MistakeRed
import com.example.lexicon.ui.theme.Muted
import com.example.lexicon.ui.theme.SoonGrey
import kotlinx.coroutines.delay

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

/**
 * The splash poster, shown over the app for 1.5 s at start, then fading out to reveal it.
 * A blurred, cropped copy fills the screen around the poster. Taps are swallowed meanwhile.
 */
@Composable
fun Splash() {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_500)
        visible = false
    }
    AnimatedVisibility(visible, exit = fadeOut()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {}) {
            val poster = painterResource(R.drawable.splash)
            Image(poster, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
            Image(poster, "Lexicon", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun LanguageScreen(
    library: Library?,
    colorOf: (Language) -> Color,
    onFolder: (Uri?) -> Unit,
    feedback: Feedback,
    feedbackOptions: FeedbackOptions,
    onFeedbackOptions: (FeedbackOptions) -> Unit,
    onPick: (Language) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        CenterColumn {
            Title("Lexicon")
            val languages = library?.languages
            when {
                languages == null -> Unit
                languages.isEmpty() -> Text("No word lists found", color = Muted)
                else -> languages.forEach { lang -> ChunkyButton(lang.name, colorOf(lang), { onPick(lang) }) }
            }
        }
        SettingsButton { showSettings = true }
    }
    if (showSettings && library != null) {
        GeneralSettings(library, onFolder, feedback, feedbackOptions, onFeedbackOptions) { showSettings = false }
    }
}

/**
 * Race!, blocked while there are more mistaken words than [RaceOptions.maxMistakes], and "Correct mistakes",
 * which shows the words while there are fewer than [MIN_CORRECTION_WORDS] and runs a correction from there.
 */
@Composable
fun ModeScreen(
    lang: Language,
    color: Color,
    options: RaceOptions,
    onOptions: (RaceOptions) -> Unit,
    mistakes: Map<String, Int>,
    onRace: () -> Unit,
    onCorrect: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMistakes by rememberSaveable { mutableStateOf(false) }
    val mistaken = remember(lang, mistakes) { lang.mistakenCards(mistakes) }
    Box(Modifier.fillMaxSize()) {
        CenterColumn {
            Title(lang.name)
            ChunkyButton("Learn", SoonGrey, {}, note = "soon", enabled = false)
            ChunkyButton("Test", SoonGrey, {}, note = "soon", enabled = false)
            val hasUnits = lang.activeUnits(options).isNotEmpty()
            val blocked = mistaken.size > options.maxMistakes
            ChunkyButton(
                "Race!",
                color,
                onRace,
                note = if (!hasUnits) "no units selected" else if (blocked) TOO_MANY_MISTAKES else null,
                enabled = hasUnits && !blocked,
            )
            val count = mistaken.sumOf { mistakes.getValue(it.id) }
            val heat = (mistaken.size.toFloat() / options.maxMistakes).coerceIn(0f, 1f)
            ChunkyButton(
                if (count > 0) "Correct mistakes · $count" else "Correct mistakes",
                if (count > 0) lerp(MistakePink, MistakeRed, heat) else SoonGrey,
                { if (mistaken.size >= MIN_CORRECTION_WORDS) onCorrect() else showMistakes = true },
                note = when {
                    count == 0 -> "none yet"
                    mistaken.size < MIN_CORRECTION_WORDS -> "see the words · runs from $MIN_CORRECTION_WORDS words"
                    else -> null
                },
                enabled = count > 0,
            )
        }
        SettingsButton { showSettings = true }
    }
    if (showSettings) LanguageSettings(lang, color, options, onOptions) { showSettings = false }
    if (showMistakes) {
        ChoiceDialog("Your mistakes", onDismiss = { showMistakes = false }) {
            mistaken.forEach { card ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(card.term, Modifier.weight(1f), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(card.translation, Modifier.weight(1f), color = Ink, fontSize = 18.sp)
                }
            }
            Text(
                "A correction run starts from $MIN_CORRECTION_WORDS words.",
                Modifier.padding(top = 8.dp),
                color = Muted,
            )
        }
    }
}

private const val TOO_MANY_MISTAKES = "too many mistakes: correct them first"

@Composable
fun StartScreen(lang: Language, color: Color, options: RaceOptions, bestMs: Long?, blocked: Boolean, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Title(lang.name)
            Chunky(if (blocked) SoonGrey else color, onStart, Modifier.size(240.dp), !blocked, CircleShape, 10.dp) {
                Text("START", color = if (blocked) Muted else Ink, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }
            if (blocked) Text(TOO_MANY_MISTAKES, color = Ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val (from, to) = if (options.reverse) lang.translationName to lang.name else lang.name to lang.translationName
            Text("$from → $to · ${options.pairs} pairs · ${options.rounds} rounds", color = Muted, textAlign = TextAlign.Center)
            if (bestMs != null) Text("Best: ${formatTime(bestMs)}", color = Ink, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BoxScope.SettingsButton(onClick: () -> Unit) {
    Chunky(
        Color.White,
        onClick,
        Modifier.align(Alignment.TopEnd).padding(16.dp).size(56.dp),
        shape = CircleShape,
        depth = 4.dp,
    ) { Text("⚙", fontSize = 26.sp, color = Muted) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cream) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Settings shared by all languages: sound and vibration, the word folder, and any word files that couldn't be used. */
@Composable
private fun GeneralSettings(
    library: Library,
    onFolder: (Uri?) -> Unit,
    feedback: Feedback,
    feedbackOptions: FeedbackOptions,
    onFeedbackOptions: (FeedbackOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolder(uri)
    }
    var soundFor by rememberSaveable { mutableStateOf<Cue?>(null) }
    var vibeFor by rememberSaveable { mutableStateOf<Cue?>(null) }
    SettingsSheet(onDismiss) {
        SectionTitle("Sound & vibration")
        SwitchRow("Sound", feedbackOptions.sound) { onFeedbackOptions(feedbackOptions.copy(sound = it)) }
        if (feedback.canVibrate) {
            SwitchRow("Vibration", feedbackOptions.vibration) { onFeedbackOptions(feedbackOptions.copy(vibration = it)) }
        }
        val vibration = feedbackOptions.vibration && feedback.canVibrate
        if (feedbackOptions.sound || vibration) Cue.entries.forEach { cue ->
            Heading(cue.title)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (feedbackOptions.sound) {
                    val sound = feedbackOptions.sounds.getValue(cue)
                    val title = remember(sound) { feedback.title(sound) }
                    SheetButton("♪ $title", Modifier.weight(1f)) { soundFor = cue }
                }
                if (vibration) SheetButton("📳 ${feedbackOptions.vibes.getValue(cue).title}", Modifier.weight(1f)) { vibeFor = cue }
            }
        }

        SectionTitle("Word folder")
        Text(
            "Your word lists: a folder per language with a words folder of YAML files inside. " +
                "They're read at every start and replace the demo words.",
            color = Muted,
        )
        Text(library.folderName ?: "None: demo words only", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetButton("Choose folder", Modifier.weight(1f)) { pick.launch(null) }
            if (library.folderName != null) SheetButton("Remove", Modifier.weight(1f)) { onFolder(null) }
        }
        if (library.problems.isNotEmpty()) {
            Heading("Skipped")
            library.problems.forEach { Text(it, color = Muted) }
        }
    }
    soundFor?.let { cue ->
        SoundChoice(cue, feedbackOptions.sounds.getValue(cue), feedback, onDismiss = { soundFor = null }) {
            onFeedbackOptions(feedbackOptions.copy(sounds = feedbackOptions.sounds + (cue to it)))
        }
    }
    vibeFor?.let { cue ->
        ChoiceDialog(cue.title, onDismiss = { vibeFor = null }) {
            Vibe.entries.forEach { vibe ->
                ChoiceRow(vibe.title, feedbackOptions.vibes[cue] == vibe) {
                    onFeedbackOptions(feedbackOptions.copy(vibes = feedbackOptions.vibes + (cue to vibe)))
                    feedback.vibrate(vibe)
                }
            }
        }
    }
}

/** Built-in sounds (the ones made for this cue first), silence, or a sound from the phone's picker. Each is played when picked. */
@Composable
private fun SoundChoice(cue: Cue, current: String, feedback: Feedback, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)?.let { onPick(it.toString()) }
    }
    ChoiceDialog(cue.title, onDismiss) {
        ChoiceRow("Off", current.isEmpty()) { onPick("") }
        Tone.entries.sortedBy { it.cue != cue }.forEach { tone ->
            ChoiceRow(tone.title, current == tone.name) {
                onPick(tone.name)
                feedback.sound(tone.name)
            }
        }
        val isPhone = current.startsWith("content:")
        val phoneTitle = remember(current) { if (isPhone) feedback.title(current) else null }
        ChoiceRow(phoneTitle?.let { "Phone: $it" } ?: "Phone sound…", isPhone) {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, cue.title)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (isPhone) current.toUri() else null)
            try {
                phonePicker.launch(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Done", color = Ink, fontWeight = FontWeight.Black) } },
        title = { Text(title, color = Ink, fontWeight = FontWeight.Black) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        containerColor = Cream,
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), color = Ink, fontSize = 18.sp)
    }
}

@Composable
private fun SheetButton(text: String, modifier: Modifier, color: Color = Color.White, onClick: () -> Unit) {
    Chunky(color, onClick, modifier.height(64.dp), shape = RoundedCornerShape(20.dp), depth = 4.dp) {
        FitText(AnnotatedString(text), maxSize = 16.sp, Modifier.padding(8.dp), maxLines = 1)
    }
}

/** Settings for one language: the units shared by every mode, then a section per mode. */
@Composable
private fun LanguageSettings(
    lang: Language,
    color: Color,
    options: RaceOptions,
    onChange: (RaceOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsSheet(onDismiss) {
        SectionTitle("Units")
        val ids = lang.units.map { it.id }
        val included = lang.activeUnits(options).map { it.id }.toSet()
        if (ids.size > 1) {
            val all = when (included.size) {
                ids.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
            Row(
                Modifier.fillMaxWidth().triStateToggleable(all, role = Role.Checkbox) {
                    val excluded = if (all == ToggleableState.On) ids else emptyList()
                    onChange(options.copy(excludedUnits = excluded.toSet()))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(all, onClick = null)
                Text("All units", Modifier.padding(start = 8.dp), color = Ink, fontWeight = FontWeight.Black)
            }
        }
        lang.units.forEach { unit ->
            val on = unit.id in included
            Row(
                Modifier.fillMaxWidth().toggleable(on) {
                    val excluded = if (it) ids - included - unit.id else ids - included + unit.id
                    onChange(options.copy(excludedUnits = excluded.toSet()))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(on, onCheckedChange = null)
                Text(unit.title, Modifier.padding(start = 8.dp), color = Ink)
            }
        }

        SectionTitle("Race")
        Heading("Direction")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(false, true).forEach { reverse ->
                val (from, to) = if (reverse) lang.translationName to lang.name else lang.name to lang.translationName
                SheetButton("$from → $to", Modifier.weight(1f), if (options.reverse == reverse) color else Color.White) {
                    onChange(options.copy(reverse = reverse))
                }
            }
        }

        Stepper("Pairs per round", options.pairs, PairsRange) { onChange(options.copy(pairs = it)) }
        Stepper("Rounds", options.rounds, RoundsRange) { onChange(options.copy(rounds = it)) }
        SwitchRow("Show forms", options.showForms) { onChange(options.copy(showForms = it)) }
        if (lang.hasGender) {
            SwitchRow("Show gender (m/v/o)", options.showGender) { onChange(options.copy(showGender = it)) }
        }
        Stepper("Max mistaken words", options.maxMistakes, MaxMistakesRange) { onChange(options.copy(maxMistakes = it)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 16.dp))
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
fun ResultsScreen(color: Color, result: RaceResult, canAgain: Boolean, onAgain: () -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CenterColumn {
            if (result.newBest) Banner("🏆 New best!", color)
            Text(formatTime(result.totalMs), color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Black)
            Text("Mistakes: ${result.mistakes}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chips = result.roundsMs.mapIndexed { i, ms -> "R${i + 1}  ${formatTime(ms)}" } +
                    listOfNotNull(result.retryMs.takeIf { it > 0 }?.let { "Retries  ${formatTime(it)}" })
                chips.forEach { chip ->
                    Text(
                        chip,
                        Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Ink,
                    )
                }
            }
            Text("Best: ${formatTime(result.bestMs)}", color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ChunkyButton("Again", color, onAgain, note = if (canAgain) null else TOO_MANY_MISTAKES, enabled = canAgain)
            ChunkyButton("Menu", Color.White, onMenu)
        }
        Confetti(pieces = if (result.newBest) 220 else 110)
    }
}

/**
 * The end of a correction run: [cleared] words matched without a mistake are gone, [left] are still to correct.
 * When none are left, it's applauded.
 */
@Composable
fun CorrectedScreen(color: Color, mistakes: Int, cleared: Int, left: Int, onAgain: () -> Unit, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CenterColumn {
            if (left == 0) {
                Applause()
                Banner("🎉 All clear!", color)
                Text("$cleared words corrected", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("Mistakes: $mistakes", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("$cleared cleared · $left to go", color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val canAgain = left >= MIN_CORRECTION_WORDS
                ChunkyButton(
                    "Again",
                    color,
                    onAgain,
                    note = if (canAgain) null else "runs from $MIN_CORRECTION_WORDS words",
                    enabled = canAgain,
                )
            }
            ChunkyButton("Done", Color.White, onDone)
        }
        if (left == 0) Confetti(pieces = 260)
    }
}

@Composable
private fun Applause() {
    val clap by rememberInfiniteTransition(label = "clap").animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(220), RepeatMode.Reverse),
        label = "clap",
    )
    Text("👏", Modifier.graphicsLayer { scaleX = clap; scaleY = clap; rotationZ = (clap - 1.125f) * 60 }, fontSize = 110.sp)
}

@Composable
private fun Banner(text: String, color: Color) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse",
    )
    Text(
        text,
        Modifier
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        color = Ink,
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
    )
}
