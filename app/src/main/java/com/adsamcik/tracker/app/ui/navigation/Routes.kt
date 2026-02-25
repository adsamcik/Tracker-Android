package com.adsamcik.tracker.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute

@Serializable
data object Dashboard : AppRoute

@Serializable
data object Stats : AppRoute

@Serializable
data object Map : AppRoute

@Serializable
data object Game : AppRoute

@Serializable
data class TripDetail(val tripId: Long) : AppRoute

@Serializable
data object History : AppRoute

@Serializable
data object Debug : AppRoute

@Serializable
data object Settings : AppRoute

@Serializable
data object ActivitySettings : AppRoute
