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
	/** Records a fixed session milestone without identifiers or provider payloads. */
	fun info(code: TrackerDiagnosticInfoCode) {
		when (code) {
			TrackerDiagnosticInfoCode.TRACKING_SESSION_START_REQUESTED ->
				Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_START_REQUESTED)
			TrackerDiagnosticInfoCode.TRACKING_SESSION_STARTED ->
				Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_STARTED)
		}
	}

	/** Records a known degraded tracking condition without arbitrary diagnostic text. */
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

	/** Records the operation code and typed failure reason; no throwable or sensor data is included. */
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

	/** Records a typed start rejection without serializing the rejected request. */
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

	/** Records that Activity callback recovery ended with an unresolved delivery gap. */
	fun activityCallbackTerminalGap() {
		Tracebox.log.warn(TrackerTraceboxTemplates.ACTIVITY_CALLBACK_TERMINAL_GAP)
	}

	/** Records the repaired envelope count as a coarse bucket, never the repaired points. */
	fun rawLocationRepairCompleted(repairedEnvelopeCount: Long) {
		Tracebox.log.info(
			TrackerTraceboxTemplates.RAW_LOCATION_REPAIR_COMPLETED,
			public(TrackingDiagnosticCountBucket.fromCount(repairedEnvelopeCount).name),
		)
	}

	/** Records the stop request before provider teardown begins. */
	fun trackingStopRequested() {
		Tracebox.log.debug(TrackerTraceboxTemplates.TRACKING_STOP_REQUESTED)
	}

	/** Records a typed teardown failure and a bucketed retry count. */
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

}

/** Fixed lifecycle milestones accepted by the payload-free compatibility logger. */
enum class TrackerDiagnosticInfoCode {
	TRACKING_SESSION_START_REQUESTED,
	TRACKING_SESSION_STARTED,
}

/** Known provider or processing conditions that can reduce collection reliability. */
enum class TrackerDiagnosticWarningCode {
	ACTIVITY_RECOGNITION_UNAVAILABLE,
	AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
	STEP_COUNTER_REGRESSED,
	TRACKING_PROCESSOR_DISABLED,
	TRACKING_REBASE_ENQUEUE_ACK_MISSING,
	TRACKING_SHUTDOWN_DEGRADED,
}

/** Fixed operation codes paired with typed failures; tracked values and exception text are excluded. */
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
	TRACKING_PREPARED_SHELL_STOPPED,
	TRACKING_PREPARED_START_FOREGROUND_FAILED,
	TRACKING_REBASE_ENQUEUE_FAILED,
	TRACKING_REDELIVERY_RESOLUTION_FAILED,
	TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
	TRACKING_SESSION_STORE_FAILED,
	TRACKING_SIGNAL_CHECKPOINT_FAILED,
	TRACKING_START_FAILED,
}

/** Fixed start-rejection codes accepted by the compatibility logger. */
enum class TrackerDiagnosticRejectionCode {
	TRACKING_SOURCE_SESSION_START_REJECTED,
}
