package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/**
 * Durable import high-water for one accepted Ambient Steps provider registration generation.
 *
 * The cursor starts no earlier than the later provider-acceptance or authorization boundary,
 * rounded forward to the provider's whole-second read contract. Progress is scoped to one
 * continuity segment. A known discontinuity starts another segment instead of silently bridging
 * the interval. Provider absence by itself does not advance this row and therefore cannot become a
 * fabricated covered zero.
 */
@Entity(
	tableName = "ambient_steps_import_cursor",
	primaryKeys = ["registration_generation"],
	indices = [
		Index(
			value = ["provider", "status", "imported_through_time_ms"],
			name = "idx_ambient_steps_import_cursor_progress",
		),
		Index(
			value = ["collected_data_epoch", "status"],
			name = "idx_ambient_steps_import_cursor_epoch",
		),
	],
)
data class AmbientStepsImportCursorEntity(
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "provider") val provider: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_clock_domain_id") val registrationClockDomainId: String,
	@ColumnInfo(name = "registration_accepted_at_ms") val registrationAcceptedAtMs: Long,
	@ColumnInfo(name = "registration_accepted_elapsed_realtime_nanos")
	val registrationAcceptedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "authorization_effective_boot_id") val authorizationEffectiveBootId: String,
	@ColumnInfo(name = "authorization_effective_elapsed_realtime_nanos")
	val authorizationEffectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "authorization_effective_wall_time_ms")
	val authorizationEffectiveWallTimeMs: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "eligible_from_time_ms") val eligibleFromTimeMs: Long,
	@ColumnInfo(name = "continuity_segment_generation") val continuitySegmentGeneration: Long,
	@ColumnInfo(name = "segment_start_time_ms") val segmentStartTimeMs: Long,
	@ColumnInfo(name = "imported_through_time_ms") val importedThroughTimeMs: Long,
	@ColumnInfo(name = "last_observed_at_ms") val lastObservedAtMs: Long,
	@ColumnInfo(name = "last_observed_boot_id") val lastObservedBootId: String,
	@ColumnInfo(name = "last_observed_zone_id") val lastObservedZoneId: String,
	@ColumnInfo(name = "last_gap_sequence") val lastGapSequence: Long,
	@ColumnInfo(name = "cursor_revision") val cursorRevision: Long,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(registrationGeneration > 0L)
		require(provider in PROVIDERS)
		require(sourceInstanceId.isNotBlank())
		require(registrationClockDomainId.isNotBlank())
		require(registrationAcceptedAtMs >= 0L)
		require(registrationAcceptedElapsedRealtimeNanos >= 0L)
		require(authorizationRevision > 0L)
		require(AUTHORIZATION_FINGERPRINT.matches(authorizationFingerprint))
		require(authorizationEffectiveBootId == registrationClockDomainId)
		require(authorizationEffectiveElapsedRealtimeNanos >= 0L)
		require(authorizationEffectiveWallTimeMs >= 0L)
		require(sourcePolicyRevision > 0L)
		require(ambientConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(
			eligibleFromTimeMs == privacyFloorTimeMs(
				registrationAcceptedAtMs,
				authorizationEffectiveWallTimeMs,
			),
		) { "Ambient Steps cursor must start at the rounded-forward privacy boundary" }
		require(continuitySegmentGeneration > 0L)
		require(lastGapSequence >= 0L)
		require(continuitySegmentGeneration == lastGapSequence + 1L)
		require(segmentStartTimeMs >= eligibleFromTimeMs)
		require(segmentStartTimeMs % MILLIS_PER_SECOND == 0L)
		require(importedThroughTimeMs >= segmentStartTimeMs)
		require(importedThroughTimeMs % MILLIS_PER_SECOND == 0L)
		require(lastObservedAtMs >= importedThroughTimeMs)
		require(lastObservedBootId == registrationClockDomainId)
		requireValidZone(lastObservedZoneId)
		require(cursorRevision > 0L)
		require(status in STATUSES)
		require(updatedAtMs >= lastObservedAtMs)
	}

	companion object {
		const val STATUS_ACTIVE = "ACTIVE"
		const val STATUS_RETIRED = "RETIRED"
		const val PROVIDER_HEALTH_CONNECT_MOBILE_STEPS =
			AmbientStepsFactRevisionEntity.PROVIDER_HEALTH_CONNECT_MOBILE_STEPS
		const val PROVIDER_LOCAL_RECORDING_STEPS =
			AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS

		private val PROVIDERS = setOf(
			PROVIDER_HEALTH_CONNECT_MOBILE_STEPS,
			PROVIDER_LOCAL_RECORDING_STEPS,
		)
		private val STATUSES = setOf(STATUS_ACTIVE, STATUS_RETIRED)
		private val AUTHORIZATION_FINGERPRINT = Regex("[0-9a-f]{64}")
		private const val MILLIS_PER_SECOND = 1_000L

		/** Smallest whole-second provider boundary that cannot include pre-authorization data. */
		fun privacyFloorTimeMs(
			registrationAcceptedAtMs: Long,
			authorizationEffectiveWallTimeMs: Long,
		): Long {
			require(registrationAcceptedAtMs >= 0L)
			require(authorizationEffectiveWallTimeMs >= 0L)
			val boundary = maxOf(registrationAcceptedAtMs, authorizationEffectiveWallTimeMs)
			val remainder = boundary % MILLIS_PER_SECOND
			return if (remainder == 0L) boundary else {
				Math.addExact(boundary, MILLIS_PER_SECOND - remainder)
			}
		}
	}
}

