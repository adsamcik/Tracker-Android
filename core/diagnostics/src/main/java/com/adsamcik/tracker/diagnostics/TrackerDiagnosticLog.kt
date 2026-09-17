package com.adsamcik.tracker.diagnostics

import dev.tracebox.Tracebox
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.public

/**
 * Tracebox-free compatibility facade for existing tracking source diagnostics.
 *
 * It accepts fixed codes and code-specific bounded operational values only. Tracking source modules
 * must not import Tracebox or its privacy/value types.
 */
object TrackerDiagnosticLog {
	fun debug(code: TrackerDiagnosticCode) {
		Tracebox.log.debug(code.template())
	}

	fun info(code: TrackerDiagnosticCode) {
		Tracebox.log.info(code.template())
	}

	fun warn(code: TrackerDiagnosticCode) {
		Tracebox.log.warn(code.template())
	}

	fun error(code: TrackerDiagnosticCode) {
		Tracebox.log.error(code.template())
	}

	fun error(error: Throwable, code: TrackerDiagnosticCode) {
		Tracebox.log.error(error, code.template())
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

	fun trackingProviderTeardownFailed(error: Throwable, attempt: Long) {
		Tracebox.log.error(
			error,
			TrackerTraceboxTemplates.TRACKING_PROVIDER_TEARDOWN_FAILED,
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

enum class TrackerDiagnosticCode {
	ACTIVITY_CALLBACK_METADATA_UPDATE_FAILED,
	ACTIVITY_CALLBACK_RETRY_SCHEDULING_FAILED,
	ACTIVITY_RECOGNITION_FAILED,
	ACTIVITY_RECOGNITION_UNAVAILABLE,
	ACTIVITY_SOURCE_RECOVERY_FAILED,
	AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
	APPLICATION_INITIALIZATION_FAILED,
	DOMAIN_EVENT_PERSISTENCE_FAILED,
	LEGACY_STEP_CONTROL_RETIREMENT_FAILED,
	PERSISTENCE_COMMIT_INCONSISTENT,
	RAW_LOCATION_REPAIR_DECODE_FAILED,
	SOURCE_POLICY_OBSERVATION_FAILED,
	STEP_COUNTER_REGRESSED,
	TRACKING_CYCLE_FAILED,
	TRACKING_PERSISTENCE_WRITE_FAILED,
	TRACKING_PIPELINE_STAGE_FAILED,
	TRACKING_PREPARED_START_FOREGROUND_FAILED,
	TRACKING_PROCESSOR_DISABLED,
	TRACKING_REBASE_ENQUEUE_ACK_MISSING,
	TRACKING_REBASE_ENQUEUE_FAILED,
	TRACKING_REDELIVERY_RESOLUTION_FAILED,
	TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
	TRACKING_SESSION_START_REQUESTED,
	TRACKING_SESSION_STARTED,
	TRACKING_SESSION_STORE_FAILED,
	TRACKING_SHUTDOWN_DEGRADED,
	TRACKING_SIGNAL_CHECKPOINT_FAILED,
	TRACKING_SOURCE_SESSION_START_REJECTED,
	TRACKING_START_FAILED,
}

private fun TrackerDiagnosticCode.template(): LogTemplate = when (this) {
	TrackerDiagnosticCode.ACTIVITY_CALLBACK_METADATA_UPDATE_FAILED ->
		TrackerTraceboxTemplates.ACTIVITY_CALLBACK_METADATA_UPDATE_FAILED
	TrackerDiagnosticCode.ACTIVITY_CALLBACK_RETRY_SCHEDULING_FAILED ->
		TrackerTraceboxTemplates.ACTIVITY_CALLBACK_RETRY_SCHEDULING_FAILED
	TrackerDiagnosticCode.ACTIVITY_RECOGNITION_FAILED ->
		TrackerTraceboxTemplates.ACTIVITY_RECOGNITION_FAILED
	TrackerDiagnosticCode.ACTIVITY_RECOGNITION_UNAVAILABLE ->
		TrackerTraceboxTemplates.ACTIVITY_RECOGNITION_UNAVAILABLE
	TrackerDiagnosticCode.ACTIVITY_SOURCE_RECOVERY_FAILED ->
		TrackerTraceboxTemplates.ACTIVITY_SOURCE_RECOVERY_FAILED
	TrackerDiagnosticCode.AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED ->
		TrackerTraceboxTemplates.AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED
	TrackerDiagnosticCode.APPLICATION_INITIALIZATION_FAILED ->
		TrackerTraceboxTemplates.APPLICATION_INITIALIZATION_FAILED
	TrackerDiagnosticCode.DOMAIN_EVENT_PERSISTENCE_FAILED ->
		TrackerTraceboxTemplates.DOMAIN_EVENT_PERSISTENCE_FAILED
	TrackerDiagnosticCode.LEGACY_STEP_CONTROL_RETIREMENT_FAILED ->
		TrackerTraceboxTemplates.LEGACY_STEP_CONTROL_RETIREMENT_FAILED
	TrackerDiagnosticCode.PERSISTENCE_COMMIT_INCONSISTENT ->
		TrackerTraceboxTemplates.PERSISTENCE_COMMIT_INCONSISTENT
	TrackerDiagnosticCode.RAW_LOCATION_REPAIR_DECODE_FAILED ->
		TrackerTraceboxTemplates.RAW_LOCATION_REPAIR_DECODE_FAILED
	TrackerDiagnosticCode.SOURCE_POLICY_OBSERVATION_FAILED ->
		TrackerTraceboxTemplates.SOURCE_POLICY_OBSERVATION_FAILED
	TrackerDiagnosticCode.STEP_COUNTER_REGRESSED ->
		TrackerTraceboxTemplates.STEP_COUNTER_REGRESSED
	TrackerDiagnosticCode.TRACKING_CYCLE_FAILED ->
		TrackerTraceboxTemplates.TRACKING_CYCLE_FAILED
	TrackerDiagnosticCode.TRACKING_PERSISTENCE_WRITE_FAILED ->
		TrackerTraceboxTemplates.TRACKING_PERSISTENCE_WRITE_FAILED
	TrackerDiagnosticCode.TRACKING_PIPELINE_STAGE_FAILED ->
		TrackerTraceboxTemplates.TRACKING_PIPELINE_STAGE_FAILED
	TrackerDiagnosticCode.TRACKING_PREPARED_START_FOREGROUND_FAILED ->
		TrackerTraceboxTemplates.TRACKING_PREPARED_START_FOREGROUND_FAILED
	TrackerDiagnosticCode.TRACKING_PROCESSOR_DISABLED ->
		TrackerTraceboxTemplates.TRACKING_PROCESSOR_DISABLED
	TrackerDiagnosticCode.TRACKING_REBASE_ENQUEUE_ACK_MISSING ->
		TrackerTraceboxTemplates.TRACKING_REBASE_ENQUEUE_ACK_MISSING
	TrackerDiagnosticCode.TRACKING_REBASE_ENQUEUE_FAILED ->
		TrackerTraceboxTemplates.TRACKING_REBASE_ENQUEUE_FAILED
	TrackerDiagnosticCode.TRACKING_REDELIVERY_RESOLUTION_FAILED ->
		TrackerTraceboxTemplates.TRACKING_REDELIVERY_RESOLUTION_FAILED
	TrackerDiagnosticCode.TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED ->
		TrackerTraceboxTemplates.TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED
	TrackerDiagnosticCode.TRACKING_SESSION_START_REQUESTED ->
		TrackerTraceboxTemplates.TRACKING_SESSION_START_REQUESTED
	TrackerDiagnosticCode.TRACKING_SESSION_STARTED ->
		TrackerTraceboxTemplates.TRACKING_SESSION_STARTED
	TrackerDiagnosticCode.TRACKING_SESSION_STORE_FAILED ->
		TrackerTraceboxTemplates.TRACKING_SESSION_STORE_FAILED
	TrackerDiagnosticCode.TRACKING_SHUTDOWN_DEGRADED ->
		TrackerTraceboxTemplates.TRACKING_SHUTDOWN_DEGRADED
	TrackerDiagnosticCode.TRACKING_SIGNAL_CHECKPOINT_FAILED ->
		TrackerTraceboxTemplates.TRACKING_SIGNAL_CHECKPOINT_FAILED
	TrackerDiagnosticCode.TRACKING_SOURCE_SESSION_START_REJECTED ->
		TrackerTraceboxTemplates.TRACKING_SOURCE_SESSION_START_REJECTED
	TrackerDiagnosticCode.TRACKING_START_FAILED ->
		TrackerTraceboxTemplates.TRACKING_START_FAILED
}
