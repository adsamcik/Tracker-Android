package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.RawSessionManifestVersion
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class StepsCountDomainWriteResult {
	INSERTED,
	EXACT_REPLAY,
	SCHEMA_UNAVAILABLE,
	STORED_EVIDENCE_UNVERIFIABLE,
	NOT_APPLICABLE,
	AUTHORITY_PENDING,
	UNPROVEN,
	IDENTITY_CONFLICT,
	REVISION_GAP,
	TERMINAL_OWNER,
}

data class StepsCountDomainOwnerLookupKey(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
) {
	init {
		require(ownerKind in StepsCountDomainOwnerRevisionEntity.BINDABLE_OWNER_KINDS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
	}
}

data class StepsCountDomainOwnerLineageKey(
	val ownerKind: String,
	val ownerIdentity: String,
)

private data class CountDomainOwnerScopeKey(
	val ownerKind: String,
	val scopeIdentity: String,
	val ownerIdentity: String,
)

private data class CountDomainMaintenanceOwnerRow(
	val cursor: CountDomainMaintenanceOwnerCursor,
	val owner: StepsCountDomainOwnerRevisionEntity,
)

private data class CountDomainMaintenanceOwnerCursor(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
) : Comparable<CountDomainMaintenanceOwnerCursor> {
	override fun compareTo(other: CountDomainMaintenanceOwnerCursor): Int =
		compareValuesBy(
			this,
			other,
			CountDomainMaintenanceOwnerCursor::ownerKind,
			CountDomainMaintenanceOwnerCursor::ownerIdentity,
			CountDomainMaintenanceOwnerCursor::ownerRevision,
		)
}

private data class CountDomainMaintenanceReceiptRow(
	val rowId: Long,
	val receipt: StepsCountDomainReceiptEntity,
)

private data class CountDomainMaintenanceMarkerRow(
	val rowId: Long,
	val marker: StepsCountDomainCompletenessMarkerEntity,
)

private data class StepsWalPruneCursor(
	val createdAtMs: Long,
	val admissionOrdinal: Long,
) : Comparable<StepsWalPruneCursor> {
	override fun compareTo(other: StepsWalPruneCursor): Int =
		compareValuesBy(
			this,
			other,
			StepsWalPruneCursor::createdAtMs,
			StepsWalPruneCursor::admissionOrdinal,
		)
}

private data class StepsWalPruneCandidate(
	val cursor: StepsWalPruneCursor,
	val eventId: String,
	val authorizationPurposeEligibilityMask: Long,
	val ownerKey: StepsCountDomainOwnerLookupKey?,
	val ownerlessRunKey: OwnerlessStepsManifestRunKey?,
) {
	val isLegacyV27: Boolean
		get() = authorizationPurposeEligibilityMask == 0L
}

internal data class OwnerlessStepsManifestRunKey(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

internal class OwnerlessStepsManifestRunTimeline(
	val key: OwnerlessStepsManifestRunKey,
	val run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
) {
	val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
	val sourcesByRevision = mutableMapOf<Long, List<SessionManifestSourceEntity>>()
}

private data class OwnerlessStepsWalCandidate(
	val candidate: StepsWalPruneCandidate,
	val ownerKey: StepsCountDomainOwnerLookupKey,
	val wal: SourceEventWalEntity,
	val runKey: OwnerlessStepsManifestRunKey,
	val manifestRevision: Long,
)

internal class OwnerlessStepsManifestTimelineCache(
	private val maximumDistinctRuns: Int = OWNERLESS_STEPS_MAX_DISTINCT_RUNS,
) {
	private var active = true
	private val timelines = linkedMapOf<OwnerlessStepsManifestRunKey, OwnerlessStepsManifestRunTimeline>()

	init {
		require(maximumDistinctRuns > 0)
	}

	suspend fun getOrLoad(
		key: OwnerlessStepsManifestRunKey,
		load: suspend () -> OwnerlessStepsManifestRunTimeline,
	): OwnerlessStepsManifestRunTimeline {
		check(active) { "Ownerless Steps manifest timeline cache is no longer active" }
		currentCoroutineContext().ensureActive()
		timelines[key]?.let { return it }
		if (timelines.size >= maximumDistinctRuns) {
			throw CountDomainMaintenanceOverflowException()
		}
		val loaded = load()
		currentCoroutineContext().ensureActive()
		if (loaded.key != key) throw CountDomainStoredEvidenceException()
		timelines[key] = loaded
		return loaded
	}

	fun clearForMutation() {
		check(active) { "Ownerless Steps manifest timeline cache is no longer active" }
		timelines.clear()
	}

	fun invalidate() {
		timelines.clear()
		active = false
	}
}

private data class TerminalOwnerCandidate(
	val linkedAtMs: Long,
	val key: StepsCountDomainOwnerLookupKey,
)

data class StepsCountDomainStoredOwner(
	val owner: StepsCountDomainOwnerRevisionEntity,
	val receipt: StepsCountDomainReceiptEntity?,
	val completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
)

data class StepsCountDomainRetirementEvidence(
	val providerFlushOutcome: String,
	val registrationRemovalOutcome: String,
) {
	init {
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
	}
}

object StepsRetirementTerminality {
	@Suppress("LongParameterList")
	fun isTerminal(
		stopStatus: String,
		registrationRemovalOutcome: String,
		providerFlushOutcome: String,
		providerCoverage: String,
		appDrainComplete: Boolean,
		lastAdmissionOrdinal: Long?,
		unresolvedSequenceStart: Long?,
		unresolvedSequenceEnd: Long?,
	): Boolean {
		if (registrationRemovalOutcome !in setOf("REMOVED", "NOT_REGISTERED") ||
			providerFlushOutcome !in setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED")
		) {
			return false
		}
		return when (stopStatus) {
			"COMPLETE" -> appDrainComplete
			"PROCESS_RESTARTED" ->
				!appDrainComplete && providerCoverage == "PROVIDER_COMPLETENESS_UNOBSERVABLE"
			"PARTIAL_UNOBSERVABLE" ->
				(appDrainComplete && lastAdmissionOrdinal != null) ||
					(!appDrainComplete &&
						unresolvedSequenceStart != null &&
						unresolvedSequenceEnd != null &&
						unresolvedSequenceStart <= unresolvedSequenceEnd)
			else -> false
		}
	}
}

sealed interface StepsTerminalCompletenessAuthentication {
	data class Authenticated(
		val completeness: SourceSessionCompletenessEntity,
		val retirementEvidence: StepsCountDomainRetirementEvidence,
		val countDomainCollectedDataEpoch: Long?,
	) : StepsTerminalCompletenessAuthentication

	data object Absent : StepsTerminalCompletenessAuthentication
	data object Unverifiable : StepsTerminalCompletenessAuthentication
}

sealed interface StepsTerminalProductAuthentication {
	data object Materializable : StepsTerminalProductAuthentication
	data object TerminalUnavailable : StepsTerminalProductAuthentication
	data object SchemaUnavailable : StepsTerminalProductAuthentication
	data object Unverifiable : StepsTerminalProductAuthentication
}

sealed interface StepsCountDomainOwnerRead {
	data class Ready(
		val owners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>,
		val latestRevisions: Map<StepsCountDomainOwnerLineageKey, Long>,
	) : StepsCountDomainOwnerRead

	data object SchemaUnavailable : StepsCountDomainOwnerRead
	data object Overflow : StepsCountDomainOwnerRead
	data object Unverifiable : StepsCountDomainOwnerRead
}

sealed interface StepsCountDomainMaintenanceResult {
	data object SchemaUnavailable : StepsCountDomainMaintenanceResult
	data object StoredEvidenceUnverifiable : StepsCountDomainMaintenanceResult
	data object Overflow : StepsCountDomainMaintenanceResult
	data class Applied(
		val removedOwners: Long,
		val removedReceipts: Long,
		val walEventsDeleted: Int = 0,
	) : StepsCountDomainMaintenanceResult
}

internal enum class StepsCountDomainMaintenanceCheckpoint {
	OWNER_KEY_PAGE_AUTHENTICATED,
	OWNER_DOMAIN_PAGE_AUTHENTICATED,
	RECEIPT_DOMAIN_PAGE_AUTHENTICATED,
	COMPLETENESS_DOMAIN_PAGE_AUTHENTICATED,
	OWNER_CANDIDATE_PAGE_AUTHENTICATED,
	WAL_DOMAIN_PAGE_AUTHENTICATED,
	WAL_REGISTRATION_PAGE_AUTHENTICATED,
	WAL_RUN_PAGE_AUTHENTICATED,
	WAL_CANDIDATE_PAGE_AUTHENTICATED,
	WAL_OWNERLESS_RUN_AUTHENTICATED,
}

internal data class StepsCountDomainMaintenanceMutationVersion(
	val totalChanges: Long,
	val schemaVersion: Long,
	val tempSchemaVersion: Long,
)

enum class StepsCountDomainFullClearMode {
	REMOVE_ALL,
	PRESERVE_TERMINAL,
}

/**
 * Source-owned bridge over the serialized AppDatabase count-domain entities.
 *
 * Calls must occur inside the producer's existing Room transaction. A completely absent namespace
 * or exact empty Room scaffold preserves pre-P5 behavior with SCHEMA_UNAVAILABLE in isolated
 * source tests before the production schema callback runs. Any other partial, markerless, legacy,
 * or corrupt namespace is STORED_EVIDENCE_UNVERIFIABLE and never activates.
 */
@Suppress("TooManyFunctions")
class StepsCountDomainStore(
	private val database: AppDatabase,
) {
	private val maintenanceAuthorityIdentity = Any()

	class OwnerMaintenanceSession internal constructor(
		private val owner: StepsCountDomainStore,
		private val authorityIdentity: Any,
		internal val schemaAvailable: Boolean,
		expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
		private val checkpointCallback: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	) {
		private var active = true
		private var expectedMutationVersion = expectedMutationVersion
		internal val ownerlessStepsManifestTimelineCache =
			OwnerlessStepsManifestTimelineCache()

		suspend fun removeOwners(
			keys: List<StepsCountDomainOwnerLookupKey>,
		): StepsCountDomainMaintenanceResult = owner.removeOwnersAuthenticated(
			session = this,
			keys = keys,
		)

		internal fun authenticates(
			expectedOwner: StepsCountDomainStore,
			expectedAuthorityIdentity: Any,
		): Boolean = active &&
			owner === expectedOwner &&
			authorityIdentity === expectedAuthorityIdentity

		internal fun invalidate() {
			ownerlessStepsManifestTimelineCache.invalidate()
			active = false
		}

		internal suspend fun checkpoint(value: StepsCountDomainMaintenanceCheckpoint) {
			checkpointCallback(value)
			requireUnchangedMutationVersion()
		}

		internal fun expectedTotalChanges(): Long = expectedMutationVersion.totalChanges

		internal fun requireUnchangedMutationVersion() {
			val current = owner.database.openHelper.writableDatabase.maintenanceMutationVersion()
			if (current != expectedMutationVersion) throw CountDomainStoredEvidenceException()
		}

		internal fun acceptOwnedChanges(knownRowEffects: Long) {
			require(knownRowEffects >= 0L)
			val current = owner.database.openHelper.writableDatabase.maintenanceMutationVersion()
			val expectedTotalChanges = Math.addExact(
				expectedMutationVersion.totalChanges,
				knownRowEffects,
			)
			if (current.schemaVersion != expectedMutationVersion.schemaVersion ||
				current.tempSchemaVersion != expectedMutationVersion.tempSchemaVersion ||
				current.totalChanges != expectedTotalChanges
			) {
				throw CountDomainStoredEvidenceException()
			}
			expectedMutationVersion = current
			ownerlessStepsManifestTimelineCache.clearForMutation()
		}
	}

	fun isInstalled(): Boolean =
		StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) ==
			StepsCountDomainSchemaState.ValidV2

	/**
	 * Authenticates one transaction-stable count-domain snapshot for every removal in [block].
	 *
	 * The supplied session is bound to this store and becomes invalid when the block returns.
	 */
	suspend fun <T> withOwnerMaintenance(
		block: suspend (OwnerMaintenanceSession) -> T,
	): T = withOwnerMaintenance(
		checkpoint = { currentCoroutineContext().ensureActive() },
		block = block,
	)

	internal suspend fun <T> withOwnerMaintenance(
		checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
		block: suspend (OwnerMaintenanceSession) -> T,
	): T = database.withTransaction {
		val sqlite = database.openHelper.writableDatabase
		val schemaAvailable = when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			-> false
			StepsCountDomainSchemaState.Incompatible ->
				throw CountDomainStoredEvidenceException()
			StepsCountDomainSchemaState.ValidV2 -> true
		}
		val expectedMutationVersion = if (schemaAvailable) {
			sqlite.authenticateCountDomainMaintenanceSnapshot(checkpoint)
		} else {
			sqlite.maintenanceMutationVersion()
		}
		val session = OwnerMaintenanceSession(
			owner = this@StepsCountDomainStore,
			authorityIdentity = maintenanceAuthorityIdentity,
			schemaAvailable = schemaAvailable,
			expectedMutationVersion = expectedMutationVersion,
			checkpointCallback = checkpoint,
		)
		try {
			block(session).also { session.requireActiveSnapshot() }
		} finally {
			session.invalidate()
		}
	}

	suspend fun recordSessionWal(
		row: SourceEventWalEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionWalAuthenticated(row, counterDomainToken)
	}

	private suspend fun recordSessionWalAuthenticated(
		row: SourceEventWalEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			row.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		val logicalTrackingId = row.logicalTrackingId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val serviceRunId = row.serviceRunId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityRevision = row.authorizationRevision
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityFingerprint = row.authorizationFingerprint
			?: return StepsCountDomainWriteResult.UNPROVEN
		if (!row.hasQualifiedIntegrity()) return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		if ((counterDomainToken != null &&
				row.payloadVersion < StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION) ||
			(counterDomainToken == null &&
				row.payloadVersion == StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}

		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			row.admissionOrdinal,
			row.eventId,
		)
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		)
		val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity,
			ownerIdentity,
		)
		val hasGenerationSafeToken =
			row.payloadVersion >= StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION &&
				counterDomainToken != null
		if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			hasGenerationSafeToken
		) {
			return StepsCountDomainWriteResult.UNPROVEN
		}
		// Payload v6 carried one token through COUNTER_RESET and therefore cannot prove that any
		// retained window stayed within one physical counter epoch. Generation-safe authority starts
		// at v7; all older WAL remains decodable for fact recovery but is permanently UNPROVEN here.
		if (!hasGenerationSafeToken) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				ownerEffectChecksum = row.integrityIdentity,
				linkedAtMs = row.createdAtMs,
			)
		}
		return appendBoundOwner(
			receipt = nativeReceipt(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				counterDomainToken = counterDomainToken,
				registrationGeneration = row.registrationGeneration,
				collectedDataEpoch = row.capturedCollectedDataEpoch,
				authorityRevision = authorityRevision,
				authorityFingerprint = authorityFingerprint,
				coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
				coverageVersion = row.payloadVersion,
				effectChecksum = row.integrityIdentity,
				completionEvidenceChecksum = null,
			),
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = row.integrityIdentity,
			linkedAtMs = row.createdAtMs,
		)
	}

	suspend fun recordSessionFact(
		fact: StepFactRevisionEntity,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionFactAuthenticated(fact)
	}

	private suspend fun recordSessionFactAuthenticated(
		fact: StepFactRevisionEntity,
	): StepsCountDomainWriteResult {
		if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val admissionOrdinal = requireNotNull(fact.sourceAdmissionOrdinal)
		val eventId = requireNotNull(fact.sourceEventId)
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.serviceRunId),
		)
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		)
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(admissionOrdinal, eventId),
				1L,
			),
			scopeIdentity,
		) ?: return appendTerminalUnproven(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
		val walReceipt = walOwner.receipt
		if (walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (walReceipt == null || !walOwner.isAuthentic() ||
			walOwner.owner.scopeIdentity != scopeIdentity ||
			walReceipt.collectedDataEpoch != fact.collectedDataEpoch
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val coverageKind = when (fact.coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE ->
				StepsCountDomainReceiptEntity.COVERAGE_BASELINE
			StepFactRevisionEntity.COVERAGE_COVERED ->
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			StepFactRevisionEntity.COVERAGE_RESET_GAP ->
				StepsCountDomainReceiptEntity.COVERAGE_RESET_GAP
			StepFactRevisionEntity.COVERAGE_PARTIAL ->
				StepsCountDomainReceiptEntity.COVERAGE_PARTIAL
			else -> return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			coverageKind = coverageKind,
			coverageVersion = fact.writerProjectionVersion,
			effectChecksum = fact.effectChecksum,
			completionEvidenceChecksum = null,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun recordSessionCompleteness(
		row: SourceSessionCompletenessEntity,
		retirementEvidence: StepsCountDomainRetirementEvidence,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionCompletenessAuthenticated(row, retirementEvidence)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun recordSessionCompletenessAuthenticated(
		row: SourceSessionCompletenessEntity,
		retirementEvidence: StepsCountDomainRetirementEvidence,
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!row.hasTerminalRetirement(retirementEvidence)) {
			return StepsCountDomainWriteResult.AUTHORITY_PENDING
		}
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
		)
		val ownerEffectChecksum =
			StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
			row.sourceInstanceId,
			row.registrationGeneration,
		)
		val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row)
		val timeline = authenticatedStepsCompletenessTimeline(
			row.logicalTrackingId,
			row.serviceRunId,
		) ?: return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		val rowIndex = orderedTimeline.indexOfFirst { candidate ->
			candidate.sourceInstanceId == row.sourceInstanceId &&
				candidate.registrationGeneration == row.registrationGeneration
		}
		if (rowIndex < 0 || orderedTimeline[rowIndex] != row) {
			return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}
		val timelinePrefix = orderedTimeline.take(rowIndex + 1)
		val timelineChecksum =
			StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)
		val timelineWithinBound = orderedTimeline.size <= MAX_COMPLETENESS_TIMELINE
		val priorTimelineIsComplete = timelineWithinBound && timelinePrefix.dropLast(1).all { prior ->
			val priorOwnerIdentity =
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					prior.logicalTrackingId,
					prior.serviceRunId,
					prior.sourceInstanceId,
					prior.registrationGeneration,
				)
			val priorOwner = loadExactOwner(
				StepsCountDomainOwnerLookupKey(
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					priorOwnerIdentity,
					StepsCountDomainReceiptIntegrity.completenessOwnerRevision(prior),
				),
				scopeIdentity,
			)
			priorOwner?.isAuthentic() == true
		}
		val exactComplete = row.hasExactCompleteRetirement(retirementEvidence) &&
			timelineWithinBound &&
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				timelinePrefix,
				row.logicalTrackingId,
				row.serviceRunId,
			) && priorTimelineIsComplete
		val marker = completenessMarker(
			row = row,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			retirementEvidence = retirementEvidence,
			timelineChecksum = timelineChecksum,
			exactComplete = exactComplete,
		)
		val admissionOrdinal = row.lastAdmissionOrdinal
		if (!exactComplete || admissionOrdinal == null) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker,
			)
		}
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
			?: return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		if (wal.logicalTrackingId != row.logicalTrackingId ||
			wal.serviceRunId != row.serviceRunId ||
			wal.sourceInstanceId != row.sourceInstanceId ||
			wal.registrationGeneration != row.registrationGeneration ||
			wal.sourceSequence != row.lastSourceSequence
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
					admissionOrdinal,
					wal.eventId,
				),
				1L,
			),
			scopeIdentity,
		) ?: return if (canonicalProjectionCommittedThrough(admissionOrdinal)) {
			appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		} else {
			StepsCountDomainWriteResult.AUTHORITY_PENDING
		}
		if (walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		}
		val walReceipt = walOwner.receipt ?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		if (!walOwner.isAuthentic() || walOwner.owner.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN,
			coverageVersion = COMPLETENESS_COVERAGE_VERSION,
			effectChecksum = ownerEffectChecksum,
			completionEvidenceChecksum = marker.evidenceChecksum,
		)
		return appendBoundOwner(
			receipt,
			scopeIdentity,
			ownerEffectChecksum,
			row.updatedAtMs,
			marker,
		)
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	suspend fun authenticateTerminalSessionCompleteness(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): StepsTerminalCompletenessAuthentication {
		if (logicalTrackingId.isBlank() || serviceRunId.isBlank() || sourceInstanceId.isBlank() ||
			registrationGeneration <= 0L
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val timeline = authenticatedStepsCompletenessTimeline(logicalTrackingId, serviceRunId)
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		if (
			!StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				timeline,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
			logicalTrackingId,
			serviceRunId,
			sourceInstanceId,
			registrationGeneration,
		)
		val completeness = timeline.singleOrNull { row ->
			row.sourceInstanceId == sourceInstanceId &&
				row.registrationGeneration == registrationGeneration
		} ?: return terminalCompletenessAbsence(
			ownerIdentity,
			StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
				logicalTrackingId,
				serviceRunId,
			),
		)
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		val completenessIndex = orderedTimeline.indexOf(completeness)
		if (completenessIndex < 0 || timeline != orderedTimeline) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val timelinePrefix = orderedTimeline.take(completenessIndex + 1)
		val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(completeness)
		val key = StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity,
			ownerRevision,
		)
		val ready = readOwners(listOf(key), limit = 1) as? StepsCountDomainOwnerRead.Ready
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		val stored = ready.owners[key]
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		if (ready.latestRevisions[
				StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
			] != ownerRevision
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val marker = stored.completenessMarker
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		)
		val expectedEffect = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(completeness)
		if (!marker.matches(stored.owner) ||
			stored.owner.scopeIdentity != expectedScope ||
			stored.owner.ownerEffectChecksum != expectedEffect ||
			stored.owner.linkedAtMs != completeness.updatedAtMs ||
			marker.lastAdmissionOrdinal != completeness.lastAdmissionOrdinal ||
			marker.lastSourceSequence != completeness.lastSourceSequence ||
			marker.registrationTimelineChecksum !=
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val retirementEvidence = StepsCountDomainRetirementEvidence(
			providerFlushOutcome = marker.providerFlushOutcome,
			registrationRemovalOutcome = marker.registrationRemovalOutcome,
		)
		if (!retirementEvidence.hasFinalRegistrationRemoval()) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val receipt = stored.receipt
		val authentic = when (stored.owner.operation) {
			StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
				stored.isAuthentic() &&
					receipt != null &&
					receipt.registrationGeneration == registrationGeneration &&
					receipt.coverageKind ==
					StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN &&
					completeness.hasExactCompleteRetirement(retirementEvidence)
			StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
				receipt == null && stored.owner.receiptIdentity == null &&
					marker.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
			else -> false
		}
		return if (authentic) {
			StepsTerminalCompletenessAuthentication.Authenticated(
				completeness,
				retirementEvidence,
				receipt?.collectedDataEpoch,
			)
		} else {
			StepsTerminalCompletenessAuthentication.Unverifiable
		}
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	suspend fun authenticateTerminalProductDisposition(
		logicalTrackingId: String,
		serviceRunId: String,
		timeline: List<SourceSessionCompletenessEntity>,
	): StepsTerminalProductAuthentication {
		if (
			logicalTrackingId.isBlank() ||
			serviceRunId.isBlank() ||
			timeline.isEmpty() ||
			timeline.size > MAX_COMPLETENESS_TIMELINE ||
			!StepsSessionCompletenessIntegrity.hasValidTimeline(
				timeline,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return StepsTerminalProductAuthentication.Unverifiable
		}
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		if (timeline != orderedTimeline) {
			return StepsTerminalProductAuthentication.Unverifiable
		}
		val keys = orderedTimeline.map { row ->
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					row.logicalTrackingId,
					row.serviceRunId,
					row.sourceInstanceId,
					row.registrationGeneration,
				),
				StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row),
			)
		}
		val ready = when (val read = readOwners(keys, MAX_COMPLETENESS_TIMELINE)) {
			is StepsCountDomainOwnerRead.Ready -> read
			StepsCountDomainOwnerRead.SchemaUnavailable ->
				return StepsTerminalProductAuthentication.SchemaUnavailable
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return StepsTerminalProductAuthentication.Unverifiable
		}
		var unavailable = false
		for ((index, pair) in orderedTimeline.zip(keys).withIndex()) {
			val (row, key) = pair
			val stored = ready.owners[key]
				?: return StepsTerminalProductAuthentication.Unverifiable
			val marker = stored.completenessMarker
				?: return StepsTerminalProductAuthentication.Unverifiable
			val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
				row.logicalTrackingId,
				row.serviceRunId,
			)
			val expectedEffect = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
			val retirementEvidence = StepsCountDomainRetirementEvidence(
				providerFlushOutcome = marker.providerFlushOutcome,
				registrationRemovalOutcome = marker.registrationRemovalOutcome,
			)
			if (
				ready.latestRevisions[
					StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
				] != key.ownerRevision ||
				!marker.matches(stored.owner) ||
				stored.owner.scopeIdentity != expectedScope ||
				stored.owner.ownerEffectChecksum != expectedEffect ||
				stored.owner.linkedAtMs != row.updatedAtMs ||
				marker.lastAdmissionOrdinal != row.lastAdmissionOrdinal ||
				marker.lastSourceSequence != row.lastSourceSequence ||
				marker.registrationTimelineChecksum !=
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(
					orderedTimeline.take(index + 1),
				) ||
				!retirementEvidence.hasFinalRegistrationRemoval()
			) {
				return StepsTerminalProductAuthentication.Unverifiable
			}
			when (stored.owner.operation) {
				StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> {
					if (
						!stored.isAuthentic() ||
						!row.hasExactCompleteRetirement(retirementEvidence)
					) {
						return StepsTerminalProductAuthentication.Unverifiable
					}
				}
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> {
					if (
						stored.receipt != null ||
						stored.owner.receiptIdentity != null ||
						marker.terminalState !=
						StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN ||
						!row.hasTerminalRetirement(retirementEvidence)
					) {
						return StepsTerminalProductAuthentication.Unverifiable
					}
					unavailable = true
				}
				else -> return StepsTerminalProductAuthentication.Unverifiable
			}
		}
		return if (unavailable) {
			StepsTerminalProductAuthentication.TerminalUnavailable
		} else {
			StepsTerminalProductAuthentication.Materializable
		}
	}

	private suspend fun authenticatedStepsCompletenessTimeline(
		logicalTrackingId: String,
		serviceRunId: String,
	): List<SourceSessionCompletenessEntity>? {
		val raw = database.sourceSessionDao().rawSourceCompletenessForServiceRun(
			serviceRunId = serviceRunId,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			limit = MAX_COMPLETENESS_TIMELINE + 1,
		)
		if (raw.size > MAX_COMPLETENESS_TIMELINE) return null
		val rows = ArrayList<SourceSessionCompletenessEntity>(raw.size)
		for (candidate in raw) {
			val row = candidate.validatedOrNull() ?: return null
			if (
				row.logicalTrackingId != logicalTrackingId ||
				row.serviceRunId != serviceRunId ||
				row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
			) {
				return null
			}
			rows += row
		}
		return rows
	}

	private fun terminalCompletenessAbsence(
		ownerIdentity: String,
		scopeIdentity: String,
	): StepsTerminalCompletenessAuthentication {
		val sqlite = database.openHelper.readableDatabase
		return try {
			when (StepsCountDomainSchema.inspect(sqlite)) {
				StepsCountDomainSchemaState.Absent,
				StepsCountDomainSchemaState.FreshRoomScaffold,
				-> StepsTerminalCompletenessAuthentication.Absent
				StepsCountDomainSchemaState.Incompatible ->
					StepsTerminalCompletenessAuthentication.Unverifiable
				StepsCountDomainSchemaState.ValidV2 -> {
					if (sqlite.queryLatestOwner(
							StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
							scopeIdentity,
							ownerIdentity,
						) == null
					) {
						StepsTerminalCompletenessAuthentication.Absent
					} else {
						StepsTerminalCompletenessAuthentication.Unverifiable
					}
				}
			}
		} catch (_: SQLiteException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		} catch (_: IllegalArgumentException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		} catch (_: IllegalStateException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		}
	}

	suspend fun recordAmbientFact(
		fact: AmbientStepsFactRevisionEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordAmbientFactAuthenticated(fact, counterDomainToken)
	}

	private suspend fun recordAmbientFactAuthenticated(
		fact: AmbientStepsFactRevisionEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult {
		if (fact.operation != AmbientStepsFactRevisionEntity.OPERATION_UPSERT ||
			fact.originKind != AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
		)
		val scopeIdentity =
			StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(fact.logicalFactId)
		val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity,
			ownerIdentity,
		)
		if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (counterDomainToken == null) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (fact.semanticRevision > 1L &&
			latestOwner == null
		) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		val receipt = nativeReceipt(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			counterDomainToken = counterDomainToken,
			registrationGeneration = requireNotNull(fact.registrationGeneration),
			collectedDataEpoch = fact.collectedDataEpoch,
			authorityRevision = requireNotNull(fact.authorizationRevision),
			authorityFingerprint = requireNotNull(fact.authorizationFingerprint),
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE,
			coverageVersion = fact.writerVersion,
			effectChecksum = fact.effectChecksum,
			completionEvidenceChecksum = null,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	suspend fun recordSessionFactRetraction(
		retraction: StepFactRevisionEntity,
		logicalTrackingId: String,
		serviceRunId: String,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionFactRetractionAuthenticated(
			retraction,
			logicalTrackingId,
			serviceRunId,
		)
	}

	private suspend fun recordSessionFactRetractionAuthenticated(
		retraction: StepFactRevisionEntity,
		logicalTrackingId: String,
		serviceRunId: String,
	): StepsCountDomainWriteResult {
		val expectedScopeIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		writeSchemaFailure()?.let { return it }
		if (retraction.operation != StepFactRevisionEntity.OPERATION_RETRACT ||
			!StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				retraction,
				expectedScopeIdentity,
			)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
					logicalTrackingId,
					serviceRunId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					retraction.writerProjectionId,
					retraction.writerProjectionVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	suspend fun recordAmbientFactRetraction(
		retraction: AmbientStepsFactRevisionEntity,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordAmbientFactRetractionAuthenticated(retraction)
	}

	private suspend fun recordAmbientFactRetractionAuthenticated(
		retraction: AmbientStepsFactRevisionEntity,
	): StepsCountDomainWriteResult {
		writeSchemaFailure()?.let { return it }
		if (retraction.operation != AmbientStepsFactRevisionEntity.OPERATION_RETRACT ||
			!AmbientStepsFactIntegrity.hasValidEffectChecksum(retraction)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(
					retraction.logicalFactId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					retraction.writerId,
					retraction.writerVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	@Suppress("LongMethod", "ReturnCount")
	suspend fun readOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
		limit: Int = MAX_OWNER_LOOKUP,
	): StepsCountDomainOwnerRead {
		val distinctKeys = keys.distinct()
		if (limit !in 1..MAX_OWNER_LOOKUP || distinctKeys.size > limit) {
			return StepsCountDomainOwnerRead.Overflow
		}
		val sqlite = database.openHelper.readableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainOwnerRead.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainOwnerRead.Unverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		return try {
			val owners = mutableListOf<StepsCountDomainOwnerRevisionEntity>()
			for (chunk in distinctKeys.chunked(OWNER_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				owners += sqlite.queryOwnerChunk(chunk)
			}

			if (owners.size > limit) return StepsCountDomainOwnerRead.Overflow
			val ownersByKey = owners.associateBy {
				StepsCountDomainOwnerLookupKey(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
				)
			}
			if (ownersByKey.size != owners.size) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			sqlite.requireAuthenticMissingCountDomainOwnerKeys(
				distinctKeys.filterNot(ownersByKey::containsKey),
			)
			sqlite.requireAuthenticCountDomainOwnerScopes(
				owners.map { owner ->
					CountDomainOwnerScopeKey(
						owner.ownerKind,
						owner.scopeIdentity,
						owner.ownerIdentity,
					)
				},
			)
			val latestRows = mutableListOf<Pair<StepsCountDomainOwnerLineageKey, Long>>()
			for (chunk in distinctKeys
				.map { StepsCountDomainOwnerLineageKey(it.ownerKind, it.ownerIdentity) }
				.distinct()
				.chunked(LATEST_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				latestRows += sqlite.queryLatestOwnerChunk(chunk)
			}
			val latestRevisions = latestRows.toMap()
			if (owners.any { owner ->
				StepsCountDomainOwnerLineageKey(owner.ownerKind, owner.ownerIdentity) !in
					latestRevisions
			}) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
				.distinct()
			if (receiptIds.size > limit) return StepsCountDomainOwnerRead.Overflow
			val receipts = mutableListOf<StepsCountDomainReceiptEntity>()
			for (chunk in receiptIds.chunked(RECEIPT_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				receipts += sqlite.queryReceiptChunk(chunk)
			}
			if (receipts.size != receiptIds.size) return StepsCountDomainOwnerRead.Unverifiable
			val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
			val completenessOwners = owners.filter {
				it.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			}
			val markers = mutableListOf<StepsCountDomainCompletenessMarkerEntity>()
			for (chunk in completenessOwners.chunked(MARKER_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				markers += sqlite.queryCompletenessMarkerChunk(chunk)
			}
			if (markers.size != completenessOwners.size) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			val markersByKey = markers.associateBy {
				StepsCountDomainOwnerLookupKey(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
				)
			}
			StepsCountDomainOwnerRead.Ready(
				distinctKeys.mapNotNull { key ->
					ownersByKey[key]?.let { owner ->
						key to StepsCountDomainStoredOwner(
							owner,
							owner.receiptIdentity?.let(receiptsById::get),
							markersByKey[key],
						)
					}
				}.toMap(),
				latestRevisions,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			StepsCountDomainOwnerRead.Unverifiable
		} catch (_: IllegalArgumentException) {
			StepsCountDomainOwnerRead.Unverifiable
		} catch (_: IllegalStateException) {
			StepsCountDomainOwnerRead.Unverifiable
		}
	}

	suspend fun removeOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
	): StepsCountDomainMaintenanceResult = removeOwners(keys) {
		currentCoroutineContext().ensureActive()
	}

	internal suspend fun removeOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
		checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance(checkpoint) { session ->
			session.removeOwners(keys)
		}
	}

	private suspend fun removeOwnersAuthenticated(
		session: OwnerMaintenanceSession,
		keys: List<StepsCountDomainOwnerLookupKey>,
		requireEveryOwner: Boolean = false,
	): StepsCountDomainMaintenanceResult {
		session.requireActiveSnapshot()
		val distinct = keys.distinct()
		if (distinct.size > MAX_MAINTENANCE_OWNER_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		if (!session.schemaAvailable) return StepsCountDomainMaintenanceResult.SchemaUnavailable
		if (distinct.isEmpty()) return StepsCountDomainMaintenanceResult.Applied(0L, 0L)
		val sqlite = database.openHelper.writableDatabase
		val ownersByKey = sqlite.queryCountDomainMaintenanceCandidates(
			keys = distinct,
			onPage = {
				session.checkpointAndRequireUnchanged(
					StepsCountDomainMaintenanceCheckpoint.OWNER_CANDIDATE_PAGE_AUTHENTICATED,
				)
			},
		)
		if (requireEveryOwner && ownersByKey.size != distinct.size) {
			return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
		}
		val owners = ownersByKey.values
		val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
			.distinct()
		currentCoroutineContext().ensureActive()
		var removedOwnerRows = 0
		distinct.chunked(OWNER_QUERY_CHUNK).forEach { chunk ->
			currentCoroutineContext().ensureActive()
			session.requireActiveSnapshot()
			val chunkOwners = chunk.mapNotNull(ownersByKey::get)
			val removedMarkers = chunkOwners.count { owner ->
				owner.ownerKind ==
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			}
			val expectedOwnerRows = chunkOwners.size.toLong()
			val expectedEffects = Math.addExact(
				Math.addExact(expectedOwnerRows, removedMarkers.toLong()),
				Math.addExact(
					sqlite.roomDeleteInvalidationEffect(
						StepsCountDomainSchema.OWNER_TABLE,
						expectedOwnerRows,
					),
					sqlite.roomDeleteInvalidationEffect(
						StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
						removedMarkers.toLong(),
					),
				),
			)
			val removedChunk = sqlite.deleteOwnerChunk(chunk)
			check(removedChunk == chunkOwners.size) {
				"Steps count-domain owner changed during removal"
			}
			session.acceptOwnedChanges(
				expectedEffects,
			)
			removedOwnerRows += removedChunk
		}
		check(removedOwnerRows == owners.size) {
			"Steps count-domain owner changed during removal"
		}
		session.requireActiveSnapshot()
		val expectedRemovedReceipts = sqlite.countUnreferencedReceipts(receiptIds)
		val receiptInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
			StepsCountDomainSchema.RECEIPT_TABLE,
			expectedRemovedReceipts,
		)
		val removedReceipts = sqlite.deleteUnreferencedReceipts(receiptIds)
		check(removedReceipts == expectedRemovedReceipts) {
			"Steps count-domain receipt changed during removal"
		}
		session.acceptOwnedChanges(Math.addExact(removedReceipts, receiptInvalidationEffect))
		return StepsCountDomainMaintenanceResult.Applied(owners.size.toLong(), removedReceipts)
	}

	suspend fun clear(
		mode: StepsCountDomainFullClearMode,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance { session -> clearAuthenticated(mode, session) }
	}

	private fun clearAuthenticated(
		mode: StepsCountDomainFullClearMode,
		session: OwnerMaintenanceSession,
	): StepsCountDomainMaintenanceResult {
		session.requireActiveSnapshot()
		if (!session.schemaAvailable) return StepsCountDomainMaintenanceResult.SchemaUnavailable
		val sqlite = database.openHelper.writableDatabase
		val ownerCount = sqlite.longForQuery(
			if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
				"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision"
			} else {
				"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision " +
					"WHERE operation = 'BIND'"
			},
		)
		if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
			val markerCount =
				sqlite.longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker")
			session.requireActiveSnapshot()
			val markerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
				StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
				markerCount,
			)
			val removedMarkers = sqlite.executeDelete(
				"DELETE FROM main.steps_count_domain_completeness_marker",
			)
			check(removedMarkers.toLong() == markerCount)
			session.acceptOwnedChanges(Math.addExact(markerCount, markerInvalidationEffect))
			session.requireActiveSnapshot()
			val ownerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
				StepsCountDomainSchema.OWNER_TABLE,
				ownerCount,
			)
			val removedOwners =
				sqlite.executeDelete("DELETE FROM main.steps_count_domain_owner_revision")
			check(removedOwners.toLong() == ownerCount)
			session.acceptOwnedChanges(Math.addExact(ownerCount, ownerInvalidationEffect))
		} else {
			val markerCount = sqlite.longForQuery(
				"SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker AS marker " +
					"JOIN main.steps_count_domain_owner_revision AS owner " +
					"ON owner.owner_kind = marker.owner_kind " +
					"AND owner.owner_identity = marker.owner_identity " +
					"AND owner.owner_revision = marker.owner_revision " +
					"WHERE owner.operation = 'BIND'",
			)
			session.requireActiveSnapshot()
			val ownerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
				StepsCountDomainSchema.OWNER_TABLE,
				ownerCount,
			)
			val markerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
				StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
				markerCount,
			)
			val removedOwners = sqlite.executeDelete(
				"DELETE FROM main.steps_count_domain_owner_revision WHERE operation = 'BIND'",
			)
			check(removedOwners.toLong() == ownerCount)
			session.acceptOwnedChanges(
				Math.addExact(
					Math.addExact(ownerCount, markerCount),
					Math.addExact(ownerInvalidationEffect, markerInvalidationEffect),
				),
			)
		}
		val receiptCount =
			sqlite.longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_receipt")
		session.requireActiveSnapshot()
		val receiptInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
			StepsCountDomainSchema.RECEIPT_TABLE,
			receiptCount,
		)
		val removedReceipts = sqlite.executeDelete("DELETE FROM main.steps_count_domain_receipt")
		check(removedReceipts.toLong() == receiptCount)
		session.acceptOwnedChanges(Math.addExact(receiptCount, receiptInvalidationEffect))
		return StepsCountDomainMaintenanceResult.Applied(ownerCount, receiptCount)
	}

	suspend fun compactTerminalOwners(
		maximumRetainedTerminalOwners: Int,
		batchSize: Int,
		sourceFenceAuthenticated: Boolean,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance { session ->
			compactTerminalOwnersAuthenticated(
				maximumRetainedTerminalOwners,
				batchSize,
				sourceFenceAuthenticated,
				session,
			)
		}
	}

	private suspend fun compactTerminalOwnersAuthenticated(
		maximumRetainedTerminalOwners: Int,
		batchSize: Int,
		sourceFenceAuthenticated: Boolean,
		session: OwnerMaintenanceSession,
	): StepsCountDomainMaintenanceResult {
		require(sourceFenceAuthenticated)
		require(maximumRetainedTerminalOwners >= 0)
		require(batchSize in 1..MAX_MAINTENANCE_OWNER_BATCH)
		session.requireActiveSnapshot()
		if (!session.schemaAvailable) return StepsCountDomainMaintenanceResult.SchemaUnavailable
		currentCoroutineContext().ensureActive()
		val candidates = database.openHelper.writableDatabase.queryTerminalCompactionCandidates(
			maximumRetainedTerminalOwners = maximumRetainedTerminalOwners,
			limit = batchSize,
			onPage = {
				session.checkpointAndRequireUnchanged(
					StepsCountDomainMaintenanceCheckpoint.OWNER_CANDIDATE_PAGE_AUTHENTICATED,
				)
			},
		)
		return removeOwnersAuthenticated(
			session = session,
			keys = candidates,
		)
	}

	suspend fun removeSessionWalOwnersForPrune(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): StepsCountDomainMaintenanceResult = removeSessionWalOwnersForPrune(
		safeOrdinal,
		createdBeforeMs,
		limit,
	) {
		currentCoroutineContext().ensureActive()
	}

	internal suspend fun removeSessionWalOwnersForPrune(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
		checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance(checkpoint) { session ->
			removeSessionWalOwnersForPruneAuthenticated(
				safeOrdinal,
				createdBeforeMs,
				limit,
				session,
			)
		}
	}

	internal suspend fun pruneSessionWalForStorage(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance { session ->
			pruneSessionWalForStorageAuthenticated(
				safeOrdinal = safeOrdinal,
				createdBeforeMs = createdBeforeMs,
				limit = limit,
				session = session,
			)
		}
	}

	private suspend fun removeSessionWalOwnersForPruneAuthenticated(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
		session: OwnerMaintenanceSession,
	): StepsCountDomainMaintenanceResult {
		require(safeOrdinal >= 0L)
		require(createdBeforeMs >= 0L)
		if (limit !in 1..MAX_WAL_PRUNE_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		session.requireActiveSnapshot()
		if (!session.schemaAvailable) return StepsCountDomainMaintenanceResult.SchemaUnavailable
		val sqlite = database.openHelper.writableDatabase
		currentCoroutineContext().ensureActive()
		sqlite.authenticateStepsWalMaintenanceSnapshot(session)
		val candidates = queryWalPruneCandidates(
			sqlite = sqlite,
			safeOrdinal = safeOrdinal,
			createdBeforeMs = createdBeforeMs,
			limit = limit,
			session = session,
		)
		val timelineCache = session.ownerlessStepsManifestTimelineCache
		var removedOwners = 0L
		var removedReceipts = 0L
		try {
			val ownerKeys = authenticateWalPruneOwnerKeys(candidates, session, timelineCache)
			timelineCache.clearForMutation()
			ownerKeys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
				currentCoroutineContext().ensureActive()
				when (
					val result = removeOwnersAuthenticated(
						session = session,
						keys = chunk,
						requireEveryOwner = true,
					)
				) {
					is StepsCountDomainMaintenanceResult.Applied -> {
						removedOwners += result.removedOwners
						removedReceipts += result.removedReceipts
					}
					StepsCountDomainMaintenanceResult.SchemaUnavailable,
					StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable,
					StepsCountDomainMaintenanceResult.Overflow,
					-> throw CountDomainStoredEvidenceException()
				}
			}
		} finally {
			timelineCache.invalidate()
		}
		return StepsCountDomainMaintenanceResult.Applied(removedOwners, removedReceipts)
	}

	private suspend fun pruneSessionWalForStorageAuthenticated(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
		session: OwnerMaintenanceSession,
	): StepsCountDomainMaintenanceResult {
		require(safeOrdinal >= 0L)
		require(createdBeforeMs >= 0L)
		if (limit !in 1..MAX_WAL_PRUNE_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		session.requireActiveSnapshot()
		if (!session.schemaAvailable) return StepsCountDomainMaintenanceResult.SchemaUnavailable
		val sqlite = database.openHelper.writableDatabase
		currentCoroutineContext().ensureActive()
		sqlite.authenticateStepsWalMaintenanceSnapshot(session)
		val candidates = queryWalPruneCandidates(
			sqlite = sqlite,
			safeOrdinal = safeOrdinal,
			createdBeforeMs = createdBeforeMs,
			limit = limit,
			session = session,
		).boundedOwnerlessRunPrefix()
		val timelineCache = session.ownerlessStepsManifestTimelineCache
		var removedOwners = 0L
		var removedReceipts = 0L
		var removedWalRows = 0
		try {
			val ownerKeys = authenticateWalPruneOwnerKeys(candidates, session, timelineCache)
			timelineCache.clearForMutation()
			ownerKeys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
				currentCoroutineContext().ensureActive()
				when (
					val result = removeOwnersAuthenticated(
						session = session,
						keys = chunk,
						requireEveryOwner = true,
					)
				) {
					is StepsCountDomainMaintenanceResult.Applied -> {
						removedOwners += result.removedOwners
						removedReceipts += result.removedReceipts
					}
					StepsCountDomainMaintenanceResult.SchemaUnavailable,
					StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable,
					StepsCountDomainMaintenanceResult.Overflow,
					-> throw CountDomainStoredEvidenceException()
				}
			}
			val legacy = candidates.filter(StepsWalPruneCandidate::isLegacyV27)
			legacy.chunked(WAL_PRUNE_CANDIDATE_PAGE_SIZE).forEach { chunk ->
				currentCoroutineContext().ensureActive()
				session.requireActiveSnapshot()
				val authority = sqlite.requireLegacyV27StepsWalRetentionAuthority()
				val invalidationEffect = sqlite.roomDeleteInvalidationEffect(
					"source_event_wal",
					chunk.size.toLong(),
				)
				val deleted = sqlite.deleteAuthenticatedLegacyV27StepsWal(
					chunk.map { it.cursor.admissionOrdinal },
					authority,
				)
				session.acceptOwnedChanges(Math.addExact(deleted.toLong(), invalidationEffect))
				removedWalRows += deleted
			}
			val current = candidates.filterNot(StepsWalPruneCandidate::isLegacyV27)
			current.chunked(WAL_PRUNE_CANDIDATE_PAGE_SIZE).forEach { chunk ->
				currentCoroutineContext().ensureActive()
				session.requireActiveSnapshot()
				val invalidationEffect = sqlite.roomDeleteInvalidationEffect(
					"source_event_wal",
					chunk.size.toLong(),
				)
				val deleted = sqlite.deleteCurrentStepsWalCandidates(chunk)
				session.acceptOwnedChanges(Math.addExact(deleted.toLong(), invalidationEffect))
				removedWalRows += deleted
			}
		} finally {
			timelineCache.invalidate()
		}
		return StepsCountDomainMaintenanceResult.Applied(
			removedOwners = removedOwners,
			removedReceipts = removedReceipts,
			walEventsDeleted = removedWalRows,
		)
	}

	private suspend fun queryWalPruneCandidates(
		sqlite: SupportSQLiteDatabase,
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
		session: OwnerMaintenanceSession,
	): List<StepsWalPruneCandidate> {
		val candidates = ArrayList<StepsWalPruneCandidate>(limit)
		var remaining = limit
		var cursor: StepsWalPruneCursor? = null
		while (remaining > 0) {
			currentCoroutineContext().ensureActive()
			session.requireActiveSnapshot()
			val page = sqlite.querySessionWalPruneOwnerPage(
				safeOrdinal = safeOrdinal,
				createdBeforeMs = createdBeforeMs,
				after = cursor,
				limit = minOf(remaining, WAL_PRUNE_CANDIDATE_PAGE_SIZE),
			)
			if (page.isEmpty()) break
			candidates += page
			if (candidates.size > MAX_WAL_PRUNE_BATCH) {
				throw CountDomainStoredEvidenceException()
			}
			session.checkpointAndRequireUnchanged(
				StepsCountDomainMaintenanceCheckpoint.WAL_CANDIDATE_PAGE_AUTHENTICATED,
			)
			remaining -= page.size
			val next = page.last().cursor
			if (cursor != null && next <= cursor) throw CountDomainStoredEvidenceException()
			cursor = next
		}
		return candidates
	}

	private suspend fun authenticateWalPruneOwnerKeys(
		candidates: List<StepsWalPruneCandidate>,
		session: OwnerMaintenanceSession,
		timelineCache: OwnerlessStepsManifestTimelineCache,
	): List<StepsCountDomainOwnerLookupKey> {
		val sqlite = database.openHelper.writableDatabase
		val ownerKeys = mutableListOf<StepsCountDomainOwnerLookupKey>()
		val ownerlessCandidates = mutableListOf<OwnerlessStepsWalCandidate>()
		candidates.forEach { candidate ->
			currentCoroutineContext().ensureActive()
			session.requireActiveSnapshot()
			val ownerKey = candidate.ownerKey ?: return@forEach
			if (sqlite.hasExactCountDomainOwner(ownerKey)) {
				ownerKeys += ownerKey
			} else {
				sqlite.requireAuthenticMissingCountDomainOwnerKeys(listOf(ownerKey))
				ownerlessCandidates += prepareOwnerlessShadowStepsWal(
					candidate = candidate,
					ownerKey = ownerKey,
					session = session,
				)
			}
		}
		val timelines = loadOwnerlessStepsManifestAuthority(
			candidates = ownerlessCandidates,
			session = session,
			timelineCache = timelineCache,
		)
		ownerlessCandidates.forEach { candidate ->
			currentCoroutineContext().ensureActive()
			requireOwnerlessShadowStepsWal(
				candidate = candidate,
				timeline = timelines[candidate.runKey]
					?: throw CountDomainStoredEvidenceException(),
				session = session,
			)
		}
		return ownerKeys
	}

	@Suppress("ComplexCondition", "LongMethod")
	private suspend fun prepareOwnerlessShadowStepsWal(
		candidate: StepsWalPruneCandidate,
		ownerKey: StepsCountDomainOwnerLookupKey,
		session: OwnerMaintenanceSession,
	): OwnerlessStepsWalCandidate {
		val wal = database.sourceEventWalDao()
			.getByAdmissionOrdinal(candidate.cursor.admissionOrdinal)
			?: throw CountDomainStoredEvidenceException()
		val sqlite = database.openHelper.writableDatabase
		if (
			wal.admissionOrdinal != candidate.cursor.admissionOrdinal ||
			wal.eventId != candidate.eventId ||
			wal.createdAtMs != candidate.cursor.createdAtMs ||
			wal.authorizationPurposeEligibilityMask !=
			candidate.authorizationPurposeEligibilityMask ||
			wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			!wal.hasQualifiedIntegrity() ||
			wal.sourceSequence <= 0L ||
			wal.registrationGeneration <= 0L ||
			wal.sourceInstanceId.isBlank() ||
			wal.physicalConfigurationFingerprint.isNullOrBlank() ||
			wal.authorizationRevision?.let { it > 0L } != true ||
			wal.authorizationFingerprint.isNullOrBlank() ||
			wal.authorizationPurposeEligibilityMask !in 1L..STEPS_WAL_ALLOWED_PURPOSE_MASK ||
			wal.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			wal.logicalTrackingId.isNullOrBlank() ||
			wal.serviceRunId.isNullOrBlank() ||
			wal.configRevision?.let { it > 0L } != true ||
			wal.sourcePolicyRevision?.let { it > 0L } != true ||
			wal.captureConsentEpoch?.let { it >= 0L } != true ||
			wal.sessionManifestRevision?.let { it > 0L } != true ||
			wal.lifecycleLeaseGeneration?.let { it > 0L } != true ||
			wal.wallTimeMs?.let { it == wal.acquiredAtMs } != true ||
			!sqlite.hasExactOwnerlessStepsWalAttributionStorage(wal)
		) {
			throw CountDomainStoredEvidenceException()
		}
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: throw CountDomainStoredEvidenceException()
		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch) {
			throw CountDomainStoredEvidenceException()
		}
		val registration = database.sourceBrokerDao().registration(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			wal.registrationGeneration,
		) ?: throw CountDomainStoredEvidenceException()
		if (!sqlite.hasExactOwnerlessStepsProviderRegistrationStorage(registration) ||
			!registration.authenticatesOwnerlessStepsWal(wal)
		) {
			throw CountDomainStoredEvidenceException()
		}

		val logicalTrackingId = requireNotNull(wal.logicalTrackingId)
		val serviceRunId = requireNotNull(wal.serviceRunId)
		val manifestRevision = requireNotNull(wal.sessionManifestRevision)
		val runKey = OwnerlessStepsManifestRunKey(logicalTrackingId, serviceRunId)
		if (candidate.ownerlessRunKey != runKey) {
			throw CountDomainStoredEvidenceException()
		}
		return OwnerlessStepsWalCandidate(
			candidate = candidate,
			ownerKey = ownerKey,
			wal = wal,
			runKey = runKey,
			manifestRevision = manifestRevision,
		)
	}

	private suspend fun loadOwnerlessStepsManifestAuthority(
		candidates: List<OwnerlessStepsWalCandidate>,
		session: OwnerMaintenanceSession,
		timelineCache: OwnerlessStepsManifestTimelineCache,
	): Map<OwnerlessStepsManifestRunKey, OwnerlessStepsManifestRunTimeline> {
		val sessionDao = database.sourceSessionDao()
		val candidatesByRun = candidates.groupBy(OwnerlessStepsWalCandidate::runKey)
		return buildMap {
			for ((runKey, runCandidates) in candidatesByRun) {
				currentCoroutineContext().ensureActive()
				session.requireActiveSnapshot()
				val timeline = timelineCache.getOrLoad(runKey) {
					val run = sessionDao.serviceRun(runKey.serviceRunId)
						?.takeIf {
							it.serviceRunId == runKey.serviceRunId &&
								it.logicalTrackingId == runKey.logicalTrackingId
						}
						?: throw CountDomainStoredEvidenceException()
					val manifests = authenticateOwnerlessStepsServiceRunManifestTimeline(
						run,
					) { afterRevision, pageLimit ->
						sessionDao.rawManifestsForServiceRunAfterRevision(
							logicalTrackingId = runKey.logicalTrackingId,
							serviceRunId = runKey.serviceRunId,
							afterRevision = afterRevision,
							limit = pageLimit,
						)
					}
					OwnerlessStepsManifestRunTimeline(runKey, run, manifests)
				}
				authenticateOwnerlessStepsManifestSourceBindings(
					timeline = timeline,
					manifestRevisions = runCandidates.map(
						OwnerlessStepsWalCandidate::manifestRevision,
					),
				) { revisionChunk, bindingLimit ->
					session.requireActiveSnapshot()
					sessionDao.rawManifestSourcesForRevisions(
						logicalTrackingId = runKey.logicalTrackingId,
						manifestRevisions = revisionChunk,
						limit = bindingLimit,
					)
				}
				session.checkpointAndRequireUnchanged(
					StepsCountDomainMaintenanceCheckpoint.WAL_OWNERLESS_RUN_AUTHENTICATED,
				)
				put(runKey, timeline)
			}
		}
	}

	@Suppress("ComplexCondition", "LongMethod")
	private suspend fun requireOwnerlessShadowStepsWal(
		candidate: OwnerlessStepsWalCandidate,
		timeline: OwnerlessStepsManifestRunTimeline,
		session: OwnerMaintenanceSession,
	) {
		session.requireActiveSnapshot()
		val wal = candidate.wal
		val ownerKey = candidate.ownerKey
		val sqlite = database.openHelper.writableDatabase
		val serviceRun = timeline.run
		val manifest = timeline.manifestsByRevision[candidate.manifestRevision]
			?: throw CountDomainStoredEvidenceException()
		val sources = timeline.sourcesByRevision[candidate.manifestRevision]
			?: throw CountDomainStoredEvidenceException()
		if (manifest.logicalTrackingId != candidate.runKey.logicalTrackingId ||
			manifest.serviceRunId != candidate.runKey.serviceRunId ||
			manifest.sourcePolicyRevision != wal.sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != wal.configRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			serviceRun.leaseGeneration != wal.lifecycleLeaseGeneration
		) {
			throw CountDomainStoredEvidenceException()
		}
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		} ?: throw CountDomainStoredEvidenceException()
		val legacyWriter = binding.isExactLegacyStepsWriterBinding(wal)
		val canonicalWriter = binding.isExactCanonicalStepsWriterBinding(wal)
		if (!legacyWriter && !canonicalWriter) {
			throw CountDomainStoredEvidenceException()
		}
		val captureMode = manifest.sessionMode.stepsCaptureModeMaskOrNull()
			?: throw CountDomainStoredEvidenceException()
		if (database.sourceProjectionStateDao().registration(
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			) != null
		) {
			throw CountDomainStoredEvidenceException()
		}
		val lanes = database.sourceProjectionStateDao().productLanesByProjection(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		)
		if (sqlite.hasMalformedStepsWalPruneLaneAttribution() ||
			lanes.any {
				!sqlite.hasExactStepsWalPruneLaneStorage(it) ||
					!it.hasAuthenticStepsWalPruneShape()
			}
		) {
			throw CountDomainStoredEvidenceException()
		}
		val lane = lanes.singleOrNull { lane ->
			(binding.writerBindingGeneration == null ||
				lane.bindingGeneration == binding.writerBindingGeneration) &&
				lane.captureModeMask and captureMode != 0L &&
				lane.activationOrdinal <= wal.admissionOrdinal &&
				lane.contiguousAdmissionOrdinal >= wal.admissionOrdinal &&
				lane.captureAdmissionCutoffOrdinal?.let {
					it >= wal.admissionOrdinal
				} != false
		} ?: throw CountDomainStoredEvidenceException()
		val shadowAttributed = when (lane.productStage) {
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW ->
				manifest.rolloutRevision >= lane.activatedRolloutRevision
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ->
				manifest.rolloutRevision < lane.activatedRolloutRevision
			else -> false
		}
		if (!legacyWriter || canonicalWriter || !shadowAttributed) {
			throw CountDomainStoredEvidenceException()
		}

		if (sqlite.longForQuery(
				"SELECT COUNT(*) FROM main.steps_count_domain_receipt " +
					"WHERE owner_kind = ? AND owner_identity = ?",
				arrayOf(ownerKey.ownerKind, ownerKey.ownerIdentity),
			) != 0L ||
			sqlite.longForQuery(
				"SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker " +
					"WHERE owner_kind = ? AND owner_identity = ?",
				arrayOf(ownerKey.ownerKind, ownerKey.ownerIdentity),
			) != 0L ||
			sqlite.longForQuery(
				"SELECT COUNT(*) FROM main.step_fact_revision " +
					"WHERE source_admission_ordinal = ? OR source_event_id = ?",
				arrayOf(wal.admissionOrdinal, wal.eventId),
			) != 0L
		) {
			throw CountDomainStoredEvidenceException()
		}
	}

	private fun OwnerMaintenanceSession.requireActiveSnapshot() {
		check(
			authenticates(this@StepsCountDomainStore, maintenanceAuthorityIdentity) &&
				database.inTransaction(),
		) { "Steps count-domain maintenance authority is no longer active" }
		requireUnchangedMutationVersion()
	}

	private suspend fun OwnerMaintenanceSession.checkpointAndRequireUnchanged(
		value: StepsCountDomainMaintenanceCheckpoint,
	) {
		checkpoint(value)
		requireActiveSnapshot()
	}

	private suspend fun authenticateCountDomainWrite(
		block: suspend () -> StepsCountDomainWriteResult,
	): StepsCountDomainWriteResult = try {
		block()
	} catch (_: CountDomainStoredEvidenceException) {
		StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}

	private suspend fun authenticateCountDomainMaintenance(
		block: suspend () -> StepsCountDomainMaintenanceResult,
	): StepsCountDomainMaintenanceResult = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: CountDomainMaintenanceOverflowException) {
		StepsCountDomainMaintenanceResult.Overflow
	} catch (_: CountDomainStoredEvidenceException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: SQLiteException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: IllegalArgumentException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: IllegalStateException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: ArithmeticException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	}

	private fun appendBoundOwner(
		receipt: StepsCountDomainReceiptEntity,
		scopeIdentity: String,
		ownerEffectChecksum: String,
		linkedAtMs: Long,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult {
		if (receipt.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			receipt,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = receipt.ownerKind,
				scopeIdentity = scopeIdentity,
				ownerIdentity = receipt.ownerIdentity,
				ownerRevision = receipt.ownerRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
				receiptIdentity = receipt.receiptIdentity,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = linkedAtMs,
			),
			completenessMarker,
		)
	}

	private fun appendTerminalUnproven(
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		ownerEffectChecksum: String,
		linkedAtMs: Long,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult = appendOwner(
		receipt = null,
		owner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
			receiptIdentity = null,
			ownerEffectChecksum = ownerEffectChecksum,
			linkedAtMs = linkedAtMs,
		),
		completenessMarker = completenessMarker,
	)

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun appendOwner(
		receipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult {
		val sqlite = database.openHelper.writableDatabase
		writeSchemaFailure()?.let { return it }
		val latest = sqlite.queryLatestOwner(
			owner.ownerKind,
			owner.scopeIdentity,
			owner.ownerIdentity,
		)
		if (receipt != null && receipt.effectChecksum != owner.ownerEffectChecksum) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if ((owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) !=
			(completenessMarker != null)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker != null && !completenessMarker.matches(owner)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker?.terminalState ==
			StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
			receipt?.completionEvidenceChecksum != completenessMarker.evidenceChecksum
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val exact = sqlite.queryOwner(
			owner.ownerKind,
			owner.scopeIdentity,
			owner.ownerIdentity,
			owner.ownerRevision,
		)
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			exact != latest
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (latest?.ownerKind ==
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS &&
			latest.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			exact != latest
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			exact != latest &&
			owner.operation != StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			!(
				owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT &&
					owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
				)
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (exact != null) {
			val storedReceipt = exact.receiptIdentity?.let(sqlite::queryReceipt)
			val storedMarker = if (
				exact.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				sqlite.queryCompletenessMarker(exact.ownerIdentity, exact.ownerRevision)
			} else {
				null
			}
			return if (exact == owner && storedReceipt == receipt &&
				storedMarker == completenessMarker
			) {
				StepsCountDomainWriteResult.EXACT_REPLAY
			} else {
				StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (latest != null && latest.scopeIdentity != owner.scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val revisionIsValid = when {
			latest == null && owner.operation ==
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> true
			owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS -> {
				latest == null || owner.ownerRevision > latest.ownerRevision
			}
			else -> {
				owner.ownerRevision == (latest?.ownerRevision ?: 0L) + 1L
			}
		}
		if (!revisionIsValid) {
			return StepsCountDomainWriteResult.REVISION_GAP
		}
		if (receipt != null && latest != null &&
			owner.ownerKind in FACT_OWNER_KINDS
		) {
			val previousReceipt = latest.receiptIdentity?.let(sqlite::queryReceipt)
				?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			if (!receipt.hasSameImmutableDomain(previousReceipt)) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (receipt != null) {
			sqlite.execSQL(INSERT_RECEIPT_SQL, receipt.bindArguments())
			if (sqlite.queryReceipt(receipt.receiptIdentity) != receipt) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		sqlite.execSQL(INSERT_OWNER_SQL, owner.bindArguments())
		if (sqlite.queryOwner(
				owner.ownerKind,
				owner.scopeIdentity,
				owner.ownerIdentity,
				owner.ownerRevision,
			) != owner
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker != null) {
			sqlite.execSQL(INSERT_COMPLETENESS_MARKER_SQL, completenessMarker.bindArguments())
			if (sqlite.queryCompletenessMarker(
					completenessMarker.ownerIdentity,
					completenessMarker.ownerRevision,
				) != completenessMarker
			) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		return StepsCountDomainWriteResult.INSERTED
	}

	private fun loadExactOwner(
		key: StepsCountDomainOwnerLookupKey,
		scopeIdentity: String,
	): StepsCountDomainStoredOwner? {
		val sqlite = database.openHelper.writableDatabase
		if (StepsCountDomainSchema.inspect(sqlite) != StepsCountDomainSchemaState.ValidV2) {
			return null
		}
		val owner = sqlite.queryOwner(
			key.ownerKind,
			scopeIdentity,
			key.ownerIdentity,
			key.ownerRevision,
		) ?: return null
		return StepsCountDomainStoredOwner(
			owner,
			owner.receiptIdentity?.let(sqlite::queryReceipt),
			if (owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				sqlite.queryCompletenessMarker(owner.ownerIdentity, owner.ownerRevision)
			} else {
				null
			},
		)
	}

	private fun writeSchemaFailure(): StepsCountDomainWriteResult? =
		when (StepsCountDomainSchema.inspect(database.openHelper.writableDatabase)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			-> StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
			StepsCountDomainSchemaState.ValidV2 -> null
			StepsCountDomainSchemaState.Incompatible ->
				StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}

	private suspend fun canonicalProjectionCommittedThrough(admissionOrdinal: Long): Boolean =
		database.sourceProjectionStateDao().productLanesByProjection(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		).any { lane ->
			lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
				lane.activationOrdinal <= admissionOrdinal &&
				lane.contiguousAdmissionOrdinal >= admissionOrdinal &&
				lane.captureAdmissionCutoffOrdinal?.let { it >= admissionOrdinal } != false
		}

	@Suppress("LongParameterList")
	private fun nativeReceipt(
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		counterDomainToken: StepsCounterDomainToken,
		registrationGeneration: Long,
		collectedDataEpoch: Long,
		authorityRevision: Long,
		authorityFingerprint: String,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
		completionEvidenceChecksum: String?,
	): StepsCountDomainReceiptEntity {
		val domainIdentity =
			StepsCountDomainReceiptIntegrity.counterDomainIdentity(counterDomainToken)
		val countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
	}

	private fun nativeReceiptFrom(
		source: StepsCountDomainReceiptEntity,
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
		completionEvidenceChecksum: String?,
	): StepsCountDomainReceiptEntity {
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = source.domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = source.domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
	}

	private fun completenessMarker(
		row: SourceSessionCompletenessEntity,
		ownerIdentity: String,
		ownerRevision: Long,
		retirementEvidence: StepsCountDomainRetirementEvidence,
		timelineChecksum: String,
		exactComplete: Boolean,
	): StepsCountDomainCompletenessMarkerEntity {
		val terminalState = if (exactComplete) {
			StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
		} else {
			StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		}
		val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			terminalState = terminalState,
			lastAdmissionOrdinal = row.lastAdmissionOrdinal,
			lastSourceSequence = row.lastSourceSequence,
			providerFlushOutcome = retirementEvidence.providerFlushOutcome,
			registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
			registrationTimelineChecksum = timelineChecksum,
		)
		return StepsCountDomainCompletenessMarkerEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			terminalState = terminalState,
			lastAdmissionOrdinal = row.lastAdmissionOrdinal,
			lastSourceSequence = row.lastSourceSequence,
			providerFlushOutcome = retirementEvidence.providerFlushOutcome,
			registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
			registrationTimelineChecksum = timelineChecksum,
			evidenceChecksum = checksum,
		)
	}

	private fun StepsCountDomainStoredOwner.isAuthentic(): Boolean {
		val receipt = receipt ?: return false
		return owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			owner.receiptIdentity == receipt.receiptIdentity &&
			owner.ownerKind == receipt.ownerKind &&
			owner.scopeIdentity == receipt.scopeIdentity &&
			owner.ownerIdentity == receipt.ownerIdentity &&
			owner.ownerRevision == receipt.ownerRevision &&
			owner.ownerEffectChecksum == receipt.effectChecksum &&
			StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt) &&
			if (owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				completenessMarker?.let {
					it.terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
						it.evidenceChecksum == receipt.completionEvidenceChecksum
				} == true
			} else {
				completenessMarker == null && receipt.completionEvidenceChecksum == null
			}
	}

	private companion object {
		const val COMPLETENESS_COVERAGE_VERSION = 1
		const val MAX_OWNER_LOOKUP = 512
		const val OWNER_QUERY_CHUNK = 100
		const val LATEST_QUERY_CHUNK = 200
		const val RECEIPT_QUERY_CHUNK = 400
		const val MARKER_QUERY_CHUNK = 200
		const val MAX_MAINTENANCE_OWNER_BATCH = 400
		const val MAX_WAL_PRUNE_BATCH = 2_048
		const val MAX_COMPLETENESS_TIMELINE = 64
		val FACT_OWNER_KINDS = setOf(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		)
		const val INSERT_RECEIPT_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_receipt (" +
					"receipt_identity, domain_identity, owner_kind, scope_identity, " +
					"owner_identity, owner_revision, " +
				"registration_generation, collected_data_epoch, authority_revision, " +
				"authority_fingerprint, coverage_kind, coverage_version, count_domain_version, " +
					"effect_checksum, completion_evidence_checksum) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_OWNER_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_owner_revision (" +
				"owner_kind, scope_identity, owner_identity, owner_revision, operation, " +
				"receipt_identity, owner_effect_checksum, linked_at_ms) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_COMPLETENESS_MARKER_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_completeness_marker (" +
				"owner_kind, owner_identity, owner_revision, terminal_state, " +
				"last_admission_ordinal, last_source_sequence, provider_flush_outcome, " +
				"registration_removal_outcome, registration_timeline_checksum, evidence_checksum) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
	}
}

/**
 * Transaction-owned serialized AppDatabase hook. The caller must already be inside the existing
 * collected-data clear transaction, after source payload fences are installed and before commit.
 */
suspend fun clearStepsCountDomainEvidenceInCurrentTransaction(
	database: AppDatabase,
	mode: StepsCountDomainFullClearMode,
): StepsCountDomainMaintenanceResult {
	check(database.inTransaction()) {
		"Steps count-domain clear requires the caller's existing AppDatabase transaction"
	}
	return StepsCountDomainStore(database).clear(mode)
}

/** Standalone wrapper for callers that do not already own a Room transaction. */
suspend fun AppDatabase.clearStepsCountDomainEvidenceInTransaction(
	mode: StepsCountDomainFullClearMode,
): StepsCountDomainMaintenanceResult = withTransaction {
	clearStepsCountDomainEvidenceInCurrentTransaction(this, mode)
}

/**
 * Publishes a Steps evidence revision on the wall-clock axis while preserving monotonic storage.
 *
 * Runtime checkpoint causal order remains in its dedicated elapsed-realtime field.
 */
suspend fun publishStepsCountDomainEvidenceRevisionAtWallTime(
	database: AppDatabase,
	wallTimeMs: Long,
) {
	check(database.inTransaction()) {
		"Steps evidence publication requires the caller's existing AppDatabase transaction"
	}
	require(wallTimeMs >= 0L)
	val dao = database.sourceEvidenceStateDao()
	dao.ensure()
	val current = requireNotNull(dao.get()) {
		"Source-evidence state disappeared inside the Steps transaction"
	}
	check(dao.incrementRevision(maxOf(current.updatedAtMs, wallTimeMs)) == 1) {
		"Unable to publish terminal Steps count-domain completeness"
	}
}

fun SourceSessionCompletenessEntity.withMonotonicStepsCountDomainRevision(
	existing: SourceSessionCompletenessEntity?,
): SourceSessionCompletenessEntity {
	require(updatedAtMs in 0L until Long.MAX_VALUE)
	if (existing == null) return this
	require(
		logicalTrackingId == existing.logicalTrackingId &&
			serviceRunId == existing.serviceRunId &&
			sourceKind == existing.sourceKind &&
			sourceInstanceId == existing.sourceInstanceId &&
			registrationGeneration == existing.registrationGeneration &&
			existing.updatedAtMs in 0L until Long.MAX_VALUE,
	)
	if (copy(updatedAtMs = existing.updatedAtMs) == existing) return existing
	val nextRevisionWallTime = existing.updatedAtMs + 1L
	require(nextRevisionWallTime < Long.MAX_VALUE)
	return copy(
		updatedAtMs = maxOf(
			updatedAtMs,
			nextRevisionWallTime,
		),
	)
}

private fun SourceSessionCompletenessEntity.hasExactCompleteRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = registrationGeneration > 0L &&
	appDrainComplete &&
	providerCoverage == "CALLBACKS_ENTERED_BEFORE_BARRIER" &&
	stopStatus == "COMPLETE" &&
	unresolvedSequenceStart == null &&
	unresolvedSequenceEnd == null &&
	lastAdmissionOrdinal != null &&
	lastSourceSequence != null &&
	evidence.providerFlushOutcome in
		setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED") &&
	evidence.registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

private fun SourceSessionCompletenessEntity.hasTerminalRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = StepsRetirementTerminality.isTerminal(
	stopStatus = stopStatus,
	registrationRemovalOutcome = evidence.registrationRemovalOutcome,
	providerFlushOutcome = evidence.providerFlushOutcome,
	providerCoverage = providerCoverage,
	appDrainComplete = appDrainComplete,
	lastAdmissionOrdinal = lastAdmissionOrdinal,
	unresolvedSequenceStart = unresolvedSequenceStart,
	unresolvedSequenceEnd = unresolvedSequenceEnd,
)

private fun StepsCountDomainRetirementEvidence.hasFinalRegistrationRemoval(): Boolean =
	registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

private fun StepsCountDomainCompletenessMarkerEntity.asUnproven():
	StepsCountDomainCompletenessMarkerEntity {
	if (terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN) return this
	val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
		ownerKind = ownerKind,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		providerFlushOutcome = providerFlushOutcome,
		registrationRemovalOutcome = registrationRemovalOutcome,
		registrationTimelineChecksum = registrationTimelineChecksum,
	)
	return copy(
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		evidenceChecksum = checksum,
	)
}

private fun StepsCountDomainCompletenessMarkerEntity.matches(
	owner: StepsCountDomainOwnerRevisionEntity,
): Boolean = ownerKind == owner.ownerKind &&
	ownerIdentity == owner.ownerIdentity &&
	ownerRevision == owner.ownerRevision &&
	when (owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		else -> false
	}

private fun StepsCountDomainReceiptEntity.bindArguments(): Array<Any?> = arrayOf(
	receiptIdentity,
	domainIdentity,
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	registrationGeneration,
	collectedDataEpoch,
	authorityRevision,
	authorityFingerprint,
	coverageKind,
	coverageVersion,
	countDomainVersion,
	effectChecksum,
	completionEvidenceChecksum,
)

private fun StepsCountDomainOwnerRevisionEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	operation,
	receiptIdentity,
	ownerEffectChecksum,
	linkedAtMs,
)

private fun StepsCountDomainCompletenessMarkerEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	ownerIdentity,
	ownerRevision,
	terminalState,
	lastAdmissionOrdinal,
	lastSourceSequence,
	providerFlushOutcome,
	registrationRemovalOutcome,
	registrationTimelineChecksum,
	evidenceChecksum,
)

private fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion

private fun SupportSQLiteDatabase.queryOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 2",
		arrayOf(ownerKind, scopeIdentity, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		owner
	}
}

private fun SupportSQLiteDatabase.queryLatestOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"ORDER BY owner_revision DESC LIMIT 2",
		arrayOf(ownerKind, scopeIdentity, ownerIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) {
			val olderOrMalformed = cursor.toStepsCountDomainOwner()
			if (olderOrMalformed.ownerKind != ownerKind ||
				olderOrMalformed.scopeIdentity != scopeIdentity ||
				olderOrMalformed.ownerIdentity != ownerIdentity
			) {
				throw CountDomainStoredEvidenceException()
			}
		}
		owner
	}
}

