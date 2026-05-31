package com.adsamcik.tracker.map.shared

import com.adsamcik.tracker.map.online.TileProvider
import java.io.File

/**
 * Provides MapLibre style URIs for basemap rendering.
 *
 * Default mode: asset-bundled z0-z6 PMTiles with light/dark style JSON.
 * Custom mode: user-imported PMTiles file referenced via runtime-generated style JSON.
 * Online mode: remote `style.json` URL served by a [TileProvider]. Online mode is
 *   strictly opt-in and OFF by default.
 *
 * # NetworkGateway integration
 *
 * Online tile fetches go through the project [com.adsamcik.tracker.network.NetworkGateway]:
 * [com.adsamcik.tracker.app.Application.onCreate] registers the gateway's
 * `okHttpCallFactory()` via
 * [com.adsamcik.tracker.map.MapLibreInitializer.setHttpCallFactory], which
 * delegates to MapLibre's `HttpRequestUtil.setOkHttpClient` (a process-global
 * static). Every MapLibre HTTP request — style.json, sprite, glyph, vector
 * tile — flows through the gateway's interceptor chain (kill switch,
 * allowlist, per-host rate limit). When the user toggles online mode off the
 * gateway's kill switch flips immediately and tile requests fail closed.
 */
object MapStyleProvider {

    private const val PMTILES_VERSION = 0x03
    private const val PMTILES_MIN_ZOOM_OFFSET = 0x64
    private const val PMTILES_MAX_ZOOM_OFFSET = 0x65
    private const val PMTILES_HEADER_SIZE = 127
    private val PMTILES_MAGIC = "PMTiles".encodeToByteArray()
    private val DEFAULT_BUNDLE_ZOOM_RANGE = ZoomRange(min = 0, max = 6)

    /**
     * Build a style JSON string at runtime referencing a user-imported PMTiles file.
     * Returns null if the file doesn't exist.
     */
    fun customStyleJson(pmtilesPath: String, isDarkTheme: Boolean): String? {
        val file = File(pmtilesPath)
        if (!file.exists()) return null
        return buildStyleJson(
            pmtilesPath = pmtilesPath,
            isDarkTheme = isDarkTheme,
            zoomRange = readPmtilesZoomRange(file),
        )
    }

    /**
     * Returns a runtime-generated style JSON referencing the extracted default basemap file.
     * The basemap must already be extracted to the filesystem (not an asset:// URI)
     * because PMTiles requires random-access I/O.
     */
    fun defaultStyleJson(basemapPath: String, isDarkTheme: Boolean): String =
        buildStyleJson(
            pmtilesPath = basemapPath,
            isDarkTheme = isDarkTheme,
            zoomRange = readPmtilesZoomRange(File(basemapPath)) ?: DEFAULT_BUNDLE_ZOOM_RANGE,
        )

    /**
     * Returns the remote `style.json` URL for [provider] suitable for passing to
     * MapLibre's [BaseStyle.Uri][org.maplibre.compose.style.BaseStyle.Uri].
     *
     * Returns `null` when the resolved URL is blank — that only happens for the
     * [TileProvider.Custom] variant when the user hasn't entered a URL yet, in
     * which case the caller should fall back to the offline basemap.
     */
    fun onlineStyleUri(provider: TileProvider, isDarkTheme: Boolean): String? =
        provider.styleUrl(isDarkTheme).takeIf { it.isNotBlank() }

    private fun buildStyleJson(
        pmtilesPath: String,
        isDarkTheme: Boolean,
        zoomRange: ZoomRange?,
    ): String {
        val normalizedPath = File(pmtilesPath).absolutePath.replace('\\', '/')
        val fileUri = if (normalizedPath.startsWith("/")) {
            "file://$normalizedPath"
        } else {
            "file:///$normalizedPath"
        }
        val zoomConfig = buildString {
            zoomRange?.let {
                append(",\n                  \"minzoom\": ${it.min}")
                append(",\n                  \"maxzoom\": ${it.max}")
            }
        }
        val bg = if (isDarkTheme) "#1a1a2e" else "#f0f0f0"
        val earth = if (isDarkTheme) "#1e1e2e" else "#e8e0d8"
        val landcover = if (isDarkTheme) "#1a2a1a" else "#d4e8c2"
        val water = if (isDarkTheme) "#0a1628" else "#aad3df"
        val landuse = if (isDarkTheme) "#1a2a1a" else "#e0e8e0"
        val boundary = if (isDarkTheme) "#555555" else "#999999"
        val road = if (isDarkTheme) "#333333" else "#ffffff"
        val building = if (isDarkTheme) "#2a2438" else "#d7c7b8"
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
                  "url": "pmtiles://$fileUri"$zoomConfig
                }
              },
              "layers": [
                {"id":"background","type":"background","paint":{"background-color":"$bg"}},
                {"id":"earth","type":"fill","source":"basemap","source-layer":"earth","paint":{"fill-color":"$earth"}},
                {"id":"landcover","type":"fill","source":"basemap","source-layer":"landcover","paint":{"fill-color":"$landcover","fill-opacity":0.5}},
                {"id":"water","type":"fill","source":"basemap","source-layer":"water","paint":{"fill-color":"$water"}},
                {"id":"landuse","type":"fill","source":"basemap","source-layer":"landuse","paint":{"fill-color":"$landuse"}},
                {"id":"boundaries","type":"line","source":"basemap","source-layer":"boundaries","paint":{"line-color":"$boundary","line-width":1}},
                {"id":"roads","type":"line","source":"basemap","source-layer":"roads","paint":{"line-color":"$road","line-width":["interpolate",["linear"],["zoom"],4,0.5,8,1,12,1.5,16,3]}},
                {"id":"buildings","type":"fill","source":"basemap","source-layer":"buildings","minzoom":12,"paint":{"fill-color":"$building","fill-opacity":0.6}}
              ]
            }
        """.trimIndent()
    }

    private fun readPmtilesZoomRange(file: File): ZoomRange? {
        if (!file.exists() || file.length() < PMTILES_HEADER_SIZE) {
            return null
        }

        return try {
            val header = ByteArray(PMTILES_HEADER_SIZE)
            file.inputStream().use { input ->
                if (input.read(header) != PMTILES_HEADER_SIZE) {
                    return null
                }
            }

            val hasValidMagic =
                header.copyOfRange(0, PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) &&
                    header[PMTILES_MAGIC.size].toInt() == PMTILES_VERSION
            if (!hasValidMagic) {
                return null
            }

            ZoomRange(
                min = header[PMTILES_MIN_ZOOM_OFFSET].toInt() and 0xFF,
                max = header[PMTILES_MAX_ZOOM_OFFSET].toInt() and 0xFF,
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class ZoomRange(
        val min: Int,
        val max: Int,
    )
}
