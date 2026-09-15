package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest

/**
 * Append-only, identity-free product evidence for one qualified Cell provider delivery.
 *
 * Raw tower, subscription, SIM, and slot identifiers are deliberately absent. The row retains the
 * exact WAL, provider, authorization, session, civil-day, deletion, and destination-writer
 * authority needed to reject stale replay and authenticate later corrections.
 */
@Entity(
	tableName = "cell_captured_fact_revision",
	primaryKeys = [
		"writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision",
	],
	indices = [
		Index(
			value = ["writer_projection_id", "writer_projection_version", "mutation_id"],
			unique = true,
			name = "idx_cell_captured_fact_mutation",
		),
		Index(
			value = ["writer_projection_id", "writer_projection_version", "source_admission_ordinal"],
			name = "idx_cell_captured_fact_admission",
		),
		Index(
			value = ["service_run_id", "logical_tracking_id", "session_segment_id"],
			name = "idx_cell_captured_fact_run_scope",
		),
	],
)
@Suppress("LongParameterList")
data class CellCapturedFactRevisionEntity(
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
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "source_delivery_identity") val sourceDeliveryIdentity: String,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "wal_integrity_identity") val walIntegrityIdentity: String,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "plan_attribution") val planAttribution: String,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "canonical_provider_semantics_digest")
	val canonicalProviderSemanticsDigest: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String,
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
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "provider_acceptance_start_nanos") val providerAcceptanceStartNanos: Long,
	@ColumnInfo(name = "provider_acceptance_end_nanos") val providerAcceptanceEndNanos: Long,
	@ColumnInfo(name = "authorization_effect_start_nanos") val authorizationEffectStartNanos: Long,
	@ColumnInfo(name = "authorization_effect_end_nanos") val authorizationEffectEndNanos: Long,
	@ColumnInfo(name = "consent_effect_start_nanos") val consentEffectStartNanos: Long,
	@ColumnInfo(name = "consent_effect_end_nanos") val consentEffectEndNanos: Long,
	@ColumnInfo(name = "session_run_effect_start_nanos") val sessionRunEffectStartNanos: Long,
	@ColumnInfo(name = "session_run_effect_end_nanos") val sessionRunEffectEndNanos: Long,
	@ColumnInfo(name = "deletion_effect_start_nanos") val deletionEffectStartNanos: Long,
	@ColumnInfo(name = "deletion_effect_end_nanos") val deletionEffectEndNanos: Long,
	@ColumnInfo(name = "maximum_observation_age_nanos") val maximumObservationAgeNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "received_elapsed_nanos") val receivedElapsedNanos: Long,
	@ColumnInfo(name = "coverage_interval_start_nanos") val coverageIntervalStartNanos: Long,
	@ColumnInfo(name = "coverage_interval_end_nanos") val coverageIntervalEndNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Float?,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "submitted_child_count") val submittedChildCount: Int,
	@ColumnInfo(name = "accepted_child_count") val acceptedChildCount: Int,
	@ColumnInfo(name = "stale_child_count") val staleChildCount: Int,
	@ColumnInfo(name = "future_time_child_count") val futureTimeChildCount: Int,
	@ColumnInfo(name = "missing_time_child_count") val missingTimeChildCount: Int,
	@ColumnInfo(name = "clock_unverifiable_child_count") val clockUnverifiableChildCount: Int,
	@ColumnInfo(name = "authority_mismatch_child_count") val authorityMismatchChildCount: Int,
	@ColumnInfo(name = "unsupported_technology_child_count") val unsupportedTechnologyChildCount: Int,
	@ColumnInfo(name = "subscription_completeness") val subscriptionCompleteness: String,
	@ColumnInfo(name = "child_completeness") val childCompleteness: String,
	@ColumnInfo(name = "observation_count") val observationCount: Int?,
	@ColumnInfo(name = "registered_observation_count") val registeredObservationCount: Int?,
	@ColumnInfo(name = "gsm_count") val gsmCount: Int?,
	@ColumnInfo(name = "cdma_count") val cdmaCount: Int?,
	@ColumnInfo(name = "wcdma_count") val wcdmaCount: Int?,
	@ColumnInfo(name = "tdscdma_count") val tdscdmaCount: Int?,
	@ColumnInfo(name = "lte_count") val lteCount: Int?,
	@ColumnInfo(name = "nr_count") val nrCount: Int?,
	@ColumnInfo(name = "quality_unknown_count") val qualityUnknownCount: Int?,
	@ColumnInfo(name = "quality_none_or_unknown_count") val qualityNoneOrUnknownCount: Int?,
	@ColumnInfo(name = "quality_poor_count") val qualityPoorCount: Int?,
	@ColumnInfo(name = "quality_moderate_count") val qualityModerateCount: Int?,
	@ColumnInfo(name = "quality_good_count") val qualityGoodCount: Int?,
	@ColumnInfo(name = "quality_great_count") val qualityGreatCount: Int?,
	@ColumnInfo(name = "weak_observation_count") val weakObservationCount: Int?,
	@ColumnInfo(name = "known_quality_observation_count") val knownQualityObservationCount: Int?,
	@ColumnInfo(name = "all_known_quality_is_weak") val allKnownQualityIsWeak: Boolean?,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION)
		require(writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION)
		require(writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
		require(semanticRevision > 0L)
		require(if (semanticRevision == 1L) supersedesSemanticRevision == null else
			supersedesSemanticRevision == semanticRevision - 1L)
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank() && sessionSegmentId > 0L)
		require(purpose == SourceBrokerPurpose.SESSION_CAPTURE)
		require(LOWERCASE_SHA_256.matches(sourceDeliveryIdentity))
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(deliveryUnitCount == 1 && deliveryUnitIndex == 0 && sourceSequence >= 0L)
		require(planAttribution == PLAN_ATTRIBUTION_CAPTURED_REGISTRATION && payloadVersion > 0)
		require(canonicalProviderSemanticsDigest == sourceDeliveryIdentity)
		require(sourceInstanceId.isNotBlank() && registrationGeneration > 0L)
		require(configurationRevision > 0L && physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L && LOWERCASE_SHA_256.matches(authorizationFingerprint))
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L)
		require(sourcePolicyRevision > 0L && captureConsentEpoch >= 0L && manifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L && collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L && clockDomainId.isNotBlank() && storedZoneId.isNotBlank())
		requireValidInterval(providerAcceptanceStartNanos, providerAcceptanceEndNanos)
		requireValidInterval(authorizationEffectStartNanos, authorizationEffectEndNanos)
		requireValidInterval(consentEffectStartNanos, consentEffectEndNanos)
		requireValidInterval(sessionRunEffectStartNanos, sessionRunEffectEndNanos)
		requireValidInterval(deletionEffectStartNanos, deletionEffectEndNanos)
		require(maximumObservationAgeNanos >= 0L)
		require(observedIntervalStartNanos > 0L && observedElapsedNanos >= observedIntervalStartNanos)
		require(receivedElapsedNanos >= observedElapsedNanos)
		require(coverageIntervalStartNanos in observedIntervalStartNanos..observedElapsedNanos)
		require(coverageIntervalEndNanos in coverageIntervalStartNanos..observedElapsedNanos)
		require(coverageIntervalStartNanos in providerAcceptanceStartNanos until providerAcceptanceEndNanos)
		require(coverageIntervalEndNanos in providerAcceptanceStartNanos until providerAcceptanceEndNanos)
		require(coverageIntervalStartNanos in authorizationEffectStartNanos until authorizationEffectEndNanos)
		require(coverageIntervalEndNanos in authorizationEffectStartNanos until authorizationEffectEndNanos)
		require(coverageIntervalStartNanos in consentEffectStartNanos until consentEffectEndNanos)
		require(coverageIntervalEndNanos in consentEffectStartNanos until consentEffectEndNanos)
		require(coverageIntervalStartNanos in sessionRunEffectStartNanos until sessionRunEffectEndNanos)
		require(coverageIntervalEndNanos in sessionRunEffectStartNanos until sessionRunEffectEndNanos)
		require(coverageIntervalStartNanos in deletionEffectStartNanos until deletionEffectEndNanos)
		require(coverageIntervalEndNanos in deletionEffectStartNanos until deletionEffectEndNanos)
		require(receivedElapsedNanos - coverageIntervalStartNanos <= maximumObservationAgeNanos)
		require(observedWallTimeMs >= 0L && wallTimeUncertaintyMs >= 0L)
		require(acquiredAtMs >= 0L && createdAtMs >= 0L && qualityFlags >= 0L)
		require(qualityConfidence == null || qualityConfidence in 0f..1f)
		require(availability == AVAILABILITY_AVAILABLE)
		require(subscriptionCompleteness == SUBSCRIPTION_COMPLETENESS_UNKNOWN)
		require(childCompleteness in CHILD_COMPLETENESS_VALUES)
		val rejectedCount = listOf(
			acceptedChildCount, staleChildCount, futureTimeChildCount, missingTimeChildCount,
			clockUnverifiableChildCount, authorityMismatchChildCount, unsupportedTechnologyChildCount,
		).fold(0) { total, count -> Math.addExact(total, count) }
		require(submittedChildCount > 0 && rejectedCount == submittedChildCount && acceptedChildCount > 0)
		require((childCompleteness == CHILD_COMPLETENESS_COMPLETE) ==
			(submittedChildCount == acceptedChildCount))
		requireFactKind()
		require(
			logicalFactId == CellCapturedFactRevisionIntegrity.logicalFactId(
				sourceDeliveryIdentity, logicalTrackingId, serviceRunId, sessionSegmentId,
				manifestRevision, collectedDataEpoch, scopeDeletionGeneration,
			),
		)
		require(mutationId == CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision))
		require(LOWERCASE_SHA_256.matches(effectChecksum))
		require(appliedAtMs == observedWallTimeMs)
	}

	private fun requireFactKind() {
		val aggregateValues = listOf(
			observationCount, registeredObservationCount, gsmCount, cdmaCount, wcdmaCount,
			tdscdmaCount, lteCount, nrCount, qualityUnknownCount, qualityNoneOrUnknownCount,
			qualityPoorCount, qualityModerateCount, qualityGoodCount, qualityGreatCount,
			weakObservationCount, knownQualityObservationCount, allKnownQualityIsWeak,
		)
		when (factKind) {
			FACT_KIND_AGGREGATE -> {
				require(aggregateOwnerLogicalFactId == null && aggregateOwnerSemanticRevision == null)
				require(aggregateValues.all { it != null })
				val count = requireNotNull(observationCount)
				require(count == acceptedChildCount && requireNotNull(registeredObservationCount) in 0..count)
				val technologyTotal = listOf(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount)
					.filterNotNull().fold(0) { total, count -> Math.addExact(total, count) }
				val qualityTotal = listOf(
					qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
					qualityModerateCount, qualityGoodCount, qualityGreatCount,
				).filterNotNull().fold(0) { total, count -> Math.addExact(total, count) }
				require(technologyTotal == count && qualityTotal == count)
				require(requireNotNull(weakObservationCount) ==
					requireNotNull(qualityNoneOrUnknownCount) + requireNotNull(qualityPoorCount))
				require(requireNotNull(knownQualityObservationCount) ==
					count - requireNotNull(qualityUnknownCount))
				require(requireNotNull(allKnownQualityIsWeak) ==
					(requireNotNull(knownQualityObservationCount) > 0 &&
						requireNotNull(weakObservationCount) == requireNotNull(knownQualityObservationCount)))
			}
			FACT_KIND_COVERAGE_ONLY -> {
				require(LOWERCASE_SHA_256.matches(requireNotNull(aggregateOwnerLogicalFactId)))
				require(requireNotNull(aggregateOwnerSemanticRevision) > 0L)
				require(aggregateValues.all { it == null })
			}
			else -> require(false) { "Unknown Cell captured fact kind $factKind" }
		}
	}

	private fun requireValidInterval(startNanos: Long, endNanos: Long) {
		require(startNanos >= 0L && endNanos > startNanos)
	}

	companion object {
		const val FACT_KIND_AGGREGATE = "AGGREGATE"
		const val FACT_KIND_COVERAGE_ONLY = "COVERAGE_ONLY"
		const val PLAN_ATTRIBUTION_CAPTURED_REGISTRATION = "CAPTURED_REGISTRATION"
		const val AVAILABILITY_AVAILABLE = "AVAILABLE"
		const val SUBSCRIPTION_COMPLETENESS_UNKNOWN = "UNKNOWN"
		const val CHILD_COMPLETENESS_COMPLETE = "COMPLETE"
		const val CHILD_COMPLETENESS_PARTIAL = "PARTIAL"
		private val CHILD_COMPLETENESS_VALUES =
			setOf(CHILD_COMPLETENESS_COMPLETE, CHILD_COMPLETENESS_PARTIAL)
		private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/** Monotonic pointer to the latest completely committed revision of one exact Cell fact. */
@Entity(
	tableName = "cell_captured_fact_cursor",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_fact_id"],
	indices = [
		Index(
			value = ["service_run_id", "logical_tracking_id", "session_segment_id"],
			name = "idx_cell_captured_cursor_run_scope",
		),
	],
)
data class CellCapturedFactCursorEntity(
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
		require(writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID)
		require(writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION)
		require(logicalFactId.isNotBlank() && logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L)
		require(writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(latestSemanticRevision > 0L && latestMutationId.isNotBlank())
		require(LOWERCASE_SHA_256.matches(latestEffectChecksum))
		require(latestSourceAdmissionOrdinal > 0L && cursorRevision > 0L && updatedAtMs >= 0L)
	}

	private companion object {
		val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/** Source-local generation fence for one exact logical-session/physical-run Cell scope. */
@Entity(
	tableName = "cell_capture_deletion_generation",
	primaryKeys = ["logical_tracking_id", "service_run_id"],
)
data class CellCaptureDeletionGenerationEntity(
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

/** Value-free local selected-entry deletion receipt retained after presentation removal. */
@Entity(
	tableName = "cell_captured_entry_deletion_receipt",
	primaryKeys = ["logical_tracking_id"],
	indices = [Index(
		value = ["entry_identity"],
		unique = true,
		name = "idx_cell_captured_entry_deletion_identity",
	)],
)
data class CellCapturedEntryDeletionReceiptEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "expected_run_count") val expectedRunCount: Int,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "run_footprint_set_checksum") val runFootprintSetChecksum: String,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(CELL_DELETION_SHA_256.matches(entryIdentity))
		require(collectedDataEpoch >= 0L && expectedRunCount in 1..MAX_RUNS)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs && deletedAtMs >= 0L)
		require(CELL_DELETION_SHA_256.matches(runFootprintSetChecksum))
		require(effectChecksum == checksum(
			logicalTrackingId,
			entryIdentity,
			collectedDataEpoch,
			expectedRunCount,
			startTimeMs,
			endTimeMs,
			runFootprintSetChecksum,
			deletedAtMs,
		))
	}

	companion object {
		const val MAX_RUNS = 128

		fun create(
			logicalTrackingId: String,
			entryIdentity: String,
			collectedDataEpoch: Long,
			runFootprints: List<CellCapturedDeletedRunEntity>,
			deletedAtMs: Long,
		): CellCapturedEntryDeletionReceiptEntity {
			require(runFootprints.isNotEmpty() && runFootprints.size <= MAX_RUNS)
			require(runFootprints.all {
				it.logicalTrackingId == logicalTrackingId &&
					it.collectedDataEpoch == collectedDataEpoch && it.deletedAtMs == deletedAtMs
			})
			val runChecksum = checksumRunFootprints(runFootprints)
			val start = runFootprints.minOf(CellCapturedDeletedRunEntity::startTimeMs)
			val end = runFootprints.maxOf(CellCapturedDeletedRunEntity::endTimeMs)
			return CellCapturedEntryDeletionReceiptEntity(
				logicalTrackingId,
				entryIdentity,
				collectedDataEpoch,
				runFootprints.size,
				start,
				end,
				runChecksum,
				deletedAtMs,
				checksum(
					logicalTrackingId,
					entryIdentity,
					collectedDataEpoch,
					runFootprints.size,
					start,
					end,
					runChecksum,
					deletedAtMs,
				),
			)
		}

		fun checksumRunFootprints(values: List<CellCapturedDeletedRunEntity>): String =
			cellDeletionDigest(
				"cell-captured-deleted-run-set-v1",
				*values.sortedWith(
					compareBy(CellCapturedDeletedRunEntity::startTimeMs)
						.thenBy(CellCapturedDeletedRunEntity::sessionSegmentId)
						.thenBy(CellCapturedDeletedRunEntity::serviceRunId),
				).flatMap { value ->
					listOf(
						value.logicalTrackingId,
						value.serviceRunId,
						value.sessionSegmentId,
						value.scopeIdentityDigest,
						value.startTimeMs,
						value.endTimeMs,
						value.collectedDataEpoch,
						value.generation,
						value.deletedAtMs,
						value.effectChecksum,
					)
				}.toTypedArray(),
			)

		private fun checksum(
			logicalTrackingId: String,
			entryIdentity: String,
			collectedDataEpoch: Long,
			expectedRunCount: Int,
			startTimeMs: Long,
			endTimeMs: Long,
			runFootprintSetChecksum: String,
			deletedAtMs: Long,
		): String = cellDeletionDigest(
			"cell-captured-entry-deletion-receipt-v1",
			logicalTrackingId,
			entryIdentity,
			collectedDataEpoch,
			expectedRunCount,
			startTimeMs,
			endTimeMs,
			runFootprintSetChecksum,
			deletedAtMs,
		)
	}
}

