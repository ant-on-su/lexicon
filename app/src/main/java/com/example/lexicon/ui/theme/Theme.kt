package com.example.lexicon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always light and pastel; no dynamic colour so each language keeps its own colour. */
private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
)

@Composable
fun LexiconTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
