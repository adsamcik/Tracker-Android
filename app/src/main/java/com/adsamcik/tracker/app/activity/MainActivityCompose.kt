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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.base.di.LocalTrackerController
import com.adsamcik.tracker.shared.base.di.LocalLockManager
import com.adsamcik.tracker.shared.base.di.LocalDailySummaryProvider
import com.adsamcik.tracker.shared.base.di.LocalDailyPointsProvider
import com.adsamcik.tracker.shared.base.di.LocalGoalProgressProvider
import com.adsamcik.tracker.shared.base.di.LocalActiveChallengesProvider
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Local DI access (keeping for future use)
val LocalAppGraph = staticCompositionLocalOf<com.adsamcik.tracker.app.AppGraph> { 
    error("AppGraph not provided") 
}

/**
 * Compose-first Main activity following north star architecture.
 * Extends ComponentActivity directly per evergreen guidelines (§11).
 * Uses SplashScreen API for async onboarding check (Plan 5 migration).
 * Uses Hilt for ViewModel injection via @AndroidEntryPoint.
 */
@AndroidEntryPoint
class MainActivityCompose : ComponentActivity() {

    private val selectedTab = mutableStateOf<Any>(Dashboard)
    
    // Async state for splash screen
    private var isReady by mutableStateOf(false)
    private var showOnboarding by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate
        val splashScreen = installSplashScreen()
        
        // Keep splash screen visible while we check onboarding state
        splashScreen.setKeepOnScreenCondition { !isReady }
        
        // Async check onboarding completion
        val onboardingRepository = DefaultOnboardingRepository(applicationContext, Dispatchers.IO)

        lifecycleScope.launch {
            try {
                android.util.Log.d("Startup", "Begin onboarding check")
                // Explicitly ensure migration happens before we check state
                // This avoids doing it inside the flow collection, preventing deadlocks
                onboardingRepository.ensureInitialized()
                android.util.Log.d("Startup", "Repository initialized")

                // Safety timeout can stay as a good practice, but logic is now safe
                val isCompleted = kotlinx.coroutines.withTimeoutOrNull(2000) {
                    onboardingRepository.isCompleted.first()
                } ?: true
                
                android.util.Log.d("Startup", "Onboarding state checked: $isCompleted")

                showOnboarding = !isCompleted
            } catch (e: Exception) {
                // Log exception for debugging but don't crash startup
                android.util.Log.e("Startup", "Error during onboarding initialization", e)
                showOnboarding = false // Default to showing app content on error
            } finally {
                // ALWAYS finish splash screen
                android.util.Log.d("Startup", "Releasing splash screen")
                isReady = true
            }
        }
        
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
        // Navigation to onboarding now happens via compose navigation in ComposeRoot
        // based on the async showOnboarding state
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val openGame = intent?.getBooleanExtra("openGame", false) == true
        if (openGame) selectedTab.value = Game
    }

    @Composable
    private fun ComposeRoot(selected: MutableState<Any>) {
        val darkTheme = isSystemInDarkTheme()
        val appGraph = (application as Application).appGraph
        
        // Wait until async check is complete
        if (!isReady) return
        
        // If onboarding not completed, navigate to onboarding
        LaunchedEffect(showOnboarding) {
            if (showOnboarding) {
                startActivity(OnboardingActivity.createIntent(this@MainActivityCompose))
            }
        }
        
        // Only show main content if onboarding is complete
        if (showOnboarding) return
        
        AppTheme(darkTheme = darkTheme) {
            CompositionLocalProvider(
                LocalAppGraph provides appGraph,
                LocalTrackerController provides appGraph.trackerServiceController,
                LocalLockManager provides appGraph.lockManager,
                LocalDailySummaryProvider provides appGraph.dailySummaryProvider,
                LocalDailyPointsProvider provides appGraph.dailyPointsProvider,
                LocalGoalProgressProvider provides appGraph.goalProgressProvider,
                LocalActiveChallengesProvider provides appGraph.activeChallengesProvider
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
        // Note: selectedTab state persistence changes.
        // For simplicity with serialization, we might skip full restoration mapping here
        // or just let it reset to default on process death for now,
        // as managing Serializable persistence manually in Bundle is verbose without Parcelable.
        // Assuming default behavior is acceptable for this migration phase.
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main_selected_tab"
    }
}
