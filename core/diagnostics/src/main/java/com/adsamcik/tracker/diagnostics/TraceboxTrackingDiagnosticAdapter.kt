package com.adsamcik.tracker.diagnostics

import dev.tracebox.Tracebox
import dev.tracebox.api.public

/** Final local Tracebox adapter; it has no public construction or extension surface. */
internal object TraceboxTrackingDiagnosticAdapter {
	fun record(event: RecordedTrackingDiagnosticEvent) {
		val metricValues = event.metricValues()
		when (event.result) {
			TrackingDiagnosticResult.RETRYABLE_FAILURE,
			TrackingDiagnosticResult.PERMANENT_FAILURE,
			-> Tracebox.log.error(
				TrackerTraceboxTemplates.TRACKING_OPERATION_EVENT,
				public(event.source.name),
				public(event.purpose.name),
				public(event.pipelineStage.name),
				public(event.operation.name),
				public(event.result.name),
				public(event.reason.stableName),
				public(event.lifecycle.name),
				public(event.scopeEventCountBucket.name),
				public(event.scopeDurationBucket.name),
				public(metricValues.firstName),
				public(metricValues.firstBucket),
				public(metricValues.secondName),
				public(metricValues.secondBucket),
			)
			TrackingDiagnosticResult.BLOCKED,
			TrackingDiagnosticResult.REJECTED,
			-> Tracebox.log.warn(
				TrackerTraceboxTemplates.TRACKING_OPERATION_EVENT,
				public(event.source.name),
				public(event.purpose.name),
				public(event.pipelineStage.name),
				public(event.operation.name),
				public(event.result.name),
				public(event.reason.stableName),
				public(event.lifecycle.name),
				public(event.scopeEventCountBucket.name),
				public(event.scopeDurationBucket.name),
				public(metricValues.firstName),
				public(metricValues.firstBucket),
				public(metricValues.secondName),
				public(metricValues.secondBucket),
			)
			else -> Tracebox.log.info(
				TrackerTraceboxTemplates.TRACKING_OPERATION_EVENT,
				public(event.source.name),
				public(event.purpose.name),
				public(event.pipelineStage.name),
				public(event.operation.name),
				public(event.result.name),
				public(event.reason.stableName),
				public(event.lifecycle.name),
				public(event.scopeEventCountBucket.name),
				public(event.scopeDurationBucket.name),
				public(metricValues.firstName),
				public(metricValues.firstBucket),
				public(metricValues.secondName),
				public(metricValues.secondBucket),
			)
		}
	}
}

private data class AdapterMetricValues(
	val firstName: String,
	val firstBucket: String,
	val secondName: String,
	val secondBucket: String,
)

private fun RecordedTrackingDiagnosticEvent.metricValues(): AdapterMetricValues = when (this) {
	is EnqueueRecordedTrackingDiagnosticEvent -> AdapterMetricValues(
		firstName = TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE.name,
		firstBucket = encodedEnvelopeSizeBucket.name,
		secondName = TrackingDiagnosticMetric.QUEUE_BACKLOG.name,
		secondBucket = queueBacklogBucket.name,
	)
	is DrainRecordedTrackingDiagnosticEvent -> AdapterMetricValues(
		firstName = TrackingDiagnosticMetric.DRAINED_ENVELOPE_COUNT.name,
		firstBucket = drainedEnvelopeCountBucket.name,
		secondName = TrackingDiagnosticMetric.REMAINING_ENVELOPE_BACKLOG.name,
		secondBucket = remainingEnvelopeBacklogBucket.name,
	)
	is WriteRecordedTrackingDiagnosticEvent -> AdapterMetricValues(
		firstName = TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT.name,
		firstBucket = persistedEnvelopeCountBucket.name,
		secondName = NO_METRIC,
		secondBucket = NO_BUCKET,
	)
	else -> AdapterMetricValues(
		firstName = NO_METRIC,
		firstBucket = NO_BUCKET,
		secondName = NO_METRIC,
		secondBucket = NO_BUCKET,
	)
}

private const val NO_METRIC = "NONE"
private const val NO_BUCKET = "NOT_REPORTED"
