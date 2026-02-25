package com.adsamcik.tracker.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.toRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Stats
import com.adsamcik.tracker.app.ui.navigation.Map
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.app.ui.navigation.TripDetail
import com.adsamcik.tracker.app.ui.navigation.History
import com.adsamcik.tracker.app.ui.navigation.Debug
import com.adsamcik.tracker.app.ui.navigation.Settings
import com.adsamcik.tracker.app.ui.navigation.ActivitySettings
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionType
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import com.adsamcik.tracker.app.tracker.ui.UpgradeToPrecisePrompt
import com.adsamcik.tracker.app.tracker.ui.UpgradeReason
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.R as PrefR
import com.adsamcik.tracker.shared.utils.style.compose.AppColors

/**
 * Main composition root hosting NavHost and the animated bottom bar.
 * Animation state managed by MainViewModel per evergreen guidelines (§5).
 * 
 * Phase 2: Precision upgrade prompt managed here (app-level overlay)
 */
@Composable
fun MainRoot(startDestination: Any = Dashboard, onRouteChanged: (Any) -> Unit = {}) {
    val viewModel: MainViewModel = hiltViewModel()

    val hazeState = remember { HazeState() }
    
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    
    // Phase 2: Precision upgrade prompt state
    val context = LocalContext.current
    val prefs = remember { Preferences.getPref(context) }
    
    var shouldShowUpgradePrompt by remember {
        mutableStateOf(
            prefs.getBooleanRes(
                PrefR.string.settings_should_show_precision_upgrade_key,
                false
            )
        )
    }
    
    var hasPreciseLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    var showPreciseLocationPermissionRequest by remember { mutableStateOf(false) }
    
    // Sync current route to ViewModel
    LaunchedEffect(currentDestination) {
        val destination = currentDestination
        if (destination != null) {
            // Priority order for route resolution
            val route = when {
                destination.hasRoute<Dashboard>() -> Dashboard
                destination.hasRoute<Stats>() -> Stats
                destination.hasRoute<Map>() -> Map
                destination.hasRoute<Game>() -> Game
                destination.hasRoute<Debug>() -> Debug
                destination.hasRoute<Settings>() -> Settings
                else -> {
                    Log.w("MainRoot", "Unrecognized destination: ${destination.route}, falling back to Dashboard")
                    Dashboard
                }
            }
            
            viewModel.setCurrentRoute(route)
        }
    }
    
    // Precise location upgrade permission request (from upgrade prompt)
    if (showPreciseLocationPermissionRequest) {
        ContextualPermissionRequest(
            permissionType = PermissionType.LOCATION_FOREGROUND,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            onPermissionResult = { granted ->
                hasPreciseLocationPermission = granted
                if (granted) {
                    // Upgrade successful - update preference and reset counter
                    prefs.edit {
                        setString(
                            PrefR.string.settings_location_precision_key,
                            "PRECISE"
                        )
                        setInt(PrefR.string.settings_approximate_session_count_key, 0)
                        setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
                    }
                    shouldShowUpgradePrompt = false
                }
                // On denial, just close without feedback (user made their choice)
                showPreciseLocationPermissionRequest = false
            },
            onDismiss = {
                showPreciseLocationPermissionRequest = false
            }
        )
    }
    
    // Phase 2: Precision upgrade prompt (non-blocking overlay)
    if (shouldShowUpgradePrompt && !hasPreciseLocationPermission) {
        UpgradeToPrecisePrompt(
            onDismiss = {
                // User dismissed - respect choice, don't prompt again
                prefs.edit {
                    setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
                    setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
                    setInt(PrefR.string.settings_approximate_session_count_key, 0)
                }
                shouldShowUpgradePrompt = false
            },
            onUpgrade = {
                // User wants to upgrade - request precise permission
                shouldShowUpgradePrompt = false
                showPreciseLocationPermissionRequest = true
            },
            reason = UpgradeReason.GENERAL
        )
    }

    // Back: if not on Map, consume back and navigate to Map; else let system handle
    // Note: This back logic needs careful review with type-safe nav.
    // For now, let's remove legacy manual back handling if it relies on string comparisons easily.
    // Or check if not tracker.
    val isDashboard = currentDestination?.hierarchy?.any { it.hasRoute<Dashboard>() } == true
    BackHandler(enabled = !isDashboard) {
        navController.navigate(Dashboard) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Navigation Items
    val navItems = listOf(
        NavigationItem(Dashboard, Icons.Filled.Home, stringResource(R.string.main_nav_tracker), "nav_dashboard"),
        NavigationItem(Stats, Icons.Filled.BarChart, stringResource(R.string.main_nav_stats), "nav_stats"),
        NavigationItem(Map, Icons.Filled.Map, stringResource(R.string.main_nav_map), "nav_map"),
        NavigationItem(Game, Icons.Filled.VideogameAsset, stringResource(R.string.main_nav_game), "nav_game")
    )

    // Determine if we need bottom padding (overlap map, pad others)
    val isMap = currentDestination?.hasRoute<Map>() == true
    val bottomPadding = if (isMap || isDashboard) 0.dp else 96.dp // 72 bar + 24 padding

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background) // Set main background color
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .padding(bottom = bottomPadding)
        ) {
            composable<Dashboard> {
                val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                com.adsamcik.tracker.dashboard.ui.compose.DashboardRoute(
                    onOpenSettings = {
                        navController.navigate(Settings) {
                            launchSingleTop = true
                        }
                    },
                    onOpenMap = {
                        navController.navigate(Map) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenGame = {
                        navController.navigate(Game) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSessionDetailClick = { sessionId ->
                        navController.navigate(TripDetail(sessionId)) {
                            launchSingleTop = true
                        }
                    },
                    contentPadding = PaddingValues(bottom = 96.dp + navBarPad)
                )
            }
            composable<Map> { 
                val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                com.adsamcik.tracker.map.ui.MapRoute(
                    contentPadding = PaddingValues(bottom = 96.dp + navBarPad)
                ) 
            }
            composable<Stats> {
                com.adsamcik.tracker.statistics.fragment.StatsRoute(
                    onTripClick = { tripId ->
                        navController.navigate(TripDetail(tripId)) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToHistory = {
                        navController.navigate(History) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToTracker = {
                        navController.navigate(Dashboard) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable<Game> { com.adsamcik.tracker.game.ui.compose.GameRoute() }
            composable<History> {
                com.adsamcik.tracker.statistics.ui.HistoryRoute(
                    onNavigateToTripDetail = { tripId ->
                        navController.navigate(TripDetail(tripId)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<TripDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<TripDetail>()
                com.adsamcik.tracker.statistics.ui.TripDetailRoute(
                    tripId = route.tripId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable<Debug> { com.adsamcik.tracker.app.debug.DebugRoute() }
            composable<Settings> {
                com.adsamcik.tracker.app.settings.SettingsRoute(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDebug = { navController.navigate(Debug) },
                    onNavigateToActivities = {
                        navController.navigate(ActivitySettings) { launchSingleTop = true }
                    }
                )
            }
            composable<ActivitySettings> {
                com.adsamcik.tracker.activity.ui.SessionActivityRoute(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // Floating Navigation Bar (overlay)
        val currentRouteObj = navItems.find { item ->
            currentDestination?.hierarchy?.any { 
                when(item.id) {
                    Dashboard -> it.hasRoute<Dashboard>()
                    Stats -> it.hasRoute<Stats>()
                    Map -> it.hasRoute<Map>()
                    Game -> it.hasRoute<Game>()
                    else -> false
                }
            } == true
        }

        // Settings icon overlay (visible on Stats, Map, Game — Dashboard has its own)
        if (currentRouteObj != null && !isDashboard) {
            IconButton(
                onClick = {
                    navController.navigate(Settings) {
                        launchSingleTop = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 8.dp, top = 4.dp)
                    .testTag("settings_global")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (currentRouteObj != null) {
            FloatingNavigationBar(
                items = navItems,
                selectedItem = currentRouteObj,
                onItemClick = { item ->
                    navController.navigate(item.id) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(), // Avoid overlap with system navigation
                hazeState = hazeState
            )
        }
    }
}