/** Exact local physical owner retained without payload or presentation values. */
@Entity(
	tableName = "cell_captured_deleted_run",
	primaryKeys = ["logical_tracking_id", "service_run_id"],
	foreignKeys = [ForeignKey(
		entity = CellCapturedEntryDeletionReceiptEntity::class,
		parentColumns = ["logical_tracking_id"],
		childColumns = ["logical_tracking_id"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["session_segment_id"],
			unique = true,
			name = "idx_cell_captured_deleted_run_segment",
		),
		Index(
			value = ["scope_identity_digest"],
			unique = true,
			name = "idx_cell_captured_deleted_run_scope",
		),
	],
)
data class CellCapturedDeletedRunEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long,
	@ColumnInfo(name = "scope_identity_digest") val scopeIdentityDigest: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L && CELL_DELETION_SHA_256.matches(scopeIdentityDigest))
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && generation == 1L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			logicalTrackingId,
			serviceRunId,
			sessionSegmentId,
			scopeIdentityDigest,
			startTimeMs,
			endTimeMs,
			collectedDataEpoch,
			generation,
			deletedAtMs,
		))
	}

	companion object {
		fun create(
			logicalTrackingId: String,
			serviceRunId: String,
			sessionSegmentId: Long,
			startTimeMs: Long,
			endTimeMs: Long,
			collectedDataEpoch: Long,
			deletedAtMs: Long,
		): CellCapturedDeletedRunEntity {
			val scope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId,
				serviceRunId,
			)
			return CellCapturedDeletedRunEntity(
				logicalTrackingId,
				serviceRunId,
				sessionSegmentId,
				scope,
				startTimeMs,
				endTimeMs,
				collectedDataEpoch,
				1L,
				deletedAtMs,
				checksum(
					logicalTrackingId,
					serviceRunId,
					sessionSegmentId,
					scope,
					startTimeMs,
					endTimeMs,
					collectedDataEpoch,
					1L,
					deletedAtMs,
				),
			)
		}

		private fun checksum(
			logicalTrackingId: String,
			serviceRunId: String,
			sessionSegmentId: Long,
			scopeIdentityDigest: String,
			startTimeMs: Long,
			endTimeMs: Long,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): String = cellDeletionDigest(
			"cell-captured-deleted-run-v1",
			logicalTrackingId,
			serviceRunId,
			sessionSegmentId,
			scopeIdentityDigest,
			startTimeMs,
			endTimeMs,
			collectedDataEpoch,
			generation,
			deletedAtMs,
		)
	}
}

