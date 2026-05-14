package com.adsamcik.tracker.app.settings

/**
 * UI-facing failure modes for basemap import, decoupled from the
 * [com.adsamcik.tracker.map.basemap.BasemapImportResult] data layer so the
 * Compose screen does not depend on map module internals.
 */
sealed class BasemapImportFailure {
    data object SourceOpenFailed : BasemapImportFailure()
    data object CopyFailed : BasemapImportFailure()
    data class InvalidFormat(val reason: String) : BasemapImportFailure()
}
