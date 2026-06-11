package com.adsamcik.tracker.feature.game.api.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Game

@Serializable
data class MiniGameSession(val gameId: String)

@Serializable
data object MiniGameScores

@Serializable
data object Achievements
