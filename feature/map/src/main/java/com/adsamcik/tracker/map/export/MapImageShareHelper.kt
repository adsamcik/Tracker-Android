package com.adsamcik.tracker.map.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Saves a rendered map [Bitmap] as a PNG under the app's shareable-files directory and launches
 * the system share sheet for it, via [FileProvider] — mirrors [com.adsamcik.tracker.statistics.export.GpxShareHelper]'s
 * pattern so both features share the same file-provider path and share-intent shape.
 */
class MapImageShareHelper @Inject constructor(
	private val dispatchersProvider: DispatchersProvider,
) {
	/**
	 * Compresses [bitmap] to PNG, writes it to a fresh file, and opens the share sheet for it.
	 * The caller owns [bitmap]'s lifecycle before this call; it is not recycled here.
	 */
	suspend fun saveAndShare(context: Context, bitmap: Bitmap) {
		val uri = withContext(dispatchersProvider.io) {
			val shareableDir = File(context.filesDir, SHARABLE_DIR).apply { mkdirs() }
			val file = File(shareableDir, "map_share_${System.currentTimeMillis()}.png")
			file.outputStream().use { output ->
				bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
			}
			FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
		}

		withContext(dispatchersProvider.main) {
			val shareIntent = Intent(Intent.ACTION_SEND).apply {
				type = MIME_TYPE_PNG
				putExtra(Intent.EXTRA_STREAM, uri)
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			context.startActivity(Intent.createChooser(shareIntent, null))
		}
	}

	companion object {
		private const val SHARABLE_DIR = "sharable"
		private const val MIME_TYPE_PNG = "image/png"

		// PNG is lossless; this value is ignored by Bitmap.compress for PNG but required by the API.
		private const val PNG_QUALITY = 100
	}
}
