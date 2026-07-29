package com.adsamcik.tracker.sqlite.runtime

/**
 * Loads the native library packaged by the official SQLite Android binding.
 *
 * The upstream binding deliberately uses the `org.sqlite` namespace and does
 * not load `libsqliteX.so` itself. Every bridge entry point reaches this guard
 * before it opens a database or invokes a vendor API that can enter native
 * code.
 */
public object SQLiteXRuntime {

	/** Ensures [LIBRARY_NAME] has been loaded for this application class loader. */
	@JvmStatic
	public fun ensureLoaded() {
		loaded
	}

	private val loaded: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		System.loadLibrary(LIBRARY_NAME)
	}

	private const val LIBRARY_NAME = "sqliteX"
}
