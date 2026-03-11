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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
import com.adsamcik.tracker.app.ui.navigation.TrophyCase
import com.adsamcik.tracker.app.ui.navigation.TripDetail
import com.adsamcik.tracker.app.ui.navigation.History
import com.adsamcik.tracker.app.ui.navigation.Debug
import com.adsamcik.tracker.app.ui.navigation.Settings
import com.adsamcik.tracker.app.ui.navigation.SettingsOrigin
import com.adsamcik.tracker.app.ui.navigation.ActivitySettings
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.activity.DeepNavigationRequest
import com.adsamcik.tracker.app.activity.MainActivityCompose
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
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout

/**
 * Main composition root hosting NavHost and the animated bottom bar.
 * Animation state managed by MainViewModel per evergreen guidelines (§5).
 * 
 * Phase 2: Precision upgrade prompt managed here (app-level overlay)
 */
@Composable
fun MainRoot(
    startDestination: AppRoute = Dashboard,
    deepNavigationRequest: DeepNavigationRequest? = null,
    onDeepNavigationHandled: () -> Unit = {},
    onRouteChanged: (AppRoute) -> Unit = {}
) {
    val viewModel: MainViewModel = hiltViewModel()

    val hazeState = remember { HazeState() }
    
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    var lastTopLevelRoute by remember {
        mutableStateOf<AppRoute>(
            when (startDestination) {
                Dashboard, Stats, Map, Game -> startDestination
                else -> Dashboard
            }
        )
    }
    var settingsLaunchNonce by remember { mutableStateOf(0L) }
    var tripDetailFallbackRoute by remember { mutableStateOf<AppRoute>(Stats) }
    var gameChallengePickerRequest by remember { mutableStateOf(0L) }
    
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
            val route: AppRoute? = when {
                destination.hasRoute<Dashboard>() -> Dashboard
                destination.hasRoute<Stats>() -> Stats
                destination.hasRoute<Map>() -> Map
                destination.hasRoute<Game>() -> Game
                destination.hasRoute<TrophyCase>() -> TrophyCase
                destination.hasRoute<TripDetail>() -> Stats
                destination.hasRoute<History>() -> Stats
                destination.hasRoute<Debug>() -> Debug
                destination.hasRoute<Settings>() -> Settings()
                destination.hasRoute<ActivitySettings>() -> ActivitySettings
                else -> null
            }

            if (route != null) {
                viewModel.setCurrentRoute(route)
                if (route == Dashboard || route == Stats || route == Map || route == Game) {
                    lastTopLevelRoute = route
                    onRouteChanged(route)
                }
            } else {
                Log.w("MainRoot", "Unrecognized destination: ${destination.route}")
            }
        }
    }

    LaunchedEffect(deepNavigationRequest) {
        val request = deepNavigationRequest ?: return@LaunchedEffect

        when (request.target) {
            MainActivityCompose.TARGET_IMPEXP -> {
                settingsLaunchNonce += 1
                navController.navigate(
                    Settings(
                        origin = lastTopLevelRoute.toSettingsOrigin(),
                        nonce = settingsLaunchNonce,
                    )
                ) {
                    launchSingleTop = true
                }
            }
            MainActivityCompose.TARGET_GAME -> {
                navController.navigate(Game) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            MainActivityCompose.TARGET_DASHBOARD -> {
                if (request.scrollTo == "goals") {
                    Log.d("MainRoot", "Deep link requested dashboard goals section")
                }
                navController.navigate(Dashboard) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            MainActivityCompose.TARGET_STATS -> {
                navController.navigate(Stats) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        onDeepNavigationHandled()
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

    // Navigation Items
    val navItems = listOf(
        NavigationItem(Dashboard, Icons.Filled.Home, stringResource(R.string.main_nav_tracker), "nav_dashboard"),
        NavigationItem(Stats, Icons.Filled.BarChart, stringResource(R.string.module_statistics_title), "nav_stats"),
        NavigationItem(Map, Icons.Filled.Map, stringResource(R.string.module_map_title), "nav_map"),
        NavigationItem(Game, Icons.Filled.VideogameAsset, stringResource(R.string.module_game_title), "nav_game")
    )

    fun routeMatches(route: AppRoute): Boolean {
        return currentDestination?.hierarchy?.any { destination ->
            when (route) {
                Dashboard -> destination.hasRoute<Dashboard>() || destination.route == "dashboard" || destination.route?.contains("Dashboard") == true
                Stats -> destination.hasRoute<Stats>() || destination.route == "stats" || destination.route?.contains("Stats") == true
                Map -> destination.hasRoute<Map>() || destination.route == "map" || destination.route?.contains("Map") == true
                Game -> destination.hasRoute<Game>() || destination.route == "game" || destination.route?.contains("Game") == true
                else -> false
            }
        } == true
    }
    val currentRouteObj = navItems.find { item -> routeMatches(item.id as AppRoute) }
    val hideTopLevelNavigation = currentDestination?.hierarchy?.any { destination ->
        destination.hasRoute<Settings>() ||
                destination.hasRoute<Debug>() ||
                destination.hasRoute<ActivitySettings>() ||
                destination.hasRoute<TripDetail>() ||
                destination.hasRoute<History>() ||
                destination.hasRoute<TrophyCase>()
    } == true
    val effectiveRouteObj = currentRouteObj ?: navItems.find { it.id == lastTopLevelRoute }
    val isDashboard = effectiveRouteObj?.id == Dashboard
    val isMap = effectiveRouteObj?.id == Map
    val navigationLayout = rememberMainNavigationLayout()
    val useSideRail = navigationLayout == MainNavigationLayout.SideRail && !hideTopLevelNavigation && effectiveRouteObj != null
    val bottomPadding = if (useSideRail || isMap || isDashboard) 0.dp else 96.dp // 72 bar + 24 padding

    fun openSettings(origin: AppRoute) {
        settingsLaunchNonce += 1
        navController.navigate(
            Settings(
                origin = origin.toSettingsOrigin(),
                nonce = settingsLaunchNonce,
            )
        ) {
            launchSingleTop = true
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background) // Set main background color
    ) {
        if (useSideRail) {
            AdaptiveNavigationRail(
                items = navItems,
                selectedItem = requireNotNull(effectiveRouteObj),
                onItemClick = { item ->
                    navController.navigate(item.id) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, top = 16.dp, bottom = 16.dp),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
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
                            openSettings(Dashboard)
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
                        onOpenChallenges = {
                            gameChallengePickerRequest += 1L
                            navController.navigate(Game) {
                                launchSingleTop = true
                            }
                        },
                        onSessionDetailClick = { sessionId ->
                            tripDetailFallbackRoute = Dashboard
                            navController.navigate(TripDetail(sessionId)) {
                                launchSingleTop = true
                            }
                        },
                        contentPadding = if (useSideRail) {
                            PaddingValues()
                        } else {
                            PaddingValues(bottom = 96.dp + navBarPad)
                        }
                    )
                }
                composable<Map> {
                    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    com.adsamcik.tracker.map.ui.MapRoute(
                        contentPadding = if (useSideRail) {
                            PaddingValues()
                        } else {
                            PaddingValues(bottom = 96.dp + navBarPad)
                        }
                    )
                }
                composable<Stats> {
                    com.adsamcik.tracker.statistics.fragment.StatsRoute(
                        onTripClick = { tripId ->
                            tripDetailFallbackRoute = Stats
                            navController.navigate(TripDetail(tripId)) {
                                launchSingleTop = true
                            }
                        },
                        onTripViewOnMap = {
                            navController.navigate(Map) {
                                launchSingleTop = true
                                restoreState = true
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
                composable<Game> {
                    com.adsamcik.tracker.game.ui.compose.GameRoute(
                        openChallengePickerRequest = gameChallengePickerRequest,
                        onOpenSettings = {
                            openSettings(Game)
                        },
                        onNavigateToTrophyCase = {
                            navController.navigate(TrophyCase) {
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
                composable<TrophyCase> {
                    com.adsamcik.tracker.game.ui.compose.TrophyCaseRoute(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<History> {
                    com.adsamcik.tracker.statistics.ui.HistoryRoute(
                        onNavigateToTripDetail = { tripId ->
                            tripDetailFallbackRoute = Stats
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
                        onBack = {
                            if (!navController.popBackStack()) {
                                navController.navigate(tripDetailFallbackRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
                composable<Debug> {
                    com.adsamcik.tracker.app.debug.DebugRoute(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable<Settings> { backStackEntry ->
                    val settingsRoute = backStackEntry.toRoute<Settings>()
                    com.adsamcik.tracker.app.settings.SettingsRoute(
                        onNavigateBack = {
                            if (!navController.popBackStack()) {
                                navController.navigate(settingsRoute.origin.toAppRoute()) {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
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

        // Global settings affordance for top-level tabs that do not render their own in-content entry point.
        if (!hideTopLevelNavigation && effectiveRouteObj != null && !isDashboard) {
            IconButton(
                onClick = {
                    openSettings((effectiveRouteObj.id as? AppRoute) ?: lastTopLevelRoute)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(1f)
                    .statusBarsPadding()
                    .padding(end = 8.dp, top = 4.dp)
                    .size(48.dp)
                    .testTag("settings_global")
            ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (!hideTopLevelNavigation && effectiveRouteObj != null && !useSideRail) {
                FloatingNavigationBar(
                    items = navItems,
                    selectedItem = effectiveRouteObj,
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
}

private fun AppRoute.toSettingsOrigin(): SettingsOrigin = when (this) {
    Dashboard -> SettingsOrigin.DASHBOARD
    Stats -> SettingsOrigin.STATS
    Map -> SettingsOrigin.MAP
    Game -> SettingsOrigin.GAME
    else -> SettingsOrigin.DASHBOARD
}

private fun SettingsOrigin.toAppRoute(): AppRoute = when (this) {
    SettingsOrigin.DASHBOARD -> Dashboard
    SettingsOrigin.STATS -> Stats
    SettingsOrigin.MAP -> Map
    SettingsOrigin.GAME -> Game
}
