package com.adsamcik.tracker.diagnostics

import dev.tracebox.Tracebox
import dev.tracebox.api.public
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Final module-owned adapter for the fixed tracking recorder entry point.
 *
 * Tracebox types and failure handling remain private to this file. The adapter accepts only the
 * closed recorded event hierarchy and returns an explicit failure instead of throwing into
 * tracking.
 */
internal class TraceboxTrackingDiagnosticAdapter internal constructor(
	private val writer: (EncodedTrackingDiagnosticEvent) -> Unit,
	private val failureReporter: () -> Unit,
) : TrackingDiagnosticEventStore {
	private val reportingFailure = AtomicBoolean(false)

	override fun append(
		event: RecordedTrackingDiagnosticEvent,
	): TrackingDiagnosticStoreResult {
		val encoded = EncodedTrackingDiagnosticEvent.from(event)
		if (TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
				encoded.serializedFields.map { (field, _) -> field.wireName },
			) !is TrackingDiagnosticPrivacyValidation.Allowed
		) {
			return TrackingDiagnosticStoreResult.FAILED
		}
		val writeSucceeded = runCatching { writer(encoded) }.isSuccess
		if (writeSucceeded) return TrackingDiagnosticStoreResult.RECORDED_LOCALLY

		reportFailureSafely()
		return TrackingDiagnosticStoreResult.FAILED
	}

	private fun reportFailureSafely() {
		if (!reportingFailure.compareAndSet(false, true)) return
		try {
			runCatching(failureReporter)
		} finally {
			reportingFailure.set(false)
		}
	}

	companion object {
		val PRODUCTION = TraceboxTrackingDiagnosticAdapter(
			writer = ::writeToTracebox,
			failureReporter = {
				Tracebox.log.warn(
					TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_ADAPTER_FAILED,
				)
			},
		)
	}
}

private fun writeToTracebox(event: EncodedTrackingDiagnosticEvent) {
	when (event.severity) {
		TrackingDiagnosticSeverity.INFO -> writeInfo(event)
		TrackingDiagnosticSeverity.WARNING -> writeWarning(event)
		TrackingDiagnosticSeverity.ERROR -> writeError(event)
	}
}

@Suppress("LongMethod")
private fun writeInfo(event: EncodedTrackingDiagnosticEvent) {
	when (event.schema) {
		TrackingDiagnosticSerializedSchema.UNMETERED -> Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_UNMETERED_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.ENQUEUE -> Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_ENQUEUE_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET)),
			public(event.value(TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.DRAIN -> Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_DRAIN_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET)),
			public(event.value(TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.WRITE_BATCH -> Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_WRITE_BATCH_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET)),
		)
	}
}

@Suppress("LongMethod")
private fun writeWarning(event: EncodedTrackingDiagnosticEvent) {
	when (event.schema) {
		TrackingDiagnosticSerializedSchema.UNMETERED -> Tracebox.log.warn(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_UNMETERED_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.ENQUEUE -> Tracebox.log.warn(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_ENQUEUE_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET)),
			public(event.value(TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.DRAIN -> Tracebox.log.warn(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_DRAIN_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET)),
			public(event.value(TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.WRITE_BATCH -> Tracebox.log.warn(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_WRITE_BATCH_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET)),
		)
	}
}

@Suppress("LongMethod")
private fun writeError(event: EncodedTrackingDiagnosticEvent) {
	when (event.schema) {
		TrackingDiagnosticSerializedSchema.UNMETERED -> Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_UNMETERED_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.ENQUEUE -> Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_ENQUEUE_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET)),
			public(event.value(TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.DRAIN -> Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_DRAIN_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET)),
			public(event.value(TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET)),
		)
		TrackingDiagnosticSerializedSchema.WRITE_BATCH -> Tracebox.log.error(
			TrackerTraceboxTemplates.TRACKING_DIAGNOSTIC_WRITE_BATCH_EVENT,
			public(event.value(TrackingDiagnosticField.SOURCE)),
			public(event.value(TrackingDiagnosticField.PURPOSE)),
			public(event.value(TrackingDiagnosticField.PIPELINE_STAGE)),
			public(event.value(TrackingDiagnosticField.OPERATION)),
			public(event.value(TrackingDiagnosticField.RESULT)),
			public(event.value(TrackingDiagnosticField.REASON)),
			public(event.value(TrackingDiagnosticField.LIFECYCLE)),
			public(event.value(TrackingDiagnosticField.OPERATION_SCOPE)),
			public(event.value(TrackingDiagnosticField.SCOPE_SEQUENCE)),
			public(event.value(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET)),
		)
	}
}
