@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalObservationOwner
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalRunOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiRetryableReason
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.isReciprocalCorrectionOf
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Wi-Fi-specific portable admission; no live source, provider, demand, writer, WAL, or location row is reachable. */
@Singleton
internal class RoomImportPortableCapturedWifi internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (PortableWifiImportWriteCheckpoint) -> Unit,
	private val limits: WifiImportLimits,
) : ImportPortableCapturedWifi {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() }, WifiImportLimits())

	override suspend fun importEntry(
		request: ImportPortableCapturedWifiRequest,
	): ImportPortableCapturedWifiResult = withContext(ioDispatcher) {
		try {
			val snapshot = snapshot(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableWifiImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortableCapturedWifiResult.RetryableFailure(
				PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: Exception) {
			ImportPortableCapturedWifiResult.RetryableFailure(
				PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun importInTransaction(
		request: ImportPortableCapturedWifiRequest,
	): ImportPortableCapturedWifiResult {
		writeCheckpoint(PortableWifiImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedWifiDao()
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortableCapturedWifiImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (!state.hasValidWifiImportShape()) storedCorrupt()
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortableCapturedWifiImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		val entry = request.entry
		if (state.retainedFromMs?.let(entry::crossesWifiRetentionFloor) == true) {
			blocked(PortableCapturedWifiImportBlockedReason.RETENTION_BOUNDARY)
		}
		authenticateGlobalCapacity(dao, NewWifiRows())

		val entryDeletion = storedValue { dao.entryDeletion(entry.identity.value) }
		if (entryDeletion != null) {
			if (entryDeletion.collectedDataEpoch != state.collectedDataEpoch) storedCorrupt()
			blocked(PortableCapturedWifiImportBlockedReason.DELETED_ENTRY)
		}
		val runIdentities = entry.runs.map { it.identity.value }
		val deletionScopes = entry.runs.map { it.deletionScopeDigest.value }
		authenticateDeletionAuthority(dao, runIdentities, deletionScopes, state)
		authenticateOpaqueIdentityOwnership(dao, entry, state)
		authenticateLiveOpaqueIdentityOwnership(dao, entry, state)

		val lineage = authenticateStoredLineage(dao, entry.identity.value, state.collectedDataEpoch)
		if (state.retainedFromMs?.let { floor ->
				lineage.revisions.any { it.entry.crossesWifiRetentionFloor(floor) }
			} == true
		) blocked(PortableCapturedWifiImportBlockedReason.RETENTION_BOUNDARY)
		val retainedRuns = lineage.revisions.flatMap { it.entry.runs }
		authenticateDeletionAuthority(
			dao,
			retainedRuns.map { it.identity.value }.distinct(),
			retainedRuns.map { it.deletionScopeDigest.value }.distinct(),
			state,
		)

		val receiptBinding = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receiptBinding != null) return authenticateReceiptReplay(request, receiptBinding, lineage)
		if (lineage.receipts.size >= ImportedWifiDao.MAX_RECEIPTS_PER_ENTRY) dependencyOverflow()

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			authenticateGlobalCapacity(dao, NewWifiRows(receipts = 1L))
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision))
			writeCheckpoint(PortableWifiImportWriteCheckpoint.RECEIPT_INSERTED)
			return ImportPortableCapturedWifiResult.Duplicate(identical.header.importRevision)
		}
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			storedCorrupt()
		}
		if (lineage.revisions.size >= ImportedWifiDao.MAX_REVISIONS_PER_ENTRY) {
			unverifiable(PortableCapturedWifiImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val latest = lineage.revisions.lastOrNull()
		if (latest != null && !entry.isReciprocalCorrectionOf(latest.entry)) {
			blocked(PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT)
		}
		val revision = try {
			Math.addExact(latest?.header?.importRevision ?: 0L, 1L)
		} catch (_: ArithmeticException) {
			unverifiable(PortableCapturedWifiImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val isNewEntry = lineage.revisions.isEmpty()
		authenticateGlobalCapacity(
			dao,
			NewWifiRows(
				entries = 1L,
				receipts = 1L,
				runs = entry.runs.size.toLong(),
				zones = entry.runs.sumOf { it.storedZoneIds.size.toLong() },
				observations = entry.runs.sumOf { it.observations.size.toLong() },
			),
			isNewEntry,
		)

		val epoch = state.collectedDataEpoch
		dao.insertEntryRevision(entry.toEntity(request, revision))
		writeCheckpoint(PortableWifiImportWriteCheckpoint.ENTRY_INSERTED)
		entry.runs.forEach { run ->
			currentCoroutineContext().ensureActive()
			dao.insertRun(run.toEntity(entry.identity.value, revision, epoch))
			writeCheckpoint(PortableWifiImportWriteCheckpoint.RUN_INSERTED)
			run.storedZoneIds.forEachIndexed { ordinal, zone ->
				dao.insertRunZone(ImportedWifiRunZoneEntity(
					entry.identity.value, revision, run.identity.value, ordinal, zone,
				))
				writeCheckpoint(PortableWifiImportWriteCheckpoint.ZONE_INSERTED)
			}
			run.observations.forEach { observation ->
				dao.insertObservation(observation.toEntity(entry.identity.value, revision, run.identity.value))
				writeCheckpoint(PortableWifiImportWriteCheckpoint.OBSERVATION_INSERTED)
			}
		}
		dao.insertReceipt(request.toReceiptEntity(revision))
		writeCheckpoint(PortableWifiImportWriteCheckpoint.RECEIPT_INSERTED)
		return ImportPortableCapturedWifiResult.Applied(
			importRevision = revision,
			physicalRunCount = entry.runs.size,
			observationCount = entry.runs.sumOf { it.observations.size },
		)
	}

	@Suppress("ComplexCondition")
	private fun authenticateReceiptReplay(
		request: ImportPortableCapturedWifiRequest,
		stored: ImportedWifiReceiptEntity,
		lineage: AuthenticatedImportedWifiLineage,
	): ImportPortableCapturedWifiResult {
		val receipt = request.receipt
		if (stored.importJobId != receipt.jobId || stored.importEntryKey != receipt.entryKey ||
			stored.importSourceName != receipt.sourceName || stored.receivedAtMs != receipt.receivedAtMs ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			stored.entryIdentity != request.entry.identity.value ||
			stored.entryContentChecksum != request.entry.contentChecksum.value
		) blocked(PortableCapturedWifiImportBlockedReason.RECEIPT_CONFLICT)
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: storedCorrupt()
		if (revision.entry != request.entry) storedCorrupt()
		return ImportPortableCapturedWifiResult.Duplicate(stored.entryImportRevision)
	}

	private suspend fun authenticateDeletionAuthority(
		dao: ImportedWifiDao,
		runIdentities: List<String>,
		deletionScopes: List<String>,
		state: SourceEvidenceState,
	) {
		if (runIdentities.isEmpty() && deletionScopes.isEmpty()) return
		val generations = storedValue {
			dao.deletionGenerationsByRun(runIdentities) + dao.deletionGenerationsByScope(deletionScopes)
		}.distinctBy { it.runIdentity }
		if (generations.any { it.collectedDataEpoch != state.collectedDataEpoch }) storedCorrupt()
		if (generations.any { it.runIdentity in runIdentities }) {
			blocked(PortableCapturedWifiImportBlockedReason.DELETED_RUN)
		}
		if (generations.any { it.deletionScopeDigest in deletionScopes }) {
			blocked(PortableCapturedWifiImportBlockedReason.DELETED_SCOPE)
		}
		val fences = storedValue {
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = deletionScopes,
			)
		}
		if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) storedCorrupt()
		if (fences.isNotEmpty()) blocked(PortableCapturedWifiImportBlockedReason.DELETED_SCOPE)
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateOpaqueIdentityOwnership(
		dao: ImportedWifiDao,
		entry: PortableCapturedWifiEntryV1,
		state: SourceEvidenceState,
	) {
		val ownership = WifiPortableOpaqueOwnership(entry)
		ownership.identityOwners.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
			val limit = Math.addExact(values.size, 1)
			val entries = storedValue { dao.existingEntryIdentities(values, limit) }
			val runs = storedValue { dao.existingRunIdentityOwners(values, limit) }
			val observations = storedValue { dao.existingObservationIdentityOwners(values, limit) }
			val scopes = storedValue { dao.existingRunScopeOwners(values, limit) }
			val selectedDeletions = storedValue {
				dao.selectedDeletionProtectedIdentityOwners(values, limit)
			}
			val tombstones = storedValue { dao.entryDeletions(values) }
			val generations = storedValue {
				dao.deletionGenerationsByRun(values) + dao.deletionGenerationsByScope(values) +
					dao.deletionGenerationsByEntry(values, 1)
			}
			if (entries.size >= limit || runs.size >= limit || observations.size >= limit ||
				scopes.size >= limit
			) {
				dependencyOverflow()
			}
			if (tombstones.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				generations.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				selectedDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch }
			) storedCorrupt()
			if (scopes.isNotEmpty() || selectedDeletions.isNotEmpty() ||
				tombstones.isNotEmpty() || generations.isNotEmpty() ||
				entries.any {
					ownership.identityOwners[it]?.kind != PortableWifiIdentityKind.LOGICAL_ENTRY
				} ||
				runs.any { row ->
					val owner = ownership.identityOwners[row.identity]
					owner?.kind != PortableWifiIdentityKind.PHYSICAL_RUN ||
						owner.entryIdentity != row.entryIdentity ||
						owner.runIdentity != row.identity ||
						owner.deletionScopeDigest != row.deletionScopeDigest
				} ||
				observations.any { row ->
					val owner = ownership.identityOwners[row.identity]
					owner?.kind != PortableWifiIdentityKind.OBSERVATION ||
						owner.entryIdentity != row.entryIdentity ||
						owner.runIdentity != row.runIdentity
				}
			) blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		ownership.scopeOwners.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
			val limit = Math.addExact(values.size, 1)
			val entries = storedValue { dao.existingEntryIdentities(values, 1) }
			val runs = storedValue { dao.existingRunIdentityOwners(values, 1) }
			val observations = storedValue { dao.existingObservationIdentityOwners(values, 1) }
			val scopes = storedValue { dao.existingRunScopeOwners(values, limit) }
			val selectedDeletions = storedValue {
				dao.selectedDeletionProtectedIdentityOwners(values, limit)
			}
			val tombstones = storedValue { dao.entryDeletions(values) }
			val generations = storedValue {
				dao.deletionGenerationsByRun(values) + dao.deletionGenerationsByScope(values) +
					dao.deletionGenerationsByEntry(values, 1)
			}
			if (tombstones.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				generations.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				selectedDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch }
			) storedCorrupt()
			if (entries.isNotEmpty() || runs.isNotEmpty() || observations.isNotEmpty() ||
				selectedDeletions.isNotEmpty() || tombstones.isNotEmpty() ||
				generations.isNotEmpty() || scopes.size >= limit ||
				scopes.any { row ->
					val owner = ownership.scopeOwners[row.deletionScopeDigest]
					owner?.kind != PortableWifiIdentityKind.PHYSICAL_RUN ||
						owner.entryIdentity != row.entryIdentity ||
						owner.runIdentity != row.runIdentity
				}
			) blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun authenticateLiveOpaqueIdentityOwnership(
		dao: ImportedWifiDao,
		entry: PortableCapturedWifiEntryV1,
		state: SourceEvidenceState,
	) {
		val incoming = WifiPortableOpaqueOwnership(entry).allValues
		incoming.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
			val limit = Math.toIntExact(limits.maximumGlobalAuthorityRows + 1L)
			val fences = storedValue {
				dao.deletionFenceIdentityOwners(values, limit)
			}
			if (fences.size >= limit) dependencyOverflow()
			if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) storedCorrupt()
			if (fences.isNotEmpty()) {
				blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
		val entryCount = authenticateLocalCount(storedValue { dao.localEntryOwnerCount() })
		val runCount = authenticateLocalCount(storedValue { dao.localRunOwnerCount() })
		val observationCount = authenticateLocalCount(storedValue { dao.localObservationOwnerCount() })
		val deletionCount = authenticateLocalCount(storedValue { dao.localDeletionOwnerCount() })
		listOf(entryCount, runCount, observationCount, deletionCount).fold(0L) { total, count ->
			checkedAuthorityCount(total, count)
		}
		val loadedEntries = pageStrings(dao::localEntryOwnerPage) { localIdentity ->
			checkLiveIdentity(PortableWifiIdentityKind.LOGICAL_ENTRY, localIdentity, incoming)
		}
		if (loadedEntries != entryCount) storedCorrupt()
		if (pageRuns(dao::localRunOwnerPage, incoming) != runCount) storedCorrupt()
		if (pageObservations(dao::localObservationOwnerPage, incoming) != observationCount) storedCorrupt()
		if (pageRuns(dao::localDeletionOwnerPage, incoming) != deletionCount) storedCorrupt()
	}

	private suspend fun pageStrings(
		loader: suspend (String?, Int) -> List<String>,
		visitor: (String) -> Unit,
	): Long {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = storedValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
			if (page.isEmpty()) return loaded
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left >= right } ||
				after?.let { page.first() <= it } == true
			) storedCorrupt()
			page.forEach(visitor)
			loaded = checkedAuthorityCount(loaded, page.size.toLong())
			after = page.last()
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) return loaded
		}
	}

	private suspend fun pageRuns(
		loader: suspend (String?, Int) -> List<WifiLocalRunOwner>,
		incoming: Set<String>,
	): Long {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = storedValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
			if (page.isEmpty()) return loaded
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left.serviceRunId >= right.serviceRunId } ||
				after?.let { page.first().serviceRunId <= it } == true
			) storedCorrupt()
			page.forEach { owner ->
				checkLiveIdentity(PortableWifiIdentityKind.LOGICAL_ENTRY, owner.logicalTrackingId, incoming)
				checkLiveIdentity(PortableWifiIdentityKind.PHYSICAL_RUN, owner.serviceRunId, incoming)
				val scope = storedValue { SourceDeletionFenceEntity.logicalServiceRunIdentity(
					SourceDestinationOwnerEntity.SOURCE_WIFI,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					owner.logicalTrackingId,
					owner.serviceRunId,
				) }
				if (scope in incoming) blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			loaded = checkedAuthorityCount(loaded, page.size.toLong())
			after = page.last().serviceRunId
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) return loaded
		}
	}

	private suspend fun pageObservations(
		loader: suspend (String?, Int) -> List<WifiLocalObservationOwner>,
		incoming: Set<String>,
	): Long {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = storedValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
			if (page.isEmpty()) return loaded
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left.logicalFactId >= right.logicalFactId } ||
				after?.let { page.first().logicalFactId <= it } == true
			) storedCorrupt()
			page.forEach { owner ->
				checkLiveIdentity(PortableWifiIdentityKind.OBSERVATION, owner.logicalFactId, incoming)
				checkLiveIdentity(PortableWifiIdentityKind.LOGICAL_ENTRY, owner.logicalTrackingId, incoming)
				checkLiveIdentity(PortableWifiIdentityKind.PHYSICAL_RUN, owner.serviceRunId, incoming)
				val scope = storedValue { SourceDeletionFenceEntity.logicalServiceRunIdentity(
					SourceDestinationOwnerEntity.SOURCE_WIFI,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					owner.logicalTrackingId,
					owner.serviceRunId,
				) }
				if (scope in incoming) blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			loaded = checkedAuthorityCount(loaded, page.size.toLong())
			after = page.last().logicalFactId
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) return loaded
		}
	}

	private fun checkLiveIdentity(kind: PortableWifiIdentityKind, local: String, incoming: Set<String>) {
		val opaque = try {
			PortableWifiOpaqueIdentity.derive(kind, local).value
		} catch (_: IllegalArgumentException) {
			storedCorrupt()
		}
		if (opaque in incoming) blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}

	private fun authenticateLocalCount(count: Long): Long {
		if (count < 0L || count > limits.maximumGlobalAuthorityRows) dependencyOverflow()
		return count
	}

	private fun checkedAuthorityCount(current: Long, added: Long): Long = try {
		if (current < 0L || added < 0L) dependencyOverflow()
		Math.addExact(current, added).also {
			if (it > limits.maximumGlobalAuthorityRows) dependencyOverflow()
		}
	} catch (_: ArithmeticException) {
		dependencyOverflow()
	}

	private suspend fun authenticateGlobalCapacity(
		dao: ImportedWifiDao,
		added: NewWifiRows,
		isNewEntry: Boolean = false,
	) {
		val existingEntries = storedValue { dao.distinctEntryCount() }
		if (existingEntries < 0L || existingEntries > limits.maximumEntries ||
			(isNewEntry && existingEntries == limits.maximumEntries)
		) {
			dependencyOverflow()
		}
		listOf(
			storedValue { dao.entryRevisionCount() } to added.entries,
			storedValue { dao.receiptCount() } to added.receipts,
			storedValue { dao.runCount() } to added.runs,
			storedValue { dao.runZoneCount() } to added.zones,
			storedValue { dao.observationCount() } to added.observations,
			storedValue { dao.entryDeletionCount() } to 0L,
			storedValue { dao.deletionGenerationCount() } to 0L,
			storedValue { dao.selectedDeletionReceiptCount() } to 0L,
			storedValue { dao.selectedDeletionProtectedIdentityCount() } to 0L,
		).fold(0L) { total, (existing, increment) ->
			checkedAuthorityCount(total, checkedAuthorityCount(existing, increment))
		}
	}

	private suspend fun authenticateStoredLineage(
		dao: ImportedWifiDao,
		identity: String,
		epoch: Long,
	): AuthenticatedImportedWifiLineage = storedValue {
		try {
			ImportedWifiLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = epoch,
				headers = dao.entryRevisionsForAdmission(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				zones = dao.allRunZonesForAdmission(identity),
				observations = dao.allObservationsForAdmission(identity),
			)
		} catch (failure: ImportedWifiLineageFailure) {
			unverifiable(failure.reason.toImportReason())
		}
	}

	private suspend fun snapshot(
		request: ImportPortableCapturedWifiRequest,
	): ImportPortableCapturedWifiRequest {
		val context = currentCoroutineContext()
		return incomingValue {
			context.ensureActive()
			val rawRuns = request.entry.runs
			if (rawRuns.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
				unverifiable(PortableCapturedWifiImportUnverifiableReason.RUN_OVERFLOW)
			}
			if (rawRuns.any { it.storedZoneIds.size > WifiCapturedPortableFormatV1.MAX_STORED_ZONES_PER_RUN }) {
				unverifiable(PortableCapturedWifiImportUnverifiableReason.ZONE_OVERFLOW)
			}
			if (rawRuns.any { it.observations.size > WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN }) {
				unverifiable(PortableCapturedWifiImportUnverifiableReason.OBSERVATION_OVERFLOW)
			}
			val totalObservations = rawRuns.fold(0L) { count, run ->
				Math.addExact(count, run.observations.size.toLong())
			}
			if (totalObservations > WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY) {
				unverifiable(PortableCapturedWifiImportUnverifiableReason.OBSERVATION_OVERFLOW)
			}
			val runs = rawRuns.map { run ->
				context.ensureActive()
				val zones = run.storedZoneIds.map(String::toString).toList()
				val observations = run.observations.map { observation ->
					require(listOf(observation.staleResultCount, observation.clockUnverifiableResultCount,
						observation.malformedResultCount).all { it >= 0 })
					require(observation.wallTimeUncertaintyMs <= observation.observedTimeMs)
					require(observation.coverageStartTimeMs <=
						observation.observedTimeMs - observation.wallTimeUncertaintyMs)
					require(observation.latestPossibleTimeMs == Math.addExact(
						observation.observedTimeMs,
						observation.wallTimeUncertaintyMs,
					))
					observation.copy()
				}.toList()
				require(observations.all { it.storedZoneId in zones })
				authenticateIncomingAggregateOwners(observations)
				run.copy(storedZoneIds = zones, observations = observations)
			}.toList()
			val identities = buildList {
				add(request.entry.identity.value)
				runs.forEach { run ->
					add(run.identity.value)
					addAll(run.observations.map { it.identity.value })
				}
			}
			val scopes = runs.map { it.deletionScopeDigest.value }
			require((identities + scopes).distinct().size == identities.size + scopes.size)
			request.copy(entry = request.entry.copy(runs = runs))
		}
	}

	private fun authenticateIncomingAggregateOwners(observations: List<PortableCapturedWifiObservationV1>) {
		val byIdentity = observations.associateBy { it.identity }
		observations.forEach { observation ->
			val ownerIdentity = observation.aggregateOwnerIdentity ?: return@forEach
			val owner = byIdentity[ownerIdentity] ?: throw IllegalArgumentException("Missing aggregate owner")
			require(owner.aggregateOwnerIdentity == null)
			require(owner.semanticRevision == observation.aggregateOwnerSemanticRevision)
			require(owner.storedZoneId == observation.storedZoneId)
			require(observation.hasExactImportedAggregate(owner))
		}
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableWifiImportAbort) {
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
	} catch (abort: PortableWifiImportAbort) {
		throw abort
	} catch (_: RuntimeException) {
		unverifiable(PortableCapturedWifiImportUnverifiableReason.ENTRY_INVALID)
	}

	private fun dependencyOverflow(): Nothing =
		unverifiable(PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW)

	private fun storedCorrupt(): Nothing =
		unverifiable(PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun blocked(reason: PortableCapturedWifiImportBlockedReason): Nothing =
		throw PortableWifiImportAbort(ImportPortableCapturedWifiResult.Blocked(reason))

	private fun unverifiable(reason: PortableCapturedWifiImportUnverifiableReason): Nothing =
		throw PortableWifiImportAbort(ImportPortableCapturedWifiResult.Unverifiable(reason))

	private class PortableWifiImportAbort(
		val result: ImportPortableCapturedWifiResult,
	) : RuntimeException(null, null, false, false)

	private companion object { const val IDENTITY_QUERY_CHUNK_SIZE = 256 }
}

