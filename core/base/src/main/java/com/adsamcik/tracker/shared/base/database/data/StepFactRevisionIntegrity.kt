package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Verifies the complete retained representation of a canonical LIVE_WAL Steps fact. */
object StepFactRevisionIntegrity {
	/** Computes the versioned digest over every retained LIVE_WAL fact field except the digest itself. */
	fun liveWalEffectChecksum(fact: StepFactRevisionEntity): String {
		require(fact.originKind == StepFactRevisionEntity.ORIGIN_LIVE_WAL)
		require(fact.operation == StepFactRevisionEntity.OPERATION_UPSERT)
		return digest(
			LIVE_WAL_RETAINED_EFFECT_VERSION,
			fact.logicalFactId,
			fact.semanticRevision,
			fact.mutationId,
			fact.stepIntervalId,
			fact.sourceEventId,
			fact.sourceAdmissionOrdinal,
			fact.originKind,
			fact.originIdentity,
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.writerBindingGeneration,
			fact.operation,
			fact.intervalStartTimeMs,
			fact.intervalEndTimeMs,
			fact.intervalStartElapsedRealtimeNanos,
			fact.intervalEndElapsedRealtimeNanos,
			fact.clockDomainId,
			fact.bootClockDomainId,
			fact.cumulativeStepCountStart,
			fact.cumulativeStepCountEnd,
			fact.wallTimeUncertaintyMs,
			fact.coverageKind,
			fact.effectiveStepCount,
			fact.logicalTrackingId,
			fact.serviceRunId,
			fact.purpose,
			fact.manifestRevision,
			fact.sourcePolicyRevision,
			fact.captureConsentEpoch,
			fact.collectedDataEpoch,
			fact.scopeDeletionGeneration,
			fact.appliedAtMs,
		)
	}

	/** Returns true only when the row is a canonical LIVE_WAL UPSERT with intact retained content. */
	fun hasValidLiveWalEffectChecksum(fact: StepFactRevisionEntity): Boolean {
		if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
		) {
			return false
		}
		return fact.effectChecksum == liveWalEffectChecksum(fact)
	}

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			when (text) {
				null -> "-1:"
				else -> "${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private const val LIVE_WAL_RETAINED_EFFECT_VERSION = "steps-live-wal-retained-effect-v1"
}
