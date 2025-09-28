package com.adsamcik.tracker.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.ui.navigation.Routes

/**
 * Main composition root hosting the primary navigation and animated map overlay.
 */
@Composable
fun MainRoot(startDestination: String = Routes.Map, onRouteChanged: (String) -> Unit = {}) {
    val navController = rememberNavController()

    val navStartDestination = remember {
        when (startDestination) {
            Routes.Game, Routes.Debug, Routes.Settings -> startDestination
            else -> Routes.Stats
        }
    }

    var isMapExpanded by rememberSaveable { mutableStateOf(startDestination == Routes.Map) }
    var lastNonMapRoute by rememberSaveable { mutableStateOf(navStartDestination) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPrimaryRoute = backStackEntry?.destination?.route ?: lastNonMapRoute

    LaunchedEffect(currentPrimaryRoute) {
        if (currentPrimaryRoute != Routes.Map) {
            lastNonMapRoute = currentPrimaryRoute
        }
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(startDestination) {
        when (startDestination) {
            Routes.Map -> if (!isMapExpanded) isMapExpanded = true
            Routes.Stats, Routes.Game, Routes.Debug, Routes.Settings -> {
                if (isMapExpanded) isMapExpanded = false
                if (currentPrimaryRoute != startDestination) {
                    navigateTo(startDestination)
                }
            }
        }
    }

    val mapProgress by animateFloatAsState(
        targetValue = if (isMapExpanded) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.75f),
        label = "map-progress"
    )

    val effectiveRoute = if (isMapExpanded) Routes.Map else currentPrimaryRoute
    LaunchedEffect(effectiveRoute) {
        onRouteChanged(effectiveRoute)
    }

    BackHandler(enabled = isMapExpanded || currentPrimaryRoute != Routes.Stats) {
        when {
            isMapExpanded -> isMapExpanded = false
            currentPrimaryRoute != Routes.Stats -> navigateTo(Routes.Stats)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val collapsedOffsetPx = with(density) { maxHeight.toPx() }
        val overlayOffset by animateFloatAsState(
            targetValue = if (isMapExpanded) 0f else collapsedOffsetPx,
            animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.78f),
            label = "map-offset"
        )

        NavHost(
            navController = navController,
            startDestination = navStartDestination,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Routes.Stats) { com.adsamcik.tracker.statistics.fragment.StatsRoute() }
            composable(Routes.Game) { com.adsamcik.tracker.game.ui.compose.GameRoute() }
            composable(Routes.Debug) { com.adsamcik.tracker.app.debug.DebugRoute() }
            composable(Routes.Settings) { com.adsamcik.tracker.app.settings.SettingsRoute() }
        }

        if (mapProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.1f * mapProgress }
                    .background(MaterialTheme.colorScheme.scrim)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = overlayOffset
                    alpha = mapProgress
                }
        ) {
            Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxSize()) {
                com.adsamcik.tracker.map.ui.MapRoute()
            }
        }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            val barElevation by animateDpAsState(
                targetValue = if (mapProgress > 0f) 10.dp else 4.dp,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "bar-elev"
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
                    val statsSelected = !isMapExpanded && currentPrimaryRoute == Routes.Stats
                    val statsScale by animateFloatAsState(
                        targetValue = if (statsSelected) 1.1f else 0.95f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "stats-scale"
                    )
                    val statsAlpha by animateFloatAsState(
                        targetValue = if (isMapExpanded) 0.85f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "stats-alpha"
                    )

                    IconButton(
                        onClick = {
                            if (isMapExpanded) isMapExpanded = false
                            if (currentPrimaryRoute != Routes.Stats) navigateTo(Routes.Stats)
                        },
                        modifier = Modifier
                            .scale(statsScale)
                            .alpha(statsAlpha)
                            .testTag("nav_stats")
                            .semantics {
                                selected = statsSelected
                                role = Role.Tab
                            }
                    ) {
                        Icon(
                            Icons.Filled.BarChart,
                            contentDescription = stringResource(R.string.main_nav_stats)
                        )
                    }

                    val mapScale by animateFloatAsState(
                        targetValue = 1f + (0.25f * mapProgress),
                        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.6f),
                        label = "map-scale"
                    )
                    val mapLift by animateDpAsState(
                        targetValue = if (isMapExpanded) 6.dp else 0.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "map-lift"
                    )
                    val mapShadow by animateDpAsState(
                        targetValue = if (isMapExpanded) 8.dp else 0.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "map-shadow"
                    )
                    val mapContainerColor by animateColorAsState(
                        targetValue = if (isMapExpanded) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.75f),
                        label = "map-color"
                    )
                    val mapIconTint by animateColorAsState(
                        targetValue = if (isMapExpanded) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.75f),
                        label = "map-icon-tint"
                    )
                    val mapToggleDescription = if (isMapExpanded) {
                        stringResource(R.string.main_nav_map_collapse)
                    } else {
                        stringResource(R.string.main_nav_map_expand)
                    }
                    val mapStateDescription = if (isMapExpanded) {
                        stringResource(R.string.main_nav_map_state_expanded)
                    } else {
                        stringResource(R.string.main_nav_map_state_collapsed)
                    }

                    Surface(
                        modifier = Modifier
                            .size(72.dp)
                            .padding(bottom = mapLift),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = if (isMapExpanded) 6.dp else 2.dp,
                        shadowElevation = mapShadow,
                        color = mapContainerColor
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = {
                                    val nextExpanded = !isMapExpanded
                                    isMapExpanded = nextExpanded
                                    if (!nextExpanded && currentPrimaryRoute != lastNonMapRoute) {
                                        navigateTo(lastNonMapRoute)
                                    }
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .scale(mapScale)
                                    .testTag("nav_map")
                                    .semantics {
                                        role = Role.Button
                                        stateDescription = mapStateDescription
                                        selected = isMapExpanded
                                    }
                            ) {
                                Icon(
                                    Icons.Filled.Map,
                                    contentDescription = mapToggleDescription,
                                    tint = mapIconTint
                                )
                            }
                        }
                    }

                    val gameSelected = !isMapExpanded && currentPrimaryRoute == Routes.Game
                    val gameScale by animateFloatAsState(
                        targetValue = if (gameSelected) 1.1f else 0.95f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "game-scale"
                    )
                    val gameAlpha by animateFloatAsState(
                        targetValue = if (isMapExpanded) 0.85f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "game-alpha"
                    )

                    IconButton(
                        onClick = {
                            if (isMapExpanded) isMapExpanded = false
                            if (currentPrimaryRoute != Routes.Game) navigateTo(Routes.Game)
                        },
                        modifier = Modifier
                            .scale(gameScale)
                            .alpha(gameAlpha)
                            .testTag("nav_game")
                            .semantics {
                                selected = gameSelected
                                role = Role.Tab
                            }
                    ) {
                        Icon(
                            Icons.Filled.VideogameAsset,
                            contentDescription = stringResource(R.string.main_nav_game)
                        )
                    }
                }
            }
        }
    }
}
