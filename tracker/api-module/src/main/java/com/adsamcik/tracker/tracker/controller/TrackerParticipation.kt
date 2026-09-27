package com.adsamcik.tracker.tracker.controller

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Existing published live lifecycle, independent of a separately hosted background purpose. */
val TrackerStateReader.hasLiveCapture: Boolean get() = sessionInfoFlow.value != null

/** Derived observation: no additional persisted lifecycle or independently mutable flag. */
val TrackerStateReader.hasLiveCaptureFlow: Flow<Boolean>
    get() = sessionInfoFlow.map { it != null }.distinctUntilChanged()

/** Physical collection hosting; it grants neither a live session nor source persistence consent. */
val TrackerStateReader.hasCollectionHost: Boolean get() = isServiceRunning
val TrackerStateReader.hasCollectionHostFlow: StateFlow<Boolean> get() = isServiceRunningFlow
