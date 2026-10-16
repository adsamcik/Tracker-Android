package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalIdentityFreeAggregate
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

// Keep this fixed cursor identity tuple together so every required field stays visible.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
internal fun CellCapturedFactCursorEntity.matchesCurrentCapturedCellRevision(
	revision: CellCapturedFactRevisionEntity,
): Boolean = logicalFactId == revision.logicalFactId &&
	logicalTrackingId == revision.logicalTrackingId &&
	serviceRunId == revision.serviceRunId &&
	sessionSegmentId == revision.sessionSegmentId &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	collectedDataEpoch == revision.collectedDataEpoch &&
	scopeDeletionGeneration == revision.scopeDeletionGeneration &&
	latestSemanticRevision == revision.semanticRevision &&
	latestMutationId == revision.mutationId &&
	latestEffectChecksum == revision.effectChecksum &&
	latestSourceAdmissionOrdinal == revision.sourceAdmissionOrdinal &&
	cursorRevision == revision.semanticRevision &&
	updatedAtMs == revision.appliedAtMs

internal fun CellCapturedFactRevisionEntity.hasExactCapturedCellRevisionIdentity(): Boolean =
	logicalFactId == CellCapturedFactRevisionIntegrity.logicalFactId(
		sourceDeliveryIdentity, logicalTrackingId, serviceRunId, sessionSegmentId,
		manifestRevision, collectedDataEpoch, scopeDeletionGeneration,
	) && mutationId == CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision)

internal fun SessionManifestSourceEntity.isExactCapturedCellBinding(
	revision: CellCapturedFactRevisionEntity,
	sessionMode: String,
): Boolean = writerOwnerGeneration == revision.writerOwnerGeneration &&
	writerBindingGeneration == revision.writerBindingGeneration &&
	isExactCapturedCellWalBinding(revision.captureConsentEpoch, sessionMode)

internal fun SessionManifestSourceEntity.isExactCapturedCellWalBinding(
	captureConsentEpoch: Long,
	sessionMode: String,
): Boolean = CellMaintenanceBindingChecks.matches(this, captureConsentEpoch, sessionMode)

/** The original purpose, destination and supported capture writer remain one immutable binding. */
private object CellMaintenanceBindingChecks {
	fun matches(
		binding: SessionManifestSourceEntity,
		captureConsentEpoch: Long,
		sessionMode: String,
	): Boolean =
		headerMatches(binding, captureConsentEpoch) && destinationMatches(binding) &&
			writerMatches(binding) && captureModeMatches(binding, sessionMode)

	private fun headerMatches(
		binding: SessionManifestSourceEntity,
		captureConsentEpoch: Long,
	): Boolean =
		binding.sourceKind == CELL_SOURCE && binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			binding.persistenceEligible && binding.consentEpoch == captureConsentEpoch

	private fun destinationMatches(
		binding: SessionManifestSourceEntity,
	): Boolean =
		binding.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
			binding.writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS

	private fun writerMatches(
		binding: SessionManifestSourceEntity,
	): Boolean =
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(binding.writerBindingGeneration ?: -1L) != null &&
			binding.writerOwnerGeneration == binding.writerBindingGeneration
				?.let(SourceWriterGenerationContract::canonicalOwnerGeneration) &&
			binding.writerProjectionId == WRITER_ID && binding.writerProjectionVersion == WRITER_VERSION

	private fun captureModeMatches(
		binding: SessionManifestSourceEntity,
		sessionMode: String,
	): Boolean =
		sessionMode == "MANUAL" || sessionMode == "AUTOMATIC" &&
			binding.writerBindingGeneration == SourceWriterGenerationContract.FINAL_CAPTURE_BINDING_GENERATION
}

internal fun capturedCellEarliestCoveredWallTime(
	walObservedWallTimeMs: Long,
	uncertaintyMs: Long,
	walObservedElapsedNanos: Long,
	coverageStartNanos: Long,
): Long = CanonicalRetentionBounds.cellCoverage(
	walObservedWallTimeMs = walObservedWallTimeMs,
	wallTimeUncertaintyMs = uncertaintyMs,
	walObservedElapsedRealtimeNanos = walObservedElapsedNanos,
	coverageStartElapsedRealtimeNanos = coverageStartNanos,
) ?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

internal fun capturedCellPointWallInterval(wal: SourceEventWalEntity,
	selectedStartNanos: Long = wal.observedElapsedNanos,
	selectedEndNanos: Long = wal.observedElapsedNanos,
): LongRange? {
	val wallTimeMs = wal.wallTimeMs ?: return null
	val uncertaintyMs = wal.wallTimeUncertaintyMs ?: return null
	return runCatching {
		val earliest = CanonicalRetentionBounds.cellCoverage(
			walObservedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = uncertaintyMs,
			walObservedElapsedRealtimeNanos = wal.observedElapsedNanos,
			coverageStartElapsedRealtimeNanos = selectedStartNanos,
		) ?: return null
		val latestWall = Math.subtractExact(wallTimeMs, (wal.observedElapsedNanos - selectedEndNanos) / 1_000_000L)
		val rounding = if ((wal.observedElapsedNanos - selectedEndNanos) % 1_000_000L == 0L) {
			0L
		} else {
			1L
		}
		val latest = Math.addExact(latestWall, Math.addExact(uncertaintyMs, rounding))
		earliest..latest
	}.getOrNull()
}