private fun SupportSQLiteDatabase.queryReceipt(
	receiptIdentity: String,
): StepsCountDomainReceiptEntity? {
	requireAuthenticCountDomainReceiptKeys(listOf(receiptIdentity))
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity = ? LIMIT 2",
		arrayOf(receiptIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val receipt = cursor.toStepsCountDomainReceipt()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		receipt
	}
}

private fun SupportSQLiteDatabase.queryCompletenessMarker(
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainCompletenessMarkerEntity? {
	requireAuthenticCountDomainMarkerKeys(listOf(ownerIdentity to ownerRevision))
	return query(
		"SELECT * FROM main.steps_count_domain_completeness_marker " +
			"WHERE owner_identity = ? AND owner_revision = ? LIMIT 2",
		arrayOf(ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val marker = cursor.toStepsCountDomainCompletenessMarker()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		marker
	}
}

private fun SupportSQLiteDatabase.queryOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): List<StepsCountDomainOwnerRevisionEntity> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainOwner())
		}
	}
}

private fun SupportSQLiteDatabase.queryReceiptChunk(
	identities: List<String>,
): List<StepsCountDomainReceiptEntity> {
	if (identities.isEmpty()) return emptyList()
	requireAuthenticCountDomainReceiptKeys(identities)
	val placeholders = List(identities.size) { "?" }.joinToString()
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"ORDER BY receipt_identity LIMIT ${identities.size + 1}",
		identities.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainReceipt())
		}
	}
}

