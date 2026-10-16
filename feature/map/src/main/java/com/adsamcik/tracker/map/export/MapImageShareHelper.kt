package com.adsamcik.tracker.map.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import kotlinx.coroutines.NonCancellable
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
	private val startupGate: TrackingStartupGate,
) {
	val currentGeneration: Long get() = startupGate.currentGeneration

	/** The request's generation is captured before rendering, so an old image cannot be readmitted. */
	suspend fun renderAndShare(
		context: Context,
		expectedGeneration: Long,
		render: suspend () -> Bitmap,
	): Boolean {
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return false
		}
		return startupGate.withReadyGenerationTransfer(expectedGeneration) {
			saveAndShare(context, render(), expectedGeneration)
		} == true
	}

	/**
	 * Compresses [bitmap] to PNG, writes it to a fresh file, and opens the share sheet for it.
	 * The caller owns [bitmap]'s lifecycle before this call; it is not recycled here.
	 */
	private suspend fun saveAndShare(context: Context, bitmap: Bitmap, generation: Long): Boolean {
		var createdFile: File? = null
		var handedOff = false
		try {
			val uri = withContext(dispatchersProvider.io) {
				val shareableDir = File(context.filesDir, SHARABLE_DIR).apply { mkdirs() }
				val file = File.createTempFile("map_share_", ".png", shareableDir)
				createdFile = file
				file.outputStream().use { output ->
					check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) {
						"Map image could not be encoded"
					}
				}
				FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
			}

			withContext(dispatchersProvider.main) {
				val shareIntent = Intent(Intent.ACTION_SEND).apply {
					type = MIME_TYPE_PNG
					putExtra(Intent.EXTRA_STREAM, uri)
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				}
				handedOff = startupGate.withRetainedDataGeneration(generation) {
					context.startActivity(Intent.createChooser(shareIntent, null))
					true
				} == true
			}
			return handedOff
		} finally {
			if (!handedOff) {
				withContext(NonCancellable + dispatchersProvider.io) {
				createdFile?.let { check(!it.exists() || it.delete()) { "Could not remove unfinished map share" } }
			}
			}
		}
	}

	companion object {
		private const val SHARABLE_DIR = "sharable"
		private const val MIME_TYPE_PNG = "image/png"

		// PNG is lossless; this value is ignored by Bitmap.compress for PNG but required by the API.
		private const val PNG_QUALITY = 100
	}
}
