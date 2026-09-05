package com.pitchmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pitchmonitor.audio.PitchViewModel
import com.pitchmonitor.ui.PitchMonitorScreen
import com.pitchmonitor.ui.PitchMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: PitchViewModel = viewModel()
                    PitchMonitorScreen(viewModel = viewModel)
                }
            }
        }
    }
}
