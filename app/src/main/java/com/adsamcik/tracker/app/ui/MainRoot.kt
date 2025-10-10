package com.adsamcik.tracker.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory
import com.adsamcik.tracker.app.ui.navigation.Routes

/**
 * Main composition root hosting NavHost and the animated bottom bar.
 * Animation state managed by MainViewModel per evergreen guidelines (§5).
 */
@Composable
fun MainRoot(startDestination: String = Routes.Tracker, onRouteChanged: (String) -> Unit = {}) {
    val factory = LocalViewModelFactory.current
    val viewModel: MainViewModel = viewModel(factory = factory)
    
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: startDestination
    
    // Sync current route to ViewModel
    LaunchedEffect(current) {
        viewModel.setCurrentRoute(current)
        onRouteChanged(current)
    }

    // Back: if not on Map, consume back and navigate to Map; else let system handle
    BackHandler(enabled = current != Routes.Tracker) {
        navController.navigate(Routes.Tracker) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination, modifier = Modifier.fillMaxSize()) {
            composable(Routes.Tracker) {
                com.adsamcik.tracker.tracker.ui.compose.TrackerRoute(
                    onOpenSettings = {
                        navController.navigate(Routes.Settings) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.Map) { com.adsamcik.tracker.map.ui.MapRoute() }
            composable(Routes.Stats) { com.adsamcik.tracker.statistics.fragment.StatsRoute() }
            composable(Routes.Game) { com.adsamcik.tracker.game.ui.compose.GameRoute() }
            composable(Routes.Debug) { com.adsamcik.tracker.app.debug.DebugRoute() }
            composable(Routes.Settings) { com.adsamcik.tracker.app.settings.SettingsRoute() }
        }

        // Bottom navigation with center prominence
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            val barElevation by animateDpAsState(
                viewModel.getBarElevationDp().dp,
                animationSpec = spring(stiffness = Spring.StiffnessLow), label = "bar-elev"
            )
            Surface(tonalElevation = barElevation) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stats
                    val statsScale by animateFloatAsState(
                        viewModel.getStatsScale(),
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "stats-scale"
                    )
                    val statsAlpha by animateFloatAsState(
                        viewModel.getStatsAlpha(),
                        animationSpec = spring(stiffness = Spring.StiffnessLow), label = "stats-alpha"
                    )
                    IconButton(onClick = {
                        navController.navigate(Routes.Stats) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }, modifier = Modifier
                        .scale(statsScale)
                        .alpha(statsAlpha)
                        .testTag("nav_stats")
                        .semantics { selected = current == Routes.Stats }
                    ) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Stats")
                    }

                    // Map (prominent)
                    val mapScale by animateFloatAsState(
                        viewModel.getMapScale(),
                        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.6f), label = "map-scale"
                    )
                    val mapLift by animateDpAsState(
                        viewModel.getMapLiftDp().dp,
                        animationSpec = spring(stiffness = Spring.StiffnessLow), label = "map-lift"
                    )
                    Box(
                        Modifier
                            .size(72.dp)
                            .padding(bottom = mapLift)
                            .shadow(elevation = viewModel.getMapShadowDp().dp, shape = MaterialTheme.shapes.large),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = {
                            val target = if (current == Routes.Map) Routes.Tracker else Routes.Map
                            navController.navigate(target) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }, modifier = Modifier
                            .size(64.dp)
                            .scale(mapScale)
                            .testTag("nav_map")
                            .semantics { selected = current == Routes.Map }
                        ) {
                            Icon(Icons.Filled.Map, contentDescription = "Map")
                        }
                    }

                    // Game
                    val gameScale by animateFloatAsState(
                        viewModel.getGameScale(),
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "game-scale"
                    )
                    val gameAlpha by animateFloatAsState(
                        viewModel.getGameAlpha(),
                        animationSpec = spring(stiffness = Spring.StiffnessLow), label = "game-alpha"
                    )
                    IconButton(onClick = {
                        navController.navigate(Routes.Game) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }, modifier = Modifier
                        .scale(gameScale)
                        .alpha(gameAlpha)
                        .testTag("nav_game")
                        .semantics { selected = current == Routes.Game }
                    ) {
                        Icon(Icons.Filled.VideogameAsset, contentDescription = "Game")
                    }
                }
            }
        }
    }
}
