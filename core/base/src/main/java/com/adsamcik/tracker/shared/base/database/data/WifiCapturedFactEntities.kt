package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest

/** Append-only identity-free evidence for one qualified Wi-Fi provider delivery. */
@Entity(
	tableName = "wifi_captured_fact_revision",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision"],
	indices = [
		Index(
			value = ["writer_projection_id", "writer_projection_version", "mutation_id"],
			unique = true,
			name = "idx_wifi_captured_fact_mutation",
		),
		Index(
			value = ["writer_projection_id", "writer_projection_version", "source_admission_ordinal"],
			name = "idx_wifi_captured_fact_admission",
		),
		Index(
			value = ["service_run_id", "logical_tracking_id", "session_segment_id"],
			name = "idx_wifi_captured_fact_run_scope",
		),
		Index(
			value = [
				"writer_projection_id", "writer_projection_version",
				"aggregate_owner_logical_fact_id", "aggregate_owner_semantic_revision",
			],
			name = "idx_wifi_captured_fact_aggregate_owner",
		),
	],
	foreignKeys = [
		ForeignKey(
			entity = WifiCapturedFactRevisionEntity::class,
			parentColumns = [
				"writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision",
			],
			childColumns = [
				"writer_projection_id", "writer_projection_version",
				"aggregate_owner_logical_fact_id", "aggregate_owner_semantic_revision",
			],
			onDelete = ForeignKey.RESTRICT,
			deferred = true,
		),
	],
)
@Suppress("LongParameterList")
data class WifiCapturedFactRevisionEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "fact_kind") val factKind: String,
	@ColumnInfo(name = "aggregate_owner_logical_fact_id") val aggregateOwnerLogicalFactId: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "aggregate_owner_cursor_revision") val aggregateOwnerCursorRevision: Long?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "captured_source_codes") val capturedSourceCodes: String,
	@ColumnInfo(name = "control_source_codes") val controlSourceCodes: String,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "wal_integrity_identity") val walIntegrityIdentity: String,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "source_delivery_identity") val sourceDeliveryIdentity: String,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "plan_attribution") val planAttribution: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "purpose_eligibility_mask") val purposeEligibilityMask: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "plan_payload_version") val planPayloadVersion: Int,
	@ColumnInfo(name = "plan_payload_checksum") val planPayloadChecksum: String,
	@ColumnInfo(name = "maximum_observation_age_nanos") val maximumObservationAgeNanos: Long,
	@ColumnInfo(name = "result_contract") val resultContract: String,
	@ColumnInfo(name = "registration_applied_at_nanos") val registrationAppliedAtNanos: Long,
	@ColumnInfo(name = "provider_acceptance_start_nanos") val providerAcceptanceStartNanos: Long,
	@ColumnInfo(name = "provider_acceptance_end_nanos") val providerAcceptanceEndNanos: Long,
	@ColumnInfo(name = "authorization_effect_start_nanos") val authorizationEffectStartNanos: Long,
	@ColumnInfo(name = "authorization_effect_end_nanos") val authorizationEffectEndNanos: Long,
	@ColumnInfo(name = "session_run_effect_start_nanos") val sessionRunEffectStartNanos: Long,
	@ColumnInfo(name = "session_run_effect_end_nanos") val sessionRunEffectEndNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "received_elapsed_nanos") val receivedElapsedNanos: Long,
	@ColumnInfo(name = "coverage_interval_start_nanos") val coverageIntervalStartNanos: Long,
	@ColumnInfo(name = "coverage_interval_end_nanos") val coverageIntervalEndNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Float?,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "submitted_result_count") val submittedResultCount: Int,
	@ColumnInfo(name = "accepted_result_count") val acceptedResultCount: Int,
	@ColumnInfo(name = "stale_result_count") val staleResultCount: Int,
	@ColumnInfo(name = "clock_unverifiable_result_count") val clockUnverifiableResultCount: Int,
	@ColumnInfo(name = "malformed_result_count") val malformedResultCount: Int,
	@ColumnInfo(name = "coverage_completeness") val coverageCompleteness: String,
	@ColumnInfo(name = "observation_count") val observationCount: Int?,
	@ColumnInfo(name = "two_point_four_ghz_count") val twoPointFourGhzCount: Int?,
	@ColumnInfo(name = "five_ghz_count") val fiveGhzCount: Int?,
	@ColumnInfo(name = "six_ghz_count") val sixGhzCount: Int?,
	@ColumnInfo(name = "other_band_count") val otherBandCount: Int?,
	@ColumnInfo(name = "strongest_signal_dbm") val strongestSignalDbm: Int?,
	@ColumnInfo(name = "weakest_signal_dbm") val weakestSignalDbm: Int?,
	@ColumnInfo(name = "signal_sum_dbm") val signalSumDbm: Long?,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION)
		require(writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION)
		require(writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
		require(semanticRevision > 0L)
		require(if (semanticRevision == 1L) supersedesSemanticRevision == null else
			supersedesSemanticRevision == semanticRevision - 1L)
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank() && sessionSegmentId > 0L)
		require(purpose == SourceBrokerPurpose.SESSION_CAPTURE)
		val capturedCodes = requireCanonicalSourceCodes(capturedSourceCodes, allowEmpty = false)
		val controlCodes = requireCanonicalSourceCodes(controlSourceCodes, allowEmpty = true)
		require(SourceDestinationOwnerEntity.SOURCE_WIFI in capturedCodes)
		require(capturedCodes.intersect(controlCodes).isEmpty())
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(LOWERCASE_SHA_256.matches(sourceDeliveryIdentity))
		require(deliveryUnitIndex == 0 && deliveryUnitCount == 1 && sourceSequence > 0L)
		require(planAttribution == PLAN_ATTRIBUTION_CAPTURED_REGISTRATION)
		require(sourceInstanceId.isNotBlank() && registrationGeneration > 0L)
		require(configurationRevision >= 0L && physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L && LOWERCASE_SHA_256.matches(authorizationFingerprint))
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L)
		require(sourcePolicyRevision > 0L && captureConsentEpoch >= 0L && manifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L && collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L && clockDomainId.isNotBlank() && storedZoneId.isNotBlank())
		require(planPayloadVersion > 0 && LOWERCASE_SHA_256.matches(planPayloadChecksum))
		require(maximumObservationAgeNanos >= 0L && resultContract.isNotBlank())
		require(registrationAppliedAtNanos >= 0L)
		requireInterval(providerAcceptanceStartNanos, providerAcceptanceEndNanos)
		requireInterval(authorizationEffectStartNanos, authorizationEffectEndNanos)
		requireInterval(sessionRunEffectStartNanos, sessionRunEffectEndNanos)
		require(observedIntervalStartNanos > 0L && observedElapsedNanos >= observedIntervalStartNanos)
		require(receivedElapsedNanos >= observedElapsedNanos)
		require(coverageIntervalStartNanos in observedIntervalStartNanos..observedElapsedNanos)
		require(coverageIntervalEndNanos in coverageIntervalStartNanos..observedElapsedNanos)
		require(observedWallTimeMs >= 0L && wallTimeUncertaintyMs >= 0L && acquiredAtMs >= 0L)
		require(qualityFlags >= 0L && (qualityConfidence == null || qualityConfidence in 0f..1f))
		require(availability == AVAILABILITY_AVAILABLE)
		val total = listOf(acceptedResultCount, staleResultCount, clockUnverifiableResultCount,
			malformedResultCount).fold(0) { sum, count -> Math.addExact(sum, count) }
		require(submittedResultCount > 0 && acceptedResultCount > 0 && total == submittedResultCount)
		require(coverageCompleteness in COVERAGE_COMPLETENESS_VALUES)
		require((coverageCompleteness == COVERAGE_COMPLETE) == (acceptedResultCount == submittedResultCount))
		requireFactKind()
		require(logicalFactId == WifiCapturedFactRevisionIntegrity.logicalFactId(
			sourceDeliveryIdentity, logicalTrackingId, serviceRunId, sessionSegmentId, manifestRevision,
			collectedDataEpoch, scopeDeletionGeneration,
		))
		require(mutationId == WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision))
		require(LOWERCASE_SHA_256.matches(effectChecksum) && appliedAtMs == observedWallTimeMs)
	}

	private fun requireFactKind() {
		val aggregate = listOf(observationCount, twoPointFourGhzCount, fiveGhzCount, sixGhzCount,
			otherBandCount, strongestSignalDbm, weakestSignalDbm, signalSumDbm)
		when (factKind) {
			FACT_KIND_AGGREGATE -> {
				require(aggregateOwnerLogicalFactId == null && aggregateOwnerSemanticRevision == null &&
					aggregateOwnerCursorRevision == null && aggregate.all { it != null })
				val count = requireNotNull(observationCount)
				require(count == acceptedResultCount && count > 0)
				require(listOf(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount)
					.filterNotNull().sum() == count)
				require(requireNotNull(strongestSignalDbm) >= requireNotNull(weakestSignalDbm))
			}
			FACT_KIND_COVERAGE_ONLY -> {
				require(LOWERCASE_SHA_256.matches(requireNotNull(aggregateOwnerLogicalFactId)))
				require(requireNotNull(aggregateOwnerSemanticRevision) > 0L)
				require(requireNotNull(aggregateOwnerCursorRevision) > 0L)
				require(aggregate.all { it == null })
			}
			else -> require(false) { "Unknown Wi-Fi captured fact kind $factKind" }
		}
	}

	private fun requireInterval(start: Long, end: Long) = require(start >= 0L && end > start)

	private fun requireCanonicalSourceCodes(value: String, allowEmpty: Boolean): Set<Int> {
		if (value.isEmpty()) {
			require(allowEmpty)
			return emptySet()
		}
		val codes = value.split(',').map { token ->
			require(token.isNotEmpty() && token.all(Char::isDigit))
			token.toInt().also { require(it > 0) }
		}
		require(codes == codes.distinct().sorted())
		return codes.toSet()
	}

	companion object {
		const val FACT_KIND_AGGREGATE = "AGGREGATE"
		const val FACT_KIND_COVERAGE_ONLY = "COVERAGE_ONLY"
		const val PLAN_ATTRIBUTION_CAPTURED_REGISTRATION = "CAPTURED_REGISTRATION"
		const val AVAILABILITY_AVAILABLE = "AVAILABLE"
		const val COVERAGE_COMPLETE = "COMPLETE"
		const val COVERAGE_PARTIAL = "PARTIAL"
		private val COVERAGE_COMPLETENESS_VALUES = setOf(COVERAGE_COMPLETE, COVERAGE_PARTIAL)
		private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/** Monotonic pointer to the latest completely committed revision of one exact Wi-Fi fact. */
@Entity(
	tableName = "wifi_captured_fact_cursor",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_fact_id"],
)
data class WifiCapturedFactCursorEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "latest_semantic_revision") val latestSemanticRevision: Long,
	@ColumnInfo(name = "latest_mutation_id") val latestMutationId: String,
	@ColumnInfo(name = "latest_effect_checksum") val latestEffectChecksum: String,
	@ColumnInfo(name = "latest_source_admission_ordinal") val latestSourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "cursor_revision") val cursorRevision: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION)
		require(logicalFactId.isNotBlank() && logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L && writerOwnerGeneration > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(latestSemanticRevision > 0L && latestMutationId.isNotBlank())
		require(LOWERCASE_SHA_256.matches(latestEffectChecksum))
		require(latestSourceAdmissionOrdinal > 0L && cursorRevision > 0L && updatedAtMs >= 0L)
	}

	private companion object { val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}") }
}

