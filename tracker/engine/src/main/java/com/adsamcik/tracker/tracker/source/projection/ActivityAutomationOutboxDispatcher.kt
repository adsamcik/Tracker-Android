package com.adsamcik.tracker.tracker.source.projection

import android.content.Context
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityAutomationOutboxDispatcher @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
) {
	suspend fun drain(limit: Int = 100): Int {
		var delivered = 0
		database.sourceProjectionStateDao()
			.pendingOutbox(ActivityAutomationProjection.OUTBOX_KIND, limit)
			.forEach { effect ->
			val decoded = decode(effect.payload)
			BackgroundTrackingApi.handleDurableActivityEvidence(
				context = context,
				activity = decoded.activityType,
				confidence = decoded.confidence,
				transitionType = decoded.transitionType,
			)
			if (database.sourceProjectionStateDao().markOutboxDelivered(
					effect.stableId,
					System.currentTimeMillis(),
				) == 1
			) delivered++
			}
		return delivered
	}

	private fun decode(payload: ByteArray): DecodedActivityEffect =
		DataInputStream(ByteArrayInputStream(payload)).use { input ->
			val kind = input.readInt()
			val activity = activityFromStableCode(input.readInt())
			val confidence = input.readInt()
			val transition = input.readInt()
			require(input.available() == 0)
			DecodedActivityEffect(
				activity,
				confidence,
				if (kind == ActivityAutomationProjection.KIND_TRANSITION) {
					ActivityTransitionType.entries.singleOrNull { it.value == transition }
				} else {
					null
				},
			)
		}

	private fun activityFromStableCode(code: Int): DetectedActivityType = when (code) {
		0 -> DetectedActivityType.STILL
		1 -> DetectedActivityType.WALKING
		2 -> DetectedActivityType.RUNNING
		3 -> DetectedActivityType.ON_BICYCLE
		4 -> DetectedActivityType.IN_VEHICLE
		5 -> DetectedActivityType.ON_FOOT
		6 -> DetectedActivityType.TILTING
		else -> DetectedActivityType.UNKNOWN
	}

	private data class DecodedActivityEffect(
		val activityType: DetectedActivityType,
		val confidence: Int,
		val transitionType: ActivityTransitionType?,
	)
}
