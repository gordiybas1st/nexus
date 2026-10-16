package com.example.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thin wrapper over [SpeechRecognizer] for one-shot dictation.
 *
 * Must be used from the main thread. The caller requests `RECORD_AUDIO` first and calls
 * [destroy] when the screen goes away. Language follows the device locale (RU/EN).
 */
class VoiceInputManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _listening = MutableStateFlow(false)

    /** True while the microphone is open. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Input loudness in 0..1, for a pulsing mic animation. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** False when the device has no speech recognition service. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening.
     *
     * @param onText best recognized phrase.
     * @param onFailure a `SpeechRecognizer.ERROR_*` code (no-match and timeout included).
     */
    fun start(onText: (String) -> Unit, onFailure: (Int) -> Unit) {
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _listening.value = true
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB is roughly -2..10 on most devices.
                _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _level.value = 0f
            }

            override fun onError(error: Int) {
                finish()
                onFailure(error)
            }

            override fun onResults(results: Bundle?) {
                finish()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onFailure(SpeechRecognizer.ERROR_NO_MATCH) else onText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        _listening.value = true
        sr.startListening(intent)
    }

    /** Stops listening; the final result (if any) still arrives. */
    fun stop() {
        recognizer?.stopListening()
        _listening.value = false
    }

    /** Releases the recognizer. */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        finish()
    }

    private fun finish() {
        _listening.value = false
        _level.value = 0f
    }
}
