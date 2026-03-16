package com.adsamcik.tracker.app.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer

/**
 * Pre-loads the MapLibre native library during App Startup.
 *
 * MapLibre's `.so` takes ~5s to load on emulators. By moving the
 * `System.loadLibrary` call to App Startup, the library is ready
 * before the first Compose frame that uses MapLibre, eliminating
 * a visible lag when navigating to the map screen.
 *
 * This initializer has **no ordering dependencies** — it purely loads
 * a native library and does not interact with any other component.
 */
class NativeLibraryInitializer : Initializer<Unit> {

	override fun create(context: Context) {
		try {
			System.loadLibrary("maplibre")
		} catch (e: UnsatisfiedLinkError) {
			Log.w(TAG, "MapLibre native library not available: ${e.message}")
		}
	}

	override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

	private companion object {
		const val TAG = "NativeLibInit"
	}
}
