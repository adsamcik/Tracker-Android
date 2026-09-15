package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Canonical identity and product-effect verification shared by the Activity writer and readers. */
object ActivityCapturedFactIntegrity {
	fun logicalWindowId(revision: ActivityCapturedWindowRevisionEntity): String = digest(
		"activity-captured-window-v1",
		listOf(
			revision.logicalTrackingId,
			revision.serviceRunId,
			revision.sourceInstanceId,
			revision.registrationGeneration.toString(),
			revision.configurationRevision.toString(),
			revision.physicalConfigurationFingerprint,
			revision.authorizationRevision.toString(),
			revision.authorizationFingerprint,
			revision.purposeEligibilityMask.toString(),
			revision.sourcePolicyRevision.toString(),
			revision.captureConsentEpoch.toString(),
			revision.manifestRevision.toString(),
			revision.lifecycleLeaseGeneration.toString(),
			revision.collectedDataEpoch.toString(),
			revision.clockDomainId,
			revision.providerAcceptanceStartNanos.toString(),
			revision.providerAcceptanceEndNanos.toString(),
			revision.authorizationEffectStartNanos.toString(),
			revision.authorizationEffectEndNanos.toString(),
			revision.sessionRunEffectStartNanos.toString(),
			revision.sessionRunEffectEndNanos.toString(),
			revision.windowStartElapsedRealtimeNanos.toString(),
			revision.windowEndElapsedRealtimeNanos.toString(),
		),
	)

	fun mutationId(logicalWindowId: String, semanticRevision: Long): String = digest(
		"activity-captured-mutation-v1",
		listOf(logicalWindowId, semanticRevision.toString()),
	)

	fun effectChecksum(
		revision: ActivityCapturedWindowRevisionEntity,
		fragments: List<ActivityCapturedFragmentEntity>,
		evidence: List<ActivityCapturedEvidenceEntity>,
	): String = digest(
		"activity-captured-effect-v1",
		listOf(
			revision.logicalWindowId,
			revision.storedZoneId,
			revision.coverage,
			revision.knownActiveDurationNanos.toString(),
			revision.knownInactiveDurationNanos.toString(),
			revision.unknownActivityDurationNanos.toString(),
			revision.unobservedDurationNanos.toString(),
		) + fragments.flatMap(::fragmentParts) + evidence.flatMap(::evidenceParts),
	)

	/** Verifies one complete immutable revision without consulting mutable current authority. */
	fun verify(
		revision: ActivityCapturedWindowRevisionEntity,
		fragments: List<ActivityCapturedFragmentEntity>,
		evidence: List<ActivityCapturedEvidenceEntity>,
	): Boolean = runCatching {
		revision.logicalWindowId == logicalWindowId(revision) &&
			revision.mutationId == mutationId(revision.logicalWindowId, revision.semanticRevision) &&
			revision.effectChecksum == effectChecksum(revision, fragments, evidence) &&
			fragments.map(ActivityCapturedFragmentEntity::fragmentOrdinal) == fragments.indices.toList() &&
			fragments.all { fragment -> fragment.belongsTo(revision) } &&
			fragments.firstOrNull()?.intervalStartElapsedRealtimeNanos ==
				revision.windowStartElapsedRealtimeNanos &&
			fragments.lastOrNull()?.intervalEndElapsedRealtimeNanos ==
				revision.windowEndElapsedRealtimeNanos &&
			fragments.zipWithNext().all { (left, right) ->
				left.intervalEndElapsedRealtimeNanos == right.intervalStartElapsedRealtimeNanos
			} &&
			evidence.all { row ->
				row.belongsTo(revision) && fragments.getOrNull(row.fragmentOrdinal)?.fragmentKind ==
					ActivityCapturedFragmentEntity.KIND_BAND
			} &&
			fragments.filter { it.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND }
				.mapNotNull(ActivityCapturedFragmentEntity::bandOrdinal) ==
				fragments.filter { it.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND }.indices.toList() &&
			fragments.filter { it.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND }
				.all { fragment -> evidence.any { it.fragmentOrdinal == fragment.fragmentOrdinal } } &&
			evidence.groupBy(ActivityCapturedEvidenceEntity::fragmentOrdinal).values.all { rows ->
				rows.map(ActivityCapturedEvidenceEntity::evidenceOrdinal) == rows.indices.toList() &&
					rows.map(ActivityCapturedEvidenceEntity::sourceEventId).distinct().size == rows.size
			} && fragments.all { fragment ->
				fragment.fragmentKind != ActivityCapturedFragmentEntity.KIND_BAND ||
					validBand(fragment, evidence.filter { it.fragmentOrdinal == fragment.fragmentOrdinal })
			} && validSummary(revision, fragments)
	}.getOrDefault(false)

