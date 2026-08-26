package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.hasCompleteTerminalRetirement
import java.security.MessageDigest

enum class SessionMode { MANUAL, AUTOMATIC, LEGACY_UNKNOWN }

enum class SessionManifestPurpose { SESSION_CAPTURE, CONTROL }

enum class LifecycleDesiredState { ACTIVE, FINALIZED }

enum class LifecycleActionFamily { SOURCE_RUNTIME }

enum class LifecycleActionStatus {
	/** Durable provider intent whose owning Android service has not yet entered foreground. */
	AWAITING_FOREGROUND,
	PENDING,
	APPLYING,
	/** Attempt N still owns provider-side work and must be released before attempt N+1. */
	CLEANUP_REQUIRED,
	START_ACCEPTED,
	TEMPORARILY_ILLEGAL,
	TERMINAL_FAILURE,
	STOP_ACCEPTED,
	SUPERSEDED,
}

data class LifecycleLeaseToken(
	val leaseName: String,
	val ownerToken: String,
	val bootId: String,
	val generation: Long,
) {
	init {
		require(leaseName.isNotBlank())
		require(ownerToken.isNotBlank())
		require(bootId.isNotBlank())
		require(generation > 0L)
	}
}

internal data class PersistedLifecycleIntent(
	val manifest: SessionManifestVersionEntity,
	val bindings: List<SessionManifestSourceEntity>,
	val intent: SessionLifecycleIntentVersionEntity,
	val actions: List<LifecycleDesiredActionEntity>,
	val demands: List<SourceDemandEntity>,
	val foregroundCapabilityFlags: Long,
)

internal data class CaptureAuthorization(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val policyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val leaseGeneration: Long,
)

internal data class SourceActionExecution(
	val applied: AppliedSourcePlan,
	val status: LifecycleActionStatus,
	val failureCode: String? = null,
	val retryTrigger: String? = null,
	val stopAck: com.adsamcik.tracker.tracker.source.runtime.SourceStopAck? = null,
	val runtimeClaim: SourceRuntimeClaim? = null,
	val captureAuthorization: CaptureAuthorization? = null,
)

internal fun SessionStartOrigin.toSessionMode(): SessionMode = when (this) {
	SessionStartOrigin.MANUAL_FOREGROUND_START,
	SessionStartOrigin.RECOVERY,
	SessionStartOrigin.POLICY_RECONCILIATION,
	-> SessionMode.MANUAL
	SessionStartOrigin.AUTOMATIC_BACKGROUND_START -> SessionMode.AUTOMATIC
}

internal fun SourceStartResult.toExecution(): SourceActionExecution = when (this) {
	is SourceStartResult.Started -> SourceActionExecution(applied, LifecycleActionStatus.START_ACCEPTED)
	is SourceStartResult.Degraded -> SourceActionExecution(applied, LifecycleActionStatus.START_ACCEPTED)
	is SourceStartResult.Blocked -> SourceActionExecution(
		applied,
		LifecycleActionStatus.TERMINAL_FAILURE,
		"SOURCE_START_BLOCKED",
	)
	is SourceStartResult.Failed -> SourceActionExecution(
		applied,
		if (retryable) LifecycleActionStatus.TEMPORARILY_ILLEGAL else LifecycleActionStatus.TERMINAL_FAILURE,
		"SOURCE_START_FAILED",
		if (retryable) "RUNTIME_RETRY" else null,
	)
}

internal fun SourceApplyResult.toExecution(desiredStarted: Boolean): SourceActionExecution {
	val execution = when (this) {
		is SourceApplyResult.Applied -> SourceActionExecution(
			state,
			if (desiredStarted) LifecycleActionStatus.START_ACCEPTED else LifecycleActionStatus.STOP_ACCEPTED,
			stopAck = stopAck,
		)
		is SourceApplyResult.Degraded -> SourceActionExecution(
			state,
			LifecycleActionStatus.START_ACCEPTED,
			stopAck = stopAck,
		)
		is SourceApplyResult.RolledBack -> SourceActionExecution(
			state,
			LifecycleActionStatus.TERMINAL_FAILURE,
			"SOURCE_APPLY_ROLLED_BACK",
			stopAck = stopAck,
		)
		is SourceApplyResult.Failed -> SourceActionExecution(
			state,
			if (retryable) LifecycleActionStatus.TEMPORARILY_ILLEGAL else LifecycleActionStatus.TERMINAL_FAILURE,
			"SOURCE_APPLY_FAILED",
			if (retryable) "RUNTIME_RETRY" else null,
			stopAck,
		)
	}
	if (desiredStarted || execution.stopAck?.hasCompleteTerminalRetirement() == true) return execution
	return execution.copy(
		status = LifecycleActionStatus.CLEANUP_REQUIRED,
		failureCode = SOURCE_RUNTIME_CLEANUP_PENDING,
		retryTrigger = RUNTIME_CLEANUP_RETRY,
	)
}

internal fun stableLifecycleChecksum(vararg parts: Any?): String {
	val canonical = parts.joinToString(separator = "\u001f") { part ->
		when (part) {
			is Iterable<*> -> part.joinToString(separator = "\u001e") { it.toString() }
			else -> part?.toString().orEmpty()
		}
	}
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