/** Source-local deletion fence. A v1 WAL can only authenticate the absence (generation zero). */
@Entity(
	tableName = "wifi_capture_deletion_generation",
	primaryKeys = ["logical_tracking_id", "service_run_id"],
)
data class WifiCaptureDeletionGenerationEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(collectedDataEpoch >= 0L && generation > 0L && updatedAtMs >= 0L)
	}
}

object WifiCapturedFactRevisionIntegrity {
	fun logicalFactId(
		sourceDeliveryIdentity: String,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		manifestRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
	): String = digest("wifi-captured-fact-v1", sourceDeliveryIdentity, logicalTrackingId,
		serviceRunId, sessionSegmentId, manifestRevision, collectedDataEpoch, scopeDeletionGeneration)

	fun mutationId(logicalFactId: String, semanticRevision: Long): String =
		digest("wifi-captured-mutation-v1", logicalFactId, semanticRevision)

	fun effectChecksum(fact: WifiCapturedFactRevisionEntity): String =
		digest("wifi-captured-effect-v1", *fact.effectParts().toTypedArray())

	fun hasValidEffectChecksum(fact: WifiCapturedFactRevisionEntity): Boolean =
		fact.effectChecksum == effectChecksum(fact)

	private fun WifiCapturedFactRevisionEntity.effectParts(): List<Any?> = listOf(
		writerProjectionId, writerProjectionVersion, writerBindingGeneration, writerOwnerGeneration,
		logicalFactId, semanticRevision, supersedesSemanticRevision, mutationId, factKind,
		aggregateOwnerLogicalFactId, aggregateOwnerSemanticRevision, aggregateOwnerCursorRevision,
		logicalTrackingId, serviceRunId, sessionSegmentId, purpose, capturedSourceCodes,
		controlSourceCodes, sourceEventId,
		sourceAdmissionOrdinal, walIntegrityIdentity, payloadChecksum, sourceDeliveryIdentity,
		deliveryUnitIndex, deliveryUnitCount, sourceSequence, planAttribution, sourceInstanceId,
		registrationGeneration, configurationRevision, physicalConfigurationFingerprint,
		authorizationRevision, authorizationFingerprint, purposeEligibilityMask, sourcePolicyRevision,
		captureConsentEpoch, manifestRevision, lifecycleLeaseGeneration, collectedDataEpoch,
		scopeDeletionGeneration, clockDomainId, storedZoneId, planPayloadVersion, planPayloadChecksum,
		maximumObservationAgeNanos, resultContract, registrationAppliedAtNanos,
		providerAcceptanceStartNanos, providerAcceptanceEndNanos, authorizationEffectStartNanos,
		authorizationEffectEndNanos, sessionRunEffectStartNanos, sessionRunEffectEndNanos,
		observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos,
		coverageIntervalStartNanos, coverageIntervalEndNanos, observedWallTimeMs,
		wallTimeUncertaintyMs, acquiredAtMs, qualityFlags, qualityConfidence, availability,
		submittedResultCount, acceptedResultCount, staleResultCount,
		clockUnverifiableResultCount, malformedResultCount, coverageCompleteness, observationCount,
		twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount, strongestSignalDbm,
		weakestSignalDbm, signalSumDbm, appliedAtMs,
	)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