internal fun CellWalIdentityFreeAggregate.toCapturedCellHistoricalAggregate() =
	CellHistoricalIdentityFreeAggregate(
		observationCount = observationCount,
		registeredObservationCount = registeredObservationCount,
		gsmCount = gsmCount,
		cdmaCount = cdmaCount,
		wcdmaCount = wcdmaCount,
		tdscdmaCount = tdscdmaCount,
		lteCount = lteCount,
		nrCount = nrCount,
		qualityUnknownCount = qualityUnknownCount,
		qualityNoneOrUnknownCount = qualityNoneOrUnknownCount,
		qualityPoorCount = qualityPoorCount,
		qualityModerateCount = qualityModerateCount,
		qualityGoodCount = qualityGoodCount,
		qualityGreatCount = qualityGreatCount,
		weakObservationCount = weakObservationCount,
		knownQualityObservationCount = knownQualityObservationCount,
		allKnownQualityIsWeak = allKnownQualityIsWeak,
	)

// Keep the complete saved session status/nullability table visible; no current lifecycle decisions.
@Suppress("CyclomaticComplexMethod")
internal fun LogicalTrackingSessionEntity.hasValidCapturedCellHistoricalShape(): Boolean {
	val restored = state == "FAILED" && failureCode == "RESTORED_INTERRUPTED_SESSION" && completedAtMs == null
	val terminal = state in TERMINAL_SESSION_STATES && !restored
	val nonterminal = state in NONTERMINAL_SESSION_STATES || restored
	if (!terminal && !nonterminal) {
		return false
	}
	// Required saved session fields and paired cutoff nullability form one record shape.
	@Suppress("ComplexCondition")
	if (logicalTrackingId.isBlank() || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		rolloutRevision <= 0L || startOrigin.isBlank() || clockDomainId.isBlank() ||
		lifecycleBootId.isNullOrBlank() || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) {
		return false
	}
	return if (terminal) {
		completedAtMs != null && cutoffAtMs != null && cutoffElapsedNanos != null &&
			completedAtMs >= cutoffAtMs && finalAdmissionOrdinal != null && finalAdmissionOrdinal >= 0L &&
			currentServiceRunId == null
	} else {
		completedAtMs == null && finalAdmissionOrdinal == null &&
			(restored || (state == SESSION_STATE_STOPPING) == (cutoffAtMs != null)) &&
			currentServiceRunId?.isNotBlank() == true
	}
}

// One saved run/session identity and terminal-field table; retain the ordered rejection exits.
@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
internal fun SourceServiceRunEntity.hasValidCapturedCellHistoricalShape(
	session: LogicalTrackingSessionEntity,
): Boolean {
	val restored = state == "FAILED" && completionReason == "RESTORED_INTERRUPTED_SESSION" &&
		session.failureCode == "RESTORED_INTERRUPTED_SESSION" && completedAtMs == null
	val terminal = state in TERMINAL_SESSION_STATES && !restored
	val nonterminal = state in NONTERMINAL_RUN_STATES || restored
	if (!terminal && !nonterminal) {
		return false
	}
	if (serviceRunId.isBlank() || logicalTrackingId != session.logicalTrackingId ||
		bootId.isBlank() ||
		(session.currentServiceRunId == serviceRunId &&
			(leaseGeneration != session.lifecycleLeaseGeneration || bootId != session.lifecycleBootId)) ||
		leaseGeneration <= 0L || leaseGeneration > session.lifecycleLeaseGeneration ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || startOrigin.isBlank() || sessionSegmentId == null ||
		preparedManifestRevision <= 0L || preparedIntentRevision <= 0L
	) {
		return false
	}
	if (terminal != (completedAtMs != null) || !restored && terminal != !completionReason.isNullOrBlank()) {
		return false
	}
	if (session.state in TERMINAL_SESSION_STATES) {
		return terminal || restored
	}
	if (terminal) {
		return session.currentServiceRunId != serviceRunId
	}
	if (session.currentServiceRunId != serviceRunId) {
		return false
	}
	return (session.state in ACTIVE_ADMISSION_SESSION_STATES && state in ACTIVE_ADMISSION_RUN_STATES) ||
		(session.state == SESSION_STATE_ACTIVE && state == SESSION_STATE_STOPPING) ||
		(session.state == SESSION_STATE_STOPPING && state == SESSION_STATE_STOPPING)
}

