package com.example.lexicon

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import java.util.Locale

/**
 * Languages the phone's voice must not read: there is none for Latin, and a Greek voice reads modern Greek,
 * not the Erasmian pronunciation of ancient Greek. These need audio files.
 */
private val NoPhoneVoice = setOf("la", "el", "grc")

/** Speaks words and example phrases: their audio file when there is one, or else the phone's voice. */
class Speech(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null

    /** True once the phone's voice is up, which takes a moment after [prepare]. */
    var ready by mutableStateOf(false)
        private set

    fun prepare() {
        if (tts == null) tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    }

    /** Whether the phone's voice can speak the language [code] ("fr"). */
    fun canSpeak(code: String?): Boolean {
        val tts = tts
        if (!ready || tts == null || code == null || code in NoPhoneVoice) return false
        return tts.isLanguageAvailable(Locale.forLanguageTag(code)) >= TextToSpeech.LANG_AVAILABLE
    }

    /** Plays [audio], a content URI, or else says [text] with the phone's voice for [code]. */
    fun say(text: String, code: String?, audio: String?) {
        stop()
        if (audio != null) {
            val player = MediaPlayer()
            this.player = player
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(context, audio.toUri())
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { stop() }
                player.prepareAsync()
            } catch (e: Exception) {
                Log.w("Lexicon", "Can't play $audio", e)
                stop()
            }
        } else if (code != null && canSpeak(code)) {
            tts?.language = Locale.forLanguageTag(code)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lexicon")
        }
    }

    fun stop() {
        tts?.stop()
        player?.release()
        player = null
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
