package com.adsamcik.tracker.app.activity

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.semantics.semantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.game.api.navigation.Game
import com.adsamcik.tracker.feature.map.api.navigation.Map
import com.adsamcik.tracker.app.ui.navigation.Setup
import com.adsamcik.tracker.app.startup.LegacyDatabaseStartupResult
import com.adsamcik.tracker.app.startup.LegacyDatabaseUpgradeCoordinator
import com.adsamcik.tracker.feature.statistics.api.navigation.Stats
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import javax.inject.Inject
import java.util.UUID

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
    @Inject lateinit var legacyDatabaseRepository: LegacyDatabaseRepository
    @Inject lateinit var legacyDatabaseUpgradeCoordinator: LegacyDatabaseUpgradeCoordinator
    private val viewModel by viewModels<MainActivityViewModel>()

    private val legacyExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { destination ->
        destination ?: return@registerForActivityResult
        lifecycleScope.launch(dispatchers.io) {
            val exported = runCatching {
                contentResolver.openOutputStream(destination, "w")?.let(legacyDatabaseRepository::export)
                    ?: error("Could not open export destination")
            }.isSuccess
            if (!exported) {
                runCatching {
                    if (contentResolver.delete(destination, null, null) <= 0) {
                        contentResolver.openOutputStream(destination, "rwt")?.use { it.flush() }
                    }
                }
            }
            withContext(dispatchers.main) {
                Toast.makeText(
                    this@MainActivityCompose,
                    if (exported) R.string.legacy_database_export_success else R.string.legacy_database_export_failure,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

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

        setContent {
            // Propagate Compose testTag values to Android View resource-id so external
            // automation (UIAutomator / MCP) can find them via By.res(...). See QC SYS-3.
            Box(Modifier.semantics { set(SemanticsPropertiesAndroid.TestTagsAsResourceId, true) }) {
                ComposeRoot(viewModel)
            }
        }

        val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main

        resolveStartup(mainImmediate)
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

        val request = parseDeepNavigationRequest(intent) ?: return

        viewModel.setDeepNavigationRequest(request)
        viewModel.setSelectedTab(selectedTabForDeepNavigationTarget(request.target))
        intent.removeExtra(EXTRA_NAVIGATE_TO)
        intent.removeExtra(LEGACY_EXTRA_OPEN_GAME)
    }

    private fun resolveStartup(mainDispatcher: kotlin.coroutines.CoroutineContext, retry: Boolean = false) {
        lifecycleScope.launch(dispatchers.io) {
            (application as Application).awaitStartupReconciliation()
            val destination = when (legacyDatabaseUpgradeCoordinator.ensureReady(retry)) {
                LegacyDatabaseStartupResult.Ready -> resolveStartupDestination(onboardingRepository)
                is LegacyDatabaseStartupResult.Failed -> StartupDestination.LegacyRecovery
            }
            withContext(mainDispatcher) { viewModel.setStartupDestination(destination) }
        }
    }

    @Composable
    private fun ComposeRoot(viewModel: MainActivityViewModel) {
        val darkTheme = isSystemInDarkTheme()
        val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
        val deepNavigationRequest by viewModel.deepNavigationRequest.collectAsStateWithLifecycle()
        val startupDestination by viewModel.startupDestination.collectAsStateWithLifecycle()
        val legacyStates = remember(legacyDatabaseRepository, dispatchers.io) {
            legacyDatabaseRepository.states.flowOn(dispatchers.io)
        }
        val legacyState by legacyStates.collectAsStateWithLifecycle(
            initialValue = LegacyDatabaseState(
                database = null,
                importStatus = LegacyImportStatus.NOT_STARTED,
                report = null,
                lastError = null,
                externallyExported = false,
            ),
        )

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

            StartupDestination.LegacyRecovery -> {
                AppTheme(darkTheme = darkTheme) {
                    LegacyDatabaseRecoveryScreen(
                        message = legacyState.lastError,
                        sizeBytes = legacyState.database?.sizeBytes ?: 0L,
                        canDelete = legacyState.canDelete,
                        onRetry = {
                            viewModel.setStartupDestination(StartupDestination.Pending)
                            val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate
                                ?: dispatchers.main
                            resolveStartup(mainImmediate, retry = true)
                        },
                        onExport = {
                            legacyExportLauncher.launch("tracker-legacy-v${legacyState.database?.sourceVersion ?: 26}.db")
                        },
                        onDelete = {
                            lifecycleScope.launch(dispatchers.io) {
                                val deleted = runCatching { legacyDatabaseRepository.delete() }.isSuccess
                                withContext(dispatchers.main) {
                                    if (deleted) {
                                        viewModel.setStartupDestination(StartupDestination.Pending)
                                        resolveStartup(dispatchers.main)
                                    } else {
                                        Toast.makeText(
                                            this@MainActivityCompose,
                                            R.string.legacy_database_delete_failure,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        },
                    )
                }
                return
            }

            StartupDestination.Onboarding,
            StartupDestination.OnboardingReadFailed -> Unit // handled below via startDestination = Setup
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
                    val effectiveStartDestination: Any =
                        if (startupDestination.requiresOnboarding) Setup else selectedTab
                    MainRoot(
                        startDestination = effectiveStartDestination,
                        deepNavigationRequest = gatedDeepNavigationRequest(
                            startupDestination = startupDestination,
                            request = deepNavigationRequest,
                        ),
                        showOnboardingReadError = startupDestination == StartupDestination.OnboardingReadFailed,
                        onSetupComplete = { viewModel.setStartupDestination(StartupDestination.Main) },
                        onDeepNavigationHandled = viewModel::consumeDeepNavigationRequest,
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
        const val LEGACY_EXTRA_OPEN_GAME = "openGame"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val TARGET_IMPEXP = "impexp"
        const val TARGET_SETTINGS = "settings"
        const val TARGET_GAME = "game"
        const val TARGET_DASHBOARD = "dashboard"
        const val TARGET_STATS = "stats"
    }
}

private fun Any.routeKey(): String = when (this) {
    Dashboard -> "dashboard"
    Stats -> "stats"
    Map -> "map"
    Game -> "game"
    else -> "dashboard"
}

private fun routeFromKey(key: String): Any = when (key) {
    "dashboard" -> Dashboard
    "stats" -> Stats
    "map" -> Map
    "game" -> Game
    else -> Dashboard
}

data class DeepNavigationRequest(
    val target: String,
    val requestId: String,
)

enum class StartupDestination {
    Pending,
    LegacyRecovery,
    Onboarding,
    OnboardingReadFailed,
    Main,
}

@Composable
private fun LegacyDatabaseRecoveryScreen(
    message: String?,
    sizeBytes: Long,
    canDelete: Boolean,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.legacy_database_import_failed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message ?: androidx.compose.ui.res.stringResource(
                        R.string.legacy_database_import_failed_message,
                    ),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.legacy_database_size,
                        android.text.format.Formatter.formatFileSize(
                            androidx.compose.ui.platform.LocalContext.current,
                            sizeBytes,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry) {
                    Text(androidx.compose.ui.res.stringResource(R.string.legacy_database_retry))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onExport) {
                    Text(androidx.compose.ui.res.stringResource(R.string.legacy_database_export))
                }
                if (canDelete) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showDeleteConfirmation = true }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.legacy_database_delete))
                    }
                }
            }
        }
    }
    if (showDeleteConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(androidx.compose.ui.res.stringResource(R.string.legacy_database_delete_confirm_title))
            },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(
                        R.string.legacy_database_delete_confirm_message,
                        android.text.format.Formatter.formatFileSize(
                            androidx.compose.ui.platform.LocalContext.current,
                            sizeBytes,
                        ),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            com.adsamcik.tracker.shared.base.R.string.generic_delete,
                        ),
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            com.adsamcik.tracker.shared.base.R.string.generic_cancel,
                        ),
                    )
                }
            },
        )
    }
}

