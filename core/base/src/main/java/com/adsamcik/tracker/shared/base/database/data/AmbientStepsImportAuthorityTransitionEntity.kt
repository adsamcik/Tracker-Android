package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/**
 * Immutable non-gap split between two legal authorizations of one physical registration.
 *
 * The old segment must be drained exactly through [effectiveBoundaryTimeMs]. The cursor then starts
 * [toContinuitySegmentGeneration] at the same rounded-forward boundary, so policy or consent
 * rotation cannot relabel old observations or invent a missing interval.
 */
@Entity(
	tableName = "ambient_steps_import_authority_transition",
	primaryKeys = ["registration_generation", "transition_sequence"],
	indices = [
		Index(
			value = ["transition_id"],
			unique = true,
			name = "idx_ambient_steps_import_authority_transition_id",
		),
		Index(
			value = ["effective_boundary_time_ms"],
			name = "idx_ambient_steps_import_authority_transition_boundary",
		),
	],
)
data class AmbientStepsImportAuthorityTransitionEntity(
	@ColumnInfo(name = "transition_id") val transitionId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "transition_sequence") val transitionSequence: Long,
	@ColumnInfo(name = "provider") val provider: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "from_continuity_segment_generation")
	val fromContinuitySegmentGeneration: Long,
	@ColumnInfo(name = "to_continuity_segment_generation") val toContinuitySegmentGeneration: Long,
	@ColumnInfo(name = "from_authorization_revision") val fromAuthorizationRevision: Long,
	@ColumnInfo(name = "from_authorization_fingerprint") val fromAuthorizationFingerprint: String,
	@ColumnInfo(name = "from_source_policy_revision") val fromSourcePolicyRevision: Long,
	@ColumnInfo(name = "from_ambient_consent_epoch") val fromAmbientConsentEpoch: Long,
	@ColumnInfo(name = "to_authorization_revision") val toAuthorizationRevision: Long,
	@ColumnInfo(name = "to_authorization_fingerprint") val toAuthorizationFingerprint: String,
	@ColumnInfo(name = "to_authorization_effective_boot_id")
	val toAuthorizationEffectiveBootId: String,
	@ColumnInfo(name = "to_authorization_effective_elapsed_realtime_nanos")
	val toAuthorizationEffectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "to_authorization_effective_wall_time_ms")
	val toAuthorizationEffectiveWallTimeMs: Long,
	@ColumnInfo(name = "to_source_policy_revision") val toSourcePolicyRevision: Long,
	@ColumnInfo(name = "to_ambient_consent_epoch") val toAmbientConsentEpoch: Long,
	@ColumnInfo(name = "registration_accepted_at_ms") val registrationAcceptedAtMs: Long,
	@ColumnInfo(name = "effective_boundary_time_ms") val effectiveBoundaryTimeMs: Long,
	@ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
) {
	init {
		require(
			transitionId == AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
				registrationGeneration,
				transitionSequence,
				provider,
				sourceInstanceId,
				collectedDataEpoch,
				fromContinuitySegmentGeneration,
				toContinuitySegmentGeneration,
				fromAuthorizationRevision,
				fromAuthorizationFingerprint,
				fromSourcePolicyRevision,
				fromAmbientConsentEpoch,
				toAuthorizationRevision,
				toAuthorizationFingerprint,
				toAuthorizationEffectiveBootId,
				toAuthorizationEffectiveElapsedRealtimeNanos,
				toAuthorizationEffectiveWallTimeMs,
				toSourcePolicyRevision,
				toAmbientConsentEpoch,
				registrationAcceptedAtMs,
				effectiveBoundaryTimeMs,
			),
		)
		require(registrationGeneration > 0L)
		require(transitionSequence > 0L)
		require(provider in PROVIDERS)
		require(sourceInstanceId.isNotBlank())
		require(collectedDataEpoch >= 0L)
		require(fromContinuitySegmentGeneration > 0L)
		require(
			toContinuitySegmentGeneration == Math.addExact(fromContinuitySegmentGeneration, 1L),
		)
		require(fromAuthorizationRevision > 0L)
		require(toAuthorizationRevision > fromAuthorizationRevision)
		require(FINGERPRINT.matches(fromAuthorizationFingerprint))
		require(FINGERPRINT.matches(toAuthorizationFingerprint))
		require(
			fromAuthorizationFingerprint != toAuthorizationFingerprint ||
				fromSourcePolicyRevision != toSourcePolicyRevision ||
				fromAmbientConsentEpoch != toAmbientConsentEpoch,
		)
		require(fromSourcePolicyRevision > 0L)
		require(toSourcePolicyRevision > 0L)
		require(fromAmbientConsentEpoch >= 0L)
		require(toAmbientConsentEpoch >= 0L)
		require(toAuthorizationEffectiveBootId.isNotBlank())
		require(toAuthorizationEffectiveElapsedRealtimeNanos >= 0L)
		require(toAuthorizationEffectiveWallTimeMs >= 0L)
		require(registrationAcceptedAtMs >= 0L)
		require(
			effectiveBoundaryTimeMs == AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				registrationAcceptedAtMs,
				toAuthorizationEffectiveWallTimeMs,
			),
		)
		require(recordedAtMs >= effectiveBoundaryTimeMs)
	}

	private companion object {
		val PROVIDERS = setOf(
			AmbientStepsImportCursorEntity.PROVIDER_HEALTH_CONNECT_MOBILE_STEPS,
			AmbientStepsImportCursorEntity.PROVIDER_LOCAL_RECORDING_STEPS,
		)
		val FINGERPRINT = Regex("[0-9a-f]{64}")
	}
}

object AmbientStepsImportAuthorityTransitionIntegrity {
	fun transitionId(
		registrationGeneration: Long,
		transitionSequence: Long,
		provider: String,
		sourceInstanceId: String,
		collectedDataEpoch: Long,
		fromContinuitySegmentGeneration: Long,
		toContinuitySegmentGeneration: Long,
		fromAuthorizationRevision: Long,
		fromAuthorizationFingerprint: String,
		fromSourcePolicyRevision: Long,
		fromAmbientConsentEpoch: Long,
		toAuthorizationRevision: Long,
		toAuthorizationFingerprint: String,
		toAuthorizationEffectiveBootId: String,
		toAuthorizationEffectiveElapsedRealtimeNanos: Long,
		toAuthorizationEffectiveWallTimeMs: Long,
		toSourcePolicyRevision: Long,
		toAmbientConsentEpoch: Long,
		registrationAcceptedAtMs: Long,
		effectiveBoundaryTimeMs: Long,
	): String = "sha256:${digest(
		"ambient-steps-authority-transition-v2",
		registrationGeneration,
		transitionSequence,
		provider,
		sourceInstanceId,
		collectedDataEpoch,
		fromContinuitySegmentGeneration,
		toContinuitySegmentGeneration,
		fromAuthorizationRevision,
		fromAuthorizationFingerprint,
		fromSourcePolicyRevision,
		fromAmbientConsentEpoch,
		toAuthorizationRevision,
		toAuthorizationFingerprint,
		toAuthorizationEffectiveBootId,
		toAuthorizationEffectiveElapsedRealtimeNanos,
		toAuthorizationEffectiveWallTimeMs,
		toSourcePolicyRevision,
		toAmbientConsentEpoch,
		registrationAcceptedAtMs,
		effectiveBoundaryTimeMs,
	)}"

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
