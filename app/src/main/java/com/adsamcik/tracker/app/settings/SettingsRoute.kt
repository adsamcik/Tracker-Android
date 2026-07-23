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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.data.DataSettingsScreen
import com.adsamcik.tracker.app.settings.debug.DebugSettingsScreen
import com.adsamcik.tracker.app.settings.game.GameSettingsScreen
import com.adsamcik.tracker.app.settings.map.MapSettingsScreen
import com.adsamcik.tracker.app.settings.root.RootSettingsScreen

import com.adsamcik.tracker.app.settings.tracking.TrackingSettingsScreen
import com.adsamcik.tracker.app.settings.tracebox.TraceboxSettingsScreen
import com.adsamcik.tracker.shared.utils.style.compose.ridgelineSettle

// Contract: Entry route for settings; manages hierarchical navigation & hosts category screens.
// Thin navigation shell — screen implementations live in per-screen packages.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToActivities: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToNotificationManagement: () -> Unit = {},
    initialScreen: SettingsScreen = SettingsScreen.Root,
) {
    val vm: SettingsViewModel = hiltViewModel()
    var currentScreen by rememberSaveable(
        initialScreen,
        saver = SettingsScreenSaver,
    ) { mutableStateOf(initialScreen) }

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
            val navigationTransitionSpec = ridgelineSettle<IntOffset>()
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val isForward = targetState != SettingsScreen.Root
                    if (isForward) {
                        slideInHorizontally(animationSpec = navigationTransitionSpec) { it } togetherWith
                            slideOutHorizontally(animationSpec = navigationTransitionSpec) { -it / 3 }
                    } else {
                        slideInHorizontally(animationSpec = navigationTransitionSpec) { -it / 3 } togetherWith
                            slideOutHorizontally(animationSpec = navigationTransitionSpec) { it }
                    }
                },
                label = "SettingsNavigation"
            ) { screen ->
                when (screen) {
                    SettingsScreen.Root -> RootSettingsScreen(
                        viewModel = vm,
                        onNavigate = { currentScreen = it },
                        onNavigateToActivities = onNavigateToActivities,
                        onNavigateToAbout = onNavigateToAbout,
                    )
                    SettingsScreen.Tracking -> TrackingSettingsScreen(
                        onNavigateToNotificationManagement = onNavigateToNotificationManagement
                    )
                    SettingsScreen.Data -> DataSettingsScreen()
                    SettingsScreen.Export -> DataSettingsScreen() // Export merged into Data
                    SettingsScreen.Map -> MapSettingsScreen()
                    SettingsScreen.Game -> GameSettingsScreen()
                    SettingsScreen.Statistics -> {
                        // Statistics sub-screen removed — navigate back to root as defensive fallback.
                        LaunchedEffect(Unit) { currentScreen = SettingsScreen.Root }
                    }
                    SettingsScreen.Debug -> DebugSettingsScreen(onNavigateToDebug)
                    SettingsScreen.Tracebox -> TraceboxSettingsScreen()
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
    data object Tracebox : SettingsScreen() {
        @Composable override fun title() = stringResource(R.string.settings_tracebox_title)
    }

    internal fun saveKey(): String = when (this) {
        Root -> "root"
        Tracking -> "tracking"
        Data -> "data"
        Export -> "export"
        Map -> "map"
        Game -> "game"
        Statistics -> "statistics"
        Debug -> "debug"
        Tracebox -> "tracebox"
    }

    internal companion object {
        fun fromSaveKey(key: String): SettingsScreen? = when (key) {
            "root" -> Root
            "tracking" -> Tracking
            "data" -> Data
            "export" -> Export
            "map" -> Map
            "game" -> Game
            "statistics" -> Statistics
            "debug" -> Debug
            "tracebox" -> Tracebox
            else -> null
        }
    }
}

internal val SettingsScreenSaver = Saver<MutableState<SettingsScreen>, String>(
    save = { it.value.saveKey() },
    restore = { key ->
        mutableStateOf(SettingsScreen.fromSaveKey(key) ?: SettingsScreen.Root)
    },
)
