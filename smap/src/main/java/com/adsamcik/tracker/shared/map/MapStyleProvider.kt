package com.adsamcik.tracker.shared.map

import java.io.File

/**
 * Provides MapLibre style URIs for basemap rendering.
 *
 * Default mode: asset-bundled z0-z6 PMTiles with light/dark style JSON.
 * Custom mode: user-imported PMTiles file referenced via runtime-generated style JSON.
 */
object MapStyleProvider {

    private const val ASSET_STYLE_LIGHT = "asset://map-style-light.json"
    private const val ASSET_STYLE_DARK = "asset://map-style-dark.json"

    /** Returns the asset URI for the bundled basemap style. */
    fun styleUri(isDarkTheme: Boolean): String =
        if (isDarkTheme) ASSET_STYLE_DARK else ASSET_STYLE_LIGHT

    /**
     * Build a style JSON string at runtime referencing a user-imported PMTiles file.
     * Returns null if the file doesn't exist.
     */
    fun customStyleJson(pmtilesPath: String, isDarkTheme: Boolean): String? {
        val file = File(pmtilesPath)
        if (!file.exists()) return null
        return buildStyleJson(pmtilesPath, isDarkTheme)
    }

    private fun buildStyleJson(pmtilesPath: String, isDarkTheme: Boolean): String {
        val bg = if (isDarkTheme) "#1a1a2e" else "#f0f0f0"
        val water = if (isDarkTheme) "#0a1628" else "#aad3df"
        val landuse = if (isDarkTheme) "#1a2a1a" else "#e0e8e0"
        val boundary = if (isDarkTheme) "#555555" else "#999999"
        val road = if (isDarkTheme) "#333333" else "#ffffff"
        val name = if (isDarkTheme) "Tracker Custom Dark" else "Tracker Custom Light"

        return """
            {
              "version": 8,
              "name": "$name",
              "sources": {
                "basemap": {
                  "type": "vector",
                  "url": "pmtiles://file:///$pmtilesPath"
                }
              },
              "layers": [
                {"id":"background","type":"background","paint":{"background-color":"$bg"}},
                {"id":"water","type":"fill","source":"basemap","source-layer":"water","paint":{"fill-color":"$water"}},
                {"id":"landuse","type":"fill","source":"basemap","source-layer":"landuse","paint":{"fill-color":"$landuse"}},
                {"id":"boundary","type":"line","source":"basemap","source-layer":"boundary","paint":{"line-color":"$boundary","line-width":1}},
                {"id":"road","type":"line","source":"basemap","source-layer":"road","paint":{"line-color":"$road","line-width":1}}
              ]
            }
        """.trimIndent()
    }
}
