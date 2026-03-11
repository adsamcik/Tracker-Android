package com.adsamcik.tracker.activity.ski

import android.net.Uri
sealed class SkiInfrastructureImportResult {
    data class Success(val path: String) : SkiInfrastructureImportResult()

    data class SourceOpenFailed(val uri: Uri) : SkiInfrastructureImportResult()

    data class CopyFailed(val cause: Throwable) : SkiInfrastructureImportResult()

    data class InvalidDatabase(val message: String) : SkiInfrastructureImportResult()
}
