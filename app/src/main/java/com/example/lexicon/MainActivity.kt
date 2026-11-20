package com.example.lexicon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.lexicon.ui.theme.Cream
import com.example.lexicon.ui.theme.LexiconTheme
import com.example.lexicon.ui.theme.Pastels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var feedback: Feedback
    private lateinit var speech: Speech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val prefs = Prefs(this)
        feedback = Feedback(this)
        speech = Speech(this)
        setContent {
            LexiconTheme {
                Box {
                    LexiconApp(prefs, feedback, speech)
                    Splash()
                }
            }
        }
    }

    override fun onDestroy() {
        feedback.release()
        speech.release()
        super.onDestroy()
    }
}

private sealed interface Screen {
    data object Languages : Screen
    data class Modes(val lang: Language) : Screen
    data class Learning(val lang: Language) : Screen
    data class Checking(val lang: Language) : Screen
    data class Start(val lang: Language) : Screen
    data class Racing(val lang: Language, val options: RaceOptions) : Screen
    data class Results(val lang: Language, val options: RaceOptions, val result: RaceResult) : Screen
    data class Correcting(val lang: Language, val options: RaceOptions) : Screen
    data class Corrected(val lang: Language, val options: RaceOptions, val mistakes: Int, val cleared: Int, val left: Int) : Screen
}