/**
 * Immutable, exact gap between two affirmative Ambient Steps continuity segments.
 *
 * Only closed, second-aligned bounds are stored. While an interval is still unknown, the cursor
 * simply remains in place. This prevents a temporary provider miss from being promoted to either
 * covered zero or an invented open-ended gap.
 */
@Entity(
	tableName = "ambient_steps_import_gap",
	primaryKeys = ["registration_generation", "gap_sequence"],
	indices = [
		Index(value = ["gap_id"], unique = true, name = "idx_ambient_steps_import_gap_id"),
		Index(
			value = ["gap_start_time_ms", "gap_end_time_ms"],
			name = "idx_ambient_steps_import_gap_window",
		),
	],
)
data class AmbientStepsImportGapEntity(
	@ColumnInfo(name = "gap_id") val gapId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "gap_sequence") val gapSequence: Long,
	@ColumnInfo(name = "provider") val provider: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "gap_start_time_ms") val gapStartTimeMs: Long,
	@ColumnInfo(name = "gap_end_time_ms") val gapEndTimeMs: Long,
	@ColumnInfo(name = "predecessor_registration_generation")
	val predecessorRegistrationGeneration: Long?,
	@ColumnInfo(name = "predecessor_provider") val predecessorProvider: String?,
	@ColumnInfo(name = "previous_clock_domain_id") val previousClockDomainId: String,
	@ColumnInfo(name = "next_clock_domain_id") val nextClockDomainId: String,
	@ColumnInfo(name = "previous_zone_id") val previousZoneId: String,
	@ColumnInfo(name = "next_zone_id") val nextZoneId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
) {
	init {
		require(AmbientStepsImportGapIntegrity.isOpaque(gapId))
		require(registrationGeneration > 0L)
		require(gapSequence > 0L)
		require(provider in PROVIDERS)
		require(sourceInstanceId.isNotBlank())
		require(reason in REASONS)
		require(gapStartTimeMs >= 0L)
		require(gapEndTimeMs >= gapStartTimeMs)
		require(gapStartTimeMs % MILLIS_PER_SECOND == 0L)
		require(gapEndTimeMs % MILLIS_PER_SECOND == 0L)
		require(
			predecessorRegistrationGeneration == null ||
				(predecessorRegistrationGeneration > 0L &&
					predecessorRegistrationGeneration != registrationGeneration),
		)
		require(
			(predecessorRegistrationGeneration == null) == (predecessorProvider == null),
		)
		require(predecessorProvider == null || predecessorProvider in PROVIDERS)
		require(previousClockDomainId.isNotBlank())
		require(nextClockDomainId.isNotBlank())
		requireValidZone(previousZoneId)
		requireValidZone(nextZoneId)
		require(collectedDataEpoch >= 0L)
		require(recordedAtMs >= gapEndTimeMs)
		require(
			gapId == AmbientStepsImportGapIntegrity.gapId(
				registrationGeneration = registrationGeneration,
				gapSequence = gapSequence,
				provider = provider,
				sourceInstanceId = sourceInstanceId,
				reason = reason,
				gapStartTimeMs = gapStartTimeMs,
				gapEndTimeMs = gapEndTimeMs,
				predecessorRegistrationGeneration = predecessorRegistrationGeneration,
				predecessorProvider = predecessorProvider,
				previousClockDomainId = previousClockDomainId,
				nextClockDomainId = nextClockDomainId,
				previousZoneId = previousZoneId,
				nextZoneId = nextZoneId,
				collectedDataEpoch = collectedDataEpoch,
			),
		) { "Ambient Steps gap identity does not match its immutable authority" }

		when (reason) {
			REASON_PROVIDER_NO_EVIDENCE,
			REASON_PROVIDER_RETENTION_LOSS,
			REASON_PROCESS_ABSENCE,
			-> require(gapEndTimeMs > gapStartTimeMs)
			REASON_BOOT_CHANGED,
			REASON_CLOCK_DISCONTINUITY,
			-> {
				require(predecessorRegistrationGeneration != null)
				require(previousClockDomainId != nextClockDomainId)
			}
			REASON_ZONE_CHANGED -> require(previousZoneId != nextZoneId)
			REASON_PROVIDER_CHANGED -> {
				require(predecessorRegistrationGeneration != null)
				require(predecessorProvider != null && predecessorProvider != provider)
			}
		}
	}

	companion object {
		const val REASON_PROVIDER_NO_EVIDENCE = "PROVIDER_NO_EVIDENCE"
		const val REASON_PROVIDER_RETENTION_LOSS = "PROVIDER_RETENTION_LOSS"
		const val REASON_PROCESS_ABSENCE = "PROCESS_ABSENCE"
		const val REASON_BOOT_CHANGED = "BOOT_CHANGED"
		const val REASON_ZONE_CHANGED = "ZONE_CHANGED"
		const val REASON_PROVIDER_CHANGED = "PROVIDER_CHANGED"
		const val REASON_CLOCK_DISCONTINUITY = "CLOCK_DISCONTINUITY"

		private val PROVIDERS = setOf(
			AmbientStepsImportCursorEntity.PROVIDER_HEALTH_CONNECT_MOBILE_STEPS,
			AmbientStepsImportCursorEntity.PROVIDER_LOCAL_RECORDING_STEPS,
		)
		private val REASONS = setOf(
			REASON_PROVIDER_NO_EVIDENCE,
			REASON_PROVIDER_RETENTION_LOSS,
			REASON_PROCESS_ABSENCE,
			REASON_BOOT_CHANGED,
			REASON_ZONE_CHANGED,
			REASON_PROVIDER_CHANGED,
			REASON_CLOCK_DISCONTINUITY,
		)
		private const val MILLIS_PER_SECOND = 1_000L
	}
}