internal enum class PortableWifiImportWriteCheckpoint {
	TRANSACTION_STARTED,
	ENTRY_INSERTED,
	RUN_INSERTED,
	ZONE_INSERTED,
	OBSERVATION_INSERTED,
	RECEIPT_INSERTED,
}

internal data class WifiImportLimits(
	val maximumEntries: Long = ImportedWifiDao.MAX_IMPORTED_ENTRIES.toLong(),
	val maximumGlobalAuthorityRows: Long = ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS.toLong(),
) {
	init {
		require(maximumEntries in 1L..ImportedWifiDao.MAX_IMPORTED_ENTRIES.toLong())
		require(maximumGlobalAuthorityRows in 1L..ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS.toLong())
	}
}

private data class NewWifiRows(
	val entries: Long = 0L,
	val receipts: Long = 0L,
	val runs: Long = 0L,
	val zones: Long = 0L,
	val observations: Long = 0L,
)

private fun SourceEvidenceState.hasValidWifiImportShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun PortableCapturedWifiEntryV1.crossesWifiRetentionFloor(floor: Long): Boolean =
	runs.asSequence().flatMap { it.observations.asSequence() }.any { it.coverageStartTimeMs < floor }

private fun PortableCapturedWifiObservationV1.hasExactImportedAggregate(
	owner: PortableCapturedWifiObservationV1,
): Boolean = observationCount == owner.observationCount &&
	twoPointFourGhzCount == owner.twoPointFourGhzCount && fiveGhzCount == owner.fiveGhzCount &&
	sixGhzCount == owner.sixGhzCount && otherBandCount == owner.otherBandCount &&
	strongestSignalDbm == owner.strongestSignalDbm && weakestSignalDbm == owner.weakestSignalDbm &&
	meanSignalDbm.toBits() == owner.meanSignalDbm.toBits()