internal val StartupDestination.requiresOnboarding: Boolean
    get() = this == StartupDestination.Onboarding || this == StartupDestination.OnboardingReadFailed

internal fun gatedDeepNavigationRequest(
    startupDestination: StartupDestination,
    request: DeepNavigationRequest?,
): DeepNavigationRequest? = request.takeIf { startupDestination == StartupDestination.Main }

internal suspend fun resolveStartupDestination(
    onboardingRepository: OnboardingRepository,
): StartupDestination = runCatching {
    if (onboardingRepository.isCompleted.first()) {
        StartupDestination.Main
    } else {
        StartupDestination.Onboarding
    }
}.getOrElse {
    StartupDestination.OnboardingReadFailed
}

internal fun parseDeepNavigationRequest(intent: Intent?): DeepNavigationRequest? {
    intent ?: return null
    val legacyOpenGame = intent.getBooleanExtra(MainActivityCompose.LEGACY_EXTRA_OPEN_GAME, false)
    val explicitTarget = intent.getStringExtra(MainActivityCompose.EXTRA_NAVIGATE_TO)
    val target = explicitTarget ?: (if (legacyOpenGame) MainActivityCompose.TARGET_GAME else null)
        ?: return null

    return target
        .takeIf { it in deepNavigationTargets }
        ?.let(::newDeepNavigationRequest)
}

