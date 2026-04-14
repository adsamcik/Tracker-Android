package com.adsamcik.tracker.map.shared

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@DisplayName("MapStyleProvider")
class MapStyleProviderTest {

	@Nested
	@DisplayName("customStyleJson")
	inner class CustomStyleJsonTests {

		@Test
		fun `returns null for non-existent file`() {
			val result = MapStyleProvider.customStyleJson("/nonexistent/path.pmtiles", isDarkTheme = false)
			result.shouldBeNull()
		}

		@Test
		fun `returns style JSON for existing file`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("test.pmtiles").toFile()
			file.writeBytes(ByteArray(200)) // dummy file, no valid header
			val result = MapStyleProvider.customStyleJson(file.absolutePath, isDarkTheme = false)
			result.shouldNotBeNull()
			result shouldContain """"version": 8"""
			result shouldContain """"Tracker Light""""
		}

		@Test
		fun `dark theme returns dark style`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("dark.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.customStyleJson(file.absolutePath, isDarkTheme = true)
			result.shouldNotBeNull()
			result shouldContain """"Tracker Dark""""
			result shouldContain "#1a1a2e" // dark background
		}

		@Test
		fun `reads valid PMTiles zoom range`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("valid.pmtiles").toFile()
			val header = ByteArray(127)
			// Write magic: "PMTiles" + version 3
			val magic = "PMTiles".encodeToByteArray()
			magic.copyInto(header)
			header[magic.size] = 0x03
			// Write zoom range
			header[0x64] = 2 // minZoom
			header[0x65] = 14 // maxZoom
			file.writeBytes(header)

			val result = MapStyleProvider.customStyleJson(file.absolutePath, isDarkTheme = false)
			result.shouldNotBeNull()
			result shouldContain """"minzoom": 2"""
			result shouldContain """"maxzoom": 14"""
		}

		@Test
		fun `file too small skips zoom range`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("tiny.pmtiles").toFile()
			file.writeBytes(ByteArray(50)) // too small for header
			val result = MapStyleProvider.customStyleJson(file.absolutePath, isDarkTheme = false)
			result.shouldNotBeNull()
			// Check source-level zoom config (space after colon) to avoid matching layer "minzoom":12
			result shouldNotContain """"minzoom": """
			result shouldNotContain """"maxzoom": """
		}

		@Test
		fun `invalid magic skips zoom range`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("bad.pmtiles").toFile()
			val header = ByteArray(127)
			// Write wrong magic
			"INVALID".encodeToByteArray().copyInto(header)
			file.writeBytes(header)
			val result = MapStyleProvider.customStyleJson(file.absolutePath, isDarkTheme = false)
			result.shouldNotBeNull()
			// Check source-level zoom config (space after colon) to avoid matching layer "minzoom":12
			result shouldNotContain """"minzoom": """
		}
	}

	@Nested
	@DisplayName("defaultStyleJson")
	inner class DefaultStyleJsonTests {

		@Test
		fun `returns style JSON with default zoom range when file has no valid header`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200)) // no valid PMTiles header
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = false)
			result shouldContain """"version": 8"""
			result shouldContain """"minzoom": 0"""
			result shouldContain """"maxzoom": 6"""
		}

		@Test
		fun `returns dark style`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = true)
			result shouldContain """"Tracker Dark""""
		}

		@Test
		fun `light theme colors`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = false)
			result shouldContain "#f0f0f0" // light background
			result shouldContain "#aad3df" // light water
		}

		@Test
		fun `dark theme colors`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = true)
			result shouldContain "#1a1a2e" // dark background
			result shouldContain "#0a1628" // dark water
		}

		@Test
		fun `style contains expected layers`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = false)
			result shouldContain """"id":"background""""
			result shouldContain """"id":"earth""""
			result shouldContain """"id":"landcover""""
			result shouldContain """"id":"water""""
			result shouldContain """"id":"landuse""""
			result shouldContain """"id":"boundaries""""
			result shouldContain """"id":"roads""""
			result shouldContain """"id":"buildings""""
		}

		@Test
		fun `file URI uses forward slashes`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("basemap.pmtiles").toFile()
			file.writeBytes(ByteArray(200))
			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = false)
			result shouldContain "pmtiles://file://"
			result shouldNotContain "\\"
		}

		@Test
		fun `reads zoom from valid PMTiles header`(@TempDir tempDir: Path) {
			val file = tempDir.resolve("valid.pmtiles").toFile()
			val header = ByteArray(127)
			val magic = "PMTiles".encodeToByteArray()
			magic.copyInto(header)
			header[magic.size] = 0x03
			header[0x64] = 0 // minZoom
			header[0x65] = 10 // maxZoom
			file.writeBytes(header)

			val result = MapStyleProvider.defaultStyleJson(file.absolutePath, isDarkTheme = false)
			result shouldContain """"minzoom": 0"""
			result shouldContain """"maxzoom": 10"""
		}
	}
}