private fun SupportSQLiteDatabase.queryCompletenessMarkerChunk(
		owners: List<StepsCountDomainOwnerRevisionEntity>,
	): List<StepsCountDomainCompletenessMarkerEntity> {
		if (owners.isEmpty()) return emptyList()
		requireAuthenticCountDomainMarkerKeys(
			owners.map { owner -> owner.ownerIdentity to owner.ownerRevision },
		)
		val predicate = owners.joinToString(" OR ") {
			"(owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = owners.flatMap { listOf(it.ownerIdentity, it.ownerRevision) }
		return query(
			"SELECT * FROM main.steps_count_domain_completeness_marker WHERE $predicate " +
				"ORDER BY owner_identity, owner_revision LIMIT ${owners.size + 1}",
			arguments.toTypedArray(),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.toStepsCountDomainCompletenessMarker())
			}
		}
	}

private fun SupportSQLiteDatabase.queryLatestOwnerChunk(
	keys: List<StepsCountDomainOwnerLineageKey>,
): List<Pair<StepsCountDomainOwnerLineageKey, Long>> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity) }
	return query(
		"SELECT owner_kind, owner_identity, MAX(owner_revision) AS latest_revision " +
			"FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"GROUP BY owner_kind, owner_identity LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLineageKey(
						cursor.requiredText("owner_kind"),
						cursor.requiredText("owner_identity"),
					) to cursor.requiredLong("latest_revision"),
				)
			}
		}
	}
}

