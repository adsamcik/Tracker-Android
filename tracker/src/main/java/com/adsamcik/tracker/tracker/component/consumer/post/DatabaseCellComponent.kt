package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseCellComponent : PostTrackerComponent {
	companion object {
		private const val TAG = "DatabaseCellComponent"
	}

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var cellLocationDao: CellLocationDao? = null
	private var cellOperatorDao: CellOperatorDao? = null
	private var cellSampleDao: CellSampleDao? = null // New: sessionless table
	private var scope: CoroutineScope? = null
	private var errorCollector: PersistenceErrorCollector? = null
	
	// Dual-write mode: write to both old and new tables during migration period
	private var enableDualWrite: Boolean = true

	private fun toOwnLocation(location: android.location.Location?): Location? {
		return if (location != null) {
			Location(location)
		} else {
			null
		}
	}

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val cellData = collectionData.cell ?: return
		val location = collectionData.location ?: toOwnLocation(tempData.tryGetLocation())
		
		// Legacy table write (only if location available)
		if (enableDualWrite && location != null) {
			saveLocation(collectionData.time, cellData, location)
		}
		
		// New table write (always, even without location)
		saveCellSamples(collectionData.time, cellData, location)

		saveOperator(cellData)
	}

	private fun saveOperator(cell: CellInfo) {
		val dao = cellOperatorDao ?: return
		scope?.launch(Dispatchers.IO) {
			try {
				dao.insert(cell.networkOperator)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to insert cell operator: ${e.message}", e)
				errorCollector?.reportErrorAsync(
					PersistenceError(
						source = TAG,
						operation = "insert cell operator",
						recordCount = 1,
						cause = e
					)
				)
			}
		}
	}

	private fun saveOperator(cell: CellData) {
		cell.registeredCells.forEach { saveOperator(it) }
	}

	private fun saveLocation(time: Long, cell: CellInfo, location: Location) {
		val dao = cellLocationDao ?: return
		val cellLocation = DatabaseCellLocation(
			time,
			cell.networkOperator.mcc,
			cell.networkOperator.mnc,
			cell.cellId,
			cell.type,
			cell.asu,
			BaseLocation(location)
		)
		scope?.launch(Dispatchers.IO) {
			try {
				dao.insert(cellLocation)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to insert cell location: ${e.message}", e)
				errorCollector?.reportErrorAsync(
					PersistenceError(
						source = TAG,
						operation = "insert cell location",
						recordCount = 1,
						cause = e
					)
				)
			}
		}
	}

	private fun saveLocation(time: Long, cell: CellData, location: Location) {
		cell.registeredCells.forEach { saveLocation(time, it, location) }
	}

	// New: Save cell samples to sessionless table (with or without coordinates)
	private fun saveCellSample(time: Long, cell: CellInfo, location: Location?) {
		val dao = cellSampleDao ?: return
		val now = Time.nowMillis
		
		// Convert to E7 format if location available
		val latE7 = location?.let { (it.latitude * 1e7).toInt() }
		val lonE7 = location?.let { (it.longitude * 1e7).toInt() }
		val provenance = if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN
		
		val sample = CellSample(
			timeMs = time,
			cellId = cell.cellId.toInt(),
			lac = 0, // LAC not currently extracted from CellInfo; would require per-network-type parsing
			mcc = cell.networkOperator.mcc.toIntOrNull() ?: 0,
			mnc = cell.networkOperator.mnc.toIntOrNull() ?: 0,
			networkType = cell.type.ordinal,
			signalStrength = cell.asu,
			latE7 = latE7,
			lonE7 = lonE7,
			provenance = provenance,
			createdAt = now
		)
		
		scope?.launch(Dispatchers.IO) { 
			try { 
				dao.insert(sample) 
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to insert cell sample: ${e.message}", e)
				errorCollector?.reportErrorAsync(
					PersistenceError(
						source = TAG,
						operation = "insert cell sample",
						recordCount = 1,
						cause = e
					)
				)
			}
		}
	}
	
	private fun saveCellSamples(time: Long, cell: CellData, location: Location?) {
		cell.registeredCells.forEach { saveCellSample(time, it, location) }
	}

	/**
	 * Sets the error collector for reporting persistence failures.
	 * Should be called before onEnable.
	 */
	fun setErrorCollector(collector: PersistenceErrorCollector) {
		this.errorCollector = collector
	}

	override suspend fun onDisable(context: Context) {
		scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.children?.toList()?.forEach { it.join() }
		scope?.cancel(); scope = null
		cellLocationDao = null
		cellOperatorDao = null
		cellSampleDao = null
		errorCollector = null
	}

	override suspend fun onEnable(context: Context) {
		val database = AppDatabase.database(context)
		cellLocationDao = database.cellLocationDao()
		cellOperatorDao = database.cellOperatorDao()
		cellSampleDao = database.cellSampleDao()
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	}
}

