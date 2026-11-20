package com.example.lexicon

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lexicon.ui.theme.Cream
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.Muted
import com.example.lexicon.ui.theme.SoonGrey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One entry of the flat list of all entries of all units, with its position in its unit. */
private class Page(val unit: WordUnit, val entry: Entry, val index: Int)

/**
 * Learn: a short loading animation, then every entry of every unit as a card, one per page.
 * Opens at [start] (unit id, entry id), and reports each page settled on through [onPlace].
 */
@Composable
fun LearnScreen(
    lang: Language,
    color: Color,
    speech: Speech,
    start: Pair<String, String>?,
    onPlace: (unit: String, entry: String) -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    // The phone's voice starts up meanwhile.
    LaunchedEffect(Unit) { speech.prepare() }
    if (loaded) Words(lang, color, speech, start, onPlace) else Loading(color) { loaded = true }
}

@Composable
private fun Loading(color: Color, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        delay(200)
        onDone()
    }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxSize(),
                color = lerp(color, Ink, 0.15f),
                strokeWidth = 14.dp,
                trackColor = Color.White,
                strokeCap = StrokeCap.Round,
            )
            Icon(painterResource(R.drawable.ic_menu_book), null, Modifier.size(100.dp), tint = Ink)
        }
        Text("${(progress.value * 100).roundToInt()}%", color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Words(
    lang: Language,
    color: Color,
    speech: Speech,
    start: Pair<String, String>?,
    onPlace: (unit: String, entry: String) -> Unit,
) {
    val pages = remember(lang) { lang.units.flatMap { unit -> unit.entries.mapIndexed { i, entry -> Page(unit, entry, i) } } }
    if (pages.isEmpty()) {
        Text("No words", Modifier.padding(32.dp), color = Muted)
        return
    }
    val unitStarts = remember(lang) { lang.units.runningFold(0) { at, unit -> at + unit.entries.size } }
    val first = remember(lang) {
        val (unit, entry) = start ?: return@remember 0
        pages.indexOfFirst { it.unit.id == unit && it.entry.id == entry }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.unit.id == unit }.coerceAtLeast(0)
    }
    val pager = rememberPagerState(first) { pages.size }
    val scope = rememberCoroutineScope()
    val page = pages[pager.currentPage]

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect {
            speech.stop()
            onPlace(pages[it].unit.id, pages[it].entry.id)
        }
    }

    UnitDrawer(lang, color, page.unit, onPick = { i -> pager.scrollToPage(unitStarts[i]) }) {
        HorizontalPager(
            pager,
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
        ) { i ->
            WordCard(pages[i].entry, lang, color, speech)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val go = { to: Int -> scope.launch { pager.animateScrollToPage(to) }; Unit }
            StepButton("‹ Previous", Color.White, pager.currentPage > 0, Modifier.weight(1f)) { go(pager.targetPage - 1) }
            Text(
                "${page.index + 1} / ${page.unit.entries.size}",
                Modifier.width(88.dp),
                color = Muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            StepButton("Next ›", color, pager.currentPage < pages.lastIndex, Modifier.weight(1f)) { go(pager.targetPage + 1) }
        }
    }
}

/**
 * The [current] unit's title and a ☰ button above [content]. The button opens the units drawer from the right,
 * where picking a unit runs [onPick] with its index, then closes the drawer.
 */
@Composable
fun UnitDrawer(
    lang: Language,
    color: Color,
    current: WordUnit,
    onPick: suspend (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    // The drawer slides in from the right: laid out right-to-left, with the contents back to the app's direction.
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(300.dp), drawerContainerColor = Cream) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        UnitList(lang, color, current) { i ->
                            scope.launch {
                                onPick(i)
                                drawer.close()
                            }
                        }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title,
                            Modifier.weight(1f),
                            color = Muted,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chunky(Color.White, { scope.launch { drawer.open() } }, Modifier.size(56.dp), shape = CircleShape, depth = 4.dp) {
                            Text("☰", fontSize = 24.sp, color = Muted)
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun StepButton(text: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Chunky(if (enabled) color else SoonGrey, onClick, modifier.height(72.dp), enabled, RoundedCornerShape(24.dp), 5.dp) {
        Text(text, color = if (enabled) Ink else Muted, fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

/** The units in their original order, with the current one highlighted. Overscroll stretches it like rubber. */
@Composable
private fun UnitList(lang: Language, color: Color, current: WordUnit, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Units", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
        val list = rememberLazyListState((lang.units.indexOf(current) - 2).coerceAtLeast(0))
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            itemsIndexed(lang.units) { i, unit ->
                val isCurrent = unit.id == current.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(if (isCurrent) color else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        unit.title,
                        Modifier.weight(1f),
                        color = Ink,
                        fontSize = 18.sp,
                        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                    )
                    Text("${unit.entries.size}", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}

/** The term with its forms and gender, every translation, and the example phrases, on a card that scrolls when long. */
@Composable
private fun WordCard(entry: Entry, lang: Language, color: Color, speech: Speech) {
    Box(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
        // A static version of the chunky look: a darker base under a white face.
        val shape = RoundedCornerShape(28.dp)
        Box(Modifier.matchParentSize().padding(top = 6.dp).background(lerp(Color.White, Color.Black, 0.12f), shape))
        Box(Modifier.matchParentSize().padding(bottom = 6.dp).background(Color.White, shape), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(entry.term, color = Ink, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                val forms = listOfNotNull(entry.gender, entry.forms).joinToString(" · ")
                if (forms.isNotEmpty()) Text(forms, color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center)
                Speaker(entry.speak, entry.audio, lang, speech, color, 76.dp)

                Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    entry.translations.forEachIndexed { i, translation ->
                        Text(
                            if (entry.translations.size > 1) "${i + 1}. $translation" else translation,
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    entry.translationForms?.let { Text(it, color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center) }
                }

                entry.examples.forEach { example ->
                    Column(
                        Modifier.padding(top = 12.dp).fillMaxWidth().background(Cream, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(example.text, Modifier.weight(1f), color = Ink, fontSize = 20.sp, fontStyle = FontStyle.Italic)
                            Speaker(example.speak, example.audio, lang, speech, color, 44.dp)
                        }
                        example.translation?.let { Text(it, color = Muted, fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}

/** Speaks [text], or plays its audio file. Hidden when there is neither a file nor a phone voice for the language. */
@Composable
private fun Speaker(text: String, audio: String, lang: Language, speech: Speech, color: Color, size: Dp) {
    val file = lang.audio[audio]
    val canSpeak = remember(speech.ready, lang.code) { speech.canSpeak(lang.code) }
    if (file == null && !canSpeak) return
    Chunky(color, { speech.say(text, lang.code, file) }, Modifier.size(size), shape = CircleShape, depth = 4.dp) {
        Text("🔊", fontSize = (size.value * 0.4f).sp)
    }
}