private fun SupportSQLiteDatabase.deleteOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): Int {
	if (keys.isEmpty()) return 0
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return compileStatement(
		"DELETE FROM main.steps_count_domain_owner_revision WHERE $predicate",
	).use { statement ->
		arguments.forEachIndexed { index, value ->
			when (value) {
				is String -> statement.bindString(index + 1, value)
				is Long -> statement.bindLong(index + 1, value)
				else -> error("Unsupported count-domain owner key value")
			}
		}
		statement.executeUpdateDelete()
	}
}

private fun SupportSQLiteDatabase.deleteUnreferencedReceipts(
	receiptIdentities: List<String>,
): Long {
	if (receiptIdentities.isEmpty()) return 0L
	val placeholders = List(receiptIdentities.size) { "?" }.joinToString()
	val before = countUnreferencedReceipts(receiptIdentities)
	execSQL(
		"DELETE FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = steps_count_domain_receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
	return before
}

private fun SupportSQLiteDatabase.countUnreferencedReceipts(
	receiptIdentities: List<String>,
): Long {
	if (receiptIdentities.isEmpty()) return 0L
	val placeholders = List(receiptIdentities.size) { "?" }.joinToString()
	return longForQuery(
		"SELECT COUNT(*) FROM main.steps_count_domain_receipt AS receipt " +
			"WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
}

private suspend fun SupportSQLiteDatabase.queryTerminalCompactionCandidates(
	maximumRetainedTerminalOwners: Int,
	limit: Int,
	onPage: suspend () -> Unit,
): List<StepsCountDomainOwnerLookupKey> {
	var skipped = 0
	var cursor: TerminalOwnerCandidate? = null
	val candidates = mutableListOf<StepsCountDomainOwnerLookupKey>()
	while (candidates.size < limit) {
		currentCoroutineContext().ensureActive()
		val remainingToRead =
			maximumRetainedTerminalOwners.toLong() - skipped.toLong() +
				limit.toLong() - candidates.size.toLong()
		val page = queryTerminalCompactionCandidatePage(
			after = cursor,
			limit = minOf(COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE.toLong(), remainingToRead).toInt(),
		)
		if (page.isEmpty()) break
		onPage()
		page.forEach { candidate ->
			if (skipped < maximumRetainedTerminalOwners) {
				skipped += 1
			} else if (candidates.size < limit) {
				candidates += candidate.key
			}
		}
		val next = page.last()
		if (cursor != null && !next.isAfterTerminalCursor(cursor)) {
			throw CountDomainStoredEvidenceException()
		}
		cursor = next
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) break
	}
	return candidates
}

private fun SupportSQLiteDatabase.queryTerminalCompactionCandidatePage(
	after: TerminalOwnerCandidate?,
	limit: Int,
): List<TerminalOwnerCandidate> {
	if (limit <= 0) return emptyList()
	val cursorPredicate = if (after == null) {
		""
	} else {
		"AND (linked_at_ms < ? OR " +
			"(linked_at_ms = ? AND owner_kind > ?) OR " +
			"(linked_at_ms = ? AND owner_kind = ? AND owner_identity > ?) OR " +
			"(linked_at_ms = ? AND owner_kind = ? AND owner_identity = ? " +
			"AND owner_revision < ?)) "
	}
	val arguments = buildList<Any?> {
		if (after != null) {
			add(after.linkedAtMs)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.key.ownerIdentity)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.key.ownerIdentity)
			add(after.key.ownerRevision)
		}
		add(limit)
	}.toTypedArray()
	return query(
		"SELECT owner_kind, owner_identity, owner_revision " +
			", linked_at_ms " +
			"FROM main.steps_count_domain_owner_revision " +
			"WHERE operation IN ('RETRACT', 'UNPROVEN') " +
			cursorPredicate +
			"ORDER BY linked_at_ms DESC, owner_kind, owner_identity, owner_revision DESC " +
			"LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					TerminalOwnerCandidate(
						linkedAtMs = cursor.requiredLong("linked_at_ms"),
						key = StepsCountDomainOwnerLookupKey(
							cursor.requiredText("owner_kind"),
							cursor.requiredText("owner_identity"),
							cursor.requiredLong("owner_revision"),
						),
					),
				)
			}
		}
	}
}

