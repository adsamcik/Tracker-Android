package com.adsamcik.tracker.map.v2.ui

import androidx.fragment.app.FragmentManager
import com.adsamcik.tracker.map.MapOwner
import com.google.android.gms.maps.GoogleMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Lifecycle-safe wrapper around MapOwner providing a cold flow that emits when map becomes ready.
 * (Task 2.3 scaffolding; does not alter existing FragmentMap behavior.)
 */
class MapHost(
    private val mapOwner: MapOwner = MapOwner()
) {
    private val _mapReady = MutableSharedFlow<GoogleMap>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val mapReady: SharedFlow<GoogleMap> = _mapReady

    init {
        mapOwner.addOnCreateListener { gm ->
            _mapReady.tryEmit(gm)
        }
    }

    fun create(fragmentManager: FragmentManager) = mapOwner.createMap(fragmentManager)
    fun enable() = mapOwner.onEnable()
    fun disable() = mapOwner.onDisable()
    fun delegate(): MapOwner = mapOwner
}
