package com.adsamcik.tracker.app.settings.osm

import dev.tracebox.Tracebox
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.osm.imp.OsmImportController
import com.adsamcik.tracker.osm.imp.OsmImportRequest
import com.adsamcik.tracker.osm.imp.OsmImportState
import com.adsamcik.tracker.osm.imp.OsmImportSummary
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Snapshot of OSM-import state surfaced to [OsmImportSection].
 *
 * @property imports list of OSM regions persisted in `osm_import`, newest first.
 * @property runtimeState live state of the most recent import job.
 */
data class OsmImportUiState(
	val imports: List<OsmImportSummary> = emptyList(),
	val runtimeState: OsmImportState = OsmImportState.Idle,
)

/**
 * ViewModel for the Map data (OpenStreetMap) section of the Data settings
 * screen. Owns the SAF picker plumbing and forwards user actions to
 * [OsmImportController].
 *
 * The "remove" operation deletes the `osm_import` row, which CASCADE-deletes
 * the dependent `osm_way` and `osm_way_cell` rows so a removed region no
 * longer participates in road-limit lookups.
 */
@HiltViewModel
class OsmImportSettingsViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val controller: OsmImportController,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	val uiState: StateFlow<OsmImportUiState> = combine(
		controller.observeImports()
			.catch { error ->
				recordImportFailure(error)
				emit(emptyList())
			},
		controller.observeImportState()
			.catch { error ->
				recordImportFailure(error)
				emit(OsmImportState.Idle)
			},
	) { imports, runtime ->
		OsmImportUiState(imports = imports, runtimeState = runtime)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OsmImportUiState())

	/**
	 * Enqueues an OSM import using the file the user picked via SAF. Pulls
	 * the display name and file size from the `OpenableColumns` projection.
	 */
	fun importFromUri(uri: Uri) {
		viewModelScope.launch {
			val (displayName, size) = withContext(dispatchers.io) {
				queryFileMeta(uri)
			}
			if (size <= 0L) {
				recordInvalidImport()
				return@launch
			}
			controller.enqueue(
				OsmImportRequest(
					contentUri = uri.toString(),
					displayName = displayName,
					fileSizeBytes = size,
				),
			)
		}
	}

	/** Cancels the in-flight import (no-op if none). */
	fun cancelImport() {
		controller.cancel()
	}

	/** Deletes a previously imported region. CASCADE removes the ways. */
	fun removeImport(import: OsmImportSummary) {
		viewModelScope.launch(dispatchers.io) {
			runCatching { controller.removeImport(import.id) }
				.onFailure(::recordImportFailure)
		}
	}

	private fun queryFileMeta(uri: Uri): Pair<String, Long> {
		var name = uri.lastPathSegment ?: "import.osm.pbf"
		var size = -1L
		val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
		appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: name
				size = cursor.long(OpenableColumns.SIZE) ?: size
			}
		}
		return name to size
	}

	private fun Cursor.string(columnName: String): String? {
		val idx = getColumnIndex(columnName)
		return if (idx >= 0 && !isNull(idx)) getString(idx) else null
	}

	private fun Cursor.long(columnName: String): Long? {
		val idx = getColumnIndex(columnName)
		return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
	}

	private fun recordImportFailure(error: Throwable) {
		Tracebox.log.error(error, "OSM import failed")
	}

	private fun recordInvalidImport() {
		Tracebox.log.warn("OSM import completed with rejected input records")
	}
}