internal fun decodeCanonicalCellPlan(bytes: ByteArray): CellHistoricalPlan? = runCatching {
	val plan = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
		require(input.readInt() == CELL_PLAN_FORMAT_VERSION)
		require(input.readUTF() == CELL_PLAN_SOURCE_NAME)
		val revision = input.readLong()
		val mode = input.readUTF()
		val minimumRefreshAttemptIntervalMs = input.readLong()
		val maximumAcceptableCachedAgeMs = input.readLong()
		val count = input.readInt()
		require(count in 0..MAX_CELL_PLAN_SUBSCRIPTIONS)
		val subscriptionIds = buildSet(count) { repeat(count) { add(input.readInt()) } }
		require(subscriptionIds.size == count)
		val backoffInitialDelayMs = input.readLong()
		val backoffMaximumDelayMs = input.readLong()
		val backoffMultiplier = input.readDouble()
		require(input.available() == 0)
		CellHistoricalPlan(
			revision = revision,
			mode = mode,
			minimumRefreshAttemptIntervalMs = minimumRefreshAttemptIntervalMs,
			maximumAcceptableCachedAgeMs = maximumAcceptableCachedAgeMs,
			subscriptionIds = subscriptionIds,
			backoffInitialDelayMs = backoffInitialDelayMs,
			backoffMaximumDelayMs = backoffMaximumDelayMs,
			backoffMultiplier = backoffMultiplier,
		)
	}
	val canonical = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(CELL_PLAN_FORMAT_VERSION)
			output.writeUTF(CELL_PLAN_SOURCE_NAME)
			output.writeLong(plan.revision)
			output.writeUTF(plan.mode)
			output.writeLong(plan.minimumRefreshAttemptIntervalMs)
			output.writeLong(plan.maximumAcceptableCachedAgeMs)
			output.writeInt(plan.subscriptionIds.size)
			plan.subscriptionIds.sorted().forEach(output::writeInt)
			output.writeLong(plan.backoffInitialDelayMs)
			output.writeLong(plan.backoffMaximumDelayMs)
			output.writeDouble(plan.backoffMultiplier)
		}
		buffer.toByteArray()
	}
	require(canonical.contentEquals(bytes))
	plan
}.getOrNull()

internal fun Long.isExactCapturedCellSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

internal fun hasValidCapturedCellZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

internal fun capturedCellSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

internal data class CellHistoricalPlan(
	val revision: Long,
	val mode: String,
	val minimumRefreshAttemptIntervalMs: Long,
	val maximumAcceptableCachedAgeMs: Long,
	val subscriptionIds: Set<Int>,
	val backoffInitialDelayMs: Long,
	val backoffMaximumDelayMs: Long,
	val backoffMultiplier: Double,
) {
	fun hasSupportedHistoricalShape(): Boolean =
		mode in CELL_CAPTURE_MODES && minimumRefreshAttemptIntervalMs >= 0L &&
			maximumAcceptableCachedAgeMs >= 0L && subscriptionIds.size <= MAX_CELL_PLAN_SUBSCRIPTIONS &&
			subscriptionIds.all { it >= 0 } && backoffInitialDelayMs >= 0L &&
			backoffMaximumDelayMs >= backoffInitialDelayMs && backoffMultiplier.isFinite() &&
			backoffMultiplier >= 1.0

	fun maximumObservationAgeNanos(): Long =
		if (maximumAcceptableCachedAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
			Long.MAX_VALUE
		} else {
			maximumAcceptableCachedAgeMs * NANOS_PER_MILLISECOND
		}

	fun physicalConfigurationFingerprint(): String {
		val components = when (mode) {
			CELL_MODE_OBSERVE_CHANGES -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				subscriptionIds.sorted().joinToString(","),
			)
			CELL_MODE_OBSERVE_AND_SPARSE_REFRESH -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				"EXPLICIT_REFRESH",
				subscriptionIds.sorted().joinToString(","),
				minimumRefreshAttemptIntervalMs,
				backoffInitialDelayMs,
				backoffMaximumDelayMs,
				backoffMultiplier,
			)
			else -> return ""
		}.joinToString("\u001f")
		return capturedCellSha256(components.toByteArray(Charsets.UTF_8))
	}
}

internal fun Long.isKnownCapturedWriterOwnerGeneration(): Boolean =
	SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(this)
		?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding) != null


private const val NANOS_PER_MILLISECOND = 1_000_000L

private const val CELL_PLAN_FORMAT_VERSION = 1

private const val CELL_PLAN_SOURCE_NAME = "CELL"

private const val CELL_MODE_OBSERVE_CHANGES = "OBSERVE_CHANGES"

private const val CELL_MODE_OBSERVE_AND_SPARSE_REFRESH = "OBSERVE_AND_SPARSE_REFRESH"

private const val MAX_CELL_PLAN_SUBSCRIPTIONS = 10_000

private val CELL_CAPTURE_MODES = setOf(
	CELL_MODE_OBSERVE_CHANGES,
	CELL_MODE_OBSERVE_AND_SPARSE_REFRESH,
)

private const val SESSION_STATE_ACTIVE = "ACTIVE"

private const val SESSION_STATE_STOPPING = "STOPPING"

private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED")

private val NONTERMINAL_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")

private val NONTERMINAL_RUN_STATES = setOf("STARTING", "ACTIVE", "STOPPING")

private val ACTIVE_ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")

private val ACTIVE_ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")

internal data class CellHistoricalIdentityFreeAggregate(
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
)

