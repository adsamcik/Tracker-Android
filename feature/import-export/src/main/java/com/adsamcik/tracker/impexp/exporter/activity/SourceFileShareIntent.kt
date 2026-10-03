package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Intent
import android.net.Uri

/** Share the chosen data file without borrowing the summary of a coincident trip. */
internal fun sourceFileShareIntent(file: Uri, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, file)
        type = mimeType
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
