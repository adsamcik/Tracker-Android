package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand

/** Opaque identity of one Room-persisted Android-service start request. */
data class PreparedTrackingStartToken(
	val value: String,
) {
	init {
		require(value.isNotBlank()) { "Prepared tracking-start token must not be blank" }
	}
}

/** Product intent supplied to the Room start authority before Android service delivery. */
data class TrackingStartRequest(
	val command: TrackingStartCommand,
	val isUserInitiated: Boolean,
	val isAmbient: Boolean,
	val automaticTrigger: AutomaticTrackingStartTrigger? = null,
	val recoveryDescriptor: ActiveTrackingSessionDescriptor? = null,
) {
	init {
		require(!isUserInitiated || automaticTrigger == null) {
			"A user-initiated start cannot carry automatic trigger evidence"
		}
		require(recoveryDescriptor == null || automaticTrigger == null) {
			"A recovery start cannot carry a fresh automatic trigger"
		}
	}
}

sealed interface TrackingStartPreparationResult {
	data class Prepared(
		val token: PreparedTrackingStartToken,
		val startupGeneration: Long = 0L,
		/**
		 * Non-authoritative foreground-deadline hint copied into the private Android Intent.
		 * TrackerService may use it only to choose an immediate foreground type; Room claim remains
		 * the authority for sources, lifecycle, and provider admission.
		 */
		val preparedSourceMaskHint: Long = 0L,
		val preparedStartIsUserInitiatedHint: Boolean = false,
	) : TrackingStartPreparationResult

	/** An equal or newer durable lifecycle already owns the requested work. */
	data object AlreadyActive : TrackingStartPreparationResult

	data class Rejected(val failureCode: String) : TrackingStartPreparationResult
}

/**
 * Narrow bridge from public start origins to the authoritative Room lifecycle.
 *
 * Implementations must commit the complete STARTING session/run/manifest/intent before returning
 * [TrackingStartPreparationResult.Prepared]. Android delivery is deliberately performed by
 * [TrackerServiceApi] only after that boundary.
 */
interface TrackingStartRequestCoordinator {
	suspend fun prepare(request: TrackingStartRequest): TrackingStartPreparationResult

	/** Linearizes the platform enqueue with the startup/deletion generation captured at prepare. */
	fun <T> withStartupEnqueuePermit(
		startupGeneration: Long,
		operation: () -> T,
	): T?

	/** Records a successful platform enqueue without regressing a faster service-delivery claim. */
	suspend fun markAndroidStartEnqueued(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
	): Boolean

	/** Finalizes only the exact prepared start; a newer lifecycle must remain untouched. */
	suspend fun compensate(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		failureCode: String,
	)
}
