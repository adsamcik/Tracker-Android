package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Entry point composable for the Game tab. Uses Hilt for dependency injection.
 */
@Composable
fun GameRoute() {
    val vm: GameViewModel = hiltViewModel()
    val points by vm.pointsToday.collectAsState()
    val steps by vm.stepsSummary.collectAsState()
    val challenges by vm.challenges.collectAsState()
    GameScreen(pointsToday = points, steps = steps, challenges = challenges)
}

