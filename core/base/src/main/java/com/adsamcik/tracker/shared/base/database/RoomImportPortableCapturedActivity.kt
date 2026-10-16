package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityRunIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityWindowIdentityOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Activity-local portable admission. It writes no live source, session, provider, or WAL row. */
// One source-local Room admission boundary owns replay, corrections, privacy checks and exact write checkpoints.
// Keep its phases with the existing failure translation instead of introducing another import owner.
@Suppress("LargeClass", "TooManyFunctions")
@Singleton
class RoomImportPortableCapturedActivity internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (PortableActivityImportWriteCheckpoint) -> Unit,
) : ImportPortableCapturedActivity {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun importEntry(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityResult = withContext(ioDispatcher) {
		try {
			val snapshot = snapshot(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableActivityImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortableCapturedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: Exception) {
			ImportPortableCapturedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun importInTransaction(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityResult {
		writeCheckpoint(PortableActivityImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedActivityDao()
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortableActivityImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortableActivityImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		authenticateAllRetentionAuthority(dao, state)

		val entry = request.entry
		val retentionFloor = state.retainedFromMs
		authenticateEntryRetention(dao, entry, state, retentionFloor)
		authenticateEntryDeletion(dao, entry, request.expectedCollectedDataEpoch)
		authenticateRunDeletion(dao, entry, request.expectedCollectedDataEpoch)
		authenticateSourceDeletion(entry, request.expectedCollectedDataEpoch)

		authenticateOpaqueIdentityOwnership(
			dao,
			entry,
			state,
		)
		val lineage = authenticateActivityImportLineage(dao, request, retentionFloor)
		return admitActivityRevision(dao, request, lineage)
	}

	private suspend fun authenticateActivityImportLineage(
		dao: ImportedActivityDao,
		request: ImportPortableCapturedActivityRequest,
		retentionFloor: Long?,
	): AuthenticatedImportedActivityLineage {
		val entry = request.entry
		val lineage = authenticateStoredLineage(
			dao,
			entry.identity.value,
			request.expectedCollectedDataEpoch,
		)
		if (retentionFloor != null && lineage.revisions.any { revision ->
				revision.entry.crossesRetentionBoundary(retentionFloor)
			}
		) {
			blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY)
		}
		val baseline = lineage.revisions.firstOrNull()?.entry
		if (baseline != null && !entry.hasSameImportedActivityStructureAs(baseline)) {
			blocked(PortableActivityImportBlockedReason.CORRECTION_CONFLICT)
		}
		return lineage
	}

	private suspend fun admitActivityRevision(
		dao: ImportedActivityDao,
		request: ImportPortableCapturedActivityRequest,
		lineage: AuthenticatedImportedActivityLineage,
	): ImportPortableCapturedActivityResult {
		val entry = request.entry
		val receiptBinding = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receiptBinding != null) {
			return authenticateReceiptReplay(request, receiptBinding, lineage)
		}
		if (lineage.receipts.size >= ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision))
			writeCheckpoint(PortableActivityImportWriteCheckpoint.RECEIPT_INSERTED)
			return ImportPortableCapturedActivityResult.Duplicate(identical.header.importRevision)
		}
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			storedCorrupt()
		}
		if (lineage.revisions.size >= ImportedActivityDao.MAX_REVISIONS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val revision = lineage.revisions.size.toLong() + 1L
		return persistActivityRevision(dao, request, entry, revision)
	}

	private suspend fun persistActivityRevision(
		dao: ImportedActivityDao,
		request: ImportPortableCapturedActivityRequest,
		entry: PortableActivityEntryV1,
		revision: Long,
	): ImportPortableCapturedActivityResult {
		val epoch = request.expectedCollectedDataEpoch
		dao.insertEntryRevision(entry.toEntity(request, revision))
		writeCheckpoint(PortableActivityImportWriteCheckpoint.ENTRY_INSERTED)
		entry.runs.forEach { run ->
			insertActivityRun(dao, entry.identity.value, revision, epoch, run)
		}
		dao.insertReceipt(request.toReceiptEntity(revision))
		writeCheckpoint(PortableActivityImportWriteCheckpoint.RECEIPT_INSERTED)
		return ImportPortableCapturedActivityResult.Applied(
			importRevision = revision,
			physicalRunCount = entry.runs.size,
			windowCount = entry.runs.sumOf { it.windows.size },
			fragmentCount = entry.runs.sumOf { run -> run.windows.sumOf { it.fragments.size } },
		)
	}

	private suspend fun insertActivityRun(
		dao: ImportedActivityDao,
		entryIdentity: String,
		revision: Long,
		epoch: Long,
		run: PortableActivityRunV1,
	) {
		dao.insertRun(run.toEntity(entryIdentity, revision, epoch))
		writeCheckpoint(PortableActivityImportWriteCheckpoint.RUN_INSERTED)
		run.zoneEpochs.forEachIndexed { ordinal, zone ->
			dao.insertZoneEpoch(zone.toEntity(entryIdentity, revision, run.identity.value, ordinal))
			writeCheckpoint(PortableActivityImportWriteCheckpoint.ZONE_EPOCH_INSERTED)
		}
		for (windows in run.windows.chunked(WRITE_BATCH_SIZE)) {
			dao.insertWindows(windows.map { window ->
				window.toEntity(entryIdentity, revision, run.identity.value)
			})
			writeCheckpoint(PortableActivityImportWriteCheckpoint.WINDOW_INSERTED)
			val fragments = windows.asSequence().flatMap { window ->
				window.fragments.asSequence().mapIndexed { ordinal, fragment ->
					fragment.toEntity(entryIdentity, revision, run.identity.value,
						window.identity.value, ordinal)
				}
			}
			for (batch in fragments.chunked(WRITE_BATCH_SIZE)) {
				dao.insertFragments(batch)
				writeCheckpoint(PortableActivityImportWriteCheckpoint.FRAGMENT_INSERTED)
			}
		}
	}

	private suspend fun authenticateEntryRetention(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		state: SourceEvidenceState,
		retentionFloor: Long?,
	) {
		if (retentionFloor != null && retentionFloor < 0L) {
			storedCorrupt()
		}
		val retainedEntry = storedValue { dao.retentionReceipt(entry.identity.value) }
		if (retainedEntry != null) {
			authenticateRetentionReceipts(listOf(retainedEntry), state)
			blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY)
		}
		if (retentionFloor != null && entry.crossesRetentionBoundary(retentionFloor)) {
			blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY)
		}
	}

	private suspend fun authenticateEntryDeletion(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		expectedCollectedDataEpoch: Long,
	) {
		val entryDeletion = storedValue { dao.entryDeletion(entry.identity.value) }
		val entryDeletionReceipt = storedValue { dao.entryDeletionReceipt(entry.identity.value) }
		// A saved deletion receipt must have its matching deletion row and exact revision stamp.
		@Suppress("ComplexCondition")
		if (
			entryDeletionReceipt != null && (
				entryDeletion == null ||
					entryDeletionReceipt.collectedDataEpoch != entryDeletion.collectedDataEpoch ||
					entryDeletionReceipt.deletedImportRevision != entryDeletion.deletedImportRevision ||
					entryDeletionReceipt.deletedAtMs != entryDeletion.deletedAtMs
			)
		) {
			storedCorrupt()
		}
		if (entryDeletion != null) {
			if (entryDeletion.collectedDataEpoch != expectedCollectedDataEpoch) {
				storedCorrupt()
			}
			blocked(PortableActivityImportBlockedReason.DELETED_ENTRY)
		}
	}

	private suspend fun authenticateRunDeletion(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		expectedCollectedDataEpoch: Long,
	) {
		val runIdentities = entry.runs.map { it.identity.value }
		val deletions = storedValue { dao.deletionGenerations(runIdentities) }
		if (deletions.any { it.collectedDataEpoch != expectedCollectedDataEpoch }) {
			storedCorrupt()
		}
		if (deletions.isNotEmpty()) {
			blocked(PortableActivityImportBlockedReason.DELETED_RUN)
		}
	}

	private suspend fun authenticateSourceDeletion(
		entry: PortableActivityEntryV1,
		expectedCollectedDataEpoch: Long,
	) {
		val requestedScopeDigests = entry.runs.mapTo(linkedSetOf()) {
			it.deletionScopeDigest.value
		}
		val sourceDeletionFences = storedValue {
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = requestedScopeDigests.toList(),
			)
		}
		if (sourceDeletionFences.any {
				it.hasInvalidActivityImportScope(requestedScopeDigests, expectedCollectedDataEpoch)
			}
		) {
			storedCorrupt()
		}
		if (sourceDeletionFences.isNotEmpty()) {
			blocked(PortableActivityImportBlockedReason.DELETED_SCOPE)
		}
	}

	// One saved fence must match this exact immutable Activity scope and original epoch bound.
	@Suppress("ComplexCondition")
	private fun SourceDeletionFenceEntity.hasInvalidActivityImportScope(
		requestedScopeDigests: Set<String>,
		expectedCollectedDataEpoch: Long,
	): Boolean = sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
		purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
		scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN ||
		scopeIdentityDigest !in requestedScopeDigests ||
		fenceGeneration != 1L ||
		collectedDataEpoch > expectedCollectedDataEpoch

	@Suppress("ComplexCondition")
	private fun authenticateReceiptReplay(
		request: ImportPortableCapturedActivityRequest,
		stored: ImportedActivityReceiptEntity,
		lineage: AuthenticatedImportedActivityLineage,
	): ImportPortableCapturedActivityResult {
		val receipt = request.receipt
		if (stored.importJobId != receipt.jobId || stored.importEntryKey != receipt.entryKey ||
			stored.importSourceName != receipt.sourceName || stored.receivedAtMs != receipt.receivedAtMs ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			stored.entryIdentity != request.entry.identity.value ||
			stored.entryContentChecksum != request.entry.contentChecksum.value
		) {
			blocked(PortableActivityImportBlockedReason.RECEIPT_CONFLICT)
		}
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: storedCorrupt()
		if (revision.entry != request.entry) {
			storedCorrupt()
		}
		return ImportPortableCapturedActivityResult.Duplicate(stored.entryImportRevision)
	}

	private suspend fun authenticateOpaqueIdentityOwnership(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		state: SourceEvidenceState,
	) {
		val kinds = buildMap {
			put(entry.identity.value, PortableActivityIdentityKind.LOGICAL_ENTRY)
			entry.runs.forEach { run ->
				put(run.identity.value, PortableActivityIdentityKind.PHYSICAL_RUN)
				run.windows.forEach { put(it.identity.value, PortableActivityIdentityKind.CAPTURE_WINDOW) }
			}
		}
		val runOwners = entry.runs.associate {
			it.identity.value to (entry.identity.value to it.deletionScopeDigest.value)
		}
		val scopeOwners = entry.runs.associate {
			it.deletionScopeDigest.value to (entry.identity.value to it.identity.value)
		}
		val windowOwners = entry.runs.flatMap { run ->
			run.windows.map { it.identity.value to run.identity.value }
		}.toMap()
		authenticateOpaqueIdentityNamespace(dao, entry, state, kinds, runOwners, windowOwners)
		authenticateOpaqueScopeNamespace(dao, state, scopeOwners)
	}

	private suspend fun authenticateOpaqueIdentityNamespace(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		state: SourceEvidenceState,
		kinds: Map<String, PortableActivityIdentityKind>,
		runOwners: Map<String, Pair<String, String>>,
		windowOwners: Map<String, String>,
	) {
		val expectedCollectedDataEpoch = state.collectedDataEpoch
		kinds.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { identities ->
			val limit = Math.addExact(identities.size, 1)
			val entries = storedValue { dao.existingEntryIdentities(identities, limit) }
			val runs = storedValue { dao.existingRunIdentityOwners(identities, limit) }
			val windows = storedValue { dao.existingWindowIdentityOwners(identities, limit) }
			val identityAsScopes = storedValue { dao.existingRunScopeOwners(identities, 1) }
			val deletedEntries = storedValue { dao.entryDeletions(identities) }
			val deletionReceipts = storedValue { dao.entryDeletionReceipts(identities) }
			val deletedRuns = storedValue { dao.deletionGenerations(identities) }
			val retainedOwners = storedValue { dao.retainedIdentityOwners(identities, limit) }
			val retainedReceiptOwners = storedValue { dao.retentionReceipts(identities) }
			authenticateRetentionOwners(dao, retainedOwners, state)
			retainedReceiptOwners.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE).forEach {
				authenticateRetentionReceipts(it, state)
			}
			authenticateActivityDeletionEpochs(
				deletedEntries, deletionReceipts, deletedRuns, expectedCollectedDataEpoch,
			)
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit) {
				unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			if (identityAsScopes.isNotEmpty() || retainedOwners.isNotEmpty() ||
				retainedReceiptOwners.isNotEmpty()
			) {
				blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			authenticateActivityHierarchyOwners(
				entries, runs, windows, kinds, runOwners, windowOwners, entry.identity.value,
			)
			authenticateActivityDeletionIdentityOwners(kinds, deletedEntries, deletionReceipts, deletedRuns)
		}
	}

	private suspend fun authenticateOpaqueScopeNamespace(
		dao: ImportedActivityDao,
		state: SourceEvidenceState,
		scopeOwners: Map<String, Pair<String, String>>,
	) {
		val expectedCollectedDataEpoch = state.collectedDataEpoch
		scopeOwners.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { digests ->
			val owners = storedValue { dao.existingRunScopeOwners(digests, digests.size + 1) }
			val scopeAsEntries = storedValue { dao.existingEntryIdentities(digests, 1) }
			val scopeAsRuns = storedValue { dao.existingRunIdentityOwners(digests, 1) }
			val scopeAsWindows = storedValue { dao.existingWindowIdentityOwners(digests, 1) }
			val scopeAsDeletedEntries = storedValue { dao.entryDeletions(digests) }
			val scopeAsDeletionReceipts = storedValue { dao.entryDeletionReceipts(digests) }
			val scopeAsDeletedRuns = storedValue { dao.deletionGenerations(digests) }
			val scopeAsRetained = storedValue { dao.retainedIdentityOwners(digests, digests.size + 1) }
			val scopeAsRetentionReceipts = storedValue { dao.retentionReceipts(digests) }
			authenticateRetentionOwners(dao, scopeAsRetained, state)
			scopeAsRetentionReceipts.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE).forEach {
				authenticateRetentionReceipts(it, state)
			}
			authenticateActivityDeletionEpochs(
				scopeAsDeletedEntries, scopeAsDeletionReceipts, scopeAsDeletedRuns, expectedCollectedDataEpoch,
			)
			if (listOf(
				scopeAsEntries, scopeAsRuns, scopeAsWindows, scopeAsDeletedEntries,
				scopeAsDeletionReceipts, scopeAsDeletedRuns, scopeAsRetained, scopeAsRetentionReceipts,
			).any { it.isNotEmpty() }
			) {
				blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			if (owners.size > digests.size) {
				blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			val hasDifferentOwner = owners.any { owner ->
				scopeOwners[owner.deletionScopeDigest] != (owner.entryIdentity to owner.identity)
			}
			if (hasDifferentOwner) {
				blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
	}

	private fun authenticateActivityDeletionEpochs(
		deletedEntries: List<ImportedActivityEntryDeletionEntity>,
		deletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
		deletedRuns: List<ImportedActivityDeletionGenerationEntity>,
		expectedCollectedDataEpoch: Long,
	) {
		if (deletedEntries.any { it.collectedDataEpoch != expectedCollectedDataEpoch } ||
			deletionReceipts.any { it.collectedDataEpoch != expectedCollectedDataEpoch } ||
			deletedRuns.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
		) {
			storedCorrupt()
		}
	}

	// These are the existing indexed ownership rows, kept explicit instead of a second context model.
	@Suppress("LongParameterList")
	private fun authenticateActivityHierarchyOwners(
		entries: List<String>,
		runs: List<ImportedActivityRunIdentityOwner>,
		windows: List<ImportedActivityWindowIdentityOwner>,
		kinds: Map<String, PortableActivityIdentityKind>,
		runOwners: Map<String, Pair<String, String>>,
		windowOwners: Map<String, String>,
		entryIdentity: String,
	) {
		if (entries.any { kinds[it] != PortableActivityIdentityKind.LOGICAL_ENTRY }) {
			blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		if (runs.any { owner ->
				val expected = runOwners[owner.identity]
				kinds[owner.identity] != PortableActivityIdentityKind.PHYSICAL_RUN ||
					expected != (owner.entryIdentity to owner.deletionScopeDigest)
			}) {
			blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		if (windows.any { owner ->
				kinds[owner.identity] != PortableActivityIdentityKind.CAPTURE_WINDOW ||
					owner.entryIdentity != entryIdentity || windowOwners[owner.identity] != owner.runIdentity
			}) {
			blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private fun authenticateActivityDeletionIdentityOwners(
		kinds: Map<String, PortableActivityIdentityKind>,
		deletedEntries: List<ImportedActivityEntryDeletionEntity>,
		deletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
		deletedRuns: List<ImportedActivityDeletionGenerationEntity>,
	) {
		if (deletedEntries.any { marker ->
				kinds[marker.entryIdentity] != PortableActivityIdentityKind.LOGICAL_ENTRY
			} || deletionReceipts.any { receipt ->
				kinds[receipt.entryIdentity] != PortableActivityIdentityKind.LOGICAL_ENTRY ||
					deletedEntries.none { marker -> marker.entryIdentity == receipt.entryIdentity }
			} || deletedRuns.any { marker ->
				kinds[marker.runIdentity] != PortableActivityIdentityKind.PHYSICAL_RUN
			}
		) {
			blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun authenticateRetentionOwners(
		dao: ImportedActivityDao,
		owners: List<ImportedActivityRetainedIdentityEntity>,
		state: SourceEvidenceState,
	) {
		if (owners.isEmpty()) {
			return
		}
		if (owners.distinctBy { it.protectedIdentity }.size != owners.size) {
			storedCorrupt()
		}
		val entryIdentities = owners.map { it.entryIdentity }.distinct()
		val receipts = storedValue { dao.retentionReceipts(entryIdentities) }
		if (receipts.map { it.entryIdentity }.toSet() != entryIdentities.toSet()) {
			storedCorrupt()
		}
		receipts.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE).forEach {
			authenticateRetentionReceipts(it, state)
		}
	}

	private suspend fun authenticateAllRetentionAuthority(
		dao: ImportedActivityDao,
		state: SourceEvidenceState,
	) {
		val receiptCount = storedValue { dao.retentionReceiptCount() }
		val markerCount = storedValue { dao.retainedIdentityCount() }
		if (receiptCount < 0L || markerCount < 0L ||
			receiptCount > ActivityCapturedPortableFormatV1.MAX_ENTRIES ||
			markerCount > ImportedActivityDao.MAX_RETAINED_IDENTITY_ROWS
		) {
			unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (storedValue { dao.orphanRetainedIdentityCount() } != 0L) {
			storedCorrupt()
		}
		val loadedCount = authenticateRetentionReceiptPages(dao, state)
		if (loadedCount != receiptCount) {
			storedCorrupt()
		}
	}

	private suspend fun authenticateRetentionReceiptPages(
		dao: ImportedActivityDao,
		state: SourceEvidenceState,
	): Long {
		var loadedCount = 0L
		var afterIdentity: String? = null
		while (true) {
			val page = storedValue {
				dao.retentionReceiptPage(
					afterIdentity,
					ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE,
				)
			}
			if (page.isEmpty()) {
				break
			}
			requireOrderedRetentionReceiptPage(page, afterIdentity)
			authenticateRetentionReceipts(page, state)
			loadedCount = try {
				Math.addExact(loadedCount, page.size.toLong())
			} catch (_: ArithmeticException) {
				unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			if (loadedCount > ActivityCapturedPortableFormatV1.MAX_ENTRIES) {
				unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			afterIdentity = page.last().entryIdentity
			if (page.size < ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE) {
				break
			}
		}
		return loadedCount
	}

	private fun requireOrderedRetentionReceiptPage(
		page: List<ImportedActivityRetentionReceiptEntity>,
		afterIdentity: String?,
	) {
		if (page.size > ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE ||
			page.zipWithNext().any { (left, right) -> left.entryIdentity >= right.entryIdentity } ||
			afterIdentity?.let { prior -> page.first().entryIdentity <= prior } == true
		) {
			storedCorrupt()
		}
	}

	private suspend fun authenticateRetentionReceipts(
		receipts: List<ImportedActivityRetentionReceiptEntity>,
		state: SourceEvidenceState,
	) {
		when (storedValue { database.authenticateImportedActivityRetentionBatch(state, receipts) }) {
			ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> storedCorrupt()
			null -> Unit
		}
	}

	private suspend fun authenticateStoredLineage(
		dao: ImportedActivityDao,
		identity: String,
		epoch: Long,
	): AuthenticatedImportedActivityLineage = storedValue {
		try {
			ImportedActivityLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = epoch,
				retainedState = checkNotNull(database.sourceEvidenceStateDao().get()).also {
					check(it.collectedDataEpoch == epoch)
				},
				headers = dao.entryRevisionsForAdmission(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				zoneEpochs = dao.allZoneEpochsForAdmission(identity),
				windows = dao.allWindowsForAdmission(identity),
				fragments = dao.allFragmentsForAdmission(identity),
			)
		} catch (failure: ImportedActivityLineageFailure) {
			unverifiable(failure.reason.toImportReason())
		}
	}

	private suspend fun snapshot(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityRequest {
		val context = currentCoroutineContext()
		return incomingValue {
			context.ensureActive()
			val rawRuns = request.entry.runs
			requireIncomingActivityBounds(rawRuns)
			val runs = rawRuns.map { run ->
				context.ensureActive()
				val zones = run.zoneEpochs.map { it.copy() }.toList()
				val windows = run.windows.map { window ->
					context.ensureActive()
					val fragments = window.fragments.map { fragment ->
						when (fragment) {
							is PortableActivityFragmentV1.Gap -> fragment.copy()
							is PortableActivityFragmentV1.Band -> fragment.copy()
						}
					}.toList()
					window.copy(fragments = fragments)
				}.toList()
					run.copy(zoneEpochs = zones, windows = windows)
			}.toList()
			requireCopiedActivityShape(request, runs)
			request.copy(entry = request.entry.copy(runs = runs))
		}
	}

	private fun requireIncomingActivityBounds(rawRuns: List<PortableActivityRunV1>) {
		if (rawRuns.size > ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.RUN_OVERFLOW)
		}
		if (rawRuns.any {
				it.zoneEpochs.size > ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN
			}
		) {
			unverifiable(PortableActivityImportUnverifiableReason.ZONE_EPOCH_OVERFLOW)
		}
		if (rawRuns.any { it.windows.size > ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN }) {
			unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
		}
		if (rawRuns.any { run ->
			run.windows.any {
				it.fragments.size > ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW
			}
		}
		) {
			unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
		}
		val totalWindows = rawRuns.sumOf { it.windows.size.toLong() }
		if (totalWindows > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
		}
		val totalFragments = rawRuns.sumOf { run ->
			run.windows.sumOf { it.fragments.size.toLong() }
		}
		if (totalFragments > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
		}
	}

	private fun requireCopiedActivityShape(
		request: ImportPortableCapturedActivityRequest,
		runs: List<PortableActivityRunV1>,
	) {
		val copiedWindowCount = runs.sumOf { it.windows.size.toLong() }
		if (copiedWindowCount > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
		}
		val copiedFragmentCount = runs.sumOf { run ->
			run.windows.sumOf { it.fragments.size.toLong() }
		}
		if (copiedFragmentCount > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
		}
		val identities = buildList {
			add(request.entry.identity.value)
			runs.forEach { run ->
				add(run.identity.value)
				addAll(run.windows.map { it.identity.value })
			}
		}
		val deletionScopes = runs.map { it.deletionScopeDigest.value }
		require((identities + deletionScopes).distinct().size == identities.size + deletionScopes.size)
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableActivityImportAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	} catch (_: DateTimeException) {
		storedCorrupt()
	}

	private inline fun <T> incomingValue(block: () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableActivityImportAbort) {
		throw abort
	} catch (_: RuntimeException) {
		unverifiable(PortableActivityImportUnverifiableReason.ENTRY_INVALID)
	}

	private fun blocked(reason: PortableActivityImportBlockedReason): Nothing =
		throw PortableActivityImportAbort(ImportPortableCapturedActivityResult.Blocked(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun unverifiable(reason: PortableActivityImportUnverifiableReason): Nothing =
		throw PortableActivityImportAbort(ImportPortableCapturedActivityResult.Unverifiable(reason))

	private class PortableActivityImportAbort(
		val result: ImportPortableCapturedActivityResult,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val IDENTITY_QUERY_CHUNK_SIZE = 256
		const val WRITE_BATCH_SIZE = 256
	}
}

internal enum class PortableActivityImportWriteCheckpoint {
	TRANSACTION_STARTED,
	ENTRY_INSERTED,
	RUN_INSERTED,
	ZONE_EPOCH_INSERTED,
	WINDOW_INSERTED,
	FRAGMENT_INSERTED,
	RECEIPT_INSERTED,
}

private fun ImportPortableCapturedActivityRequest.toReceiptEntity(revision: Long) =
	ImportedActivityReceiptEntity(
		importJobId = receipt.jobId,
		importEntryKey = receipt.entryKey,
		importSourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		entryIdentity = entry.identity.value,
		entryImportRevision = revision,
		entryContentChecksum = entry.contentChecksum.value,
		collectedDataEpoch = expectedCollectedDataEpoch,
	)

private fun PortableActivityEntryV1.toEntity(
	request: ImportPortableCapturedActivityRequest,
	revision: Long,
) = ImportedActivityEntryRevisionEntity(
	identity = identity.value,
	importRevision = revision,
	supersedesImportRevision = if (revision == 1L) {
		null
	} else {
		revision - 1L
	},
	contentChecksum = contentChecksum.value,
	sourceFormat = ActivityCapturedPortableFormatV1.FORMAT,
	sourceSchemaVersion = ActivityCapturedPortableFormatV1.SCHEMA_VERSION,
	sessionMode = sessionMode.name,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	importJobId = request.receipt.jobId,
	importEntryKey = request.receipt.entryKey,
	importSourceName = request.receipt.sourceName,
	receivedAtMs = request.receipt.receivedAtMs,
)

private fun PortableActivityRunV1.toEntity(entry: String, revision: Long, epoch: Long) =
	ImportedActivityRunEntity(
		entryIdentity = entry,
		entryImportRevision = revision,
		identity = identity.value,
		deletionScopeDigest = deletionScopeDigest.value,
		contentChecksum = contentChecksum.value,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		captureCoverage = captureCoverage.name,
		collectedDataEpoch = epoch,
		scopeDeletionGeneration = 0L,
	)

private fun PortableActivityZoneEpochV1.toEntity(
	entry: String,
	revision: Long,
	run: String,
	ordinal: Int,
) = ImportedActivityZoneEpochEntity(entry, revision, run, ordinal, effectiveWallTimeMs, zoneId)

private fun PortableActivityWindowV1.toEntity(entry: String, revision: Long, run: String) =
	ImportedActivityWindowEntity(
		entryIdentity = entry,
		entryImportRevision = revision,
		runIdentity = run,
		identity = identity.value,
		contentChecksum = contentChecksum.value,
		startOffsetNanos = startOffsetNanos,
		endOffsetNanos = endOffsetNanos,
		storedZoneId = storedZoneId,
		coverage = coverage.name,
		knownActiveDurationNanos = knownActiveDurationNanos,
		knownInactiveDurationNanos = knownInactiveDurationNanos,
		unknownActivityDurationNanos = unknownActivityDurationNanos,
		unobservedDurationNanos = unobservedDurationNanos,
	)

@Suppress("LongMethod")
private fun PortableActivityFragmentV1.toEntity(
	entry: String,
	revision: Long,
	run: String,
	window: String,
	ordinal: Int,
): ImportedActivityFragmentEntity = when (this) {
	is PortableActivityFragmentV1.Gap -> ImportedActivityFragmentEntity(
		entry, revision, run, window, ordinal, ImportedActivityFragmentEntity.KIND_GAP,
		startOffsetNanos, endOffsetNanos, reason, null, null, null, null, null, null, null,
		null, null, null, null, null, null, null,
	)
	is PortableActivityFragmentV1.Band -> ImportedActivityFragmentEntity(
		entry, revision, run, window, ordinal, ImportedActivityFragmentEntity.KIND_BAND,
		startOffsetNanos, endOffsetNanos, null, activity, mechanism, refinedTransitionActivity,
		confidenceKind, confidenceMinimumPercent, confidenceMaximumPercent,
		confidenceObservationCount, startWallTimeMs, startWallTimeUncertaintyMs,
		startBoundaryKind, endWallTimeMs, endWallTimeUncertaintyMs, endBoundaryKind,
		wallTimeContinuity,
	)
}

private fun ImportedActivityLineageAuthenticator.Reason.toImportReason() = when (this) {
	ImportedActivityLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
		PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedActivityLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW ->
		PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.RUN_OVERFLOW ->
		PortableActivityImportUnverifiableReason.RUN_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.WINDOW_OVERFLOW ->
		PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.FRAGMENT_OVERFLOW ->
		PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.ZONE_EPOCH_OVERFLOW ->
		PortableActivityImportUnverifiableReason.ZONE_EPOCH_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.REVISION_OVERFLOW ->
		PortableActivityImportUnverifiableReason.REVISION_OVERFLOW
}

internal fun PortableActivityEntryV1.crossesRetentionBoundary(retainedFromMs: Long): Boolean {
	val earliestEvidenceMs = runs.asSequence()
		.flatMap { run -> run.windows.asSequence() }
		.flatMap { window -> window.fragments.asSequence() }
		.filterIsInstance<PortableActivityFragmentV1.Band>()
		.flatMap { band ->
			sequenceOf(
				band.startWallTimeMs.earliestPossibleTime(band.startWallTimeUncertaintyMs),
				band.endWallTimeMs.earliestPossibleTime(band.endWallTimeUncertaintyMs),
			)
		}
		.minOrNull()
	return earliestEvidenceMs == null || earliestEvidenceMs < retainedFromMs
}

private fun Long.earliestPossibleTime(uncertaintyMs: Long): Long =
	if (uncertaintyMs >= this) {
		0L
	} else {
		this - uncertaintyMs
	}
