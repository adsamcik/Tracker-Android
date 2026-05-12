package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.app.ui.navigation.Map
import com.adsamcik.tracker.app.ui.navigation.Setup
import com.adsamcik.tracker.app.ui.navigation.Stats
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @Inject lateinit var onboardingRepository: com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
    private val viewModel by viewModels<MainActivityViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate
        installSplashScreen()

        // Enable edge-to-edge for modern Compose UI
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setTheme(R.style.AppTheme_Translucent)

        // Handle initial intent only on cold start to avoid replay after config changes
        if (savedInstanceState == null) {
            handleDeepNavigation(intent)
        }

        setContent { ComposeRoot(viewModel) }

        val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main

        lifecycleScope.launch(dispatchers.io) {
            val destination = runCatching {
                if (onboardingRepository.isCompleted.first()) {
                    StartupDestination.Main
                } else {
                    StartupDestination.Onboarding
                }
            }.getOrDefault(StartupDestination.Main)

            withContext(mainImmediate) {
                viewModel.setStartupDestination(destination)
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
        viewModel.setDeepNavigationRequest(
            DeepNavigationRequest(
                target = target,
                challengeId = challengeId,
                scrollTo = scrollTo
            )
        )

        viewModel.setSelectedTab(when (target) {
            TARGET_GAME -> Game
            TARGET_STATS -> Stats
            TARGET_SETTINGS -> Dashboard
            else -> Dashboard
        })
        intent.removeExtra(EXTRA_NAVIGATE_TO)
    }

    @Composable
    private fun ComposeRoot(viewModel: MainActivityViewModel) {
        val darkTheme = isSystemInDarkTheme()
        val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
        val deepNavigationRequest by viewModel.deepNavigationRequest.collectAsStateWithLifecycle()
        val startupDestination by viewModel.startupDestination.collectAsStateWithLifecycle()

        when (startupDestination) {
            StartupDestination.Pending -> {
                AppTheme(darkTheme = darkTheme) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                return
            }

            StartupDestination.Onboarding -> Unit // handled below via startDestination = Setup
            StartupDestination.Main -> Unit
        }

        LaunchedEffect(Unit) {
            withFrameNanos { }
            kotlinx.coroutines.delay(DEFERRED_STARTUP_DELAY_MS)
            (application as? Application)?.startDeferredStartupIfNeeded()
        }

        LaunchedEffect(Unit) {
            withFrameNanos { }
            kotlinx.coroutines.delay(MAINTENANCE_STARTUP_DELAY_MS)
            (application as? Application)?.startMaintenanceStartupIfNeeded()
        }

        AppTheme(darkTheme = darkTheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    val effectiveStartDestination: AppRoute =
                        if (startupDestination == StartupDestination.Onboarding) Setup else selectedTab
                    MainRoot(
                        startDestination = effectiveStartDestination,
                        deepNavigationRequest = deepNavigationRequest,
                        onDeepNavigationHandled = viewModel::clearDeepNavigationRequest
                    ) { route ->
                        if (selectedTab != route) viewModel.setSelectedTab(route)
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFERRED_STARTUP_DELAY_MS = 250L
        private const val MAINTENANCE_STARTUP_DELAY_MS = 5_000L
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        const val EXTRA_SCROLL_TO = "scroll_to"
        const val TARGET_IMPEXP = "impexp"
        const val TARGET_SETTINGS = "settings"
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
    "dashboard" -> Dashboard
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

enum class StartupDestination {
    Pending,
    Onboarding,
    Main,
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(
        routeFromKey(savedStateHandle[KEY_SELECTED_TAB] ?: Dashboard.routeKey())
    )
    val selectedTab: StateFlow<AppRoute> = _selectedTab.asStateFlow()

    private val _deepNavigationRequest = MutableStateFlow(
        savedStateHandle.toDeepNavigationRequest()
    )
    val deepNavigationRequest: StateFlow<DeepNavigationRequest?> = _deepNavigationRequest.asStateFlow()

    private val _startupDestination = MutableStateFlow(
        savedStateHandle.get<String>(KEY_STARTUP_DESTINATION)?.let { name ->
            try {
                StartupDestination.valueOf(name)
            } catch (e: IllegalArgumentException) {
                Reporter.w("MainActivityCompose", "Unknown startup destination '$name': ${e.message}")
                StartupDestination.Pending
            }
        } ?: StartupDestination.Pending
    )
    val startupDestination: StateFlow<StartupDestination> = _startupDestination.asStateFlow()

    fun setSelectedTab(route: AppRoute) {
        _selectedTab.value = route
        savedStateHandle[KEY_SELECTED_TAB] = route.routeKey()
    }

    fun setDeepNavigationRequest(request: DeepNavigationRequest?) {
        _deepNavigationRequest.value = request
        savedStateHandle[KEY_DEEP_NAV_TARGET] = request?.target
        savedStateHandle[KEY_DEEP_NAV_CHALLENGE_ID] = request?.challengeId
        savedStateHandle[KEY_DEEP_NAV_SCROLL_TO] = request?.scrollTo
    }

    fun clearDeepNavigationRequest() {
        setDeepNavigationRequest(null)
    }

    fun setStartupDestination(destination: StartupDestination) {
        _startupDestination.value = destination
        savedStateHandle[KEY_STARTUP_DESTINATION] = destination.name
    }

    private fun SavedStateHandle.toDeepNavigationRequest(): DeepNavigationRequest? {
        val target = get<String>(KEY_DEEP_NAV_TARGET) ?: return null
        return DeepNavigationRequest(
            target = target,
            challengeId = get<Long>(KEY_DEEP_NAV_CHALLENGE_ID) ?: -1L,
            scrollTo = get<String>(KEY_DEEP_NAV_SCROLL_TO)
        )
    }

    companion object {
        const val KEY_SELECTED_TAB = "main_selected_tab"
        const val KEY_DEEP_NAV_TARGET = "main_deep_nav_target"
        const val KEY_DEEP_NAV_CHALLENGE_ID = "main_deep_nav_challenge_id"
        const val KEY_DEEP_NAV_SCROLL_TO = "main_deep_nav_scroll_to"
        const val KEY_STARTUP_DESTINATION = "main_startup_destination"
    }
}
