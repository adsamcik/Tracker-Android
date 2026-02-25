package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Writes barometric pressure samples to the database.
 * Reads pressure data produced by [BarometerDataProducer] and persists it as [PressureSample] rows.
 */
internal class PressureSampleWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)
	}

	override suspend fun onDisable(context: Context) {
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		tempData: CollectionTempData
	) {
		val reading = tempData.tryGet<PressureReading>(BarometerDataProducer.PRESSURE_KEY) ?: return
		val now = Time.nowMillis

		val sample = PressureSample(
			timeMs = now,
			elapsedRealtimeNanos = tempData.elapsedRealtimeNanos,
			pressureHpa = reading.pressureHpa,
			altitudeM = reading.altitudeM,
			bucketId = null,
			createdAt = now
		)

		scope?.launch(Dispatchers.IO) {
			database.pressureSampleDao().insert(sample)
		}
	}
}
