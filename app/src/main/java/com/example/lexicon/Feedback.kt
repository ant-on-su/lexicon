package com.example.lexicon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.net.toUri
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Moments that get a sound and a vibration. The first [Tone] made for a cue is its default sound. */
enum class Cue(val title: String, val defaultVibe: Vibe) {
    Countdown("Countdown 3-2-1", Vibe.Off),
    Go("Go!", Vibe.Off),
    Correct("Correct match", Vibe.Off),
    Mistake("Mistake", Vibe.Buzz),
    Finish("Race finished", Vibe.Off),
    Record("New best", Vibe.Off);

    val defaultTone: Tone get() = Tone.entries.first { it.cue == this }
}

enum class Vibe(val title: String) { Off("Off"), Tick("Tick"), Tap("Tap"), Double("Double"), Buzz("Buzz"), Party("Party") }

internal const val SAMPLE_RATE = 44_100

/** Partials as (frequency ratio, amplitude). */
private enum class Wave(vararg val partials: Pair<Double, Double>) {
    Sine(1.0 to 1.0),
    Bell(1.0 to 1.0, 2.76 to 0.35, 5.4 to 0.15),
    Triangle(1.0 to 1.0, 3.0 to -1.0 / 9, 5.0 to 1.0 / 25),
    Square(1.0 to 1.0, 3.0 to 1.0 / 3, 5.0 to 1.0 / 5, 7.0 to 1.0 / 7, 9.0 to 1.0 / 9),
    Brass(*Array(8) { (it + 1.0) to 1.0 / (it + 1) }),
}

/** A note from [at] for [length] seconds. Pitch is a MIDI number (60 = middle C), gliding to [glideTo]. */
private class Note(
    val at: Double,
    val length: Double,
    val pitch: Int,
    val wave: Wave,
    val decay: Double = 0.0,
    val glideTo: Int = pitch,
    val volume: Double = 1.0,
)

/** Notes [step] seconds apart; a step of 0 makes a chord. */
private fun seq(at: Double, step: Double, length: Double, wave: Wave, decay: Double, vararg pitches: Int, volume: Double = 1.0) =
    pitches.mapIndexed { i, pitch -> Note(at + i * step, length, pitch, wave, decay, volume = volume) }

/** The built-in sounds, synthesized on the device. [cue] is what each was made for. */
enum class Tone(val title: String, val cue: Cue, notes: List<Note>) {
    Beep("Beep", Cue.Countdown, listOf(Note(0.0, 0.15, 72, Wave.Triangle, decay = 2.0))),
    Tick("Tick", Cue.Countdown, listOf(Note(0.0, 0.06, 96, Wave.Triangle, decay = 40.0))),
    Knock("Knock", Cue.Countdown, listOf(Note(0.0, 0.12, 60, Wave.Triangle, decay = 20.0, glideTo = 48))),
    GoBeep("Go beep", Cue.Go, listOf(Note(0.0, 0.45, 84, Wave.Triangle, decay = 2.0))),
    Rise("Rise", Cue.Go, listOf(Note(0.0, 0.3, 60, Wave.Square, decay = 1.0, glideTo = 84))),
    Horn("Horn", Cue.Go, seq(0.0, 0.0, 0.5, Wave.Brass, 1.5, 72, 79)),
    Ding("Ding", Cue.Correct, listOf(Note(0.0, 0.4, 88, Wave.Bell, decay = 10.0))),
    Coin("Coin", Cue.Correct, listOf(Note(0.0, 0.07, 83, Wave.Square), Note(0.07, 0.3, 88, Wave.Square, decay = 9.0))),
    Blip("Blip", Cue.Correct, listOf(Note(0.0, 0.06, 84, Wave.Triangle), Note(0.06, 0.14, 91, Wave.Triangle, decay = 14.0))),
    Pop("Pop", Cue.Correct, listOf(Note(0.0, 0.09, 60, Wave.Sine, decay = 18.0, glideTo = 86))),
    Buzz("Buzz", Cue.Mistake, seq(0.0, 0.0, 0.28, Wave.Brass, 3.0, 48, 49)),
    Bonk("Bonk", Cue.Mistake, listOf(Note(0.0, 0.22, 69, Wave.Triangle, decay = 10.0, glideTo = 52))),
    UhOh("Uh-oh", Cue.Mistake, listOf(Note(0.0, 0.14, 67, Wave.Triangle), Note(0.17, 0.28, 63, Wave.Triangle, decay = 5.0))),
    WahWah("Wah-wah", Cue.Mistake, listOf(Note(0.0, 0.4, 57, Wave.Square, decay = 3.0, glideTo = 50))),
    TaDa("Ta-da", Cue.Finish, listOf(Note(0.0, 0.1, 72, Wave.Brass)) + seq(0.13, 0.0, 1.0, Wave.Brass, 2.5, 72, 76, 79, 84)),
    Chimes("Chimes", Cue.Finish, seq(0.0, 0.09, 1.0, Wave.Bell, 4.0, 72, 76, 79, 84)),
    Jingle("Jingle", Cue.Finish, seq(0.0, 0.1, 0.1, Wave.Triangle, 0.0, 72, 76, 79, 76, 79) + Note(0.5, 0.6, 84, Wave.Triangle, decay = 4.0)),
    Fanfare(
        "Fanfare",
        Cue.Record,
        seq(0.0, 0.13, 0.11, Wave.Brass, 0.0, 67, 67, 67) +
            Note(0.39, 0.45, 72, Wave.Brass, decay = 1.0) +
            seq(0.9, 0.0, 1.2, Wave.Brass, 1.5, 60, 72, 76, 79) +
            Note(0.9, 0.8, 96, Wave.Bell, decay = 4.0, volume = 0.3),
    ),
    Triumph(
        "Triumph",
        Cue.Record,
        seq(0.0, 0.08, 0.12, Wave.Brass, 0.0, 60, 64, 67, 72, 76, 79) +
            seq(0.5, 0.0, 1.4, Wave.Brass, 1.5, 60, 72, 76, 79, 84) +
            seq(0.5, 0.1, 0.8, Wave.Bell, 4.0, 96, 100, 103, volume = 0.3),
    ),
    Hooray(
        "Hooray",
        Cue.Record,
        seq(0.0, 0.15, 0.14, Wave.Square, 0.0, 72, 74, 76, 79, 76, volume = 0.5) +
            Note(0.75, 0.9, 79, Wave.Square, decay = 2.0, volume = 0.5) +
            seq(0.75, 0.07, 0.8, Wave.Bell, 4.0, 84, 88, 91, 96, volume = 0.5),
    );