	private fun validSummary(
		revision: ActivityCapturedWindowRevisionEntity,
		fragments: List<ActivityCapturedFragmentEntity>,
	): Boolean {
		var active = 0L
		var inactive = 0L
		var unknown = 0L
		var unobserved = 0L
		fragments.forEach { fragment ->
			val duration = Math.subtractExact(
				fragment.intervalEndElapsedRealtimeNanos,
				fragment.intervalStartElapsedRealtimeNanos,
			)
			when {
				fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_GAP ->
					unobserved = Math.addExact(unobserved, duration)
				fragment.activity in ACTIVE_ACTIVITIES -> active = Math.addExact(active, duration)
				fragment.activity == "STILL" -> inactive = Math.addExact(inactive, duration)
				else -> unknown = Math.addExact(unknown, duration)
			}
		}
		val expectedCoverage = when {
			fragments.none { it.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND } -> "NONE"
			fragments.none { it.fragmentKind == ActivityCapturedFragmentEntity.KIND_GAP } -> "COMPLETE"
			else -> "PARTIAL"
		}
		return revision.knownActiveDurationNanos == active &&
			revision.knownInactiveDurationNanos == inactive &&
			revision.unknownActivityDurationNanos == unknown &&
			revision.unobservedDurationNanos == unobserved && revision.coverage == expectedCoverage
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validBand(
		fragment: ActivityCapturedFragmentEntity,
		evidence: List<ActivityCapturedEvidenceEntity>,
	): Boolean {
		val evidenceById = evidence.associateBy(ActivityCapturedEvidenceEntity::sourceEventId)
		val startAnchor = evidenceById[fragment.startAnchorSourceEventId] ?: return false
		val endAnchor = evidenceById[fragment.endAnchorSourceEventId] ?: return false
		if (fragment.activity !in ALL_ACTIVITIES || fragment.startBoundaryKind !in BOUNDARY_KINDS ||
			fragment.endBoundaryKind !in BOUNDARY_KINDS || fragment.wallTimeContinuity !in CONTINUITIES ||
			startAnchor.providerElapsedRealtimeNanos != fragment.startAnchorProviderElapsedNanos ||
			endAnchor.providerElapsedRealtimeNanos != fragment.endAnchorProviderElapsedNanos ||
			fragment.wallTimeContinuity == "SAME_ANCHOR" && startAnchor.sourceEventId != endAnchor.sourceEventId ||
			fragment.startBoundaryKind == "EXACT_PROVIDER_OBSERVATION" &&
			fragment.startAnchorProviderElapsedNanos != fragment.intervalStartElapsedRealtimeNanos ||
			fragment.endBoundaryKind == "EXACT_PROVIDER_OBSERVATION" &&
			fragment.endAnchorProviderElapsedNanos != fragment.intervalEndElapsedRealtimeNanos
		) return false
		val sampled = evidence.filter { it.observationKind == ActivityCapturedEvidenceEntity.KIND_SAMPLED_CLASSIFICATION }
		val transitions = evidence.filter { it.observationKind == ActivityCapturedEvidenceEntity.KIND_TRANSITION }
		return when (fragment.mechanism) {
			"TRANSITION" -> fragment.confidenceKind == ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION &&
				fragment.refinedTransitionActivity == null && sampled.isEmpty() && transitions.any {
					it.observedActivity == fragment.activity && it.transitionChange == "ENTER"
				}
			"SAMPLED_REFINEMENT" -> validSampledConfidence(fragment, sampled) &&
				compatibleRefinement(fragment.refinedTransitionActivity, fragment.activity) && transitions.any {
					it.observedActivity == fragment.refinedTransitionActivity && it.transitionChange == "ENTER"
				}
			"SAMPLED_CLASSIFICATION" -> fragment.refinedTransitionActivity == null &&
				validSampledConfidence(fragment, sampled)
			else -> false
		}
	}

	private fun validSampledConfidence(
		fragment: ActivityCapturedFragmentEntity,
		sampled: List<ActivityCapturedEvidenceEntity>,
	): Boolean {
		val confidence = sampled.mapNotNull(ActivityCapturedEvidenceEntity::confidencePercent)
		return sampled.isNotEmpty() && sampled.all { row ->
			row.observedActivity == fragment.activity &&
				row.providerElapsedRealtimeNanos < fragment.intervalEndElapsedRealtimeNanos &&
				requireNotNull(row.coverageEndExclusiveElapsedRealtimeNanos) >
					fragment.intervalStartElapsedRealtimeNanos
		} && fragment.confidenceKind == ActivityCapturedFragmentEntity.CONFIDENCE_SAMPLED &&
			fragment.confidenceMinimumPercent == confidence.minOrNull() &&
			fragment.confidenceMaximumPercent == confidence.maxOrNull() &&
			fragment.confidenceObservationCount == confidence.size
	}

	private fun compatibleRefinement(coarse: String?, detail: String?): Boolean = when (coarse) {
		"ON_FOOT" -> detail == "WALKING" || detail == "RUNNING"
		"UNKNOWN" -> detail != null && detail != "UNKNOWN"
		else -> false
	}

	private fun ActivityCapturedFragmentEntity.belongsTo(
		revision: ActivityCapturedWindowRevisionEntity,
	): Boolean = writerProjectionId == revision.writerProjectionId &&
		writerProjectionVersion == revision.writerProjectionVersion &&
		logicalWindowId == revision.logicalWindowId && semanticRevision == revision.semanticRevision

	private fun ActivityCapturedEvidenceEntity.belongsTo(
		revision: ActivityCapturedWindowRevisionEntity,
	): Boolean = writerProjectionId == revision.writerProjectionId &&
		writerProjectionVersion == revision.writerProjectionVersion &&
		logicalWindowId == revision.logicalWindowId && semanticRevision == revision.semanticRevision

	private fun fragmentParts(fragment: ActivityCapturedFragmentEntity): List<String> = listOf(
		fragment.fragmentOrdinal,
		fragment.fragmentKind,
		fragment.bandOrdinal,
		fragment.intervalStartElapsedRealtimeNanos,
		fragment.intervalEndElapsedRealtimeNanos,
		fragment.gapReason,
		fragment.activity,
		fragment.mechanism,
		fragment.refinedTransitionActivity,
		fragment.confidenceKind,
		fragment.confidenceMinimumPercent,
		fragment.confidenceMaximumPercent,
		fragment.confidenceObservationCount,
		fragment.startWallTimeMs,
		fragment.startWallTimeUncertaintyMs,
		fragment.startBoundaryKind,
		fragment.startAnchorSourceEventId,
		fragment.startAnchorProviderElapsedNanos,
		fragment.endWallTimeMs,
		fragment.endWallTimeUncertaintyMs,
		fragment.endBoundaryKind,
		fragment.endAnchorSourceEventId,
		fragment.endAnchorProviderElapsedNanos,
		fragment.wallTimeContinuity,
	).map { it?.toString() ?: "null" }

	private fun evidenceParts(evidence: ActivityCapturedEvidenceEntity): List<String> = listOf(
		evidence.fragmentOrdinal.toString(),
		evidence.evidenceOrdinal.toString(),
		evidence.sourceEventId,
		evidence.sourceAdmissionOrdinal.toString(),
		evidence.sourceSequence.toString(),
		evidence.providerElapsedRealtimeNanos.toString(),
		evidence.receivedElapsedRealtimeNanos.toString(),
		evidence.observationKind,
		evidence.observedActivity,
		evidence.transitionChange ?: "null",
		evidence.confidencePercent?.toString() ?: "null",
		evidence.coverageEndExclusiveElapsedRealtimeNanos?.toString() ?: "null",
	)

	private fun digest(domain: String, values: List<String>): String {
		val canonical = (listOf(domain) + values).joinToString(separator = "") { value ->
			"${value.length}:$value"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private val ACTIVE_ACTIVITIES = setOf("WALKING", "RUNNING", "ON_BICYCLE", "IN_VEHICLE", "ON_FOOT")
	private val ALL_ACTIVITIES = ACTIVE_ACTIVITIES + setOf("STILL", "TILTING", "UNKNOWN")
	private val BOUNDARY_KINDS = setOf("EXACT_PROVIDER_OBSERVATION", "SAME_CLOCK_EXTRAPOLATION")
	private val CONTINUITIES = setOf("SAME_ANCHOR", "CONSISTENT_WITHIN_UNCERTAINTY", "DISCONTINUITY_DETECTED")
}

/** Exact canonical Activity member decoded from one immutable desired-plan row. */
data class ActivityCapturedDesiredPlan(
	val revision: Long,
	val mode: String,
	val desiredDetectionLatencyMs: Long,
	val confidenceThresholdPercent: Int,
	val transitionTypes: Set<Int>,
	val physicalConfigurationFingerprint: String,
) {
	val enabled: Boolean
		get() = mode != MODE_OFF

	companion object {
		const val MODE_OFF = "OFF"
	}
}

/**
 * Canonical source-plan-v1 verification shared by the Activity writer and historical reader.
 *
 * The re-encode requirement rejects trailing bytes, duplicate/unsorted transition members, and
 * any alternate byte representation before a checksum or physical fingerprint can authorize data.
 */
object ActivityCapturedPlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): ActivityCapturedDesiredPlan? = runCatching {
		require(row.sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
		require(row.payloadVersion == PAYLOAD_VERSION)
		require(row.payloadChecksum == sha256(row.payload))
		val decoded = DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
			require(input.readInt() == FORMAT_VERSION)
			require(input.readUTF() == SOURCE_ACTIVITY)
			val revision = input.readLong()
			val mode = input.readUTF()
			val latency = input.readLong()
			val confidence = input.readInt()
			val transitionCount = input.readInt()
			require(transitionCount in 0..MAX_TRANSITION_TYPES)
			val transitions = buildSet(transitionCount) {
				repeat(transitionCount) { add(input.readInt()) }
			}
			require(input.available() == 0)
			require(revision == row.revision && revision > 0L)
			require(mode in ACTIVITY_MODES)
			require(latency >= 0L)
			require(confidence in 0..100)
			ActivityCapturedDesiredPlan(
				revision = revision,
				mode = mode,
				desiredDetectionLatencyMs = latency,
				confidenceThresholdPercent = confidence,
				transitionTypes = transitions,
				physicalConfigurationFingerprint = physicalFingerprint(
					mode,
					latency,
					confidence,
					transitions,
				),
			)
		}
		require(row.payload.contentEquals(encode(decoded)))
		decoded
	}.getOrNull()

	private fun encode(plan: ActivityCapturedDesiredPlan): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(FORMAT_VERSION)
				output.writeUTF(SOURCE_ACTIVITY)
				output.writeLong(plan.revision)
				output.writeUTF(plan.mode)
				output.writeLong(plan.desiredDetectionLatencyMs)
				output.writeInt(plan.confidenceThresholdPercent)
				output.writeInt(plan.transitionTypes.size)
				plan.transitionTypes.sorted().forEach(output::writeInt)
			}
			buffer.toByteArray()
		}

	private fun physicalFingerprint(
		mode: String,
		latency: Long,
		confidence: Int,
		transitions: Set<Int>,
	): String = sha256(
		listOf(
			SOURCE_ACTIVITY,
			mode,
			latency,
			confidence,
			transitions.sorted().joinToString(","),
		).joinToString("\u001f").toByteArray(Charsets.UTF_8),
	)

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private const val PAYLOAD_VERSION = 1
	private const val FORMAT_VERSION = 1
	private const val SOURCE_ACTIVITY = "ACTIVITY"
	private const val MAX_TRANSITION_TYPES = 10_000
	private val ACTIVITY_MODES = setOf("OFF", "TRANSITIONS_ONLY", "CONTINUOUS_RECOGNITION")
}
