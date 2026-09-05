package com.pitchmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pitchmonitor.audio.PitchViewModel
import com.pitchmonitor.data.SessionStore
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.ui.PitchMonitorScreen
import com.pitchmonitor.ui.PitchMonitorTheme
import com.pitchmonitor.ui.screens.PlaybackScreen
import com.pitchmonitor.ui.screens.SessionsScreen

sealed interface Screen {
    data object Monitor : Screen
    data object Sessions : Screen
    data class Playback(val sessionId: Long) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val viewModel: PitchViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Monitor) }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (slideInHorizontally { it / 5 } + fadeIn())
                .togetherWith(slideOutHorizontally { -it / 5 } + fadeOut())
        },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.Monitor -> PitchMonitorScreen(
                viewModel = viewModel,
                onOpenSessions = { screen = Screen.Sessions },
                onSavedGoSessions = { screen = Screen.Sessions },
            )
            Screen.Sessions -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                val sessions = remember { mutableStateOf<List<PitchSession>>(emptyList()) }
                LaunchedEffect(Unit) {
                    sessions.value = SessionStore.list(context)
                }
                SessionsScreen(
                    sessions = sessions.value,
                    onBack = { screen = Screen.Monitor },
                    onOpen = { id -> screen = Screen.Playback(id) },
                    onDelete = { id ->
                        viewModel.deleteSession(id)
                        sessions.value = sessions.value.filterNot { it.id == id }
                    },
                )
            }
            is Screen.Playback -> {
                var session by remember { mutableStateOf<PitchSession?>(null) }
                LaunchedEffect(s.sessionId) {
                    session = viewModel.loadSession(s.sessionId)
                }
                session?.let { pitchSession ->
                    PlaybackScreen(
                        session = pitchSession,
                        onBack = { screen = Screen.Sessions },
                    )
                }
            }
        }
    }
}