private fun newDeepNavigationRequest(target: String): DeepNavigationRequest =
    DeepNavigationRequest(target = target, requestId = UUID.randomUUID().toString())

private val deepNavigationTargets = setOf(
    MainActivityCompose.TARGET_IMPEXP,
    MainActivityCompose.TARGET_SETTINGS,
    MainActivityCompose.TARGET_GAME,
    MainActivityCompose.TARGET_DASHBOARD,
    MainActivityCompose.TARGET_STATS,
)

internal fun selectedTabForDeepNavigationTarget(target: String): Any = when (target) {
    MainActivityCompose.TARGET_GAME -> Game
    MainActivityCompose.TARGET_STATS -> Stats
    else -> Dashboard
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(
        routeFromKey(savedStateHandle[KEY_SELECTED_TAB] ?: Dashboard.routeKey())
    )
    val selectedTab: StateFlow<Any> = _selectedTab.asStateFlow()

    private val _deepNavigationRequest = MutableStateFlow(
        savedStateHandle.toDeepNavigationRequest()
    )
    val deepNavigationRequest: StateFlow<DeepNavigationRequest?> = _deepNavigationRequest.asStateFlow()

    private val _startupDestination = MutableStateFlow(
        savedStateHandle.get<String>(KEY_STARTUP_DESTINATION)?.let { name ->
            try {
                StartupDestination.valueOf(name)
            } catch (_: IllegalArgumentException) {
                Tracebox.log.warn(TrackerTraceboxTemplates.NAVIGATION_DESTINATION_REJECTED)
                StartupDestination.Pending
            }
        } ?: StartupDestination.Pending
    )
    val startupDestination: StateFlow<StartupDestination> = _startupDestination.asStateFlow()

    fun setSelectedTab(route: Any) {
        _selectedTab.value = route
        savedStateHandle[KEY_SELECTED_TAB] = route.routeKey()
    }

    fun setDeepNavigationRequest(request: DeepNavigationRequest?) {
        _deepNavigationRequest.value = request
        if (request == null) {
            savedStateHandle.remove<String>(KEY_DEEP_NAV_TARGET)
            savedStateHandle.remove<String>(KEY_DEEP_NAV_REQUEST_ID)
        } else {
            savedStateHandle[KEY_DEEP_NAV_TARGET] = request.target
            savedStateHandle[KEY_DEEP_NAV_REQUEST_ID] = request.requestId
        }
    }

    fun clearDeepNavigationRequest() {
        setDeepNavigationRequest(null)
    }

    /**
     * Claims a pending request before navigation starts, preventing process-death replay.
     */
    fun consumeDeepNavigationRequest(requestId: String): Boolean {
        val request = _deepNavigationRequest.value ?: return false
        if (request.requestId != requestId) return false

        if (savedStateHandle.get<String>(KEY_LAST_HANDLED_DEEP_NAV_REQUEST_ID) == requestId) {
            clearDeepNavigationRequest()
            return false
        }

        savedStateHandle[KEY_LAST_HANDLED_DEEP_NAV_REQUEST_ID] = requestId
        clearDeepNavigationRequest()
        return true
    }

    fun setStartupDestination(destination: StartupDestination) {
        _startupDestination.value = destination
        savedStateHandle[KEY_STARTUP_DESTINATION] = destination.name
    }

    private fun SavedStateHandle.toDeepNavigationRequest(): DeepNavigationRequest? {
        val target = get<String>(KEY_DEEP_NAV_TARGET) ?: return null
        val requestId = get<String>(KEY_DEEP_NAV_REQUEST_ID) ?: return null
        return DeepNavigationRequest(target = target, requestId = requestId)
            .takeUnless {
                requestId == get<String>(KEY_LAST_HANDLED_DEEP_NAV_REQUEST_ID)
            }
    }

    companion object {
        const val KEY_SELECTED_TAB = "main_selected_tab"
        const val KEY_DEEP_NAV_TARGET = "main_deep_nav_target"
        const val KEY_DEEP_NAV_REQUEST_ID = "main_deep_nav_request_id"
        const val KEY_LAST_HANDLED_DEEP_NAV_REQUEST_ID = "main_last_handled_deep_nav_request_id"
        const val KEY_STARTUP_DESTINATION = "main_startup_destination"
    }
}
