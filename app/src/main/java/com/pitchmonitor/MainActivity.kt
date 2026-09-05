package com.pitchmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pitchmonitor.audio.PitchViewModel
import com.pitchmonitor.data.SessionStore
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.ui.PitchMonitorScreen
import com.pitchmonitor.ui.PitchMonitorTheme
import com.pitchmonitor.ui.ThemeMode
import com.pitchmonitor.ui.screens.PlaybackScreen
import com.pitchmonitor.ui.screens.SessionsScreen
import com.pitchmonitor.util.Prefs

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
            App()
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(Prefs.theme(context)) }
    val viewModel: PitchViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Monitor) }

    // system Back key walks the internal screens instead of exiting
    BackHandler(enabled = screen != Screen.Monitor) {
        screen = when (screen) {
            is Screen.Playback -> Screen.Sessions
            else -> Screen.Monitor
        }
    }

    PitchMonitorTheme(mode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
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
                        themeMode = themeMode,
                        onThemeChange = {
                            themeMode = it
                            Prefs.setTheme(context, it)
                        },
                        onOpenSessions = { screen = Screen.Sessions },
                        onSavedGoSessions = { screen = Screen.Sessions },
                    )
                    Screen.Sessions -> {
                        val sessions = remember { mutableStateOf<List<PitchSession>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            sessions.value = SessionStore.list(context)
                        }
                        SessionsScreen(
                            sessions = sessions.value,
                            importState = viewModel.importState.collectAsState().value,
                            onBack = { screen = Screen.Monitor },
                            onOpen = { id -> screen = Screen.Playback(id) },
                            onDelete = { id ->
                                viewModel.deleteSession(id)
                                sessions.value = sessions.value.filterNot { it.id == id }
                            },
                            onRename = { id, name ->
                                viewModel.renameSession(id, name)
                                sessions.value = sessions.value.map {
                                    if (it.id == id) it.copy(name = name) else it
                                }
                            },
                            onImportPicked = viewModel::startImport,
                            onCancelImport = viewModel::cancelImport,
                            onImported = { id ->
                                viewModel.consumeImportDone()
                                sessions.value = SessionStore.list(context)
                                screen = Screen.Playback(id)
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
    }
}
