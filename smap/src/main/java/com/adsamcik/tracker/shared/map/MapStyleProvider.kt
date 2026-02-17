package com.adsamcik.tracker.shared.map

import java.io.File

/**
 * Provides MapLibre style URIs for basemap rendering.
 *
 * Default mode: asset-bundled z0-z6 PMTiles with light/dark style JSON.
 * Custom mode: user-imported PMTiles file referenced via runtime-generated style JSON.
 */
object MapStyleProvider {

    /**
     * Build a style JSON string at runtime referencing a user-imported PMTiles file.
     * Returns null if the file doesn't exist.
     */
    fun customStyleJson(pmtilesPath: String, isDarkTheme: Boolean): String? {
        val file = File(pmtilesPath)
        if (!file.exists()) return null
        return buildStyleJson(pmtilesPath, isDarkTheme)
    }

    /**
     * Returns a runtime-generated style JSON referencing the extracted default basemap file.
     * The basemap must already be extracted to the filesystem (not an asset:// URI)
     * because PMTiles requires random-access I/O.
     */
    fun defaultStyleJson(basemapPath: String, isDarkTheme: Boolean): String =
        buildStyleJson(basemapPath, isDarkTheme)

    private fun buildStyleJson(pmtilesPath: String, isDarkTheme: Boolean): String {
        val bg = if (isDarkTheme) "#1a1a2e" else "#f0f0f0"
        val earth = if (isDarkTheme) "#1e1e2e" else "#e8e0d8"
        val landcover = if (isDarkTheme) "#1a2a1a" else "#d4e8c2"
        val water = if (isDarkTheme) "#0a1628" else "#aad3df"
        val landuse = if (isDarkTheme) "#1a2a1a" else "#e0e8e0"
        val boundary = if (isDarkTheme) "#555555" else "#999999"
        val road = if (isDarkTheme) "#333333" else "#ffffff"
        val name = if (isDarkTheme) "Tracker Dark" else "Tracker Light"

        // Source layer names must match the Protomaps basemap schema:
        // earth, landcover, landuse, water, boundaries, roads, places, pois, buildings
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
                {"id":"earth","type":"fill","source":"basemap","source-layer":"earth","paint":{"fill-color":"$earth"}},
                {"id":"landcover","type":"fill","source":"basemap","source-layer":"landcover","paint":{"fill-color":"$landcover","fill-opacity":0.5}},
                {"id":"water","type":"fill","source":"basemap","source-layer":"water","paint":{"fill-color":"$water"}},
                {"id":"landuse","type":"fill","source":"basemap","source-layer":"landuse","paint":{"fill-color":"$landuse"}},
                {"id":"boundaries","type":"line","source":"basemap","source-layer":"boundaries","paint":{"line-color":"$boundary","line-width":1}},
                {"id":"roads","type":"line","source":"basemap","source-layer":"roads","paint":{"line-color":"$road","line-width":1}}
              ]
            }
        """.trimIndent()
    }
}