private fun ImportPortableCapturedWifiRequest.toReceiptEntity(revision: Long) =
	ImportedWifiReceiptEntity(
		importJobId = receipt.jobId,
		importEntryKey = receipt.entryKey,
		importSourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		entryIdentity = entry.identity.value,
		entryImportRevision = revision,
		entryContentChecksum = entry.contentChecksum.value,
		collectedDataEpoch = expectedCollectedDataEpoch,
	)

private fun PortableCapturedWifiEntryV1.toEntity(
	request: ImportPortableCapturedWifiRequest,
	revision: Long,
) = ImportedWifiEntryRevisionEntity(
	identity = identity.value,
	importRevision = revision,
	supersedesImportRevision = revision.takeIf { it > 1L }?.minus(1L),
	contentChecksum = contentChecksum.value,
	sourceFormat = format,
	sourceSchemaVersion = schemaVersion,
	sessionMode = sessionMode.name,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	importJobId = request.receipt.jobId,
	importEntryKey = request.receipt.entryKey,
	importSourceName = request.receipt.sourceName,
	receivedAtMs = request.receipt.receivedAtMs,
)

private fun PortableCapturedWifiRunV1.toEntity(
	entry: String,
	revision: Long,
	epoch: Long,
) = ImportedWifiRunEntity(
	entryIdentity = entry,
	entryImportRevision = revision,
	identity = identity.value,
	deletionScopeDigest = deletionScopeDigest.value,
	contentChecksum = contentChecksum.value,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	captureCoverage = captureCoverage.name,
	availability = availability.name,
	acquisitionCompleteness = acquisitionCompleteness.name,
	hasUnresolvedProviderRange = hasUnresolvedProviderRange,
	retentionLoss = retentionLoss,
	collectedDataEpoch = epoch,
	scopeDeletionGeneration = 0L,
)