    /** 16-bit mono samples at [SAMPLE_RATE]. */
    internal val pcm: ShortArray by lazy { render(notes) }
}

private fun render(notes: List<Note>): ShortArray {
    val mix = FloatArray((notes.maxOf { it.at + it.length } * SAMPLE_RATE).toInt() + 1)
    for (note in notes) {
        val start = (note.at * SAMPLE_RATE).toInt()
        var phase = 0.0
        for (i in 0 until (note.length * SAMPLE_RATE).toInt()) {
            val t = i.toDouble() / SAMPLE_RATE
            val pitch = note.pitch + (note.glideTo - note.pitch) * t / note.length
            phase += 2 * PI * 440.0 * 2.0.pow((pitch - 69) / 12) / SAMPLE_RATE
            // Short fade in and out against clicks, then exponential decay.
            val envelope = min(1.0, t / 0.005) * min(1.0, (note.length - t) / 0.015) * exp(-note.decay * t)
            val wave = note.wave.partials.sumOf { (ratio, amplitude) -> amplitude * sin(ratio * phase) }
            mix[start + i] += (note.volume * envelope * wave).toFloat()
        }
    }
    val peak = mix.maxOf { abs(it) }
    return ShortArray(mix.size) { (mix[it] / peak * 0.7f * Short.MAX_VALUE).toInt().toShort() }
}

/**
 * Plays the sound and vibration of a cue. A sound is a [Tone] name, a phone sound's content URI,
 * or "" for none. Tones play from static audio tracks, so they start without delay.
 */
class Feedback(private val context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val tracks = mutableMapOf<Tone, AudioTrack>()
    private var ringtone: Ringtone? = null
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    val canVibrate: Boolean get() = vibrator.hasVibrator()

    init {
        // Synthesize in the background, so the first play doesn't stall the UI.
        thread(isDaemon = true) { Tone.entries.forEach { it.pcm } }
    }

    fun play(cue: Cue, options: FeedbackOptions) {
        if (options.sound) sound(options.sounds.getValue(cue))
        if (options.vibration) vibrate(options.vibes.getValue(cue))
    }

    fun sound(sound: String) {
        val tone = Tone.entries.find { it.name == sound }
        if (tone != null) {
            tracks.getOrPut(tone) { track(tone.pcm) }.run {
                stop()
                reloadStaticData()
                play()
            }
        } else if (sound.isNotEmpty()) {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(context, sound.toUri())?.apply {
                audioAttributes = attributes
                play()
            }
        }
    }

    fun vibrate(vibe: Vibe) {
        val effect = when (vibe) {
            Vibe.Off -> return
            Vibe.Tick -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Vibe.Tap -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Vibe.Double -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            Vibe.Buzz -> VibrationEffect.createWaveform(longArrayOf(0, 70, 50, 110), intArrayOf(0, 255, 0, 255), -1)
            Vibe.Party -> VibrationEffect.createWaveform(
                longArrayOf(0, 40, 60, 40, 60, 40, 60, 220),
                intArrayOf(0, 160, 0, 160, 0, 160, 0, 255),
                -1,
            )
        }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    }

    fun title(sound: String): String = Tone.entries.find { it.name == sound }?.title
        ?: if (sound.isEmpty()) "Off" else RingtoneManager.getRingtone(context, sound.toUri())?.getTitle(context) ?: "Phone sound"

    fun release() {
        tracks.values.forEach { it.release() }
        tracks.clear()
        ringtone?.stop()
    }

    private fun track(pcm: ShortArray) = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .apply { write(pcm, 0, pcm.size) }
}
