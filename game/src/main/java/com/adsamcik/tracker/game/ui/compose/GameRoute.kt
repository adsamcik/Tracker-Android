package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory

/**
 * Entry point composable for the Game tab. Uses ViewModelFactory for dependency injection.
 */
@Composable
fun GameRoute() {
    val factory = LocalViewModelFactory.current
    val vm: GameViewModel = viewModel(factory = factory)
    val points by vm.pointsToday.collectAsState()
    val steps by vm.stepsSummary.collectAsState()
    val challenges by vm.challenges.collectAsState()
    GameScreen(pointsToday = points, steps = steps, challenges = challenges)
}

