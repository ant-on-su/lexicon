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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val prefs = Prefs(this)
        feedback = Feedback(this)
        setContent {
            LexiconTheme {
                Box {
                    LexiconApp(prefs, feedback)
                    Splash()
                }
            }
        }
    }

    override fun onDestroy() {
        feedback.release()
        super.onDestroy()
    }
}

private sealed interface Screen {
    data object Languages : Screen
    data class Modes(val lang: Language) : Screen
    data class Start(val lang: Language) : Screen
    data class Racing(val lang: Language, val options: RaceOptions) : Screen
    data class Results(val lang: Language, val options: RaceOptions, val result: RaceResult) : Screen
    data class Correcting(val lang: Language, val options: RaceOptions) : Screen
    data class Corrected(val lang: Language, val options: RaceOptions, val mistakes: Int, val cleared: Int, val left: Int) : Screen
}

@Composable
private fun LexiconApp(prefs: Prefs, feedback: Feedback) {
    val context = LocalContext.current.applicationContext
    var folder by remember { mutableStateOf(prefs.wordFolder) }
    var feedbackOptions by remember { mutableStateOf(prefs.feedback()) }
    val library by produceState<Library?>(null, folder) {
        value = withContext(Dispatchers.IO) { loadLibrary(context, folder) }
    }
    val languages = library?.languages
    var screen by remember { mutableStateOf<Screen>(Screen.Languages) }
    val colorOf = { lang: Language -> Pastels[(languages?.indexOf(lang) ?: 0).coerceAtLeast(0) % Pastels.size] }
    val tooManyMistakes = { lang: Language, options: RaceOptions ->
        lang.mistakenCards(prefs.mistakes(lang.folder)).size > options.maxMistakes
    }

    BackHandler(enabled = screen != Screen.Languages) {
        screen = when (val s = screen) {
            Screen.Languages -> s
            is Screen.Modes -> Screen.Languages
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
                    onRace = { screen = Screen.Start(s.lang) },
                    onCorrect = { screen = Screen.Correcting(s.lang, options) },
                )
            }
            is Screen.Start -> {
                val options = remember(s.lang) { prefs.options(s.lang.folder) }
                val blocked = remember(s.lang) { tooManyMistakes(s.lang, options) }
                StartScreen(s.lang, colorOf(s.lang), options, prefs.best(bestKey(s.lang, options)), blocked) {
                    screen = Screen.Racing(s.lang, options)
                }
            }
            is Screen.Racing -> RaceScreen(
                colorOf(s.lang),
                s.options,
                timed = true,
                newRace = { Race(s.lang.cards(s.options), s.options.pairs, s.options.pairs * s.options.rounds) },
                onCue = { feedback.play(it, feedbackOptions) },
                onMiss = { prefs.addMistake(s.lang.folder, it.id) },
            ) { race, totalMs, roundsMs, retryMs ->
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
                canAgain = remember(s) { !tooManyMistakes(s.lang, s.options) },
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