private fun TerminalOwnerCandidate.isAfterTerminalCursor(
	previous: TerminalOwnerCandidate,
): Boolean =
	linkedAtMs < previous.linkedAtMs ||
		(linkedAtMs == previous.linkedAtMs && key.ownerKind > previous.key.ownerKind) ||
		(linkedAtMs == previous.linkedAtMs &&
			key.ownerKind == previous.key.ownerKind &&
			key.ownerIdentity > previous.key.ownerIdentity) ||
		(linkedAtMs == previous.linkedAtMs &&
			key.ownerKind == previous.key.ownerKind &&
			key.ownerIdentity == previous.key.ownerIdentity &&
			key.ownerRevision < previous.key.ownerRevision)

private fun SupportSQLiteDatabase.querySessionWalPruneOwnerPage(
	safeOrdinal: Long,
	createdBeforeMs: Long,
	after: StepsWalPruneCursor?,
	limit: Int,
): List<StepsWalPruneCandidate> {
	val cursorPredicate = if (after == null) {
		""
	} else {
		"AND (wal.created_at_ms > ? OR " +
			"(wal.created_at_ms = ? AND wal.admission_ordinal > ?)) "
	}
	val arguments = buildList<Any?> {
		add(SourceDestinationOwnerEntity.SOURCE_STEPS)
		add(createdBeforeMs)
		add(safeOrdinal)
		if (after != null) {
			add(after.createdAtMs)
			add(after.createdAtMs)
			add(after.admissionOrdinal)
		}
		add(SourceBrokerPurpose.MASK_SESSION_CAPTURE)
		add(limit)
	}.toTypedArray()
	return query(
		"SELECT wal.admission_ordinal, wal.event_id, wal.created_at_ms, " +
			"wal.authorization_purpose_eligibility_mask, wal.logical_tracking_id, " +
			"wal.service_run_id " +
			"FROM source_event_wal AS wal " +
			"WHERE wal.source_kind = ? AND wal.created_at_ms < ? AND wal.admission_ordinal <= ? " +
			cursorPredicate +
			"AND NOT (" +
			"wal.logical_tracking_id IS NOT NULL AND wal.service_run_id IS NOT NULL AND " +
			"wal.admission_ordinal = (" +
			"SELECT MAX(candidate.admission_ordinal) FROM source_event_wal AS candidate " +
			"WHERE candidate.source_kind = wal.source_kind " +
			"AND candidate.source_instance_id = wal.source_instance_id " +
			"AND candidate.registration_generation = wal.registration_generation " +
			"AND candidate.logical_tracking_id = wal.logical_tracking_id " +
			"AND candidate.service_run_id = wal.service_run_id " +
			"AND (candidate.authorization_purpose_eligibility_mask & ?) != 0" +
			") AND (" +
			"EXISTS (SELECT 1 FROM provider_registration_generation AS registration " +
			"WHERE registration.source_kind = wal.source_kind " +
			"AND registration.source_instance_id = wal.source_instance_id " +
			"AND registration.registration_generation = wal.registration_generation " +
			"AND registration.status IN ('RESERVED', 'ACTIVE', 'RETIRING')) " +
			"OR EXISTS (SELECT 1 FROM source_service_run AS run " +
			"WHERE run.service_run_id = wal.service_run_id " +
			"AND run.logical_tracking_id = wal.logical_tracking_id " +
			"AND (run.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') OR " +
			"(run.completed_at_ms IS NULL AND NOT (" +
			V28_MIGRATION_TERMINAL_RUN_SQL + "))))" +
			")) ORDER BY wal.created_at_ms, wal.admission_ordinal LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				val admissionOrdinal = cursor.requiredLong(0)
				val eventId = cursor.requiredText(1)
				val candidateCursor = StepsWalPruneCursor(
					createdAtMs = cursor.requiredLong(2),
					admissionOrdinal = admissionOrdinal,
				)
				val eligibilityMask = cursor.requiredLong(3)
				val logicalTrackingId = cursor.nullableText("logical_tracking_id")
				val serviceRunId = cursor.nullableText("service_run_id")
				val captureEligible =
					eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
				val ownerlessRunKey = if (eligibilityMask != 0L && captureEligible) {
					if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
						throw CountDomainStoredEvidenceException()
					}
					OwnerlessStepsManifestRunKey(logicalTrackingId, serviceRunId)
				} else {
					if (eligibilityMask != 0L &&
						(logicalTrackingId != null || serviceRunId != null)
					) {
						throw CountDomainStoredEvidenceException()
					}
					null
				}
				if (admissionOrdinal <= 0L || eventId.isBlank() ||
					(after != null && candidateCursor <= after)
				) {
					throw CountDomainStoredEvidenceException()
				}
				add(
					StepsWalPruneCandidate(
						cursor = candidateCursor,
						eventId = eventId,
						authorizationPurposeEligibilityMask = eligibilityMask,
						ownerKey = if (
							eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
						) {
							StepsCountDomainOwnerLookupKey(
								ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
								ownerIdentity =
									StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
										admissionOrdinal,
										eventId,
									),
								ownerRevision = 1L,
							)
						} else {
							null
						},
						ownerlessRunKey = ownerlessRunKey,
					),
				)
			}
		}
	}
}

