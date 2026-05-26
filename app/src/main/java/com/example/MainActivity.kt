package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.speech.SpeechManager
import com.example.ui.LabordetDashboard
import com.example.ui.LabordetViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LabordetViewModel by viewModels()
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create speech synthesizer and recognizer engine
        speechManager = SpeechManager(applicationContext) { voiceAction ->
            viewModel.handleVoiceCommandInput(voiceAction)
        }

        // Connect the speech controller to the lifecycle of state model
        viewModel.bindSpeechManager(speechManager)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    LabordetDashboard(viewModel = viewModel)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Gracefully pause background audio and continuous recognition
        viewModel.pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}
