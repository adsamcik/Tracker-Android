package com.adsamcik.tracker.map.basemap

import android.net.Uri
sealed class BasemapImportResult {
    data class Success(val path: String) : BasemapImportResult()

    data class SourceOpenFailed(val uri: Uri) : BasemapImportResult()

    data class CopyFailed(val cause: Throwable) : BasemapImportResult()

    data class InvalidFormat(val reason: String) : BasemapImportResult()
}
