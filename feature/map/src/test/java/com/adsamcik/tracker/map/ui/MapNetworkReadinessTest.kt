package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.map.online.TileProvider
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.maplibre.compose.style.BaseStyle
import java.nio.file.Path

class MapNetworkReadinessTest {

    @Test
    fun `online preference falls back to offline basemap until gateway factory is installed`(
        @TempDir tempDir: Path,
    ) {
        val basemap = tempDir.resolve("offline.pmtiles").toFile().apply {
            writeBytes(ByteArray(200))
        }

        val style = resolveBaseStyle(
            customPath = "",
            isDark = false,
            defaultBasemapPath = basemap.absolutePath,
            onlineTilesEnabled = true,
            onlineNetworkReady = false,
            onlineProviderId = TileProvider.OpenFreeMap.ID,
            onlineCustomUrl = "",
        ).shouldBeInstanceOf<BaseStyle.Json>()

        style.json shouldContain "pmtiles://file://"
    }

    @Test
    fun `online style is selected only after gateway factory installation`(@TempDir tempDir: Path) {
        val basemap = tempDir.resolve("offline.pmtiles").toFile().apply {
            writeBytes(ByteArray(200))
        }

        val style = resolveBaseStyle(
            customPath = "",
            isDark = false,
            defaultBasemapPath = basemap.absolutePath,
            onlineTilesEnabled = true,
            onlineNetworkReady = true,
            onlineProviderId = TileProvider.OpenFreeMap.ID,
            onlineCustomUrl = "",
        ).shouldBeInstanceOf<BaseStyle.Uri>()

        style.uri shouldContain "tiles.openfreemap.org"
    }

    @Test
    fun `opt-out keeps offline style even when the gateway factory is installed`(@TempDir tempDir: Path) {
        val basemap = tempDir.resolve("offline.pmtiles").toFile().apply {
            writeBytes(ByteArray(200))
        }

        val style = resolveBaseStyle(
            customPath = "",
            isDark = false,
            defaultBasemapPath = basemap.absolutePath,
            onlineTilesEnabled = false,
            onlineNetworkReady = true,
            onlineProviderId = TileProvider.OpenFreeMap.ID,
            onlineCustomUrl = "",
        ).shouldBeInstanceOf<BaseStyle.Json>()

        style.json shouldContain "pmtiles://file://"
    }

    @Test
    fun `online preference exposes no remote style when factory is absent and no offline map exists`() {
        resolveBaseStyle(
            customPath = "",
            isDark = false,
            defaultBasemapPath = null,
            onlineTilesEnabled = true,
            onlineNetworkReady = false,
            onlineProviderId = TileProvider.OpenFreeMap.ID,
            onlineCustomUrl = "",
        ).shouldBeNull()
    }
}