@Composable
private fun LexiconApp(prefs: Prefs, feedback: Feedback, speech: Speech) {
    val context = LocalContext.current.applicationContext
    var folder by remember { mutableStateOf(prefs.wordFolder) }
    var feedbackOptions by remember { mutableStateOf(prefs.feedback()) }
    val library by produceState<Library?>(null, folder) {
        value = withContext(Dispatchers.IO) { loadLibrary(context, folder) }
    }
    val languages = library?.languages
    var screen by remember { mutableStateOf<Screen>(Screen.Languages) }
    val colorOf = { lang: Language -> Pastels[(languages?.indexOf(lang) ?: 0).coerceAtLeast(0) % Pastels.size] }

    BackHandler(enabled = screen != Screen.Languages) {
        screen = when (val s = screen) {
            Screen.Languages -> s
            is Screen.Modes -> Screen.Languages
            is Screen.Learning -> Screen.Modes(s.lang)
            is Screen.Checking -> Screen.Modes(s.lang)
            is Screen.Start -> Screen.Modes(s.lang)
            is Screen.Racing -> Screen.Start(s.lang)
            is Screen.Results -> Screen.Start(s.lang)
            is Screen.Correcting -> Screen.Modes(s.lang)
            is Screen.Corrected -> Screen.Modes(s.lang)
        }
    }

    Box(Modifier.fillMaxSize().background(Cream).safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Languages -> LanguageScreen(
                library = library,
                colorOf = colorOf,
                onFolder = { uri ->
                    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    folder?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, read) } }
                    uri?.let { context.contentResolver.takePersistableUriPermission(it, read) }
                    prefs.wordFolder = uri
                    folder = uri
                },
                feedback = feedback,
                feedbackOptions = feedbackOptions,
                onFeedbackOptions = {
                    feedbackOptions = it
                    prefs.saveFeedback(it)
                },
                onPick = { screen = Screen.Modes(it) },
            )
            is Screen.Modes -> {
                var options by remember(s.lang) { mutableStateOf(prefs.options(s.lang.folder)) }
                val mistakes = remember(s.lang) { prefs.mistakes(s.lang.folder) }
                ModeScreen(
                    lang = s.lang,
                    color = colorOf(s.lang),
                    options = options,
                    onOptions = {
                        options = it
                        prefs.saveOptions(s.lang.folder, it)
                    },
                    mistakes = mistakes,
                    onLearn = {
                        // Check follows Learn: it opens next at the unit Learn ends at.
                        prefs.saveCheckUnit(s.lang.folder, null)
                        screen = Screen.Learning(s.lang)
                    },
                    onCheck = { screen = Screen.Checking(s.lang) },
                    onRace = { screen = Screen.Start(s.lang) },
                    onCorrect = { screen = Screen.Correcting(s.lang, options) },
                )
            }
            is Screen.Learning -> LearnScreen(
                lang = s.lang,
                color = colorOf(s.lang),
                speech = speech,
                start = remember(s.lang) { prefs.learnAt(s.lang.folder) },
                onPlace = { unit, entry -> prefs.saveLearnAt(s.lang.folder, unit, entry) },
            )
            is Screen.Checking -> {
                val options = remember(s.lang) { prefs.options(s.lang.folder) }
                val start = remember(s.lang) {
                    val id = prefs.checkUnit(s.lang.folder) ?: prefs.learnAt(s.lang.folder)?.first
                    (s.lang.units.find { it.id == id } ?: s.lang.units.first()).also { prefs.saveCheckUnit(s.lang.folder, it.id) }
                }
                CheckScreen(
                    lang = s.lang,
                    color = colorOf(s.lang),
                    speech = speech,
                    options = options,
                    start = start,
                    onUnit = { prefs.saveCheckUnit(s.lang.folder, it.id) },
                    onCue = { feedback.play(it, feedbackOptions) },
                    onMiss = { if (options.checkMistakes) prefs.addMistake(s.lang.folder, it.id) },
                    onDone = { screen = Screen.Modes(s.lang) },
                )
            }
            is Screen.Start -> {
                val options = remember(s.lang) { prefs.options(s.lang.folder) }
                val extra = remember(s.lang) { s.lang.poorlyLearned(prefs.mistakes(s.lang.folder)).size }
                StartScreen(s.lang, colorOf(s.lang), options, prefs.best(bestKey(s.lang, options)), extra) {
                    screen = Screen.Racing(s.lang, options)
                }
            }
            is Screen.Racing -> RaceScreen(
                colorOf(s.lang),
                s.options,
                timed = true,
                newRace = {
                    val extra = s.lang.poorlyLearned(prefs.mistakes(s.lang.folder))
                    Race(s.lang.cards(s.options), s.options.pairs, s.options.pairs * s.options.rounds, extra = extra)
                },
                onCue = { feedback.play(it, feedbackOptions) },
                onMiss = { prefs.addMistake(s.lang.folder, it.id) },
            ) { race, totalMs, roundsMs, retryMs ->
                // Poorly learned words matched without a mistake are cleared, as in a correction.
                val cleared = race.extra.filter { it in race.solved && it !in race.missed }.map { it.id }
                if (cleared.isNotEmpty()) prefs.saveMistakes(s.lang.folder, prefs.mistakes(s.lang.folder) - cleared.toSet())
                val key = bestKey(s.lang, s.options)
                val previous = prefs.best(key)
                if (previous == null || totalMs < previous) prefs.saveBest(key, totalMs)
                val newBest = previous != null && totalMs < previous
                feedback.play(if (newBest) Cue.Record else Cue.Finish, feedbackOptions)
                screen = Screen.Results(
                    s.lang,
                    s.options,
                    RaceResult(
                        totalMs = totalMs,
                        mistakes = race.mistakes,
                        roundsMs = roundsMs,
                        retryMs = retryMs,
                        bestMs = minOf(previous ?: totalMs, totalMs),
                        newBest = newBest,
                    ),
                )
            }
            is Screen.Results -> ResultsScreen(
                color = colorOf(s.lang),
                result = s.result,
                onAgain = { screen = Screen.Racing(s.lang, s.options) },
                onMenu = { screen = Screen.Languages },
            )
            is Screen.Correcting -> {
                val mistakes = remember(s) { prefs.mistakes(s.lang.folder) }
                val pool = remember(s) { s.lang.mistakenCards(mistakes) }
                RaceScreen(
                    colorOf(s.lang),
                    s.options,
                    timed = false,
                    newRace = { Race(pool, minOf(s.options.pairs, pool.size), pool.size, priority = mistakes) },
                    onCue = { feedback.play(it, feedbackOptions) },
                    onMiss = { prefs.addMistake(s.lang.folder, it.id) },
                ) { race, _, _, _ ->
                    // Words matched without a mistake are corrected; the mistaken ones stay, with their counts raised.
                    val cleared = (race.solved - race.missed).map { it.id }.toSet()
                    val now = prefs.mistakes(s.lang.folder) - cleared
                    prefs.saveMistakes(s.lang.folder, now)
                    val left = s.lang.mistakenCards(now).size
                    feedback.play(if (left == 0) Cue.Record else Cue.Finish, feedbackOptions)
                    screen = Screen.Corrected(s.lang, s.options, race.mistakes, cleared.size, left)
                }
            }
            is Screen.Corrected -> CorrectedScreen(
                color = colorOf(s.lang),
                mistakes = s.mistakes,
                cleared = s.cleared,
                left = s.left,
                onAgain = { screen = Screen.Correcting(s.lang, s.options) },
                onDone = { screen = Screen.Modes(s.lang) },
            )
        }
    }
}
