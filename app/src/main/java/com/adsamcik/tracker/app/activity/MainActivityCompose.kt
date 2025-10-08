package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.Routes
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory

// Local DI access (keeping for future use)
val LocalAppGraph = staticCompositionLocalOf<com.adsamcik.tracker.app.AppGraph> { 
    error("AppGraph not provided") 
}

/**
 * Compose-first Main activity following north star architecture.
 * Extends ComponentActivity directly per evergreen guidelines (§11).
 */
@OptIn(ExperimentalStdlibApi::class)
class MainActivityCompose : ComponentActivity() {

    private val selectedTab = mutableStateOf(Routes.Tracker)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge for modern Compose UI
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        setTheme(R.style.AppTheme_Translucent)

        // Restore selected tab
        savedInstanceState?.getString(KEY_SELECTED_TAB)?.let { restored ->
            selectedTab.value = restored
        }

        // Handle initial intent
        handleIntent(intent)

        setContent { ComposeRoot(selectedTab) }
    }

    override fun onStart() {
        super.onStart()
        if (!OnboardingActivity.isOnboardingCompleted(this)) {
            startActivity(OnboardingActivity.createIntent(this))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val openGame = intent?.getBooleanExtra("openGame", false) == true
        if (openGame) selectedTab.value = Routes.Game
    }

    @Composable
    private fun ComposeRoot(selected: MutableState<String>) {
        val darkTheme = isSystemInDarkTheme()
        val appGraph = (application as Application).appGraph
        
        AppTheme(darkTheme = darkTheme) {
            CompositionLocalProvider(
                LocalAppGraph provides appGraph,
                LocalViewModelFactory provides appGraph.viewModelFactory
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        // Compose Navigation root with all app routes
                        MainRoot(startDestination = selected.value) { route ->
                            if (selected.value != route) selected.value = route
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, selectedTab.value)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main_selected_tab"
    }
}