private fun cellDeletionDigest(namespace: String, vararg values: Any?): String {
	val canonical = (listOf(namespace) + values.map { it?.toString() ?: "<null>" })
		.joinToString(separator = "") { "${it.length}:$it" }
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private val CELL_DELETION_SHA_256 = Regex("[0-9a-f]{64}")

/** Complete retained-effect digest and stable identities for Cell fact revisions. */
object CellCapturedFactRevisionIntegrity {
	fun logicalFactId(
		sourceDeliveryIdentity: String,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		manifestRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
	): String = digest(
		"cell-captured-fact-v1", sourceDeliveryIdentity, logicalTrackingId, serviceRunId,
		sessionSegmentId, manifestRevision, collectedDataEpoch, scopeDeletionGeneration,
	)

	fun mutationId(logicalFactId: String, semanticRevision: Long): String =
		digest("cell-captured-mutation-v1", logicalFactId, semanticRevision)

	fun effectChecksum(fact: CellCapturedFactRevisionEntity): String = digest(
		"cell-captured-effect-v1",
		*fact.effectParts().toTypedArray(),
	)

	fun hasValidEffectChecksum(fact: CellCapturedFactRevisionEntity): Boolean =
		fact.effectChecksum == effectChecksum(fact)

	private fun CellCapturedFactRevisionEntity.effectParts(): List<Any?> = listOf(
		writerProjectionId, writerProjectionVersion, writerBindingGeneration, writerOwnerGeneration,
		logicalFactId, semanticRevision, supersedesSemanticRevision, mutationId, factKind,
		aggregateOwnerLogicalFactId, aggregateOwnerSemanticRevision, logicalTrackingId, serviceRunId,
		sessionSegmentId, purpose, sourceDeliveryIdentity, sourceEventId, sourceAdmissionOrdinal,
		walIntegrityIdentity, payloadChecksum, deliveryUnitIndex, deliveryUnitCount, sourceSequence,
		planAttribution, payloadVersion, canonicalProviderSemanticsDigest, sourceInstanceId,
		registrationGeneration, configurationRevision, physicalConfigurationFingerprint,
		authorizationRevision, authorizationFingerprint, purposeEligibilityMask, sourcePolicyRevision,
		captureConsentEpoch, manifestRevision, lifecycleLeaseGeneration, collectedDataEpoch,
		scopeDeletionGeneration, clockDomainId, storedZoneId, structuralEpochDay,
		providerAcceptanceStartNanos, providerAcceptanceEndNanos, authorizationEffectStartNanos,
		authorizationEffectEndNanos, consentEffectStartNanos, consentEffectEndNanos,
		sessionRunEffectStartNanos, sessionRunEffectEndNanos, deletionEffectStartNanos,
		deletionEffectEndNanos, maximumObservationAgeNanos, observedIntervalStartNanos,
		observedElapsedNanos, receivedElapsedNanos, coverageIntervalStartNanos,
		coverageIntervalEndNanos, observedWallTimeMs, wallTimeUncertaintyMs,
		acquiredAtMs, createdAtMs, qualityFlags, qualityConfidence, availability, submittedChildCount,
		acceptedChildCount, staleChildCount, futureTimeChildCount, missingTimeChildCount,
		clockUnverifiableChildCount, authorityMismatchChildCount, unsupportedTechnologyChildCount,
		subscriptionCompleteness, childCompleteness, observationCount, registeredObservationCount,
		gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount, qualityUnknownCount,
		qualityNoneOrUnknownCount, qualityPoorCount, qualityModerateCount, qualityGoodCount,
		qualityGreatCount, weakObservationCount, knownQualityObservationCount, allKnownQualityIsWeak,
		appliedAtMs,
	)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

}
