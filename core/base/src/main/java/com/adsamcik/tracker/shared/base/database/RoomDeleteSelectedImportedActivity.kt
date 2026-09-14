package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProtectedIdentityOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selected imported-Activity erasure. It never mutates live source or provider authority. */
@Singleton
class RoomDeleteSelectedImportedActivity internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (ImportedActivityDeletionCheckpoint) -> Unit,
) : DeleteSelectedImportedActivity {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun delete(
		request: DeleteSelectedImportedActivityRequest,
	): DeleteSelectedImportedActivityResult = withContext(ioDispatcher) {
		try {
			val snapshot = request.copy(
				selected = request.selected.copy(
					runDeletionScopes = request.selected.runDeletionScopes.map { it.copy() }.toList(),
					windowIdentities = request.selected.windowIdentities.toList(),
				),
			)
			database.withTransaction { deleteInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedActivityDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: Exception) {
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "ComplexCondition", "CyclomaticComplexMethod", "NestedBlockDepth")
	private suspend fun deleteInTransaction(
		request: DeleteSelectedImportedActivityRequest,
	): DeleteSelectedImportedActivityResult {
		writeCheckpoint(ImportedActivityDeletionCheckpoint.TRANSACTION_STARTED)
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		if (state.revision < 0L || state.deletedSourceEventHighWaterOrdinal < 0L ||
			state.retainedFromMs?.let { it < 0L } == true || state.updatedAtMs < 0L
		) storedCorrupt()

		val authority = ExpectedImportedActivityDeletionAuthority.from(request.selected)
		val dao = database.importedActivityDao()
		val evaluation = storedValue {
			ImportedActivityProductReader(database).selectIdentityInTransaction(
				request.selected.entryIdentity,
			)
		}
		if (evaluation == null) {
			val deletion = storedValue { dao.entryDeletion(authority.entryIdentity) }
			if (deletion == null) {
				storedValue { authenticateAbsentIdentity(dao, authority, exactDeletion = false) }
				return DeleteSelectedImportedActivityResult.NotFound
			}
			return authenticateReplay(request, state, dao, deletion, authority)
		}
		val readable = when (evaluation) {
			is ImportedActivityProductEvaluation.Retained ->
				blocked(SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY)
			is ImportedActivityProductEvaluation.Unverifiable -> unverifiable(evaluation.reason)
			is ImportedActivityProductEvaluation.Readable -> evaluation
		}
		if (readable.candidate.identity != authority.entryIdentity ||
			readable.candidate.importRevision != request.selected.importRevision ||
			readable.candidate.contentChecksum != request.selected.contentChecksum.value
		) blocked(SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION)
		if (readable.entryDeleted) storedCorrupt()
		if (readable.retainedFromMs != state.retainedFromMs) storedCorrupt()
		if (readable.retentionLimited) {
			blocked(SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY)
		}

		val latestDurableTimeMs = storedValue {
			maxOf(
				state.updatedAtMs,
				readable.candidate.receivedAtMs,
				dao.receiptsForAdmission(readable.candidate.identity)
					.maxOfOrNull { it.receivedAtMs } ?: 0L,
			)
		}
		val liveRunScopes = readable.entry.runs.map { run ->
			run.identity.value to run.deletionScopeDigest.value
		}
		val liveWindowIdentities = readable.entry.runs.flatMap { run ->
			run.windows.map { window -> window.identity.value }
		}
		if (!authority.exactlyMatches(liveRunScopes, liveWindowIdentities)) {
			blocked(SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION)
		}
		val runDeletions = storedValue { dao.deletionGenerations(authority.runIdentities) }
		val sourceDeletions = storedValue { sourceDeletions(authority) }
		authenticateDeletionRows(
			authority,
			request.expectedCollectedDataEpoch,
			runDeletions,
			sourceDeletions,
			requireEveryRun = false,
		)
		val exactLatestDurableTimeMs = maxOf(
			latestDurableTimeMs,
			runDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
			sourceDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
		)
		if (request.deletedAtMs < exactLatestDurableTimeMs) {
			blocked(SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST)
		}
		val runDeletionIdentities = runDeletions.mapTo(hashSetOf()) { it.runIdentity }
		val sourceDeletionScopes = sourceDeletions.mapTo(hashSetOf()) { it.scopeIdentityDigest }
		val exactlyDeletedRuns = authority.runScopes.filter { (run, scope) ->
			run in runDeletionIdentities || scope in sourceDeletionScopes
		}.keys
		if (readable.deletedRunIdentities != exactlyDeletedRuns) storedCorrupt()
		writeCheckpoint(ImportedActivityDeletionCheckpoint.AUTHORITY_AUTHENTICATED)

		val entryDeletion = ImportedActivityEntryDeletionEntity.create(
			entryIdentity = authority.entryIdentity,
			collectedDataEpoch = request.expectedCollectedDataEpoch,
			deletedImportRevision = request.selected.importRevision,
			deletedAtMs = request.deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		val deletionsByRun = runDeletions.associateBy { it.runIdentity }
		val missingRunDeletions = authority.runIdentities.mapNotNull { runIdentity ->
			if (runIdentity in deletionsByRun) {
				null
			} else {
				ImportedActivityDeletionGenerationEntity.create(
					runIdentity = runIdentity,
					collectedDataEpoch = request.expectedCollectedDataEpoch,
					generation = 1L,
					deletedAtMs = request.deletedAtMs,
				)
			}
		}
		if (missingRunDeletions.isNotEmpty()) dao.insertDeletionGenerations(missingRunDeletions)
		val finalRunDeletions = storedValue { dao.deletionGenerations(authority.runIdentities) }
		authenticateDeletionRows(
			authority,
			request.expectedCollectedDataEpoch,
			finalRunDeletions,
			sourceDeletions,
			requireEveryRun = true,
		)
		val deletionReceipt = storedValue {
			ImportedActivityEntryDeletionReceiptEntity.create(
				entryDeletion = entryDeletion,
				deletedContentChecksum = request.selected.contentChecksum.value,
				runScopes = authority.runScopes.entries.map { it.key to it.value },
				windowIdentities = authority.windowIdentities,
				runDeletions = finalRunDeletions,
				sourceFences = sourceDeletions,
				retainedFromMs = state.retainedFromMs,
			)
		}
		dao.insertEntryDeletionReceipt(deletionReceipt)
		writeCheckpoint(ImportedActivityDeletionCheckpoint.TOMBSTONES_RECORDED)

		val expectedRevisionCount = try {
			Math.toIntExact(request.selected.importRevision)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
		}
		if (dao.deleteEntryRevisions(authority.entryIdentity) != expectedRevisionCount) {
			storedCorrupt()
		}
		storedValue { authenticateAbsentIdentity(dao, authority, exactDeletion = true) }
		if (dao.latestHistoryCandidate(authority.entryIdentity) != null ||
			dao.entryDeletion(authority.entryIdentity) != entryDeletion ||
			dao.entryDeletionReceipt(authority.entryIdentity) != deletionReceipt ||
			dao.deletionGenerations(authority.runIdentities).toSet() != finalRunDeletions.toSet()
		) storedCorrupt()
		writeCheckpoint(ImportedActivityDeletionCheckpoint.HIERARCHY_REMOVED)
		return DeleteSelectedImportedActivityResult.Deleted(
			importRevisionCount = expectedRevisionCount,
			physicalRunCount = authority.runIdentities.size,
		)
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun authenticateReplay(
		request: DeleteSelectedImportedActivityRequest,
		state: SourceEvidenceState,
		dao: ImportedActivityDao,
		entryDeletion: ImportedActivityEntryDeletionEntity,
		authority: ExpectedImportedActivityDeletionAuthority,
	): DeleteSelectedImportedActivityResult {
		val receipt = storedValue { dao.entryDeletionReceipt(authority.entryIdentity) }
			?: storedCorrupt()
		if (entryDeletion.entryIdentity != receipt.entryIdentity ||
			entryDeletion.collectedDataEpoch != receipt.collectedDataEpoch ||
			entryDeletion.deletedImportRevision != receipt.deletedImportRevision ||
			entryDeletion.deletedAtMs != receipt.deletedAtMs ||
			entryDeletion.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			receipt.deletedImportRevision > ImportedActivityDao.MAX_REVISIONS_PER_ENTRY.toLong()
		) storedCorrupt()
		if (entryDeletion.deletedImportRevision != request.selected.importRevision ||
			receipt.deletedContentChecksum != request.selected.contentChecksum.value ||
			receipt.expectedRunCount != authority.runIdentities.size ||
			receipt.runScopeSetChecksum != ImportedActivityEntryDeletionReceiptEntity
				.checksumRunScopes(authority.runScopes.entries.map { it.key to it.value }) ||
			receipt.expectedWindowCount != authority.windowIdentities.size ||
			receipt.windowIdentitySetChecksum != ImportedActivityEntryDeletionReceiptEntity
				.checksumWindowIdentities(authority.windowIdentities)
		) blocked(SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION)
		if (receipt.retainedFromMs != state.retainedFromMs) {
			blocked(SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY)
		}
		storedValue { authenticateAbsentIdentity(dao, authority, exactDeletion = true) }

		val runDeletions = storedValue { dao.deletionGenerations(authority.runIdentities) }
		val sourceDeletions = storedValue { sourceDeletions(authority) }
		authenticateDeletionRows(
			authority,
			request.expectedCollectedDataEpoch,
			runDeletions,
			sourceDeletions,
			requireEveryRun = true,
		)
		if (receipt.runDeletionSetChecksum != ImportedActivityEntryDeletionReceiptEntity
				.checksumRunDeletions(runDeletions) ||
			receipt.sourceFenceCount != sourceDeletions.size ||
			receipt.sourceFenceSetChecksum != ImportedActivityEntryDeletionReceiptEntity
				.checksumSourceFences(sourceDeletions)
		) storedCorrupt()
		val latestDurableTimeMs = maxOf(
			state.updatedAtMs,
			entryDeletion.deletedAtMs,
			runDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
			sourceDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
		)
		if (request.deletedAtMs < latestDurableTimeMs) {
			blocked(SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST)
		}
		return DeleteSelectedImportedActivityResult.AlreadyDeleted(
			entryDeletion.deletedImportRevision,
		)
	}

	@Suppress("ComplexCondition", "LongMethod")
	private suspend fun authenticateAbsentIdentity(
		dao: ImportedActivityDao,
		authority: ExpectedImportedActivityDeletionAuthority,
		exactDeletion: Boolean,
	) {
		val foundEntryDeletions = linkedSetOf<String>()
		val foundReceipts = linkedSetOf<String>()
		val foundRunDeletions = linkedSetOf<String>()
		authority.protectedIdentities
			.chunked(ImportedActivityDao.PROTECTED_IDENTITY_AUDIT_BATCH_SIZE)
			.forEach { identityBatch ->
				val maximumExpectedOwnerCount = identityBatch.size + 1
				val owners = dao.protectedIdentityOwners(
					identities = identityBatch,
					limit = maximumExpectedOwnerCount + 1,
				)
				if (owners.size > maximumExpectedOwnerCount) dependencyOverflow()
				owners.forEach { owner ->
					authenticateProtectedIdentityOwner(
						owner,
						authority,
						exactDeletion,
						foundEntryDeletions,
						foundReceipts,
						foundRunDeletions,
					)
				}
			}
		if (!exactDeletion) return
		if (foundEntryDeletions != setOf(authority.entryIdentity) ||
			foundReceipts != setOf(authority.entryIdentity) ||
			foundRunDeletions != authority.runIdentities.toSet()
		) storedCorrupt()
	}

	@Suppress("LongParameterList", "ComplexCondition")
	private fun authenticateProtectedIdentityOwner(
		owner: ImportedActivityProtectedIdentityOwner,
		authority: ExpectedImportedActivityDeletionAuthority,
		exactDeletion: Boolean,
		foundEntryDeletions: MutableSet<String>,
		foundReceipts: MutableSet<String>,
		foundRunDeletions: MutableSet<String>,
	) {
		when (owner.ownerKind) {
			ImportedActivityProtectedIdentityOwner.ENTRY_DELETION -> {
				if (!exactDeletion || owner.protectedIdentity != authority.entryIdentity) originConflict()
				foundEntryDeletions += owner.protectedIdentity
			}
			ImportedActivityProtectedIdentityOwner.ENTRY_DELETION_RECEIPT -> {
				if (!exactDeletion || owner.protectedIdentity != authority.entryIdentity) originConflict()
				foundReceipts += owner.protectedIdentity
			}
			ImportedActivityProtectedIdentityOwner.RUN_DELETION -> {
				if (!exactDeletion || owner.protectedIdentity !in authority.runIdentities) originConflict()
				foundRunDeletions += owner.protectedIdentity
			}
			ImportedActivityProtectedIdentityOwner.SOURCE_DELETION_SCOPE -> {
				if (!exactDeletion || owner.protectedIdentity !in authority.scopeDigests ||
					owner.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
					owner.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
					owner.scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN
				) originConflict()
			}
			else -> if (owner.protectedIdentity == authority.entryIdentity) {
				storedCorrupt()
			} else {
				originConflict()
			}
		}
	}

	@Suppress("ComplexCondition")
	private fun authenticateDeletionRows(
		authority: ExpectedImportedActivityDeletionAuthority,
		epoch: Long,
		runDeletions: List<ImportedActivityDeletionGenerationEntity>,
		sourceDeletions: List<SourceDeletionFenceEntity>,
		requireEveryRun: Boolean,
	) {
		if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
			runDeletions.any {
				it.runIdentity !in authority.runIdentities ||
					it.collectedDataEpoch != epoch || it.generation != 1L
			} || requireEveryRun && runDeletions.size != authority.runIdentities.size ||
			sourceDeletions.distinctBy { it.scopeIdentityDigest }.size != sourceDeletions.size ||
			sourceDeletions.any {
				it.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
					it.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
					it.scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN ||
					it.scopeIdentityDigest !in authority.scopeDigests ||
					it.collectedDataEpoch != epoch || it.fenceGeneration != 1L
			}
		) storedCorrupt()
	}

	private suspend fun sourceDeletions(
		authority: ExpectedImportedActivityDeletionAuthority,
	): List<SourceDeletionFenceEntity> = database.trackingHistoryReadDao().deletionFences(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigests = authority.scopeDigests,
	)

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: ImportedActivityDeletionAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	}

	private fun blocked(reason: SelectedImportedActivityDeletionBlockedReason): Nothing =
		throw ImportedActivityDeletionAbort(DeleteSelectedImportedActivityResult.Blocked(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)

	private fun originConflict(): Nothing =
		unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)

	private fun dependencyOverflow(): Nothing =
		unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)

	private fun unverifiable(reason: ImportedActivityProductFailure): Nothing =
		throw ImportedActivityDeletionAbort(DeleteSelectedImportedActivityResult.Unverifiable(reason))

	private class ImportedActivityDeletionAbort(
		val result: DeleteSelectedImportedActivityResult,
	) : RuntimeException(null, null, false, false)
}

private data class ExpectedImportedActivityDeletionAuthority(
	val entryIdentity: String,
	val runScopes: Map<String, String>,
	val windowIdentities: List<String>,
) {
	val runIdentities: List<String> = runScopes.keys.toList()
	val scopeDigests: List<String> = runScopes.values.toList()
	val protectedIdentities: List<String> = buildList {
		add(entryIdentity)
		addAll(runIdentities)
		addAll(windowIdentities)
		addAll(scopeDigests)
	}

	init {
		require(protectedIdentities.distinct().size == protectedIdentities.size)
	}

	fun exactlyMatches(
		runs: List<Pair<String, String>>,
		windows: List<String>,
	): Boolean = runs.size == runScopes.size && runs.distinctBy { it.first }.size == runs.size &&
		runs.distinctBy { it.second }.size == runs.size && runs.toMap() == runScopes &&
		windows.size == windowIdentities.size && windows.toSet() == windowIdentities.toSet()

	companion object {
		fun from(selected: SelectedImportedActivityIdentity) = ExpectedImportedActivityDeletionAuthority(
			entryIdentity = selected.entryIdentity.value,
			runScopes = selected.runDeletionScopes.associate { run ->
				run.runIdentity.value to run.deletionScopeDigest.value
			},
			windowIdentities = selected.windowIdentities.map { it.value },
		)
	}
}

internal enum class ImportedActivityDeletionCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	TOMBSTONES_RECORDED,
	HIERARCHY_REMOVED,
}
