package com.adsamcik.tracker.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.data.DataSettingsScreen
import com.adsamcik.tracker.app.settings.debug.DebugSettingsScreen
import com.adsamcik.tracker.app.settings.game.GameSettingsScreen
import com.adsamcik.tracker.app.settings.map.MapSettingsScreen
import com.adsamcik.tracker.app.settings.root.RootSettingsScreen

import com.adsamcik.tracker.app.settings.tracking.TrackingSettingsScreen

// Contract: Entry route for settings; manages hierarchical navigation & hosts category screens.
// Thin navigation shell — screen implementations live in per-screen packages.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(onNavigateBack: () -> Unit = {}, onNavigateToDebug: () -> Unit = {}, onNavigateToActivities: () -> Unit = {}) {
    val vm: SettingsViewModel = hiltViewModel()
    var currentScreen by remember { mutableStateOf<SettingsScreen>(SettingsScreen.Root) }

    BackHandler(enabled = currentScreen != SettingsScreen.Root) {
        currentScreen = SettingsScreen.Root
    }

    Scaffold(
        // Opaque container stops other Settings sub-screens bleeding through the AnimatedContent
        // transition (setting is rendered inside AnimatedContent below).
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.title()) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentScreen != SettingsScreen.Root) {
                            currentScreen = SettingsScreen.Root
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_navigate_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val isForward = targetState != SettingsScreen.Root
                    if (isForward) {
                        slideInHorizontally { it } togetherWith
                                slideOutHorizontally { -it / 3 }
                    } else {
                        slideInHorizontally { -it / 3 } togetherWith
                                slideOutHorizontally { it }
                    }
                },
                label = "SettingsNavigation"
            ) { screen ->
                when (screen) {
                    SettingsScreen.Root -> RootSettingsScreen(
                        viewModel = vm,
                        onNavigate = { currentScreen = it },
                        onNavigateToActivities = onNavigateToActivities
                    )
                    SettingsScreen.Tracking -> TrackingSettingsScreen()
                    SettingsScreen.Data -> DataSettingsScreen()
                    SettingsScreen.Export -> DataSettingsScreen() // Export merged into Data
                    SettingsScreen.Map -> MapSettingsScreen()
                    SettingsScreen.Game -> GameSettingsScreen()
                    SettingsScreen.Statistics -> {
                        // Statistics sub-screen removed — navigate back to root as defensive fallback.
                        LaunchedEffect(Unit) { currentScreen = SettingsScreen.Root }
                    }
                    SettingsScreen.Debug -> DebugSettingsScreen(onNavigateToDebug)
                }
            }
        }
    }
}

// Sealed hierarchy for settings navigation
sealed class SettingsScreen {
    @Composable
    abstract fun title(): String

    data object Root : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_title)
    }
    data object Tracking : SettingsScreen() {
        @Composable override fun title() = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
    }
    data object Data : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_data_title)
    }
    data object Export : SettingsScreen() {
        @Composable override fun title() = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title)
    }
    data object Map : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_map_title)
    }
    data object Game : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_game_title)
    }
    data object Statistics : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.module_statistics_title)
    }
    data object Debug : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_debug_title)
    }
}
