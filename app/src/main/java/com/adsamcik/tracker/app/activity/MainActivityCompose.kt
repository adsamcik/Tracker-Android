package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.app.ui.navigation.Map
import com.adsamcik.tracker.app.ui.navigation.Stats
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Compose-first Main activity following north star architecture.
 * Extends ComponentActivity directly per evergreen guidelines (§11).
 * Uses SplashScreen API for async onboarding check (Plan 5 migration).
 * Uses Hilt for ViewModel injection via @AndroidEntryPoint.
 */
@AndroidEntryPoint
class MainActivityCompose : ComponentActivity() {

    @Inject lateinit var dispatchers: DispatchersProvider

    private val selectedTab = mutableStateOf<AppRoute>(Dashboard)
    private val deepNavigationRequest = mutableStateOf<DeepNavigationRequest?>(null)
    
    private var startupDestination by mutableStateOf(StartupDestination.Pending)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate
        val splashScreen = installSplashScreen()

        // Enable edge-to-edge for modern Compose UI
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val trackerApplication = application as Application
        splashScreen.setKeepOnScreenCondition {
            startupDestination == StartupDestination.Pending || !trackerApplication.isStartupReady
        }

        setTheme(R.style.AppTheme_Translucent)

        // Restore selected tab
        savedInstanceState?.getString(KEY_SELECTED_TAB)?.let { restored ->
            selectedTab.value = routeFromKey(restored)
        }

        // Handle initial intent only on cold start to avoid replay after config changes
        if (savedInstanceState == null) {
            handleDeepNavigation(intent)
        }

        setContent { ComposeRoot(selectedTab) }

        val onboardingRepository = DefaultOnboardingRepository(applicationContext, dispatchers.io)
        val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main

        lifecycleScope.launch(dispatchers.io) {
            val destination = runCatching {
                onboardingRepository.ensureInitialized()
                if (onboardingRepository.isCompleted.first()) {
                    StartupDestination.Main
                } else {
                    StartupDestination.Onboarding
                }
            }.getOrDefault(StartupDestination.Main)

            withContext(mainImmediate) {
                startupDestination = destination
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Navigation to onboarding is driven from ComposeRoot once startup resolution finishes.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepNavigation(intent)
    }

    private fun handleDeepNavigation(intent: Intent?) {
        intent ?: return

        val legacyOpenGame = intent.getBooleanExtra("openGame", false)
        val target = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: if (legacyOpenGame) TARGET_GAME else null
        if (target == null) return

        val challengeId = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
        val scrollTo = intent.getStringExtra(EXTRA_SCROLL_TO)
        deepNavigationRequest.value = DeepNavigationRequest(
            target = target,
            challengeId = challengeId,
            scrollTo = scrollTo
        )

        selectedTab.value = when (target) {
            TARGET_GAME -> Game
            TARGET_STATS -> Stats
            else -> Dashboard
        }
        intent.removeExtra(EXTRA_NAVIGATE_TO)
    }

    @Composable
    private fun ComposeRoot(selected: MutableState<AppRoute>) {
        val darkTheme = isSystemInDarkTheme()

        when (startupDestination) {
            StartupDestination.Pending -> {
                AppTheme(darkTheme = darkTheme) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {}
                    }
                }
                return
            }

            StartupDestination.Onboarding -> {
                LaunchedEffect(Unit) {
                    startActivity(OnboardingActivity.createIntent(this@MainActivityCompose))
                }
                return
            }

            StartupDestination.Main -> Unit
        }

        AppTheme(darkTheme = darkTheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    // Compose Navigation root with all app routes
                    MainRoot(
                        startDestination = selected.value,
                        deepNavigationRequest = deepNavigationRequest.value,
                        onDeepNavigationHandled = { deepNavigationRequest.value = null }
                    ) { route ->
                        if (selected.value != route) selected.value = route
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, selectedTab.value.routeKey())
    }

    private enum class StartupDestination {
        Pending,
        Onboarding,
        Main,
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main_selected_tab"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        const val EXTRA_SCROLL_TO = "scroll_to"
        const val TARGET_IMPEXP = "impexp"
        const val TARGET_GAME = "game"
        const val TARGET_DASHBOARD = "dashboard"
        const val TARGET_STATS = "stats"
    }
}

private fun AppRoute.routeKey(): String = when (this) {
    Dashboard -> "dashboard"
    Stats -> "stats"
    Map -> "map"
    Game -> "game"
    else -> "dashboard"
}

private fun routeFromKey(key: String): AppRoute = when (key) {
    "stats" -> Stats
    "map" -> Map
    "game" -> Game
    else -> Dashboard
}

data class DeepNavigationRequest(
    val target: String,
    val challengeId: Long = -1L,
    val scrollTo: String? = null
)
