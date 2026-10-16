package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionProofCandidateRow
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Producer receipt/owner write result separating exact replay, pending/unproven authority and
 * invalid identity/revision states.
 */
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

/**
 * Exact Steps owner kind, opaque identity and positive revision for a count-domain lookup.
 */
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

/**
 * Stable owner kind/identity used to locate the latest count-domain revision.
 */
data class StepsCountDomainOwnerLineageKey(
	val ownerKind: String,
	val ownerIdentity: String,
)

internal data class CountDomainOwnerScopeKey(
	val ownerKind: String,
	val scopeIdentity: String,
	val ownerIdentity: String,
)

/**
 * Saved owner revision with optional source receipt and completeness marker; missing receipt is
 * not exact-count proof.
 */
data class StepsCountDomainStoredOwner(
	val owner: StepsCountDomainOwnerRevisionEntity,
	val receipt: StepsCountDomainReceiptEntity?,
	val completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
)

/**
 * Actual provider flush and registration removal outcomes used to qualify terminal Steps coverage.
 */
data class StepsCountDomainRetirementEvidence(
	val providerFlushOutcome: String,
	val registrationRemovalOutcome: String,
) {
	init {
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
	}
}

/**
 * Evaluates actual Steps stop, provider, app-drain and unresolved-sequence outcomes before
 * accepting terminal coverage.
 */
object StepsRetirementTerminality {
	/**
	 * Checks actual stop, provider flush/removal, app drain and unresolved sequence state before
	 * qualifying terminal Steps.
	 */
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
				hasTerminalPartialCoverage(appDrainComplete, lastAdmissionOrdinal, unresolvedSequenceStart, unresolvedSequenceEnd)
			else -> false
		}
	}

	private fun hasTerminalPartialCoverage(
		appDrainComplete: Boolean,
		lastAdmissionOrdinal: Long?,
		unresolvedSequenceStart: Long?,
		unresolvedSequenceEnd: Long?,
	): Boolean = (appDrainComplete && lastAdmissionOrdinal != null) ||
		(!appDrainComplete && unresolvedSequenceStart != null && unresolvedSequenceEnd != null &&
			unresolvedSequenceStart <= unresolvedSequenceEnd)

}

/**
 * Verified saved terminal Steps completeness or explicit absent/unverifiable source evidence.
 */
sealed interface StepsTerminalCompletenessAuthentication {
	/**
	 * Original terminal completeness and actual retirement outcomes, with a data epoch only when
	 * count-domain ownership is present.
	 */
	data class Authenticated(
		val completeness: SourceSessionCompletenessEntity,
		val retirementEvidence: StepsCountDomainRetirementEvidence,
		val countDomainCollectedDataEpoch: Long?,
	) : StepsTerminalCompletenessAuthentication

	/**
	 * No original registration, owner or deletion/retention witness matches this exact source
	 * selection.
	 */
	data object Absent : StepsTerminalCompletenessAuthentication
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : StepsTerminalCompletenessAuthentication
}

/**
 * Whether terminal Steps can materialize, is honestly unavailable, or lacks valid schema/source
 * evidence.
 */
sealed interface StepsTerminalProductAuthentication {
	/**
	 * Original terminal completeness permits the retained Steps product lane to finish
	 * materialization.
	 */
	data object Materializable : StepsTerminalProductAuthentication
	/**
	 * The original terminal state cannot provide a qualified count; unavailable is not converted to
	 * measured zero.
	 */
	data object TerminalUnavailable : StepsTerminalProductAuthentication
	/**
	 * The complete supported count-domain schema is unavailable; no exact owner result or mutation is
	 * reported.
	 */
	data object SchemaUnavailable : StepsTerminalProductAuthentication
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : StepsTerminalProductAuthentication
}

/**
 * Bounded selected owner revisions and latest lineage heads, or schema, size or stored-evidence
 * failure.
 */
sealed interface StepsCountDomainOwnerRead {
	/**
	 * Exact selected owner rows and latest lineage revisions; optional receipts/markers preserve
	 * unproven states.
	 */
	data class Ready(
		val owners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>,
		val latestRevisions: Map<StepsCountDomainOwnerLineageKey, Long>,
	) : StepsCountDomainOwnerRead

