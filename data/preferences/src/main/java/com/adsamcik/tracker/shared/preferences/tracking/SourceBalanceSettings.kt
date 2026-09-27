package com.adsamcik.tracker.shared.preferences.tracking

/** Additive preference metadata. Absence preserves the original legacy/custom configuration. */
data class SourceBalanceOverrides(
    val location: Int? = null,
    val activity: Int? = null,
    val steps: Int? = null,
    val pressure: Int? = null,
    val wifi: Int? = null,
    val cell: Int? = null,
) {
    init { listOf(location, activity, steps, pressure, wifi, cell).forEach { require(it == null || it in 1..5) } }
    operator fun get(source: TrackingSourceComponent): Int? = when (source) {
        TrackingSourceComponent.LOCATION -> location
        TrackingSourceComponent.ACTIVITY -> activity
        TrackingSourceComponent.STEPS -> steps
        TrackingSourceComponent.PRESSURE -> pressure
        TrackingSourceComponent.WIFI -> wifi
        TrackingSourceComponent.CELL -> cell
    }
    fun with(source: TrackingSourceComponent, level: Int?): SourceBalanceOverrides = when (source) {
        TrackingSourceComponent.LOCATION -> copy(location = level)
        TrackingSourceComponent.ACTIVITY -> copy(activity = level)
        TrackingSourceComponent.STEPS -> copy(steps = level)
        TrackingSourceComponent.PRESSURE -> copy(pressure = level)
        TrackingSourceComponent.WIFI -> copy(wifi = level)
        TrackingSourceComponent.CELL -> copy(cell = level)
    }
}

object SourceBalanceSettings {
    const val PROFILE_VERSION = 1
    const val DEFAULT_LEVEL = 3
    const val LIVE_LEVEL = 4
    fun requireValid(version: Int?, overall: Int?, overrides: SourceBalanceOverrides) {
        require((version == null) == (overall == null))
        require(version == null || version == PROFILE_VERSION)
        require(overall == null || overall in 1..5)
        require(version != null || overrides == SourceBalanceOverrides())
    }
}

fun TrackingParamsState.baselineBalanceLevel(source: TrackingSourceComponent): Int? =
    if (balanceProfileVersion != null) sourceBalanceOverrides[source] ?: overallBalanceLevel
    else locationBalanceLevel.takeIf { source == TrackingSourceComponent.LOCATION }

fun TrackingParamsState.captureParticipates(source: TrackingSourceComponent): Boolean {
    val selected = when (source) {
        TrackingSourceComponent.LOCATION -> locationEnabled
        TrackingSourceComponent.ACTIVITY -> activityEnabled
        TrackingSourceComponent.STEPS -> stepsEnabled
        TrackingSourceComponent.PRESSURE -> barometerEnabled
        TrackingSourceComponent.WIFI -> wifiEnabled
        TrackingSourceComponent.CELL -> cellEnabled
    }
    val frequency = when (source) {
        TrackingSourceComponent.LOCATION -> sourceCollectionSettings.location
        TrackingSourceComponent.ACTIVITY -> sourceCollectionSettings.activity
        TrackingSourceComponent.STEPS -> sourceCollectionSettings.steps
        TrackingSourceComponent.PRESSURE -> sourceCollectionSettings.pressure
        TrackingSourceComponent.WIFI -> sourceCollectionSettings.wifi
        TrackingSourceComponent.CELL -> sourceCollectionSettings.cell
    }
    return selected && (balanceProfileVersion != null || frequency != SourceCollectionFrequency.OFF)
}
