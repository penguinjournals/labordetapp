package com.example.speech

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class SpeechState {
    IDLE,
    READING_CANDIDATES,    // TTS is listing the 3 candidate options
    WAITING_FOR_SELECTION, // Microphone is open, listening for choice (1, 2, 3, "otro")
    NARRATING_LANDMARK,    // TTS is reading the full chosen Landmark's summary
    NARRATION_PAUSED       // Narration was paused, mic could be listening for commands
}

class SpeechManager(
    private val context: Context,
    private val onActionTriggered: (ActionType) -> Unit
) : TextToSpeech.OnInitListener {

    private val tag = "SpeechManager"

    // Exposed States
    private val _state = MutableStateFlow(SpeechState.IDLE)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentSpokenText = MutableStateFlow("")
    val currentSpokenText: StateFlow<String> = _currentSpokenText.asStateFlow()

    private val _lastHeardWord = MutableStateFlow("")
    val lastHeardWord: StateFlow<String> = _lastHeardWord.asStateFlow()

    private val _isMaleVoice = MutableStateFlow(false)
    val isMaleVoice: StateFlow<Boolean> = _isMaleVoice.asStateFlow()

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected: StateFlow<Boolean> = _isBluetoothConnected.asStateFlow()

    // Engines
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var activeSpeechText: String = ""
    private var isTtsInitialized = false
    private var selectIntrosFinishedAction: (() -> Unit)? = null

    // For tracking dynamic speech rates (words per minute)
    private var currentSpeechRate = 1.0f

    var isHandsFreeActive: Boolean = true
    private var feedbackFinishedAction: (() -> Unit)? = null

    enum class ActionType {
        SELECT_ONE,
        SELECT_TWO,
        SELECT_THREE,
        REQUEST_MORE,
        SKIP_NEXT,
        PAUSE,
        RESUME,
        MORE_INFO,
        NEXT_ROUTE,
        REPEAT_NARRATION
    }

    init {
        initTts()
        initSpeechRecognizer()
        checkBluetoothStatus()
    }

    private fun initTts() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val spanish = Locale("es", "ES")
            val result = tts.setLanguage(spanish)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(tag, "Spanish language is not supported or missing in TextToSpeech.")
                tts.language = Locale.getDefault()
            }
            isTtsInitialized = true
            setVoiceGender(_isMaleVoice.value)
            setupTtsProgressListener()
            Log.d(tag, "TextToSpeech initialized successfully in Spanish!")
        } else {
            Log.e(tag, "Failed to initialize TextToSpeech")
        }
    }

    fun speakFeedbackAndTrigger(message: String, action: () -> Unit) {
        if (!isTtsInitialized) {
            action()
            return
        }
        stopVoiceListening()
        feedbackFinishedAction = action
        _currentSpokenText.value = message
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "feedback")
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, params, "feedback")
    }

    private fun setupTtsProgressListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(tag, "TTS Started speaking: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(tag, "TTS Finished speaking: $utteranceId")
                CoroutineScope(Dispatchers.Main).launch {
                    if (utteranceId == "candidate_intro") {
                        // After introducing candidates, open microphone to listen for selection
                        _state.value = SpeechState.WAITING_FOR_SELECTION
                        startVoiceListening()
                        selectIntrosFinishedAction?.invoke()
                    } else if (utteranceId == "narration") {
                        // Ended reading the article
                        _state.value = SpeechState.IDLE
                        _currentSpokenText.value = ""
                        if (isHandsFreeActive) {
                            startVoiceListening()
                        }
                    } else if (utteranceId == "feedback") {
                        feedbackFinishedAction?.invoke()
                        feedbackFinishedAction = null
                        if (isHandsFreeActive) {
                            startVoiceListening()
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(tag, "TTS Error speaking: $utteranceId")
            }
        })
    }

    fun setVoiceGender(isMale: Boolean) {
        _isMaleVoice.value = isMale
        if (!isTtsInitialized) return

        try {
            val spanish = Locale("es", "ES")
            val voices = tts.voices ?: emptySet()
            // Scan for matches containing male/masc or voice names matching male tags
            val targetVoice = voices.find { voice ->
                voice.locale.language == spanish.language &&
                if (isMale) {
                    voice.name.lowercase().contains("male") || 
                    voice.name.lowercase().contains("masc") ||
                    voice.name.lowercase().contains("es-es-x-sfd")
                } else {
                    voice.name.lowercase().contains("female") || 
                    voice.name.lowercase().contains("fem") ||
                    voice.name.lowercase().contains("es-es-x-ana") ||
                    voice.name.lowercase().contains("es-es-x-aod")
                }
            }

            if (targetVoice != null) {
                tts.voice = targetVoice
                tts.setPitch(1.0f)
                Log.d(tag, "Selected specific language voice: ${targetVoice.name}")
            } else {
                // Highly effective pitch-shifting fallback
                if (isMale) {
                    tts.setPitch(0.72f)   // Deep chest tone (Baritone)
                    tts.setSpeechRate(0.95f)
                } else {
                    tts.setPitch(1.24f)   // High-frequency head tone (Soprano)
                    tts.setSpeechRate(1.02f)
                }
                Log.d(tag, "Used voice pitch fallback modulation. Male=$isMale")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error setting TTS voice", e)
        }
    }

    private fun initSpeechRecognizer() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(createSpeechListener())
                    }
                    Log.d(tag, "SpeechRecognizer created successfully.")
                } else {
                    Log.e(tag, "SpeechRecognizer is not available on this platform.")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error initializing SpeechRecognizer", e)
            }
        }
    }

    fun speakCandidates(introText: String, candidateTitles: List<String>, onDone: () -> Unit) {
        if (!isTtsInitialized) return
        stopAllSpeech()

        _state.value = SpeechState.READING_CANDIDATES
        selectIntrosFinishedAction = onDone

        val sb = StringBuilder(introText).append(" ")
        candidateTitles.forEachIndexed { idx, title ->
            val numEsp = when(idx) {
                0 -> "Uno"
                1 -> "Dos"
                2 -> "Tres"
                else -> "Siguiente"
            }
            sb.append("$numEsp: $title. ")
        }
        sb.append("¿Cuál escoges? Di uno, dos, tres, u otro.")

        val fullText = sb.toString()
        _currentSpokenText.value = fullText
        
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "candidate_intro")
        }
        tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, params, "candidate_intro")
    }

    fun narrate(title: String, body: String) {
        if (!isTtsInitialized) return
        stopAllSpeech()

        _state.value = SpeechState.NARRATING_LANDMARK
        val textToSpeak = "Narrando $title: $body"
        _currentSpokenText.value = textToSpeak

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "narration")
        }
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "narration")
    }

    fun pauseNarration() {
        if (!isTtsInitialized) return
        if (_state.value == SpeechState.NARRATING_LANDMARK) {
            tts.stop()
            _state.value = SpeechState.NARRATION_PAUSED
            _currentSpokenText.value = "[PAUSA] ${_currentSpokenText.value}"
            onActionTriggered(ActionType.PAUSE)
            // Listen for resume commands
            startVoiceListening()
        }
    }

    fun resumeNarration(onSpeakReady: () -> Unit) {
        if (!isTtsInitialized) return
        if (_state.value == SpeechState.NARRATION_PAUSED) {
            stopVoiceListening()
            _state.value = SpeechState.NARRATING_LANDMARK
            onActionTriggered(ActionType.RESUME)
            onSpeakReady()
        }
    }

    fun skipCurrent() {
        stopAllSpeech()
        _state.value = SpeechState.IDLE
        _currentSpokenText.value = ""
        onActionTriggered(ActionType.SKIP_NEXT)
    }

    fun stopAllSpeech() {
        if (isTtsInitialized) {
            tts.stop()
        }
        stopVoiceListening()
        _state.value = SpeechState.IDLE
    }

    fun startVoiceListening() {
        val recognizer = speechRecognizer ?: return
        Log.d(tag, "Start voice listening initiated")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                _isListening.value = true
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(tag, "Error starting voice recognition client", e)
                _isListening.value = false
            }
        }
    }

    fun stopVoiceListening() {
        _isListening.value = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun createSpeechListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "Speech recognizer ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "User began speaking")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(tag, "User finished speaking")
            _isListening.value = false
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No voice match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
                else -> "Unknown speech error"
            }
            Log.d(tag, "Speech recognition error: $error ($message)")
            _isListening.value = false

            // Auto-restart listening if in waiting or paused state, or hands-free is general
            if (_state.value == SpeechState.WAITING_FOR_SELECTION || _state.value == SpeechState.NARRATION_PAUSED || isHandsFreeActive) {
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(1000)
                    if (_state.value == SpeechState.WAITING_FOR_SELECTION || _state.value == SpeechState.NARRATION_PAUSED || isHandsFreeActive) {
                        if (!_isListening.value && !tts.isSpeaking) {
                            startVoiceListening()
                        }
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            Log.d(tag, "Voice match results: $matches")

            for (match in matches) {
                val cleaned = match.lowercase().trim()
                _lastHeardWord.value = cleaned
                
                // 1. Specific complex commands first for robust matching!
                if (cleaned.contains("más información") || cleaned.contains("mas informacion") || cleaned.contains("más info") || cleaned.contains("mas info") || cleaned.contains("información") || cleaned.contains("informacion")) {
                    Log.d(tag, "Command detected: MORE_INFO")
                    speakFeedbackAndTrigger("Entendido. Buscando más detalles e información ampliada.") {
                        onActionTriggered(ActionType.MORE_INFO)
                    }
                    return
                }

                if (cleaned.contains("siguiente ruta") || cleaned.contains("cambiar ruta") || cleaned.contains("ruta siguiente") || cleaned.contains("camino siguiente") || cleaned.contains("otra ruta") || cleaned.contains("siguiente auto")) {
                    Log.d(tag, "Command detected: NEXT_ROUTE")
                    speakFeedbackAndTrigger("Es correcto. Cambiando a la siguiente ruta turística disponible.") {
                        onActionTriggered(ActionType.NEXT_ROUTE)
                    }
                    return
                }

                if (cleaned.contains("repetir") || cleaned.contains("repite") || cleaned.contains("otra vez") || cleaned.contains("de nuevo") || cleaned.contains("reproducir de nuevo")) {
                    Log.d(tag, "Command detected: REPEAT_NARRATION")
                    speakFeedbackAndTrigger("Entendido, repitiendo la última narración.") {
                        onActionTriggered(ActionType.REPEAT_NARRATION)
                    }
                    return
                }

                // 2. Classic simple matches
                if (cleaned.contains("uno") || cleaned == "1" || cleaned.contains("primero") || cleaned.contains("un")) {
                    Log.d(tag, "Command detected: SELECT_ONE")
                    onActionTriggered(ActionType.SELECT_ONE)
                    return
                }
                if (cleaned.contains("dos") || cleaned == "2" || cleaned.contains("segundo")) {
                    Log.d(tag, "Command detected: SELECT_TWO")
                    onActionTriggered(ActionType.SELECT_TWO)
                    return
                }
                if (cleaned.contains("tres") || cleaned == "3" || cleaned.contains("tercero")) {
                    Log.d(tag, "Command detected: SELECT_THREE")
                    onActionTriggered(ActionType.SELECT_THREE)
                    return
                }
                if (cleaned.contains("otro") || cleaned.contains("otra") || cleaned.contains("más") || cleaned.contains("mas") || cleaned.contains("siguiente")) {
                    if (_state.value == SpeechState.WAITING_FOR_SELECTION) {
                        Log.d(tag, "Command detected: REQUEST_MORE")
                        onActionTriggered(ActionType.REQUEST_MORE)
                    } else {
                        Log.d(tag, "Command detected: SKIP_NEXT")
                        onActionTriggered(ActionType.SKIP_NEXT)
                    }
                    return
                }
                if (cleaned.contains("pausa") || cleaned.contains("pausar") || cleaned.contains("parar") || cleaned.contains("detener") || cleaned.contains("silencio")) {
                    Log.d(tag, "Command detected: PAUSE")
                    pauseNarration()
                    return
                }
                if (cleaned.contains("reanudar") || cleaned.contains("continuar") || cleaned.contains("reproducir") || cleaned.contains("sigue")) {
                    Log.d(tag, "Command detected: RESUME")
                    onActionTriggered(ActionType.RESUME)
                    return
                }
            }

            // If heard but no command matches, check if we must open again
            if (_state.value == SpeechState.WAITING_FOR_SELECTION || _state.value == SpeechState.NARRATION_PAUSED || isHandsFreeActive) {
                if (!_isListening.value && !tts.isSpeaking) {
                    startVoiceListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun checkBluetoothStatus() {
        val connected = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
        _isBluetoothConnected.value = connected
        Log.d(tag, "Bluetooth connection output capability: $connected")
    }

    fun destroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
    }
}
