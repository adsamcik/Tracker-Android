package com.adsamcik.tracker.shared.map

/**
 * Provides MapLibre style URIs based on theme preference.
 * Uses OpenFreeMap styles (no API key required).
 */
object MapStyleProvider {

    private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

    fun styleUri(isDarkTheme: Boolean): String =
        if (isDarkTheme) STYLE_DARK else STYLE_LIGHT
}