/** Stable retry identity for one exact Ambient Steps discontinuity. */
object AmbientStepsImportGapIntegrity {
	fun gapId(
		registrationGeneration: Long,
		gapSequence: Long,
		provider: String,
		sourceInstanceId: String,
		reason: String,
		gapStartTimeMs: Long,
		gapEndTimeMs: Long,
		predecessorRegistrationGeneration: Long?,
		predecessorProvider: String?,
		previousClockDomainId: String,
		nextClockDomainId: String,
		previousZoneId: String,
		nextZoneId: String,
		collectedDataEpoch: Long,
	): String = "sha256:${digest(
		"ambient-steps-import-gap-v1",
		registrationGeneration,
		gapSequence,
		provider,
		sourceInstanceId,
		reason,
		gapStartTimeMs,
		gapEndTimeMs,
		predecessorRegistrationGeneration,
		predecessorProvider,
		previousClockDomainId,
		nextClockDomainId,
		previousZoneId,
		nextZoneId,
		collectedDataEpoch,
	)}"

	internal fun isOpaque(value: String): Boolean = OPAQUE_IDENTITY.matches(value)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private val OPAQUE_IDENTITY = Regex("sha256:[0-9a-f]{64}")
}

private fun requireValidZone(zoneId: String) {
	require(zoneId.isNotBlank() && zoneId.length <= 128)
	require(runCatching { ZoneId.of(zoneId) }.isSuccess) { "Invalid Ambient Steps zone authority" }
}
