package com.adsamcik.tracker.diagnostics

import dev.tracebox.Tracebox
import dev.tracebox.api.public

/**
 * Tracebox-free compatibility facade for existing tracking source diagnostics.
 *
 * It accepts fixed codes, typed reasons, and code-specific bounded operational values only.
 * Throwable objects and arbitrary text never cross this boundary.
 */
object TrackerDiagnosticLog {
	fun info(code: TrackerDiagnosticInfoCode) {
		when (code) {
			TrackerDiagnosticInfoCode.TRACKING_SESSION_START_REQUESTED ->
				Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_START_REQUESTED)
			TrackerDiagnosticInfoCode.TRACKING_SESSION_STARTED ->
				Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_STARTED)
		}
	}

	fun warn(code: TrackerDiagnosticWarningCode) {
		when (code) {
			TrackerDiagnosticWarningCode.ACTIVITY_RECOGNITION_UNAVAILABLE ->
				Tracebox.log.warn(TrackerTraceboxTemplates.ACTIVITY_RECOGNITION_UNAVAILABLE)
			TrackerDiagnosticWarningCode.AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED ->
				Tracebox.log.warn(
					TrackerTraceboxTemplates.AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
				)
			TrackerDiagnosticWarningCode.STEP_COUNTER_REGRESSED ->
				Tracebox.log.warn(TrackerTraceboxTemplates.STEP_COUNTER_REGRESSED)
			TrackerDiagnosticWarningCode.TRACKING_PROCESSOR_DISABLED ->
				Tracebox.log.warn(TrackerTraceboxTemplates.TRACKING_PROCESSOR_DISABLED)
			TrackerDiagnosticWarningCode.TRACKING_REBASE_ENQUEUE_ACK_MISSING ->
				Tracebox.log.warn(TrackerTraceboxTemplates.TRACKING_REBASE_ENQUEUE_ACK_MISSING)
			TrackerDiagnosticWarningCode.TRACKING_SHUTDOWN_DEGRADED ->
				Tracebox.log.warn(TrackerTraceboxTemplates.TRACKING_SHUTDOWN_DEGRADED)
		}
	}

	fun failure(
		code: TrackerDiagnosticFailureCode,
		reason: TrackingDiagnosticFailureReason,
	) {
		Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_TYPED_FAILURE,
			public(code.name),
			public(reason.name),
		)
	}

	fun rejected(
		code: TrackerDiagnosticRejectionCode,
		reason: TrackingDiagnosticRejectedReason,
	) {
		Tracebox.log.warn(
			TrackerTraceboxTemplates.TRACKING_TYPED_REJECTION,
			public(code.name),
			public(reason.name),
		)
	}

	fun activityCallbackTerminalGap() {
		Tracebox.log.warn(TrackerTraceboxTemplates.ACTIVITY_CALLBACK_TERMINAL_GAP)
	}

	fun rawLocationRepairCompleted(repairedEnvelopeCount: Long) {
		Tracebox.log.info(
			TrackerTraceboxTemplates.RAW_LOCATION_REPAIR_COMPLETED,
			public(TrackingDiagnosticCountBucket.fromCount(repairedEnvelopeCount).name),
		)
	}

	fun trackingPreparedShellStopped() {
		Tracebox.log.error(TrackerTraceboxTemplates.TRACKING_PREPARED_SHELL_STOPPED)
	}

	fun trackingStopRequested() {
		Tracebox.log.debug(TrackerTraceboxTemplates.TRACKING_STOP_REQUESTED)
	}

	fun trackingProviderTeardownFailed(
		reason: TrackingDiagnosticFailureReason,
		attempt: Long,
	) {
		Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_PROVIDER_TEARDOWN_FAILED,
			public(reason.name),
			public(TrackingDiagnosticCountBucket.fromCount(attempt).name),
		)
	}

	@Suppress("LongParameterList")
	fun trackingCoordinatorSessionMetrics(
		projectionDrainCount: Long,
		projectedEventCount: Long,
		projectionDrainNanos: Long,
		planRevisionCount: Long,
		trackingFrameCount: Long,
		trackingFrameWakeLockNanos: Long,
		sourceTimerWakeupCount: Long,
		sourceTimerRequestCount: Long,
		motionPolicyChangeCount: Long,
		stationaryOptimizationCount: Long,
		fullFidelityRestoreCount: Long,
	) {
		Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_COUNTS,
			public(TrackingDiagnosticCountBucket.fromCount(projectionDrainCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(projectedEventCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(planRevisionCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(trackingFrameCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(sourceTimerWakeupCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(sourceTimerRequestCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(motionPolicyChangeCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(stationaryOptimizationCount).name),
			public(TrackingDiagnosticCountBucket.fromCount(fullFidelityRestoreCount).name),
		)
		Tracebox.log.performanceEvent(
			TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_TIMINGS,
			public(
				TrackingDiagnosticDurationBucket.fromNanoseconds(projectionDrainNanos).name,
			),
			public(
				TrackingDiagnosticDurationBucket.fromNanoseconds(trackingFrameWakeLockNanos).name,
			),
		)
	}

	fun <T> processTrackingCycle(block: () -> T): T =
		Tracebox.log.performanceSuspend(TrackerTraceboxTemplates.PROCESS_TRACKING_CYCLE) { block() }
}

enum class TrackerDiagnosticInfoCode {
	TRACKING_SESSION_START_REQUESTED,
	TRACKING_SESSION_STARTED,
}

enum class TrackerDiagnosticWarningCode {
	ACTIVITY_RECOGNITION_UNAVAILABLE,
	AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
	STEP_COUNTER_REGRESSED,
	TRACKING_PROCESSOR_DISABLED,
	TRACKING_REBASE_ENQUEUE_ACK_MISSING,
	TRACKING_SHUTDOWN_DEGRADED,
}

enum class TrackerDiagnosticFailureCode {
	ACTIVITY_CALLBACK_METADATA_UPDATE_FAILED,
	ACTIVITY_CALLBACK_RETRY_SCHEDULING_FAILED,
	ACTIVITY_RECOGNITION_FAILED,
	ACTIVITY_SOURCE_RECOVERY_FAILED,
	AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
	APPLICATION_INITIALIZATION_FAILED,
	DOMAIN_EVENT_PERSISTENCE_FAILED,
	LEGACY_STEP_CONTROL_RETIREMENT_FAILED,
	PERSISTENCE_COMMIT_INCONSISTENT,
	RAW_LOCATION_REPAIR_DECODE_FAILED,
	SOURCE_POLICY_OBSERVATION_FAILED,
	TRACKING_CYCLE_FAILED,
	TRACKING_PERSISTENCE_WRITE_FAILED,
	TRACKING_PIPELINE_STAGE_FAILED,
	TRACKING_PREPARED_START_FOREGROUND_FAILED,
	TRACKING_REBASE_ENQUEUE_FAILED,
	TRACKING_REDELIVERY_RESOLUTION_FAILED,
	TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
	TRACKING_SESSION_STORE_FAILED,
	TRACKING_SIGNAL_CHECKPOINT_FAILED,
	TRACKING_START_FAILED,
}

enum class TrackerDiagnosticRejectionCode {
	TRACKING_SOURCE_SESSION_START_REJECTED,
}
