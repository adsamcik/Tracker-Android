package com.adsamcik.tracker.impexp.exporter.research

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.research.RESEARCH_EVIDENCE_SCHEMA_V2
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionConfiguration
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionRecord
import com.adsamcik.tracker.stats.api.research.ResearchControlDigestRecord
import com.adsamcik.tracker.stats.api.research.ResearchControlEventRecord
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord
import com.adsamcik.tracker.stats.api.research.ResearchGapRecord
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorRecord
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTerminalIntegrityRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleRecord
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only NDJSON exporter for explicit V2 control-evidence snapshots.
 *
 * The caller owns the in-memory snapshot and must deliberately wrap this exporter with
 * [encrypted]. This class is not registered in the production format registry and rejects any
 * record outside the coordinate-free V2 control family, preventing an accidental export of a
 * broader research payload through this route.
 */
class ResearchControlEvidencePayloadExporter(
	private val evidenceProvider: () -> List<ResearchEvidenceEnvelope>,
) : Exporter {
	override val canSelectDateRange: Boolean = false
	override val mimeType: String = "application/x-ndjson"
	override val extension: String = "ndjson"

	fun encrypted(
		passphraseProvider: suspend () -> CharArray,
	): EncryptedResearchTraceExporter = EncryptedResearchTraceExporter(
		delegate = this,
		passphraseProvider = passphraseProvider,
	)

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult = try {
		val evidence = evidenceProvider().toList()
		validate(evidence)
		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.write(manifest(evidence).toString())
			writer.newLine()
			evidence.forEach { envelope ->
				writer.write(envelope.toJson().toString())
				writer.newLine()
			}
			writer.flush()
		}
		ExportResult.Success
	} catch (exception: CancellationException) {
		throw exception
	} catch (exception: Exception) {
		Reporter.report(exception)
		ExportResult.Error(
			LocalizedString(
				R.string.export_error_with_reason,
				exception.message ?: "Failed to write V2 control evidence",
			),
		)
	}

	private fun validate(evidence: List<ResearchEvidenceEnvelope>) {
		if (evidence.isEmpty()) return
		val identity = evidence.first().identity
		var previousSequence = -1L
		evidence.forEach { envelope ->
			require(envelope.schemaVersion == RESEARCH_EVIDENCE_SCHEMA_V2) {
				"V2 control exporter does not accept schema ${envelope.schemaVersion}"
			}
			require(envelope.identity == identity) { "All exported control evidence must share one identity" }
			require(envelope.sequence > previousSequence) { "Control evidence sequences must be strictly ordered" }
			require(envelope.privacyClass == ResearchPrivacyClass.ENCRYPTED_RESEARCH) {
				"Control evidence must be explicitly marked encrypted research"
			}
			require(envelope.record.isSupportedControlRecord()) {
				"Control exporter received unsupported ${envelope.record::class.simpleName}"
			}
			previousSequence = envelope.sequence
		}
	}

	private fun manifest(evidence: List<ResearchEvidenceEnvelope>): JSONObject = JSONObject().apply {
		put("recordType", "manifest")
		put("format", "tracker-research-control-evidence-ndjson")
		put("schemaVersion", RESEARCH_EVIDENCE_SCHEMA_V2)
		put("encryptedRequired", true)
		put("envelopeCount", evidence.size)
		put("complete", false)
		put("note", "Wrap with EncryptedResearchTraceExporter; this payload alone is not safe to persist")
	}

	private fun ResearchEvidenceEnvelope.toJson(): JSONObject = JSONObject().apply {
		put("recordType", "evidence")
		put("schemaVersion", schemaVersion)
		put("sequence", sequence)
		put("identity", JSONObject().apply {
			put("traceId", identity.traceId)
			putNullable("runId", identity.runId)
			putNullable("sessionId", identity.sessionId)
		})
		put("clockDomain", JSONObject().apply {
			put("id", clockDomain.id)
			put("kind", clockDomain.kind.name)
			putNullable("bootId", clockDomain.bootId)
		})
		put("privacyClass", privacyClass.name)
		put("algorithmVersions", JSONObject(algorithmVersions.toSortedMap()))
		putNullable("lifecycleBoundary", lifecycleBoundary?.name)
		put("capabilities", JSONObject().apply {
			put("rawPressureEvents", capabilities.rawPressureEvents)
			put("pressureAggregateWindows", capabilities.pressureAggregateWindows)
			put("altitudeConversionOutcomes", capabilities.altitudeConversionOutcomes)
			put("altitudeEstimatorDecisions", capabilities.altitudeEstimatorDecisions)
			put("canonicalSegmentationObservations", capabilities.canonicalSegmentationObservations)
			put("segmentationReducerOutputs", capabilities.segmentationReducerOutputs)
			put("truthMarkers", capabilities.truthMarkers)
			put("controlTraceEvents", capabilities.controlTraceEvents)
			put("logicalTrackingLifecycle", capabilities.logicalTrackingLifecycle)
			put("acquisitionDecisions", capabilities.acquisitionDecisions)
			put("horizontalEstimatorDecisions", capabilities.horizontalEstimatorDecisions)
			put("replayDigests", capabilities.replayDigests)
		})
		put("lossRanges", JSONArray().apply {
			lossRanges.forEach { range -> put(JSONObject().apply {
				put("firstSequence", range.firstSequence)
				put("lastSequence", range.lastSequence)
				put("reason", range.reason.name)
			}) }
		})
		putNullable("terminalIntegrity", terminalIntegrity?.toJson())
		put("record", record.toJson())
	}

	private fun ResearchEvidenceRecord.isSupportedControlRecord(): Boolean = this is ResearchControlEventRecord ||
		this is ResearchTrackingLifecycleRecord || this is ResearchAcquisitionRecord ||
		this is ResearchHorizontalEstimatorRecord || this is ResearchGapRecord ||
		this is ResearchControlDigestRecord || this is ResearchTerminalIntegrityRecord

	private fun ResearchEvidenceRecord.toJson(): JSONObject = when (this) {
		is ResearchControlEventRecord -> JSONObject().apply {
			put("type", "control_event")
			put("logicalTrackingId", logicalTrackingId)
			putNullable("eventEpochMs", eventEpochMs)
			putNullable("eventElapsedNanos", eventElapsedNanos)
			put("kind", kind.name)
			putNullable("reason", reason)
			putNullable("correlationId", correlationId)
			put("payload", JSONObject(payload.toSortedMap()))
		}
		is ResearchTrackingLifecycleRecord -> JSONObject().apply {
			put("type", "tracking_lifecycle")
			put("logicalTrackingId", logicalTrackingId)
			put("transition", transition.name)
			put("trackingMode", trackingMode.name)
			putNullable("eventEpochMs", eventEpochMs)
			putNullable("eventElapsedNanos", eventElapsedNanos)
			putNullable("stopCause", stopCause?.name)
			putNullable("reason", reason)
			putNullable("correlationId", correlationId)
			putNullable("resumedFromLogicalTrackingId", resumedFromLogicalTrackingId)
		}
		is ResearchAcquisitionRecord -> JSONObject().apply {
			put("type", "acquisition")
			put("logicalTrackingId", logicalTrackingId)
			put("requestId", requestId)
			putNullable("eventEpochMs", eventEpochMs)
			putNullable("eventElapsedNanos", eventElapsedNanos)
			put("desired", desired.toJson())
			putNullable("applied", applied?.toJson())
			put("outcome", outcome.name)
			putNullable("reason", reason)
			putNullable("correlationId", correlationId)
		}
		is ResearchHorizontalEstimatorRecord -> JSONObject().apply {
			put("type", "horizontal_estimator")
			put("logicalTrackingId", logicalTrackingId)
			put("estimatorVersion", estimatorVersion)
			put("decision", decision.name)
			putNullable("eventEpochMs", eventEpochMs)
			putNullable("sourceElapsedNanos", sourceElapsedNanos)
			putNullable("deltaNanos", deltaNanos)
			putNullable("sourceSequence", sourceSequence)
			put("stateDimension", stateDimension)
			put("stateBefore", stateBefore.toJsonArray())
			put("stateAfter", stateAfter.toJsonArray())
			put("covarianceBefore", covarianceBefore.toJsonArray())
			put("covarianceAfter", covarianceAfter.toJsonArray())
			put("processNoise", processNoise.toJsonArray())
			putNullable("measurementEastM", measurementEastM)
			putNullable("measurementNorthM", measurementNorthM)
			put("measurementCovariance", measurementCovariance.toJsonArray())
			put("innovation", innovation.toJsonArray())
			putNullable("normalizedInnovationSquared", normalizedInnovationSquared)
			putNullable("accepted", accepted)
			putNullable("reason", reason)
			putNullable("correlationId", correlationId)
		}
		is ResearchGapRecord -> JSONObject().apply {
			put("type", "gap")
			putNullable("logicalTrackingId", logicalTrackingId)
			putNullable("startEpochMs", startEpochMs)
			putNullable("endEpochMs", endEpochMs)
			put("clockDomainId", clockDomainId)
			putNullable("startElapsedNanos", startElapsedNanos)
			putNullable("endElapsedNanos", endElapsedNanos)
			putNullable("reason", reason)
		}
		is ResearchControlDigestRecord -> JSONObject().apply {
			put("type", "control_digest")
			put("kind", kind.name)
			put("algorithm", digestAlgorithm)
			put("digest", digest)
			putNullable("logicalTrackingId", logicalTrackingId)
			put("envelopeCount", envelopeCount)
			putNullable("firstSequence", firstSequence)
			putNullable("lastSequence", lastSequence)
		}
		is ResearchTerminalIntegrityRecord -> JSONObject().apply {
			put("type", "terminal_integrity")
			put("envelopeCount", envelopeCount)
			putNullable("firstSequence", firstSequence)
			putNullable("lastSequence", lastSequence)
			put("lossRangeCount", lossRangeCount)
			put("lostEventCount", lostEventCount)
			put("complete", complete)
		}
		else -> error("Unsupported research control record")
	}

	private fun ResearchAcquisitionConfiguration.toJson(): JSONObject = JSONObject().apply {
		put("mode", mode.name)
		putNullable("intervalMs", intervalMs)
		putNullable("minUpdateDistanceM", minUpdateDistanceM)
		putNullable("maxUpdateDelayMs", maxUpdateDelayMs)
		putNullable("requestGnssStatus", requestGnssStatus)
		putNullable("providerIdentity", providerIdentity)
		putNullable("priority", priority)
	}

	private fun JSONObject.putNullable(key: String, value: Any?) {
		put(key, value ?: JSONObject.NULL)
	}

	private fun List<Double>.toJsonArray(): JSONArray = JSONArray().also { array ->
		forEach { value -> array.put(value) }
	}
}
