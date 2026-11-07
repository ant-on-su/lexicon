package com.example.lexicon

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val prefs = Prefs(this)
        setContent { LexiconTheme { LexiconApp(prefs) } }
    }
}

private sealed interface Screen {
    data object Languages : Screen
    data class Modes(val lang: Language) : Screen
    data class Start(val lang: Language) : Screen
    data class Racing(val lang: Language, val options: RaceOptions) : Screen
    data class Results(val lang: Language, val options: RaceOptions, val result: RaceResult) : Screen
}

@Composable
private fun LexiconApp(prefs: Prefs) {
    val assets = LocalContext.current.assets
    val languages by produceState<List<Language>?>(null) {
        value = withContext(Dispatchers.IO) { loadLanguages(assets) }
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Languages) }
    val colorOf = { lang: Language -> Pastels[(languages?.indexOf(lang) ?: 0).coerceAtLeast(0) % Pastels.size] }

    BackHandler(enabled = screen != Screen.Languages) {
        screen = when (val s = screen) {
            Screen.Languages -> s
            is Screen.Modes -> Screen.Languages
            is Screen.Start -> Screen.Modes(s.lang)
            is Screen.Racing -> Screen.Start(s.lang)
            is Screen.Results -> Screen.Start(s.lang)
        }
    }

    Box(Modifier.fillMaxSize().background(Cream).safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Languages -> LanguageScreen(languages, colorOf) { screen = Screen.Modes(it) }
            is Screen.Modes -> ModeScreen(s.lang, colorOf(s.lang)) { screen = Screen.Start(s.lang) }
            is Screen.Start -> {
                var options by remember(s.lang) { mutableStateOf(prefs.options(s.lang.folder)) }
                StartScreen(
                    lang = s.lang,
                    color = colorOf(s.lang),
                    options = options,
                    bestMs = prefs.best(bestKey(s.lang, options)),
                    onOptions = {
                        options = it
                        prefs.saveOptions(s.lang.folder, it)
                    },
                    onStart = { screen = Screen.Racing(s.lang, options) },
                )
            }
            is Screen.Racing -> RaceScreen(s.lang, colorOf(s.lang), s.options) { totalMs, mistakes, roundsMs ->
                val key = bestKey(s.lang, s.options)
                val previous = prefs.best(key)
                if (previous == null || totalMs < previous) prefs.saveBest(key, totalMs)
                screen = Screen.Results(
                    s.lang,
                    s.options,
                    RaceResult(
                        totalMs = totalMs,
                        mistakes = mistakes,
                        roundsMs = roundsMs,
                        bestMs = minOf(previous ?: totalMs, totalMs),
                        newBest = previous != null && totalMs < previous,
                    ),
                )
            }
            is Screen.Results -> ResultsScreen(
                color = colorOf(s.lang),
                result = s.result,
                onAgain = { screen = Screen.Racing(s.lang, s.options) },
                onMenu = { screen = Screen.Languages },
            )
        }
    }
}