@Suppress("LongMethod")
private fun PortableCapturedWifiObservationV1.toEntity(
	entry: String,
	revision: Long,
	run: String,
) = ImportedWifiObservationEntity(
	entryIdentity = entry,
	entryImportRevision = revision,
	runIdentity = run,
	identity = identity.value,
	semanticRevision = semanticRevision,
	supersedesSemanticRevision = supersedesSemanticRevision,
	aggregateOwnerIdentity = aggregateOwnerIdentity?.value,
	aggregateOwnerSemanticRevision = aggregateOwnerSemanticRevision,
	contentChecksum = contentChecksum.value,
	coverageStartTimeMs = coverageStartTimeMs,
	observedTimeMs = observedTimeMs,
	latestPossibleTimeMs = latestPossibleTimeMs,
	wallTimeUncertaintyMs = wallTimeUncertaintyMs,
	storedZoneId = storedZoneId,
	availability = availability.name,
	resultCompleteness = resultCompleteness.name,
	submittedResultCount = submittedResultCount,
	acceptedResultCount = acceptedResultCount,
	staleResultCount = staleResultCount,
	clockUnverifiableResultCount = clockUnverifiableResultCount,
	malformedResultCount = malformedResultCount,
	observationCount = observationCount,
	twoPointFourGhzCount = twoPointFourGhzCount,
	fiveGhzCount = fiveGhzCount,
	sixGhzCount = sixGhzCount,
	otherBandCount = otherBandCount,
	strongestSignalDbm = strongestSignalDbm,
	weakestSignalDbm = weakestSignalDbm,
	meanSignalDbm = meanSignalDbm,
	sourceQualityFlags = sourceQualityFlags,
	sourceQualityConfidence = sourceQualityConfidence,
)

private fun ImportedWifiLineageAuthenticator.Reason.toImportReason() = when (this) {
	ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
		PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedWifiLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW ->
		PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedWifiLineageAuthenticator.Reason.RUN_OVERFLOW ->
		PortableCapturedWifiImportUnverifiableReason.RUN_OVERFLOW
	ImportedWifiLineageAuthenticator.Reason.ZONE_OVERFLOW ->
		PortableCapturedWifiImportUnverifiableReason.ZONE_OVERFLOW
	ImportedWifiLineageAuthenticator.Reason.OBSERVATION_OVERFLOW ->
		PortableCapturedWifiImportUnverifiableReason.OBSERVATION_OVERFLOW
	ImportedWifiLineageAuthenticator.Reason.REVISION_OVERFLOW ->
		PortableCapturedWifiImportUnverifiableReason.REVISION_OVERFLOW
}
