package com.adsamcik.tracker.map.online

import java.net.URI

/**
 * A named provider of MapLibre style URLs for online vector tiles.
 *
 * Each provider serves two URLs (light + dark) returned by [styleUrl]. The
 * dark/light selection mirrors the existing offline PMTiles theming.
 *
 * The [allowedHosts] set must list every host the provider's style.json
 * references (style.json + sprite + glyph + tile hosts can all differ).
 * These get added to the gateway's NetworkPolicy when the user enables this
 * provider.
 *
 * # Privacy
 *
 * Online tile providers see the user's IP address (round-trip TCP) and the
 * tile coordinates being fetched — i.e. roughly which part of the map is
 * being viewed. They do NOT see any other data (no installation id, no
 * account, no auth token). Tracker does not append identifying parameters.
 *
 * The provider catalog is **opt-in** and OFF by default. With no online
 * provider selected the app continues to render only the bundled offline
 * PMTiles basemap — zero network traffic flows.
 */
sealed interface TileProvider {
	/** Stable identifier persisted into preferences. */
	val id: String

	/** Human-facing name shown in pickers. */
	val displayName: String

	/** Single-line attribution string rendered next to the map and in settings. */
	val attribution: String

	/**
	 * Hosts the gateway must accept for this provider to function. Includes
	 * the style.json host and every host that style.json's sprite/glyph/tile
	 * URLs resolve to. Subdomain wildcards are NOT supported — list every
	 * concrete host.
	 */
	val allowedHosts: Set<String>

	/**
	 * Returns the MapLibre `style.json` URL for the requested theme. The
	 * returned URL is passed verbatim to MapLibre's `BaseStyle.Uri`.
	 */
	fun styleUrl(isDarkTheme: Boolean): String

	/**
	 * OpenFreeMap — free OpenMapTiles-schema vector tiles, no API key, no
	 * tracking. Preferred default because its terms of service explicitly
	 * cover free anonymous use.
	 */
	data object OpenFreeMap : TileProvider {
		override val id: String = ID
		override val displayName: String = "OpenFreeMap"
		override val attribution: String =
			"\u00A9 OpenStreetMap contributors, OpenMapTiles, OpenFreeMap"
		override val allowedHosts: Set<String> = setOf("tiles.openfreemap.org")
		override fun styleUrl(isDarkTheme: Boolean): String = if (isDarkTheme) {
			"https://tiles.openfreemap.org/styles/dark"
		} else {
			"https://tiles.openfreemap.org/styles/liberty"
		}

		const val ID: String = "openfreemap"
	}

	/**
	 * Protomaps API — matches the offline PMTiles schema the app already uses
	 * for the bundled basemap, so layer behaviour is consistent across modes.
	 */
	data object Protomaps : TileProvider {
		override val id: String = ID
		override val displayName: String = "Protomaps"
		override val attribution: String =
			"\u00A9 OpenStreetMap contributors, Protomaps"
		override val allowedHosts: Set<String> = setOf("api.protomaps.com")
		override fun styleUrl(isDarkTheme: Boolean): String = if (isDarkTheme) {
			"https://api.protomaps.com/styles/v5/dark.json"
		} else {
			"https://api.protomaps.com/styles/v5/light.json"
		}

		const val ID: String = "protomaps"
	}

	/**
	 * User-supplied style URL. A single URL is used for both light and dark
	 * themes — the provider URL must support theme switching client-side if
	 * desired (e.g. a `?theme=` query parameter the user includes themselves).
	 *
	 * [allowedHosts] is inferred from [url]; malformed URLs collapse to an
	 * empty set, which causes the gateway to reject every request from this
	 * provider (intentional fail-closed behaviour).
	 */
	data class Custom(val url: String) : TileProvider {
		override val id: String = ID
		override val displayName: String = "Custom URL"
		override val attribution: String = "Custom provider"
		override val allowedHosts: Set<String> = inferHost(url)
		override fun styleUrl(isDarkTheme: Boolean): String = url

		companion object {
			const val ID: String = "custom"

			private fun inferHost(raw: String): Set<String> {
				if (raw.isBlank()) return emptySet()
				return runCatching {
					val host = URI.create(raw.trim()).host
					if (host.isNullOrBlank()) emptySet() else setOf(host.lowercase())
				}.getOrDefault(emptySet())
			}
		}
	}

	companion object {
		/** Provider list shown in the settings picker (does NOT include [Custom]). */
		val builtIn: List<TileProvider> = listOf(OpenFreeMap, Protomaps)

		/** Provider id used when none has been explicitly chosen. */
		const val DEFAULT_ID: String = OpenFreeMap.ID

		/**
		 * Resolve a stored preference (id + optional custom URL) into a
		 * concrete [TileProvider]. Unknown ids fall back to the default
		 * built-in provider.
		 */
		fun resolve(id: String?, customUrl: String = ""): TileProvider = when (id) {
			Protomaps.ID -> Protomaps
			Custom.ID -> Custom(customUrl)
			OpenFreeMap.ID, null, "" -> OpenFreeMap
			else -> OpenFreeMap
		}
	}
}
