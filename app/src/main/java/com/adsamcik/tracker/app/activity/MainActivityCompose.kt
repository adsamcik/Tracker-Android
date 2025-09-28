package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.Routes
import com.adsamcik.tracker.shared.utils.activity.CoreUIActivity
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory
import kotlin.ExperimentalStdlibApi
import kotlin.OptIn

// Local DI access (keeping for future use)
val LocalAppGraph = staticCompositionLocalOf<com.adsamcik.tracker.app.AppGraph> { 
    error("AppGraph not provided") 
}

/** Dedicated Compose-first Main activity. */
class MainActivityCompose : CoreUIActivity() {

    private val selectedRoute = mutableStateOf(Routes.Map)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_Translucent)
        super.onCreate(savedInstanceState)

        // Restore selected tab
        savedInstanceState?.getString(KEY_SELECTED_TAB)?.let { restored ->
            selectedRoute.value = restored
        }

        // Handle initial intent
        handleIntent(intent)

        setContent { ComposeRoot(selectedRoute) }
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
        if (openGame) selectedRoute.value = Routes.Game
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Composable
    private fun ComposeRoot(selected: MutableState<String>) {
        val dark = isSystemInDarkTheme()
        val appGraph = (application as Application).appGraph
        
        AppTheme(dark = dark) {
            CompositionLocalProvider(
                LocalAppGraph provides appGraph,
                LocalViewModelFactory provides appGraph.viewModelFactory
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
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
        outState.putString(KEY_SELECTED_TAB, selectedRoute.value)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main_selected_tab"
    }
}
