@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.adsamcik.tracker.diagnostics

import dev.tracebox.Tracebox
import dev.tracebox.api.public
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Final module-owned adapter for the fixed tracking recorder entry point.
 *
 * Tracebox types and failure handling remain private to this file. The adapter accepts only the
 * closed recorded event hierarchy and returns an explicit failure instead of throwing into
 * tracking. Coroutine cancellation always propagates.
 */
internal class TraceboxTrackingDiagnosticAdapter internal constructor(
	private val writer: (EncodedTrackingDiagnosticEvent) -> Unit,
	private val failureReporter: suspend () -> Unit,
) : TrackingDiagnosticEventStore {
	private val reportingFailure = AtomicBoolean(false)

	override suspend fun append(
		event: EncodedTrackingDiagnosticEvent,
	): TrackingDiagnosticStorageResult {
		if (TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
				event.serializedFields.map { (field, _) -> field.wireName },
			) !is TrackingDiagnosticPrivacyValidation.Allowed
		) {
			return TrackingDiagnosticStorageResult.PERMANENT_REJECTED
		}
		val writeSucceeded = try {
			writer(event)
			true
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Throwable) {
			false
		}
		if (writeSucceeded) return TrackingDiagnosticStorageResult.STORED

		reportFailureSafely()
		return TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
	}

	private suspend fun reportFailureSafely() {
		if (!reportingFailure.compareAndSet(false, true)) return
		try {
			try {
				failureReporter()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Throwable) {
				Unit
			}
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
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
			public(event.value(TrackingDiagnosticField.COARSE_TIME_BUCKET)),
			public(event.value(TrackingDiagnosticField.SCOPE_DURATION_BUCKET)),
			public(event.value(TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET)),
		)
	}
}
