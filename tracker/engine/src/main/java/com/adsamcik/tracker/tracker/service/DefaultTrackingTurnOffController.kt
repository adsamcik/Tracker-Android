package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.TRACKING_TURN_OFF_EXIT_PREFIX
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.TrackingPurposeUserStopFencer
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.TrackingTurnOffController
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingTurnOffReservation
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/** One durable user command closes every source purpose without altering chosen settings or facts. */
@Singleton
class DefaultTrackingTurnOffController @Inject constructor(
	@ApplicationContext private val context: Context,
	private val commands: TrackingLifecycleCommandAuthority,
	private val database: AppDatabase,
	private val bootClock: BootClockDomainProvider,
	private val ownerFencer: TrackingPurposeUserStopFencer,
) : TrackingTurnOffController {
	override suspend fun reserveAndFence(expectedStartGeneration: Long?): TrackingTurnOffReservation? {
		val wallTimeMs = System.currentTimeMillis()
		val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		return commands.reserveTurnOff(
			exitId = TRACKING_TURN_OFF_EXIT_PREFIX + UUID.randomUUID(),
			requestedAtEpochMs = wallTimeMs,
			requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
			expectedStartGeneration = expectedStartGeneration,
		) { fenceAllPurposes(wallTimeMs, elapsedRealtimeNanos); Unit }
	}

	override suspend fun closeOwnersAfterSessionStop(exitId: String): Boolean? =
		commands.withPendingTurnOff(exitId) {
			// A process may have died after persisting Off but before its first Room fence.
			// Repeating the transaction is safe: already-retiring demands keep their boundary.
			val sourcesFenced = fenceAllPurposes(
				System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
			)
			// Session teardown runs first because shared radio/location
			// owners correctly refuse ambient-only closure while a session plan is still attached.
			val ownersClosed = try {
				ownerFencer.closeForUserStop() is
					TrackingPurposeSettingsReconciliationResult.Complete
			} catch (_: Exception) {
				false
			}
			val automaticClosed = try {
				BackgroundTrackingApi.stopAutomaticControlForUser(context)
			} catch (_: Exception) {
				false
			}
			sourcesFenced && ownersClosed && automaticClosed
		}

	private suspend fun fenceAllPurposes(
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	): Boolean = try {
		val bootId = bootClock.current()
		database.withTransaction {
			SourceKind.entries.forEach { source ->
				database.fenceSourcePurposesInTransaction(
					sourceKind = source.stableCode,
					purposes = ALL_TRACKING_PURPOSES,
					bootId = bootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
				)
			}
		}
		true
	} catch (_: Exception) {
		false
	}
}

private val ALL_TRACKING_PURPOSES = listOf(
	SourceBrokerPurpose.SESSION_CAPTURE,
	SourceBrokerPurpose.AMBIENT_PRODUCT,
	SourceBrokerPurpose.CONTROL_AUTOSTART,
	SourceBrokerPurpose.CONTROL_CONTINUATION,
)