private fun List<StepsWalPruneCandidate>.boundedOwnerlessRunPrefix(): List<StepsWalPruneCandidate> {
	val distinctRuns = linkedSetOf<OwnerlessStepsManifestRunKey>()
	forEachIndexed { index, candidate ->
		val runKey = candidate.ownerlessRunKey ?: return@forEachIndexed
		if (runKey !in distinctRuns && distinctRuns.size >= OWNERLESS_STEPS_MAX_DISTINCT_RUNS) {
			return take(index)
		}
		distinctRuns += runKey
	}
	return this
}

private fun SupportSQLiteDatabase.requireAuthenticMissingCountDomainOwnerKeys(
	keys: List<StepsCountDomainOwnerLookupKey>,
) {
	for (chunk in keys.distinct().chunked(OWNER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"(CAST(owner_kind AS TEXT) = ? AND CAST(owner_identity AS TEXT) = ? " +
				"AND CAST(owner_revision AS INTEGER) = ?) OR " +
				"(owner_kind = ? AND owner_revision = ? AND typeof(owner_identity) != 'text') OR " +
				"(owner_identity = ? AND owner_revision = ? AND typeof(owner_kind) != 'text') OR " +
				"(owner_kind = ? AND owner_identity = ? AND typeof(owner_revision) != 'integer')"
		}
		val arguments = chunk.flatMap { key ->
			listOf(
				key.ownerKind,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerRevision,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerIdentity,
			)
		}
		val hidden = query(
			"SELECT 1 FROM main.steps_count_domain_owner_revision WHERE ($predicate) LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (hidden) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.hasExactCountDomainOwner(
	key: StepsCountDomainOwnerLookupKey,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision " +
		"WHERE typeof(owner_kind) = 'text' AND owner_kind = ? " +
		"AND typeof(owner_identity) = 'text' AND owner_identity = ? " +
		"AND typeof(owner_revision) = 'integer' AND owner_revision = ?",
	arrayOf(key.ownerKind, key.ownerIdentity, key.ownerRevision),
).let { count ->
	if (count !in 0L..1L) throw CountDomainStoredEvidenceException()
	count == 1L
}

private fun SupportSQLiteDatabase.hasExactOwnerlessStepsWalAttributionStorage(
	wal: SourceEventWalEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.source_event_wal WHERE admission_ordinal = ? " +
		"AND typeof(admission_ordinal) = 'integer' " +
		"AND typeof(event_id) = 'text' " +
		"AND typeof(provider_dedup_key) IN ('null', 'text') " +
		"AND typeof(delivery_identity) IN ('null', 'text') " +
		"AND typeof(delivery_unit_index) IN ('null', 'integer') " +
		"AND typeof(delivery_unit_count) IN ('null', 'integer') " +
		"AND typeof(logical_tracking_id) = 'text' " +
		"AND typeof(service_run_id) = 'text' " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(source_instance_id) = 'text' " +
		"AND typeof(registration_generation) = 'integer' " +
		"AND typeof(physical_configuration_fingerprint) = 'text' " +
		"AND typeof(authorization_revision) = 'integer' " +
		"AND typeof(authorization_purpose_eligibility_mask) = 'integer' " +
		"AND typeof(authorization_fingerprint) = 'text' " +
		"AND typeof(source_sequence) = 'integer' " +
		"AND typeof(config_revision) = 'integer' " +
		"AND typeof(plan_attribution) = 'integer' AND plan_attribution = ? " +
		"AND typeof(clock_domain_id) = 'text' " +
		"AND typeof(observed_elapsed_nanos) = 'integer' " +
		"AND typeof(observed_interval_start_nanos) IN ('null', 'integer') " +
		"AND typeof(received_elapsed_nanos) = 'integer' " +
		"AND typeof(received_wall_time_ms) IN ('null', 'integer') " +
		"AND typeof(wall_time_ms) = 'integer' " +
		"AND typeof(wall_time_uncertainty_ms) = 'integer' " +
		"AND typeof(captured_collected_data_epoch) = 'integer' " +
		"AND typeof(activity_automation_epoch) IN ('null', 'integer') " +
		"AND typeof(source_policy_revision) = 'integer' " +
		"AND typeof(capture_consent_epoch) = 'integer' " +
		"AND typeof(session_manifest_revision) = 'integer' " +
		"AND typeof(lifecycle_lease_generation) = 'integer' " +
		"AND typeof(acquired_at_ms) = 'integer' " +
		"AND typeof(quality_flags) = 'integer' " +
		"AND typeof(quality_confidence) IN ('null', 'real') " +
		"AND typeof(payload_version) = 'integer' " +
		"AND typeof(payload) = 'blob' " +
		"AND typeof(payload_checksum) = 'text' " +
		"AND typeof(integrity_identity) = 'text' " +
		"AND typeof(created_at_ms) = 'integer'",
	arrayOf(wal.admissionOrdinal, OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION),
) == 1L

private fun SupportSQLiteDatabase.hasExactOwnerlessStepsProviderRegistrationStorage(
	registration: ProviderRegistrationGenerationEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.provider_registration_generation " +
		"WHERE source_kind = ? AND registration_generation = ? " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(registration_generation) = 'integer' " +
		"AND typeof(source_instance_id) = 'text' " +
		"AND typeof(owner_scope) = 'text' " +
		"AND typeof(clock_domain_id) = 'text' " +
		"AND typeof(physical_configuration_fingerprint) = 'text' " +
		"AND typeof(collected_data_epoch) = 'integer' " +
		"AND typeof(provider_residency) = 'text' " +
		"AND typeof(provider_process_incarnation_id) IN ('null', 'text') " +
		"AND typeof(status) = 'text' " +
		"AND typeof(reserved_at_ms) = 'integer' " +
		"AND typeof(reserved_elapsed_realtime_nanos) = 'integer' " +
		"AND typeof(accepted_at_ms) IN ('null', 'integer') " +
		"AND typeof(accepted_elapsed_realtime_nanos) IN ('null', 'integer') " +
		"AND typeof(retired_at_ms) IN ('null', 'integer') " +
		"AND typeof(retired_elapsed_realtime_nanos) IN ('null', 'integer') " +
		"AND typeof(failure_code) IN ('null', 'text') " +
		"AND typeof(capture_callback_barrier_authorization_revision) = 'integer'",
	arrayOf(registration.sourceKind, registration.registrationGeneration),
) == 1L

@Suppress("ComplexCondition")
internal suspend fun authenticateOwnerlessStepsServiceRunManifestTimeline(
	run: SourceServiceRunEntity,
	loadPage: suspend (afterRevision: Long, limit: Int) -> List<RawSessionManifestVersion>,
): List<SessionManifestVersionEntity> {
	val manifests = ArrayList<SessionManifestVersionEntity>()
	var afterRevision = 0L
	var expectedContinuation: SessionManifestVersionEntity? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS - manifests.size
		if (remaining <= 0) throw CountDomainStoredEvidenceException()
		val pageCapacity = minOf(OWNERLESS_STEPS_MANIFEST_PAGE_SIZE, remaining)
		val queryLimit = Math.addExact(pageCapacity, 1)
		val rawPage = loadPage(afterRevision, queryLimit)
		if (rawPage.size > queryLimit) throw CountDomainStoredEvidenceException()
		if (rawPage.isEmpty()) {
			if (expectedContinuation != null) throw CountDomainStoredEvidenceException()
			break
		}
		val page = rawPage.map { raw ->
			raw.validatedOrNull() ?: throw CountDomainStoredEvidenceException()
		}
		if (
			page.first().manifestRevision <= afterRevision ||
			page.any { manifest ->
				manifest.logicalTrackingId != run.logicalTrackingId ||
					manifest.serviceRunId != run.serviceRunId
			} ||
			page.zipWithNext().any { (prior, next) ->
				next.manifestRevision <= prior.manifestRevision
			} ||
			expectedContinuation?.let { expected -> page.first() != expected } == true
		) {
			throw CountDomainStoredEvidenceException()
		}
		val accepted = page.take(pageCapacity)
		manifests += accepted
		val hasLookahead = page.size > pageCapacity
		if (!hasLookahead) break
		if (manifests.size >= OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS) {
			throw CountDomainStoredEvidenceException()
		}
		expectedContinuation = page[pageCapacity]
		afterRevision = accepted.last().manifestRevision
	}
	if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
		throw CountDomainStoredEvidenceException()
	}
	return manifests
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
internal suspend fun authenticateOwnerlessStepsManifestSourceBindings(
	timeline: OwnerlessStepsManifestRunTimeline,
	manifestRevisions: Collection<Long>,
	loadChunk: suspend (
		manifestRevisions: List<Long>,
		limit: Int,
	) -> List<SessionManifestSourceEntity.RawSessionManifestSource>,
) {
	val revisions = manifestRevisions.distinct().sorted()
	if (revisions.isEmpty() ||
		revisions.size > OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS ||
		revisions.any { revision -> revision !in timeline.manifestsByRevision }
	) {
		throw CountDomainStoredEvidenceException()
	}
	val uncachedRevisions = revisions.filterNot(timeline.sourcesByRevision::containsKey)
	val loadedByRevision = linkedMapOf<Long, List<SessionManifestSourceEntity>>()
	for (revisionChunk in uncachedRevisions.chunked(OWNERLESS_STEPS_MANIFEST_SOURCE_QUERY_CHUNK)) {
		currentCoroutineContext().ensureActive()
		val bindingLimit = Math.addExact(
			Math.multiplyExact(revisionChunk.size, OWNERLESS_STEPS_MAX_MANIFEST_SOURCES),
			1,
		)
		val rawBindings = loadChunk(revisionChunk, bindingLimit)
		currentCoroutineContext().ensureActive()
		if (rawBindings.size >= bindingLimit) throw CountDomainStoredEvidenceException()
		val bindingsByRevision =
			linkedMapOf<Long, LinkedHashMap<Pair<Int, String>, SessionManifestSourceEntity>>()
		rawBindings.forEachIndexed { index, rawBinding ->
			if (index % OWNERLESS_STEPS_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val binding = rawBinding.validatedOrNull()
				?: throw CountDomainStoredEvidenceException()
			if (binding.logicalTrackingId != timeline.key.logicalTrackingId ||
				binding.manifestRevision !in revisionChunk
			) {
				throw CountDomainStoredEvidenceException()
			}
			val revisionBindings = bindingsByRevision.getOrPut(binding.manifestRevision) {
				linkedMapOf()
			}
			val bindingKey = binding.sourceKind to binding.purpose
			if (revisionBindings.put(bindingKey, binding) != null ||
				revisionBindings.size > OWNERLESS_STEPS_MAX_MANIFEST_SOURCES
			) {
				throw CountDomainStoredEvidenceException()
			}
		}
		revisionChunk.forEachIndexed { index, revision ->
			if (index % OWNERLESS_STEPS_CANCELLATION_STRIDE == 0) {
				currentCoroutineContext().ensureActive()
			}
			val manifest = timeline.manifestsByRevision[revision]
				?: throw CountDomainStoredEvidenceException()
			val bindings = bindingsByRevision[revision]?.values?.toList()
				?: throw CountDomainStoredEvidenceException()
			if (!SessionManifestIntegrity.verify(manifest, bindings) ||
				loadedByRevision.put(revision, bindings) != null
			) {
				throw CountDomainStoredEvidenceException()
			}
		}
	}
	currentCoroutineContext().ensureActive()
	timeline.sourcesByRevision.putAll(loadedByRevision)
	if (revisions.any { revision -> revision !in timeline.sourcesByRevision }) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.hasMalformedStepsWalPruneLaneAttribution(): Boolean =
	longForQuery(
		"SELECT EXISTS(SELECT 1 FROM main.source_product_projection_lane WHERE " +
			"(CAST(source_kind AS INTEGER) = ? OR " +
			"CAST(projection_id AS TEXT) = ?) AND (" +
			"typeof(source_kind) != 'integer' OR source_kind != ? OR " +
			"typeof(projection_id) != 'text' OR projection_id != ? OR " +
			"typeof(projection_version) != 'integer' OR projection_version != ?) LIMIT 1)",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		),
	) == 1L

private fun SupportSQLiteDatabase.hasExactStepsWalPruneLaneStorage(
	lane: SourceProductProjectionLaneEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.source_product_projection_lane " +
		"WHERE source_kind = ? AND binding_generation = ? " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(binding_generation) = 'integer' " +
		"AND typeof(projection_id) = 'text' " +
		"AND typeof(projection_version) = 'integer' " +
		"AND typeof(capture_mode_mask) = 'integer' " +
		"AND typeof(product_stage) = 'text' " +
		"AND typeof(activated_rollout_revision) = 'integer' " +
		"AND typeof(activation_ordinal) = 'integer' " +
		"AND typeof(contiguous_admission_ordinal) = 'integer' " +
		"AND typeof(capture_admission_cutoff_ordinal) IN ('null', 'integer') " +
		"AND typeof(retention_required) = 'integer' " +
		"AND typeof(status) = 'text' " +
		"AND typeof(terminal_disposition) IN ('null', 'text') " +
		"AND typeof(terminal_at_ms) IN ('null', 'integer') " +
		"AND typeof(installed_at_ms) = 'integer' " +
		"AND typeof(updated_at_ms) = 'integer'",
	arrayOf(lane.sourceKind, lane.bindingGeneration),
) == 1L

@Suppress("ComplexCondition")
internal fun ProviderRegistrationGenerationEntity.authenticatesOwnerlessStepsWal(
	wal: SourceEventWalEntity,
): Boolean {
	val acceptedAt = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	val retiredAt = retiredAtMs
	val retiredElapsed = retiredElapsedRealtimeNanos
	val capturedWallTime = wal.wallTimeMs ?: return false
	val validStatusBoundaries = when (status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAt == null && retiredElapsed == null
		ProviderRegistrationGenerationEntity.STATUS_RETIRING,
		ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		-> retiredAt != null &&
			retiredElapsed != null &&
			retiredAt >= acceptedAt &&
			retiredElapsed >= acceptedElapsed &&
			capturedWallTime < retiredAt &&
			wal.observedElapsedNanos < retiredElapsed
		else -> false
	}
	return sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		registrationGeneration == wal.registrationGeneration &&
		sourceInstanceId == wal.sourceInstanceId &&
		SourceProviderPurposeScope.supportsPurpose(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			ownerScope,
			SourceBrokerPurpose.SESSION_CAPTURE,
		) &&
		clockDomainId == wal.clockDomainId &&
		physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
		collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		wal.planAttribution == OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION &&
		validStatusBoundaries &&
		reservedAtMs in 0L..acceptedAt &&
		reservedElapsedRealtimeNanos in 0L..acceptedElapsed &&
		acceptedAt <= capturedWallTime &&
		acceptedElapsed <= wal.observedElapsedNanos &&
		(retiredAt == null) == (retiredElapsed == null)
}

@Suppress("ComplexCondition")
private fun SessionManifestSourceEntity.isExactLegacyStepsWriterBinding(
	wal: SourceEventWalEntity,
): Boolean =
	logicalTrackingId == wal.logicalTrackingId &&
		manifestRevision == wal.sessionManifestRevision &&
		sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		persistenceEligible &&
		consentEpoch == wal.captureConsentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL &&
		writerOwnerGeneration?.let { it > 0L } == true &&
		writerProjectionId == null &&
		writerProjectionVersion == null &&
		writerBindingGeneration == null

@Suppress("ComplexCondition")
private fun SessionManifestSourceEntity.isExactCanonicalStepsWriterBinding(
	wal: SourceEventWalEntity,
): Boolean =
	logicalTrackingId == wal.logicalTrackingId &&
		manifestRevision == wal.sessionManifestRevision &&
		sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		persistenceEligible &&
		consentEpoch == wal.captureConsentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
		writerOwnerGeneration?.let {
			it >= SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		} == true &&
		writerProjectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
		writerBindingGeneration in setOf(
			SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION,
		)

private fun String.stepsCaptureModeMaskOrNull(): Long? = when (this) {
	"MANUAL" -> STEPS_MANUAL_CAPTURE_MODE_MASK
	"AUTOMATIC" -> STEPS_AUTOMATIC_CAPTURE_MODE_MASK
	else -> null
}

@Suppress("ComplexCondition")
private fun SourceProductProjectionLaneEntity.hasAuthenticStepsWalPruneShape(): Boolean {
	val expectedCaptureModeMask = when (bindingGeneration) {
		SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION ->
			STEPS_MANUAL_CAPTURE_MODE_MASK
		SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION ->
			STEPS_MANUAL_CAPTURE_MODE_MASK or STEPS_AUTOMATIC_CAPTURE_MODE_MASK
		else -> return false
	}
	return sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		projectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
		projectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
		captureModeMask == expectedCaptureModeMask &&
		productStage in setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		) &&
		activatedRolloutRevision > 0L &&
		activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
		captureAdmissionCutoffOrdinal?.let { cutoff ->
			cutoff >= activationOrdinal - 1L && contiguousAdmissionOrdinal <= cutoff
		} != false &&
		status in setOf(
			SourceProductProjectionLaneEntity.STATUS_ACTIVE,
			SourceProductProjectionLaneEntity.STATUS_RETIRED,
		) &&
		(status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) == retentionRequired &&
		(status == SourceProductProjectionLaneEntity.STATUS_ACTIVE).let { active ->
			if (active) {
				terminalDisposition == null && terminalAtMs == null
			} else {
				(terminalDisposition == null) == (terminalAtMs == null) &&
					terminalDisposition?.isNotBlank() != false &&
					terminalAtMs?.let { it >= 0L } != false
			}
		} &&
		installedAtMs >= 0L &&
		updatedAtMs >= installedAtMs
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainOwnerScopes(
	keys: List<CountDomainOwnerScopeKey>,
) {
	for (chunk in keys.distinct().chunked(LATEST_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val invalidPredicate = chunk.joinToString(" OR ") {
			"((((owner_kind = ? AND owner_identity = ?) OR " +
				"((typeof(owner_kind) != 'text' OR typeof(owner_identity) != 'text') " +
				"AND CAST(owner_kind AS TEXT) = ? AND CAST(owner_identity AS TEXT) = ?) OR " +
				"(owner_kind = ? AND scope_identity = ? AND typeof(owner_identity) != 'text') OR " +
				"(scope_identity = ? AND owner_identity = ? AND typeof(owner_kind) != 'text') OR " +
				"(owner_kind = ? AND owner_identity = ? AND typeof(scope_identity) != 'text'))) AND (" +
				"typeof(owner_kind) != 'text' OR " +
				"owner_kind NOT IN " +
				"('SESSION_WAL', 'SESSION_FACT', 'SESSION_COMPLETENESS', 'AMBIENT_FACT') OR " +
				"typeof(scope_identity) != 'text' OR CAST(scope_identity AS TEXT) != ? OR " +
				"length(scope_identity) != 71 OR substr(scope_identity, 1, 7) != 'sha256:' OR " +
				"substr(scope_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_identity) != 'text' OR length(owner_identity) != 71 OR " +
				"substr(owner_identity, 1, 7) != 'sha256:' OR " +
				"substr(owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_revision) != 'integer' OR owner_revision <= 0))"
		}
		val arguments = chunk.flatMap { key ->
			listOf(
				key.ownerKind,
				key.ownerIdentity,
				key.ownerKind,
				key.ownerIdentity,
				key.ownerKind,
				key.scopeIdentity,
				key.scopeIdentity,
				key.ownerIdentity,
				key.ownerKind,
				key.ownerIdentity,
				key.scopeIdentity,
			)
		}
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_owner_revision " +
				"WHERE $invalidPredicate LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainReceiptKeys(
	identities: List<String>,
) {
	for (chunk in identities.distinct().chunked(RECEIPT_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"(receipt_identity = ? OR (typeof(receipt_identity) != 'text' " +
				"AND CAST(receipt_identity AS TEXT) = ?))"
		}
		val arguments = chunk.flatMap { identity -> listOf(identity, identity) }
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_receipt WHERE ($predicate) AND (" +
				"typeof(receipt_identity) != 'text' OR length(receipt_identity) != 71 OR " +
				"substr(receipt_identity, 1, 7) != 'sha256:' OR " +
				"substr(receipt_identity, 8) GLOB '*[^0-9a-f]*') LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMarkerKeys(
	keys: List<Pair<String, Long>>,
) {
	for (chunk in keys.distinct().chunked(MARKER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"((owner_identity = ? AND owner_revision = ?) OR " +
				"((typeof(owner_identity) != 'text' OR typeof(owner_revision) != 'integer') " +
				"AND CAST(owner_identity AS TEXT) = ? AND CAST(owner_revision AS INTEGER) = ?))"
		}
		val arguments = chunk.flatMap { (identity, revision) ->
			listOf(identity, revision, identity, revision)
		}
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_completeness_marker WHERE ($predicate) AND (" +
				"typeof(owner_kind) != 'text' OR owner_kind != 'SESSION_COMPLETENESS' OR " +
				"typeof(owner_identity) != 'text' OR length(owner_identity) != 71 OR " +
				"substr(owner_identity, 1, 7) != 'sha256:' OR " +
				"substr(owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_revision) != 'integer' OR owner_revision <= 0) LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private suspend fun SupportSQLiteDatabase.authenticateCountDomainMaintenanceSnapshot(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
): StepsCountDomainMaintenanceMutationVersion {
	val expectedMutationVersion = maintenanceMutationVersion()
	auditCountDomainOwnerKeyPages(checkpoint, expectedMutationVersion)
	auditCountDomainOwnerPages(checkpoint, expectedMutationVersion)
	auditCountDomainReceiptPages(checkpoint, expectedMutationVersion)
	auditCountDomainMarkerPages(checkpoint, expectedMutationVersion)
	requireMaintenanceMutationVersion(expectedMutationVersion)
	return expectedMutationVersion
}

private suspend fun SupportSQLiteDatabase.auditCountDomainOwnerKeyPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_owner_revision")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`steps_count_domain_owner_revision`",
			columns = "typeof(owner_kind), typeof(owner_identity), typeof(owner_revision)",
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
		) { cursor ->
			val rowId = cursor.requiredLong("maintenance_rowid")
			if (cursor.requiredText(1) != "text" ||
				cursor.requiredText(2) != "text" ||
				cursor.requiredText(3) != "integer"
			) {
				throw CountDomainStoredEvidenceException()
			}
			rowId
		}
		if (page.isEmpty()) break
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		checkpoint(StepsCountDomainMaintenanceCheckpoint.OWNER_KEY_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private suspend fun SupportSQLiteDatabase.auditCountDomainOwnerPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val expectedCount =
		longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_owner_revision")
	var after: CountDomainMaintenanceOwnerCursor? = null
	var auditedCount = 0L
	var priorLineage: StepsCountDomainOwnerLineageKey? = null
	var priorScope: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceOwnerPage(after)
		if (page.isEmpty()) break
		requireAuthenticCountDomainMaintenanceDependencies(page.map { it.owner })
		page.forEach { row ->
			if (after != null && row.cursor <= after) {
				throw CountDomainStoredEvidenceException()
			}
			val lineage = StepsCountDomainOwnerLineageKey(
				row.owner.ownerKind,
				row.owner.ownerIdentity,
			)
			if (lineage == priorLineage && row.owner.scopeIdentity != priorScope) {
				throw CountDomainStoredEvidenceException()
			}
			priorLineage = lineage
			priorScope = row.owner.scopeIdentity
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		after = page.last().cursor
		checkpoint(StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private suspend fun SupportSQLiteDatabase.auditCountDomainReceiptPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_receipt")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceReceiptPage(afterRowId, maximumRowId)
		if (page.isEmpty()) break
		page.forEach { row ->
			if (afterRowId != null && row.rowId <= afterRowId) {
				throw CountDomainStoredEvidenceException()
			}
			if (!StepsCountDomainReceiptIntegrity.hasValidReceipt(row.receipt)) {
				throw CountDomainStoredEvidenceException()
			}
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last().rowId
		checkpoint(StepsCountDomainMaintenanceCheckpoint.RECEIPT_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private suspend fun SupportSQLiteDatabase.auditCountDomainMarkerPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_completeness_marker")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceMarkerPage(afterRowId, maximumRowId)
		if (page.isEmpty()) break
		page.forEach { row ->
			if (afterRowId != null && row.rowId <= afterRowId) {
				throw CountDomainStoredEvidenceException()
			}
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last().rowId
		checkpoint(StepsCountDomainMaintenanceCheckpoint.COMPLETENESS_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private fun SupportSQLiteDatabase.maintenanceRowIdBoundary(
	table: String,
): Pair<Long?, Long> = query(
	"SELECT MAX(rowid), COUNT(*) FROM main.`$table`",
).use { cursor ->
	if (!cursor.moveToFirst()) throw CountDomainStoredEvidenceException()
	val maximumRowId = if (cursor.isNull(0)) null else cursor.requiredLong(0)
	val count = cursor.requiredLong(1)
	if (count < 0L || cursor.moveToNext()) throw CountDomainStoredEvidenceException()
	maximumRowId to count
}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceOwnerPage(
	after: CountDomainMaintenanceOwnerCursor?,
): List<CountDomainMaintenanceOwnerRow> {
	val predicate = if (after == null) {
		""
	} else {
		"WHERE owner_kind > ? OR " +
			"(owner_kind = ? AND owner_identity > ?) OR " +
			"(owner_kind = ? AND owner_identity = ? AND owner_revision > ?)"
	}
	val arguments = buildList<Any?> {
		if (after != null) {
			add(after.ownerKind)
			add(after.ownerKind)
			add(after.ownerIdentity)
			add(after.ownerKind)
			add(after.ownerIdentity)
			add(after.ownerRevision)
		}
		add(COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE)
	}.toTypedArray()
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			var previous = after
			while (cursor.moveToNext()) {
				val owner = cursor.toStepsCountDomainOwner()
				val next = CountDomainMaintenanceOwnerCursor(
					owner.ownerKind,
					owner.ownerIdentity,
					owner.ownerRevision,
				)
				if (previous != null && next <= previous) {
					throw CountDomainStoredEvidenceException()
				}
				add(CountDomainMaintenanceOwnerRow(next, owner))
				previous = next
			}
		}
	}
}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceReceiptPage(
	afterRowId: Long?,
	maximumRowId: Long,
): List<CountDomainMaintenanceReceiptRow> =
	queryCountDomainMaintenancePage(
		table = "steps_count_domain_receipt",
		afterRowId = afterRowId,
		maximumRowId = maximumRowId,
	) { cursor ->
		CountDomainMaintenanceReceiptRow(
			rowId = cursor.requiredLong("maintenance_rowid"),
			receipt = cursor.toStepsCountDomainReceipt(),
		)
	}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceMarkerPage(
	afterRowId: Long?,
	maximumRowId: Long,
): List<CountDomainMaintenanceMarkerRow> =
	queryCountDomainMaintenancePage(
		table = "steps_count_domain_completeness_marker",
		afterRowId = afterRowId,
		maximumRowId = maximumRowId,
	) { cursor ->
		CountDomainMaintenanceMarkerRow(
			rowId = cursor.requiredLong("maintenance_rowid"),
			marker = cursor.toStepsCountDomainCompletenessMarker(),
		)
	}

private fun <T> SupportSQLiteDatabase.queryCountDomainMaintenancePage(
	table: String,
	afterRowId: Long?,
	maximumRowId: Long,
	map: (android.database.Cursor) -> T,
): List<T> = queryMaintenanceRowIdPage(
	table = "main.`$table`",
	columns = "*",
	afterRowId = afterRowId,
	maximumRowId = maximumRowId,
	map = map,
)

private fun <T> SupportSQLiteDatabase.queryMaintenanceRowIdPage(
	table: String,
	columns: String,
	afterRowId: Long?,
	maximumRowId: Long,
	pageSize: Int = COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE,
	map: (android.database.Cursor) -> T,
): List<T> {
	val predicate = if (afterRowId == null) {
		"rowid <= ?"
	} else {
		"rowid > ? AND rowid <= ?"
	}
	val arguments = buildList<Any?> {
		if (afterRowId != null) add(afterRowId)
		add(maximumRowId)
		add(pageSize)
	}.toTypedArray()
	return query(
		"SELECT rowid AS maintenance_rowid, $columns FROM $table " +
			"WHERE $predicate ORDER BY rowid ASC LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(map(cursor))
		}
	}
}

private suspend fun SupportSQLiteDatabase.queryCountDomainMaintenanceCandidates(
	keys: List<StepsCountDomainOwnerLookupKey>,
	onPage: suspend () -> Unit,
): Map<StepsCountDomainOwnerLookupKey, StepsCountDomainOwnerRevisionEntity> {
	val requested = keys.toSet()
	val candidates = mutableMapOf<
		StepsCountDomainOwnerLookupKey,
		StepsCountDomainOwnerRevisionEntity
	>()
	for (page in keys.chunked(OWNER_QUERY_CHUNK)) {
		currentCoroutineContext().ensureActive()
		val predicate = page.joinToString(" OR ") {
			"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = page.flatMap { key ->
			listOf(key.ownerKind, key.ownerIdentity, key.ownerRevision)
		}.toTypedArray()
		query(
			"SELECT * FROM main.steps_count_domain_owner_revision WHERE $predicate",
			arguments,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val owner = cursor.toStepsCountDomainOwner()
				val key = StepsCountDomainOwnerLookupKey(
					owner.ownerKind,
					owner.ownerIdentity,
					owner.ownerRevision,
				)
				if (key !in requested || candidates.put(key, owner) != null) {
					throw CountDomainStoredEvidenceException()
				}
			}
		}
		onPage()
	}
	return candidates
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMaintenanceDependencies(
	owners: List<StepsCountDomainOwnerRevisionEntity>,
) {
	val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
		.distinct()
	val receipts = receiptIds.chunked(COUNT_DOMAIN_MAINTENANCE_RECEIPT_CHUNK)
		.flatMap { chunk -> queryReceiptChunk(chunk) }
	val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
	if (receipts.size != receiptIds.size || receiptsById.size != receipts.size) {
		throw CountDomainStoredEvidenceException()
	}
	val completenessOwners = owners.filter { owner ->
		owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
	}
	val markers = completenessOwners.chunked(COUNT_DOMAIN_MAINTENANCE_MARKER_CHUNK)
		.flatMap { chunk -> queryCompletenessMarkerChunk(chunk) }
	val markersByKey = markers.associateBy { marker ->
		StepsCountDomainOwnerLookupKey(
			marker.ownerKind,
			marker.ownerIdentity,
			marker.ownerRevision,
		)
	}
	if (markers.size != completenessOwners.size || markersByKey.size != markers.size) {
		throw CountDomainStoredEvidenceException()
	}
	owners.forEach { owner ->
		val marker = markersByKey[
			StepsCountDomainOwnerLookupKey(
				owner.ownerKind,
				owner.ownerIdentity,
				owner.ownerRevision,
			)
		]
		val receipt = owner.receiptIdentity?.let(receiptsById::get)
		if (!owner.hasAuthenticMaintenanceDependencies(receipt, marker)) {
			throw CountDomainStoredEvidenceException()
		}
	}
}

private fun StepsCountDomainOwnerRevisionEntity.hasAuthenticMaintenanceDependencies(
	receipt: StepsCountDomainReceiptEntity?,
	marker: StepsCountDomainCompletenessMarkerEntity?,
): Boolean {
	val receiptMatches = receipt?.let { stored ->
		operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			receiptIdentity == stored.receiptIdentity &&
			ownerKind == stored.ownerKind &&
			scopeIdentity == stored.scopeIdentity &&
			ownerIdentity == stored.ownerIdentity &&
			ownerRevision == stored.ownerRevision &&
			ownerEffectChecksum == stored.effectChecksum &&
			StepsCountDomainReceiptIntegrity.hasValidReceipt(stored)
	} ?: (operation != StepsCountDomainOwnerRevisionEntity.OPERATION_BIND && receiptIdentity == null)
	if (!receiptMatches) return false
	return if (ownerKind ==
		StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
	) {
		marker?.matches(this) == true &&
			when (operation) {
				StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
					receipt?.completionEvidenceChecksum == marker.evidenceChecksum
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> receipt == null
				else -> false
			}
	} else {
		marker == null && receipt?.completionEvidenceChecksum == null
	}
}

private const val COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE = 64
private const val COUNT_DOMAIN_MAINTENANCE_RECEIPT_CHUNK = 400
private const val COUNT_DOMAIN_MAINTENANCE_MARKER_CHUNK = 200
private const val WAL_MAINTENANCE_PAGE_SIZE = 128
private const val WAL_PRUNE_CANDIDATE_PAGE_SIZE = 128

private suspend fun SupportSQLiteDatabase.authenticateStepsWalMaintenanceSnapshot(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	auditStepsWalMaintenancePages(session)
	auditStepsRegistrationMaintenancePages(session)
	auditStepsRunMaintenancePages(session)
	session.requireUnchangedMutationVersion()
}

private suspend fun SupportSQLiteDatabase.auditStepsWalMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	val (maximumRowId, expectedCount) = maintenanceRowIdBoundary("source_event_wal")
	var afterRowId: Long? = null
	var auditedCount = 0L
	var legacyAuthority: LegacyV27StepsWalRetentionAuthority? = null
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`source_event_wal`",
			columns = "admission_ordinal, event_id, created_at_ms, source_kind, " +
				"source_instance_id, registration_generation, " +
				"authorization_purpose_eligibility_mask, logical_tracking_id, service_run_id",
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
			pageSize = WAL_MAINTENANCE_PAGE_SIZE,
		) { cursor ->
			val rowId = cursor.requiredLong("maintenance_rowid")
			val sourceKind = cursor.requiredLong("source_kind")
			if (sourceKind !in 1L..6L) throw CountDomainStoredEvidenceException()
			if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS.toLong()) {
				val admissionOrdinal = cursor.requiredLong("admission_ordinal")
				val eventId = cursor.requiredText("event_id")
				val createdAtMs = cursor.requiredLong("created_at_ms")
				val sourceInstanceId = cursor.requiredText("source_instance_id")
				val registrationGeneration = cursor.requiredLong("registration_generation")
				val eligibilityMask =
					cursor.requiredLong("authorization_purpose_eligibility_mask")
				val logicalTrackingId = cursor.nullableText("logical_tracking_id")
				val serviceRunId = cursor.nullableText("service_run_id")
				val captureEligible =
					eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
				val commonInvalid =
					admissionOrdinal <= 0L || eventId.isBlank() || createdAtMs < 0L ||
					sourceInstanceId.isBlank() || registrationGeneration <= 0L ||
					(logicalTrackingId == null) != (serviceRunId == null) ||
					logicalTrackingId?.isBlank() == true ||
					serviceRunId?.isBlank() == true
				if (commonInvalid) {
					throw CountDomainStoredEvidenceException()
				}
				if (eligibilityMask == 0L) {
					val authority = legacyAuthority
						?: requireLegacyV27StepsWalRetentionAuthority().also {
							legacyAuthority = it
						}
					requireAuthenticatedLegacyV27StepsWal(admissionOrdinal, authority)
				} else if (
					eligibilityMask !in 1L..STEPS_WAL_ALLOWED_PURPOSE_MASK ||
					captureEligible != (logicalTrackingId != null)
				) {
					throw CountDomainStoredEvidenceException()
				}
			}
			rowId
		}
		if (page.isEmpty()) break
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		session.checkpoint(StepsCountDomainMaintenanceCheckpoint.WAL_DOMAIN_PAGE_AUTHENTICATED)
		if (totalChanges() != session.expectedTotalChanges()) {
			throw CountDomainStoredEvidenceException()
		}
		if (page.size < WAL_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private suspend fun SupportSQLiteDatabase.auditStepsRegistrationMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("provider_registration_generation")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`provider_registration_generation`",
			columns = "source_kind, registration_generation, source_instance_id, status",
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
			pageSize = WAL_MAINTENANCE_PAGE_SIZE,
		) { cursor ->
			val rowId = cursor.requiredLong("maintenance_rowid")
			val sourceKind = cursor.requiredLong("source_kind")
			if (sourceKind !in 1L..6L) throw CountDomainStoredEvidenceException()
			if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS.toLong()) {
				val generation = cursor.requiredLong("registration_generation")
				val sourceInstanceId = cursor.requiredText("source_instance_id")
				val status = cursor.requiredText("status")
				if (generation <= 0L || sourceInstanceId.isBlank() ||
					status !in setOf("RESERVED", "ACTIVE", "RETIRING", "RETIRED", "FAILED")
				) {
					throw CountDomainStoredEvidenceException()
				}
			}
			rowId
		}
		if (page.isEmpty()) break
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		session.checkpoint(
			StepsCountDomainMaintenanceCheckpoint.WAL_REGISTRATION_PAGE_AUTHENTICATED,
		)
		if (totalChanges() != session.expectedTotalChanges()) {
			throw CountDomainStoredEvidenceException()
		}
		if (page.size < WAL_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private suspend fun SupportSQLiteDatabase.auditStepsRunMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	val (maximumRowId, expectedCount) = maintenanceRowIdBoundary("source_service_run")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`source_service_run`",
			columns = "service_run_id, logical_tracking_id, state, completed_at_ms, " +
				"completion_reason, runtime_acknowledgement, runtime_failure_code",
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
			pageSize = WAL_MAINTENANCE_PAGE_SIZE,
		) { cursor ->
			val rowId = cursor.requiredLong("maintenance_rowid")
			val serviceRunId = cursor.requiredText("service_run_id")
			val logicalTrackingId = cursor.requiredText("logical_tracking_id")
			val state = cursor.requiredText("state")
			val completedAtMs = cursor.nullableLong("completed_at_ms")
			val completionReason = cursor.nullableText("completion_reason")
			val runtimeAcknowledgement = cursor.requiredText("runtime_acknowledgement")
			val runtimeFailureCode = cursor.nullableText("runtime_failure_code")
			val terminal = state in setOf("FINALIZED", "CLOSED", "FAILED")
			val migratedTerminal =
				state == "FINALIZED" &&
					completedAtMs == null &&
					!completionReason.isNullOrBlank() &&
					runtimeAcknowledgement == "TERMINAL_FAILURE" &&
					runtimeFailureCode == V28_MIGRATION_INTERRUPTION_REASON
			if (serviceRunId.isBlank() || logicalTrackingId.isBlank() ||
				state !in setOf(
					"IDLE", "STARTING", "ACTIVE", "RECONFIGURING", "STOPPING",
					"FINALIZED", "CLOSED", "FAILED",
				) ||
				completedAtMs?.let { it < 0L } == true ||
				terminal != (completedAtMs != null || migratedTerminal)
			) {
				throw CountDomainStoredEvidenceException()
			}
			rowId
		}
		if (page.isEmpty()) break
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		session.checkpoint(StepsCountDomainMaintenanceCheckpoint.WAL_RUN_PAGE_AUTHENTICATED)
		if (totalChanges() != session.expectedTotalChanges()) {
			throw CountDomainStoredEvidenceException()
		}
		if (page.size < WAL_MAINTENANCE_PAGE_SIZE) break
	}
	if (auditedCount != expectedCount) throw CountDomainStoredEvidenceException()
}

private fun SupportSQLiteDatabase.totalChanges(): Long =
	longForQuery("SELECT total_changes()")

private fun SupportSQLiteDatabase.maintenanceMutationVersion() =
	StepsCountDomainMaintenanceMutationVersion(
		totalChanges = totalChanges(),
		schemaVersion = longForQuery("PRAGMA main.schema_version"),
		tempSchemaVersion = longForQuery("PRAGMA temp.schema_version"),
	)

private fun SupportSQLiteDatabase.requireMaintenanceMutationVersion(
	expected: StepsCountDomainMaintenanceMutationVersion,
) {
	if (maintenanceMutationVersion() != expected) throw CountDomainStoredEvidenceException()
}

private fun SupportSQLiteDatabase.executeDelete(sql: String): Int =
	compileStatement(sql).use { statement -> statement.executeUpdateDelete() }

private fun SupportSQLiteDatabase.deleteCurrentStepsWalCandidates(
	candidates: List<StepsWalPruneCandidate>,
): Int {
	check(candidates.isNotEmpty() && candidates.none(StepsWalPruneCandidate::isLegacyV27))
	val predicate = candidates.joinToString(" OR ") {
		"(admission_ordinal = ? AND event_id = ? AND " +
			"authorization_purpose_eligibility_mask = ?)"
	}
	return compileStatement(
		"DELETE FROM source_event_wal WHERE source_kind = ? AND ($predicate)",
	).use { statement ->
		var index = 1
		statement.bindLong(index++, SourceDestinationOwnerEntity.SOURCE_STEPS.toLong())
		candidates.forEach { candidate ->
			statement.bindLong(index++, candidate.cursor.admissionOrdinal)
			statement.bindString(index++, candidate.eventId)
			statement.bindLong(index++, candidate.authorizationPurposeEligibilityMask)
		}
		statement.executeUpdateDelete()
	}.also { deleted ->
		if (deleted != candidates.size) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.roomDeleteInvalidationEffect(
	table: String,
	affectedRows: Long,
): Long {
	if (affectedRows == 0L) return 0L
	val triggerName = "room_table_modification_trigger_${table}_DELETE"
	val triggerSql = query(
		"SELECT sql FROM sqlite_temp_master WHERE type = 'trigger' AND name = ? LIMIT 2",
		arrayOf(triggerName),
	).use { cursor ->
		if (!cursor.moveToFirst() || cursor.isNull(0)) return 0L
		val sql = cursor.getString(0)
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		sql
	}
	val tableIds = ROOM_INVALIDATION_TABLE_ID.findAll(triggerSql)
		.mapNotNull { match -> match.groupValues[1].toLongOrNull() }
		.toList()
	if (tableIds.size != 1 || tableIds.single() !in 0L..Int.MAX_VALUE.toLong()) {
		throw CountDomainStoredEvidenceException()
	}
	val invalidated = longForQuery(
		"SELECT invalidated FROM temp.room_table_modification_log WHERE table_id = ?",
		arrayOf(tableIds.single()),
	)
	if (invalidated !in 0L..1L) throw CountDomainStoredEvidenceException()
	return if (invalidated == 0L) 1L else 0L
}

private fun SupportSQLiteDatabase.longForQuery(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	if (!cursor.moveToFirst()) throw CountDomainStoredEvidenceException()
	val value = cursor.requiredLong(0)
	if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
	value
}

private fun android.database.Cursor.toStepsCountDomainOwner() =
	authenticatedCountDomainRow {
		StepsCountDomainOwnerRevisionEntity(
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			operation = requiredText("operation"),
			receiptIdentity = nullableText("receipt_identity"),
			ownerEffectChecksum = requiredText("owner_effect_checksum"),
			linkedAtMs = requiredLong("linked_at_ms"),
		)
	}

private fun android.database.Cursor.toStepsCountDomainReceipt() =
	authenticatedCountDomainRow {
		StepsCountDomainReceiptEntity(
			receiptIdentity = requiredText("receipt_identity"),
			domainIdentity = requiredText("domain_identity"),
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			registrationGeneration = requiredLong("registration_generation"),
			collectedDataEpoch = requiredLong("collected_data_epoch"),
			authorityRevision = requiredLong("authority_revision"),
			authorityFingerprint = requiredText("authority_fingerprint"),
			coverageKind = requiredText("coverage_kind"),
			coverageVersion = requiredInt("coverage_version"),
			countDomainVersion = requiredInt("count_domain_version"),
			effectChecksum = requiredText("effect_checksum"),
			completionEvidenceChecksum = nullableText("completion_evidence_checksum"),
		)
	}

private fun android.database.Cursor.toStepsCountDomainCompletenessMarker() =
	authenticatedCountDomainRow {
		StepsCountDomainCompletenessMarkerEntity(
			ownerKind = requiredText("owner_kind"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			terminalState = requiredText("terminal_state"),
			lastAdmissionOrdinal = nullableLong("last_admission_ordinal"),
			lastSourceSequence = nullableLong("last_source_sequence"),
			providerFlushOutcome = requiredText("provider_flush_outcome"),
			registrationRemovalOutcome = requiredText("registration_removal_outcome"),
			registrationTimelineChecksum = requiredText("registration_timeline_checksum"),
			evidenceChecksum = requiredText("evidence_checksum"),
		)
	}

private inline fun <T> authenticatedCountDomainRow(block: () -> T): T =
	try {
		block()
	} catch (error: CountDomainStoredEvidenceException) {
		throw error
	} catch (error: IllegalArgumentException) {
		throw CountDomainStoredEvidenceException(error)
	} catch (error: IllegalStateException) {
		throw CountDomainStoredEvidenceException(error)
	}

private fun android.database.Cursor.requiredText(column: String): String =
	requiredText(getColumnIndexOrThrow(column))

private fun android.database.Cursor.requiredText(index: Int): String {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_STRING) {
		throw CountDomainStoredEvidenceException()
	}
	return getString(index) ?: throw CountDomainStoredEvidenceException()
}

private fun android.database.Cursor.nullableText(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_STRING ->
			getString(index) ?: throw CountDomainStoredEvidenceException()
		else -> throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requiredLong(column: String): Long =
	requiredLong(getColumnIndexOrThrow(column))

private fun android.database.Cursor.requiredLong(index: Int): Long {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_INTEGER) {
		throw CountDomainStoredEvidenceException()
	}
	return getLong(index)
}

private fun android.database.Cursor.requiredInt(column: String): Int {
	val value = requiredLong(column)
	if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
		throw CountDomainStoredEvidenceException()
	}
	return value.toInt()
}

private fun android.database.Cursor.nullableLong(column: String): Long? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_INTEGER -> getLong(index)
		else -> throw CountDomainStoredEvidenceException()
	}
}

private class CountDomainStoredEvidenceException(
	cause: Throwable? = null,
) : IllegalStateException("Stored Steps count-domain evidence is unverifiable", cause)

private class CountDomainMaintenanceOverflowException :
	IllegalStateException("Steps count-domain maintenance exceeds its bounded workload")

private val ROOM_INVALIDATION_TABLE_ID =
	Regex("""(?i)\btable_id\s*=\s*([0-9]+)\b""")

private const val STEPS_WAL_ALLOWED_PURPOSE_MASK =
	SourceBrokerPurpose.CONTROL_MASK or SourceBrokerPurpose.MASK_SESSION_CAPTURE
private const val STEPS_MANUAL_CAPTURE_MODE_MASK = 1L
private const val STEPS_AUTOMATIC_CAPTURE_MODE_MASK = 1L shl 1
private const val OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
private const val OWNERLESS_STEPS_MAX_DISTINCT_RUNS = 64
private const val OWNERLESS_STEPS_MANIFEST_PAGE_SIZE = 64
private const val OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS = 2_048
private const val OWNERLESS_STEPS_MAX_MANIFEST_SOURCES = 16
private const val OWNERLESS_STEPS_MANIFEST_SOURCE_QUERY_CHUNK = 100
private const val OWNERLESS_STEPS_CANCELLATION_STRIDE = 64
