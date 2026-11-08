package com.example.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FeedbackTest {
    @Test
    fun everyToneIsShortAndLoudWithoutClipping() {
        Tone.entries.forEach { tone ->
            val pcm = tone.pcm
            assertTrue(tone.name, pcm.size in SAMPLE_RATE / 20..SAMPLE_RATE * 3)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(tone.name, peak > Short.MAX_VALUE / 2 && peak < Short.MAX_VALUE)
        }
    }

    @Test
    fun everyCueHasItsOwnDefaultTone() {
        Cue.entries.forEach { assertEquals(it, it.defaultTone.cue) }
    }
}