	/**
	 * The complete supported count-domain schema is unavailable; no exact owner result or mutation is
	 * reported.
	 */
	data object SchemaUnavailable : StepsCountDomainOwnerRead
	/**
	 * The complete source scope exceeds its finite dependency bound; a truncated prefix is not
	 * treated as complete.
	 */
	data object Overflow : StepsCountDomainOwnerRead
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : StepsCountDomainOwnerRead
}

/**
 * Removed owner/receipt/WAL counts or a schema, integrity or bound failure preventing maintenance.
 */
sealed interface StepsCountDomainMaintenanceResult {
	/**
	 * The complete supported count-domain schema is unavailable; no exact owner result or mutation is
	 * reported.
	 */
	data object SchemaUnavailable : StepsCountDomainMaintenanceResult
	/**
	 * Stored owner/receipt relationships cannot prove the complete requested maintenance scope.
	 */
	data object StoredEvidenceUnverifiable : StepsCountDomainMaintenanceResult
	/**
	 * The complete source scope exceeds its finite dependency bound; a truncated prefix is not
	 * treated as complete.
	 */
	data object Overflow : StepsCountDomainMaintenanceResult
	/**
	 * Reports the rows removed or changed by this exact verified source operation.
	 */
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

/**
 * Removes every count-domain row or preserves terminal replay fences while clearing active
 * products.
 */
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
class StepsCountDomainStore private constructor(
	internal val database: AppDatabase,
	private val writeBatch: WriteBatchScope?,
) {
	constructor(database: AppDatabase) : this(database, null)

	private val maintenanceAuthorityIdentity = Any()

	private class WriteBatchScope(
		val sqlite: SupportSQLiteDatabase,
		val schemaState: StepsCountDomainSchemaState,
		private val openHelper: SupportSQLiteOpenHelper,
	) {
		private val thread = Thread.currentThread()
		var active = true

		fun requireActive(database: AppDatabase) {
			// Room's query callback decorates each database getter with a fresh wrapper.
			// Bind to its stable helper and the captured live transaction, not that wrapper.
			if (!active || Thread.currentThread() !== thread || !ownsTransaction(database)) {
				throw CountDomainStoredEvidenceException()
			}
		}

		private fun ownsTransaction(database: AppDatabase): Boolean =
			database.openHelper === openHelper && database.inTransaction() &&
				sqlite.isOpen && sqlite.inTransaction()
	}

	/**
	 * Reuses exact schema inspection for one producer transaction. Each row still authenticates
	 * its original carrier, token and owner; a failed final inspection rolls the batch back.
	 * The supplied store cannot be used after the block or on another transaction thread.
	 */
	suspend fun <T> withValidatedWriteBatch(
		block: suspend (StepsCountDomainStore) -> T,
	): T = database.withTransaction {
		val sqlite = database.openHelper.writableDatabase
		val scope = WriteBatchScope(
			sqlite,
			StepsCountDomainSchema.inspect(sqlite),
			database.openHelper,
		)
		try {
			val result = block(StepsCountDomainStore(database, scope))
			currentCoroutineContext().ensureActive()
			scope.requireActive(database)
			if (StepsCountDomainSchema.inspect(sqlite) != scope.schemaState) {
				throw CountDomainStoredEvidenceException()
			}
			result
		} finally {
			scope.active = false
		}
	}

	internal fun inspectedSchemaState(sqlite: SupportSQLiteDatabase): StepsCountDomainSchemaState {
		val scope = writeBatch ?: return StepsCountDomainSchema.inspect(sqlite)
		scope.requireActive(database)
		return scope.schemaState
	}

	/**
	 * Store-issued live maintenance scope that rechecks schema and mutation version around exact
	 * owner removal.
	 */
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

		/**
		 * Removes the exact selected owner revisions under this live maintenance session and its
		 * mutation-version checks.
		 */
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
			if (current != expectedMutationVersion) {
				throw CountDomainStoredEvidenceException()
			}
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

	/**
	 * Returns whether the live database has the complete recognized v2 count-domain schema.
	 */
	fun isInstalled(): Boolean =
		inspectedSchemaState(database.openHelper.writableDatabase) ==
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
			block(session).also { requireActiveSnapshot(session) }
		} finally {
			session.invalidate()
		}
	}

	/**
	 * Verifies the original admitted session event and optional sensor-domain token before appending
	 * its count-domain receipt.
	 */
	suspend fun recordSessionWal(
		row: SourceEventWalEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = recordSessionWalInCountDomain(row, counterDomainToken)

	/**
	 * Verifies the canonical session fact and appends the corresponding exact owner revision.
	 */
	suspend fun recordSessionFact(
		fact: StepFactRevisionEntity,
	): StepsCountDomainWriteResult = recordSessionFactInCountDomain(fact)

	/**
	 * Records terminal run coverage using real retirement evidence; missing admitted count proof
	 * remains unproven.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun recordSessionCompleteness(
		row: SourceSessionCompletenessEntity,
		retirementEvidence: StepsCountDomainRetirementEvidence,
	): StepsCountDomainWriteResult = recordSessionCompletenessInCountDomain(row, retirementEvidence)

	/**
	 * Verifies original terminal completeness for the exact logical/run/source-instance/registration
	 * identities.
	 */
	suspend fun authenticateTerminalSessionCompleteness(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): StepsTerminalCompletenessAuthentication =
		authenticateTerminalSessionCompletenessInCountDomain(
			logicalTrackingId,
			serviceRunId,
			sourceInstanceId,
			registrationGeneration,
		)

	/**
	 * Determines whether the original terminal timeline can materialize a Steps product or is
	 * honestly unavailable.
	 */
	suspend fun authenticateTerminalProductDisposition(
		logicalTrackingId: String,
		serviceRunId: String,
		timeline: List<SourceSessionCompletenessEntity>,
	): StepsTerminalProductAuthentication =
		authenticateTerminalProductDispositionInCountDomain(
			logicalTrackingId,
			serviceRunId,
			timeline,
		)

	/**
	 * Verifies the original all-day Steps aggregate and optional domain token before appending its
	 * receipt and owner.
	 */
	suspend fun recordAmbientFact(
		fact: AmbientStepsFactRevisionEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = recordAmbientFactInCountDomain(fact, counterDomainToken)

	/**
	 * Records exact removal of the original session fact owner without rewriting its count-domain
	 * identity.
	 */
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

	/**
	 * Records exact removal of the original all-day Steps fact owner while retaining terminal replay
	 * authority.
	 */
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

	/**
	 * Reads bounded exact owner revisions and their latest lineage heads, preserving optional
	 * receipts and markers.
	 */
	suspend fun readOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
		limit: Int = MAX_OWNER_LOOKUP,
	): StepsCountDomainOwnerRead = readOwnersInCountDomain(keys, limit)

	/**
	 * Removes the exact owner revisions with authenticated maintenance; unrelated live owners remain
	 * untouched.
	 */
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

	/**
	 * Clears count-domain rows under the explicit mode, preserving terminal replay fences when
	 * requested.
	 */
	suspend fun clear(
		mode: StepsCountDomainFullClearMode,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance { session -> clearAuthenticated(mode, session) }
	}

	/**
	 * Compacts a bounded terminal-owner batch only under the supplied authenticated source fence.
	 */
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

	/**
	 * Removes bounded original session-WAL owners at the safe admission ordinal and UTC millisecond
	 * cutoff before WAL pruning.
	 */
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
		beforeWalDeletion: suspend (Int, List<SourceEventRetentionProofCandidateRow>) -> Unit = { _, _ -> },
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		withOwnerMaintenance { session ->
			pruneSessionWalForStorageAuthenticated(
				safeOrdinal = safeOrdinal,
				createdBeforeMs = createdBeforeMs,
				limit = limit,
				session = session,
				beforeWalDeletion = beforeWalDeletion,
			)
		}
	}

	internal fun requireActiveSnapshot(session: OwnerMaintenanceSession) {
		check(
			session.authenticates(this, maintenanceAuthorityIdentity) &&
				database.inTransaction(),
		) { "Steps count-domain maintenance authority is no longer active" }
		session.requireUnchangedMutationVersion()
	}

	internal suspend fun checkpointAndRequireUnchanged(
		session: OwnerMaintenanceSession,
		value: StepsCountDomainMaintenanceCheckpoint,
	) {
		session.checkpoint(value)
		requireActiveSnapshot(session)
	}

	internal suspend fun authenticateCountDomainWrite(
		block: suspend () -> StepsCountDomainWriteResult,
	): StepsCountDomainWriteResult = try {
		currentCoroutineContext().ensureActive()
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

	internal fun appendBoundOwner(
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

	internal fun appendTerminalUnproven(
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

	internal fun loadExactOwner(
		key: StepsCountDomainOwnerLookupKey,
		scopeIdentity: String,
	): StepsCountDomainStoredOwner? {
		val sqlite = database.openHelper.writableDatabase
		if (inspectedSchemaState(sqlite) != StepsCountDomainSchemaState.ValidV2) {
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

	internal fun writeSchemaFailure(): StepsCountDomainWriteResult? =
		when (inspectedSchemaState(database.openHelper.writableDatabase)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			-> StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
			StepsCountDomainSchemaState.ValidV2 -> null
			StepsCountDomainSchemaState.Incompatible ->
				StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}

	internal companion object {
		const val COMPLETENESS_COVERAGE_VERSION = 1
		const val MAX_OWNER_LOOKUP = 512
		const val MAX_MAINTENANCE_OWNER_BATCH = 400
		const val MAX_WAL_PRUNE_BATCH = 2_048
		const val MAX_COMPLETENESS_TIMELINE = 64
		private val FACT_OWNER_KINDS = setOf(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		)
		private const val INSERT_RECEIPT_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_receipt (" +
					"receipt_identity, domain_identity, owner_kind, scope_identity, " +
					"owner_identity, owner_revision, " +
				"registration_generation, collected_data_epoch, authority_revision, " +
				"authority_fingerprint, coverage_kind, coverage_version, count_domain_version, " +
					"effect_checksum, completion_evidence_checksum) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		private const val INSERT_OWNER_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_owner_revision (" +
				"owner_kind, scope_identity, owner_identity, owner_revision, operation, " +
				"receipt_identity, owner_effect_checksum, linked_at_ms) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
		private const val INSERT_COMPLETENESS_MARKER_SQL =
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

/**
 * Preserves exact replay and advances a changed terminal completeness timestamp so owner revisions
 * remain monotonic.
 */
fun SourceSessionCompletenessEntity.withMonotonicStepsCountDomainRevision(
	existing: SourceSessionCompletenessEntity?,
): SourceSessionCompletenessEntity {
	require(updatedAtMs in 0L until Long.MAX_VALUE)
	if (existing == null) {
		return this
	}
	require(
		logicalTrackingId == existing.logicalTrackingId &&
			serviceRunId == existing.serviceRunId &&
			sourceKind == existing.sourceKind &&
			sourceInstanceId == existing.sourceInstanceId &&
			registrationGeneration == existing.registrationGeneration &&
			existing.updatedAtMs in 0L until Long.MAX_VALUE,
	)
	if (copy(updatedAtMs = existing.updatedAtMs) == existing) {
		return existing
	}
	val nextRevisionWallTime = existing.updatedAtMs + 1L
	require(nextRevisionWallTime < Long.MAX_VALUE)
	return copy(
		updatedAtMs = maxOf(
			updatedAtMs,
			nextRevisionWallTime,
		),
	)
}

internal class CountDomainStoredEvidenceException(
	cause: Throwable? = null,
) : IllegalStateException("Stored Steps count-domain evidence is unverifiable", cause)

internal class CountDomainMaintenanceOverflowException :
	IllegalStateException("Steps count-domain maintenance exceeds its bounded workload")

