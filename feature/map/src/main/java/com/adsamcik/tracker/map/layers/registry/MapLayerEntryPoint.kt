package com.adsamcik.tracker.map.layers.registry

import android.content.Context
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used by [DefaultLayerRegistry] to resolve singletons that the
 * registry can't receive via constructor injection (the registry is constructed
 * eagerly inside Compose `remember { ... }` blocks). Map layers only call this
 * lazily, inside their factory lambdas, so the application context is always
 * available by the time the entry point fires.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MapLayerEntryPoint {
	fun speedLimitSource(): SpeedLimitSource
}

internal fun Context.speedLimitSource(): SpeedLimitSource =
	EntryPointAccessors.fromApplication(applicationContext, MapLayerEntryPoint::class.java)
		.speedLimitSource()
