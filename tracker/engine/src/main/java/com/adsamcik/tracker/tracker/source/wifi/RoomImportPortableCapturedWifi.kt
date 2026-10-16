@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RadioSelectedFileMember
import com.adsamcik.tracker.shared.base.database.radioFileMembership
import com.adsamcik.tracker.shared.base.database.copyImportedRadioSelectionChildren
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiAuthorityOwner
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalObservationOwner
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalRunOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership
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
import com.adsamcik.tracker.stats.api.repository.mergeReadingSelection
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
			val snapshot = snapshotWifiImportInput(request)
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

	private suspend fun importInTransaction(
		input: ImportPortableCapturedWifiRequest,
	): ImportPortableCapturedWifiResult {
		var request = input
		writeCheckpoint(PortableWifiImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedWifiDao()
		val state = readWifiImportAdmissionState(request.expectedCollectedDataEpoch)
		var entry = request.entry
		if (state.retainedFromMs?.let(entry::crossesWifiRetentionFloor) == true) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.RETENTION_BOUNDARY)
		}
		authenticateGlobalCapacity(dao, NewWifiRows())
		authenticateIncomingEntryAuthority(dao, entry, state, input.mergeSelectedReadings)
		val lineage = authenticateStoredLineage(dao, entry.identity.value, state.collectedDataEpoch)
		val selectedMembership = if (input.mergeSelectedReadings) {
			val (saved, original) = readSelectedMembership(input, state)
			if (saved.isNotEmpty()) {
				return authenticateSelectedReceiptReplay(dao, input, lineage, saved, original)
			}
			lineage.revisions.lastOrNull()?.entry?.let { previous ->
				entry = try { previous.mergeReadingSelection(entry) } catch (_: IllegalArgumentException) {
					abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT)
				}
				request = input.copy(entry = entry)
			}
			original
		} else {
			null
		}
		authenticateRetainedEntryAuthority(dao, lineage, state)
		return admitWifiImportRevision(dao, request, lineage, state, selectedMembership)
	}

	private suspend fun readWifiImportAdmissionState(expectedEpoch: Long): SourceEvidenceState {
		val state = storedWifiImportValue { database.sourceEvidenceStateDao().get() }
			?: abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (!state.hasValidWifiImportShape()) {
			wifiImportStoredCorrupt()
		}
		if (state.collectedDataEpoch != expectedEpoch) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		return state
	}

	private suspend fun authenticateIncomingEntryAuthority(
		dao: ImportedWifiDao, entry: PortableCapturedWifiEntryV1, state: SourceEvidenceState,
		mergeSelectedReadings: Boolean,
	) {
		val entryDeletion = storedWifiImportValue { dao.entryDeletion(entry.identity.value) }
		if (entryDeletion != null) {
			if (entryDeletion.collectedDataEpoch != state.collectedDataEpoch) {
				wifiImportStoredCorrupt()
			}
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.DELETED_ENTRY)
		}
		val runIdentities = entry.runs.map { it.identity.value }
		val deletionScopes = entry.runs.map { it.deletionScopeDigest.value }
		authenticateDeletionAuthority(dao, runIdentities, deletionScopes, state)
		authenticateOpaqueIdentityOwnership(dao, entry, state)
		authenticateWifiImportLiveOpaqueOwnership(dao, entry, state, mergeSelectedReadings, limits)
	}

	private suspend fun readSelectedMembership(
		input: ImportPortableCapturedWifiRequest, state: SourceEvidenceState,
	): Pair<List<ImportedRadioFileMembership>, List<ImportedRadioFileMembership>> {
		val saved = database.importedRadioFileMembershipDao().members(SourceDestinationOwnerEntity.SOURCE_WIFI,
			input.receipt.jobId, input.receipt.entryKey, WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY +
				WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 2)
		val members = input.entry.runs.flatMap { run ->
			run.observations.map { RadioSelectedFileMember("OBSERVATION", it.identity.value, it.semanticRevision,
                    it.contentChecksum.value) } +
				listOfNotNull(RadioSelectedFileMember("GAP", run.identity.value, 1L, run.contentChecksum.value)
					.takeIf { run.retentionLoss || run.hasUnresolvedProviderRange })
		}
		val original = radioFileMembership(SourceDestinationOwnerEntity.SOURCE_WIFI, input.receipt.jobId,
			input.receipt.entryKey, input.entry.identity.value, input.entry.contentChecksum.value,
			saved.firstOrNull()?.entryImportRevision ?: 1L, state.collectedDataEpoch, members)
		return saved to original
	}

	private suspend fun authenticateSelectedReceiptReplay(
		dao: ImportedWifiDao, input: ImportPortableCapturedWifiRequest, lineage: AuthenticatedImportedWifiLineage,
		saved: List<ImportedRadioFileMembership>, original: List<ImportedRadioFileMembership>,
	): ImportPortableCapturedWifiResult {
		if (saved != original) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.RECEIPT_CONFLICT)
		}
		val receipt = dao.receipt(input.receipt.jobId, input.receipt.entryKey) ?: wifiImportStoredCorrupt()
		val revision =
                    lineage.revisions.singleOrNull { it.header.importRevision == receipt.entryImportRevision } ?:
                    wifiImportStoredCorrupt()
		if (receipt.entryImportRevision != saved.first().entryImportRevision ||
			receipt.entryContentChecksum != revision.entry.contentChecksum.value ||
			receipt.importSourceName != input.receipt.sourceName ||
                        receipt.receivedAtMs != input.receipt.receivedAtMs) {
			wifiImportStoredCorrupt()
		}
		return ImportPortableCapturedWifiResult.Duplicate(receipt.entryImportRevision)
	}

	private suspend fun authenticateRetainedEntryAuthority(
		dao: ImportedWifiDao, lineage: AuthenticatedImportedWifiLineage, state: SourceEvidenceState,
	) {
		if (state.retainedFromMs?.let { floor ->
				lineage.revisions.any { it.entry.crossesWifiRetentionFloor(floor) }
			} == true
		) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.RETENTION_BOUNDARY)
		}
		val retainedRuns = lineage.revisions.flatMap { it.entry.runs }
		authenticateDeletionAuthority(
			dao,
			retainedRuns.map { it.identity.value }.distinct(),
			retainedRuns.map { it.deletionScopeDigest.value }.distinct(),
			state,
		)
	}

	private suspend fun admitWifiImportRevision(
		dao: ImportedWifiDao, request: ImportPortableCapturedWifiRequest, lineage: AuthenticatedImportedWifiLineage,
		state: SourceEvidenceState, selectedMembership: List<ImportedRadioFileMembership>?,
	): ImportPortableCapturedWifiResult {
		val entry = request.entry
		val receiptBinding = storedWifiImportValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receiptBinding != null) {
			return authenticateReceiptReplay(request, receiptBinding, lineage)
		}
		if (lineage.receipts.size >= ImportedWifiDao.MAX_RECEIPTS_PER_ENTRY) {
			wifiImportDependencyOverflow()
		}

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			authenticateGlobalCapacity(dao, NewWifiRows(receipts = 1L))
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision))
			writeCheckpoint(PortableWifiImportWriteCheckpoint.RECEIPT_INSERTED)
			selectedMembership?.let { rows -> database.importedRadioFileMembershipDao().insert(
				rows.map { it.copy(entryImportRevision = identical.header.importRevision) }) }
			return ImportPortableCapturedWifiResult.Duplicate(identical.header.importRevision)
		}
		val (latest, revision) = nextWifiImportRevision(entry, lineage)
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
		return persistWifiImportRevision(dao, request, revision, latest, selectedMembership, state)
	}

	private fun nextWifiImportRevision(
		entry: PortableCapturedWifiEntryV1, lineage: AuthenticatedImportedWifiLineage,
	): Pair<AuthenticatedImportedWifiRevision?, Long> {
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			wifiImportStoredCorrupt()
		}
		if (lineage.revisions.size >= ImportedWifiDao.MAX_REVISIONS_PER_ENTRY) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val latest = lineage.revisions.lastOrNull()
		if (latest != null && !entry.isReciprocalCorrectionOf(latest.entry)) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT)
		}
		val revision = try {
			Math.addExact(latest?.header?.importRevision ?: 0L, 1L)
		} catch (_: ArithmeticException) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.REVISION_OVERFLOW)
		}
		return latest to revision
	}

	private suspend fun persistWifiImportRevision(
		dao: ImportedWifiDao, request: ImportPortableCapturedWifiRequest, revision: Long,
		latest: AuthenticatedImportedWifiRevision?, selectedMembership: List<ImportedRadioFileMembership>?,
		state: SourceEvidenceState,
	): ImportPortableCapturedWifiResult {
		val entry = request.entry
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
		if (selectedMembership != null) {
			latest?.let { previous -> copyImportedRadioSelectionChildren(database,
                SourceDestinationOwnerEntity.SOURCE_WIFI,
				entry.identity.value, previous.header.importRevision, revision) }
			database.importedRadioFileMembershipDao().insert(selectedMembership.map { it.copy(entryImportRevision =
                revision) })
		}
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
		) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.RECEIPT_CONFLICT)
		}
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: wifiImportStoredCorrupt()
		if (revision.entry != request.entry) {
			wifiImportStoredCorrupt()
		}
		return ImportPortableCapturedWifiResult.Duplicate(stored.entryImportRevision)
	}

	private suspend fun authenticateDeletionAuthority(
		dao: ImportedWifiDao,
		runIdentities: List<String>,
		deletionScopes: List<String>,
		state: SourceEvidenceState,
	) {
		if (runIdentities.isEmpty() && deletionScopes.isEmpty()) {
			return
		}
		val generations = storedWifiImportValue {
			dao.deletionGenerationsByRun(runIdentities) + dao.deletionGenerationsByScope(deletionScopes)
		}.distinctBy { it.runIdentity }
		if (generations.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
			wifiImportStoredCorrupt()
		}
		if (generations.any { it.runIdentity in runIdentities }) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.DELETED_RUN)
		}
		if (generations.any { it.deletionScopeDigest in deletionScopes }) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.DELETED_SCOPE)
		}
		val fences = storedWifiImportValue {
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = deletionScopes,
			)
		}
		if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
			wifiImportStoredCorrupt()
		}
		if (fences.isNotEmpty()) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.DELETED_SCOPE)
		}
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateOpaqueIdentityOwnership(
		dao: ImportedWifiDao,
		entry: PortableCapturedWifiEntryV1,
		state: SourceEvidenceState,
	) {
		val ownership = WifiPortableOpaqueOwnership(entry)
		ownership.allValues.chunked(WIFI_IMPORT_IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
			val limit = Math.addExact(Math.multiplyExact(values.size, 16), 1)
			val owners = storedWifiImportValue { dao.authorityOwners(values, limit) }
			if (owners.size >= limit) {
				wifiImportDependencyOverflow()
			}
			if (owners.any { owner ->
					owner.collectedDataEpoch != state.collectedDataEpoch
				}
			) {
				wifiImportStoredCorrupt()
			}
			if (owners.any { owner -> !owner.isCompatibleExistingOwner(ownership) }) {
				abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
	}

	private suspend fun authenticateGlobalCapacity(
		dao: ImportedWifiDao,
		added: NewWifiRows,
		isNewEntry: Boolean = false,
	) {
		val existingEntries = storedWifiImportValue { dao.distinctEntryCount() }
		if (existingEntries < 0L || existingEntries > limits.maximumEntries ||
			(isNewEntry && existingEntries == limits.maximumEntries)
		) {
			wifiImportDependencyOverflow()
		}
		listOf(
			storedWifiImportValue { dao.entryRevisionCount() } to added.entries,
			storedWifiImportValue { dao.receiptCount() } to added.receipts,
			storedWifiImportValue { dao.runCount() } to added.runs,
			storedWifiImportValue { dao.runZoneCount() } to added.zones,
			storedWifiImportValue { dao.observationCount() } to added.observations,
			storedWifiImportValue { dao.entryDeletionCount() } to 0L,
			storedWifiImportValue { dao.deletionGenerationCount() } to 0L,
			storedWifiImportValue { dao.selectedDeletionReceiptCount() } to 0L,
			storedWifiImportValue { dao.selectedDeletionProtectedIdentityCount() } to 0L,
		).fold(0L) { total, (existing, increment) ->
			limits.checkedWifiImportAuthorityCount(total, limits.checkedWifiImportAuthorityCount(existing, increment))
		}
	}

	private suspend fun authenticateStoredLineage(
		dao: ImportedWifiDao,
		identity: String,
		epoch: Long,
	): AuthenticatedImportedWifiLineage = storedWifiImportValue {
		try {
			ImportedWifiLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = epoch,
				retainedState = checkNotNull(database.sourceEvidenceStateDao().get()).also {
					check(it.collectedDataEpoch == epoch)
				},
				headers = dao.entryRevisionsForAdmission(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				zones = dao.allRunZonesForAdmission(identity),
				observations = dao.allObservationsForAdmission(identity),
			)
		} catch (failure: ImportedWifiLineageFailure) {
			abortWifiImportUnverifiable(failure.reason.toImportReason())
		}
	}

}

private suspend fun snapshotWifiImportInput(
	request: ImportPortableCapturedWifiRequest,
): ImportPortableCapturedWifiRequest {
	val context = currentCoroutineContext()
	return incomingWifiImportValue {
		context.ensureActive()
		val rawRuns = request.entry.runs
		if (rawRuns.size > WifiCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.RUN_OVERFLOW)
		}
		if (rawRuns.any { it.storedZoneIds.size > WifiCapturedPortableFormatV1.MAX_STORED_ZONES_PER_RUN }) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.ZONE_OVERFLOW)
		}
		if (rawRuns.any { it.observations.size > WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN }) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.OBSERVATION_OVERFLOW)
		}
		val totalObservations = rawRuns.fold(0L) { count, run ->
			Math.addExact(count, run.observations.size.toLong())
		}
		if (totalObservations > WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY) {
			abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.OBSERVATION_OVERFLOW)
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
			authenticateIncomingWifiAggregateOwners(observations)
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

private fun authenticateIncomingWifiAggregateOwners(observations: List<PortableCapturedWifiObservationV1>) {
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

private inline fun <T> incomingWifiImportValue(block: () -> T): T = try {
	block()
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (abort: PortableWifiImportAbort) {
	throw abort
} catch (_: RuntimeException) {
	abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.ENTRY_INVALID)
}

private const val WIFI_IMPORT_IDENTITY_QUERY_CHUNK_SIZE = 256

private suspend fun authenticateWifiImportLiveOpaqueOwnership(
	dao: ImportedWifiDao,
	entry: PortableCapturedWifiEntryV1,
	state: SourceEvidenceState,
	allowMatchingNativeOwners: Boolean,
	limits: WifiImportLimits,
) {
	val ownership = WifiPortableOpaqueOwnership(entry)
	val incoming = ownership.allValues
	val matching = ownership.takeIf { allowMatchingNativeOwners }
	authenticateWifiImportLiveFences(dao, incoming, state, limits)
	val entryCount = limits.authenticateWifiImportLocalCount(storedWifiImportValue { dao.localEntryOwnerCount() })
	val runCount = limits.authenticateWifiImportLocalCount(storedWifiImportValue { dao.localRunOwnerCount() })
	val observationCount = limits.authenticateWifiImportLocalCount(
		storedWifiImportValue { dao.localObservationOwnerCount() },
	)
	val deletionCount = limits.authenticateWifiImportLocalCount(storedWifiImportValue { dao.localDeletionOwnerCount() })
	listOf(entryCount, runCount, observationCount, deletionCount).fold(0L) { total, count ->
		limits.checkedWifiImportAuthorityCount(total, count)
	}
	val loadedEntries = pageWifiImportStrings(dao::localEntryOwnerPage, limits) { localIdentity ->
		val opaque = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, localIdentity).value
		if (opaque in incoming &&
                matching?.identityOwners?.get(opaque)?.kind != PortableWifiIdentityKind.LOGICAL_ENTRY)
			{
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}
	if (loadedEntries != entryCount) {
		wifiImportStoredCorrupt()
	}
	if (pageWifiImportRuns(dao::localRunOwnerPage, incoming, limits, matching) != runCount) {
		wifiImportStoredCorrupt()
	}
	if (pageWifiImportObservations(dao::localObservationOwnerPage, incoming, limits, matching) != observationCount) {
		wifiImportStoredCorrupt()
	}
	if (pageWifiImportRuns(dao::localDeletionOwnerPage, incoming, limits) != deletionCount) {
		wifiImportStoredCorrupt()
	}
}

private suspend fun authenticateWifiImportLiveFences(
	dao: ImportedWifiDao, incoming: Set<String>, state: SourceEvidenceState, limits: WifiImportLimits,
) {
	incoming.chunked(WIFI_IMPORT_IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
		val limit = Math.toIntExact(limits.maximumGlobalAuthorityRows + 1L)
		val fences = storedWifiImportValue {
			dao.deletionFenceIdentityOwners(values, limit)
		}
		if (fences.size >= limit) {
			wifiImportDependencyOverflow()
		}
		if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
			wifiImportStoredCorrupt()
		}
		if (fences.isNotEmpty()) {
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}
}

private suspend fun pageWifiImportStrings(
	loader: suspend (String?, Int) -> List<String>,
	limits: WifiImportLimits,
	visitor: (String) -> Unit,
): Long {
	var after: String? = null
	var loaded = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = storedWifiImportValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
		if (page.isEmpty()) {
			return loaded
		}
		if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
			page.zipWithNext().any { (left, right) -> left >= right } ||
			after?.let { page.first() <= it } == true
		) {
			wifiImportStoredCorrupt()
		}
		page.forEach(visitor)
		loaded = limits.checkedWifiImportAuthorityCount(loaded, page.size.toLong())
		after = page.last()
		if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) {
			return loaded
		}
	}
}

private suspend fun pageWifiImportRuns(
	loader: suspend (String?, Int) -> List<WifiLocalRunOwner>,
	incoming: Set<String>,
	limits: WifiImportLimits,
	matching: WifiPortableOpaqueOwnership? = null,
): Long {
	var after: String? = null
	var loaded = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = storedWifiImportValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
		if (page.isEmpty()) {
			return loaded
		}
		if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
			page.zipWithNext().any { (left, right) -> left.serviceRunId >= right.serviceRunId } ||
			after?.let { page.first().serviceRunId <= it } == true
		) {
			wifiImportStoredCorrupt()
		}
		page.forEach { owner -> owner.authenticateWifiImportRunOwner(incoming, matching) }
		loaded = limits.checkedWifiImportAuthorityCount(loaded, page.size.toLong())
		after = page.last().serviceRunId
		if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) {
			return loaded
		}
	}
}

private suspend fun pageWifiImportObservations(
	loader: suspend (String?, Int) -> List<WifiLocalObservationOwner>,
	incoming: Set<String>,
	limits: WifiImportLimits,
	matching: WifiPortableOpaqueOwnership? = null,
): Long {
	var after: String? = null
	var loaded = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = storedWifiImportValue { loader(after, ImportedWifiDao.OWNER_PAGE_SIZE) }
		if (page.isEmpty()) {
			return loaded
		}
		if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
			page.zipWithNext().any { (left, right) -> left.logicalFactId >= right.logicalFactId } ||
			after?.let { page.first().logicalFactId <= it } == true
		) {
			wifiImportStoredCorrupt()
		}
		page.forEach { owner -> owner.authenticateWifiImportObservationOwner(incoming, matching) }
		loaded = limits.checkedWifiImportAuthorityCount(loaded, page.size.toLong())
		after = page.last().logicalFactId
		if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) {
			return loaded
		}
	}
}

private suspend fun WifiLocalRunOwner.authenticateWifiImportRunOwner(
	incoming: Set<String>, matching: WifiPortableOpaqueOwnership?,
) {
	authenticateWifiImportNativeTuple(logicalTrackingId, serviceRunId, incoming, matching)
	val scope = storedWifiImportValue { SourceDeletionFenceEntity.logicalServiceRunIdentity(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		logicalTrackingId,
		serviceRunId,
	) }
	if (scope in incoming && matching == null) {
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

private suspend fun WifiLocalObservationOwner.authenticateWifiImportObservationOwner(
	incoming: Set<String>, matching: WifiPortableOpaqueOwnership?,
) {
	authenticateWifiImportNativeTuple(logicalTrackingId, serviceRunId, incoming, matching)
	val observation = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION,
                    logicalFactId).value
	if (observation in incoming) {
		val expected = matching?.identityOwners?.get(observation)
		if (expected?.kind != PortableWifiIdentityKind.OBSERVATION ||
			expected.entryIdentity != PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY,
                            logicalTrackingId).value ||
			expected.runIdentity != PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN,
                            serviceRunId).value)
			{
			abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}
	val scope = storedWifiImportValue { SourceDeletionFenceEntity.logicalServiceRunIdentity(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		logicalTrackingId,
		serviceRunId,
	) }
	if (scope in incoming && matching == null) {
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

/** An omitted sibling run is not a collision; a reused reading/run must keep its original owner. */
private fun authenticateWifiImportNativeTuple(logicalId: String, runId: String, incoming: Set<String>,
	matching: WifiPortableOpaqueOwnership?) {
	if (matching == null) {
		checkWifiImportLiveIdentity(PortableWifiIdentityKind.LOGICAL_ENTRY, logicalId, incoming)
		checkWifiImportLiveIdentity(PortableWifiIdentityKind.PHYSICAL_RUN, runId, incoming)
		return
	}
	val entry = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, logicalId).value
	val run = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN, runId).value
	val scope = SourceDeletionFenceEntity.logicalServiceRunIdentity(SourceDestinationOwnerEntity.SOURCE_WIFI,
		SessionManifestPurposeCode.SESSION_CAPTURE, logicalId, runId)
	if (entry in incoming && matching.identityOwners[entry]?.kind != PortableWifiIdentityKind.LOGICAL_ENTRY)
		{
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
	if (run in incoming && matching.identityOwners[run] !=
		WifiPortableOpaqueOwner(PortableWifiIdentityKind.PHYSICAL_RUN, entry, run, scope))
		{
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
	if (scope in incoming && matching.scopeOwners[scope] !=
		WifiPortableOpaqueOwner(PortableWifiIdentityKind.PHYSICAL_RUN, entry, run, scope))
		{
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

private fun checkWifiImportLiveIdentity(kind: PortableWifiIdentityKind, local: String, incoming: Set<String>) {
	val opaque = try {
		PortableWifiOpaqueIdentity.derive(kind, local).value
	} catch (_: IllegalArgumentException) {
		wifiImportStoredCorrupt()
	}
	if (opaque in incoming) {
		abortWifiImportBlocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

private fun WifiImportLimits.authenticateWifiImportLocalCount(count: Long): Long {
	if (count < 0L || count > maximumGlobalAuthorityRows) {
		wifiImportDependencyOverflow()
	}
	return count
}

private fun WifiImportLimits.checkedWifiImportAuthorityCount(current: Long, added: Long): Long = try {
	if (current < 0L || added < 0L) {
		wifiImportDependencyOverflow()
	}
	Math.addExact(current, added).also {
		if (it > maximumGlobalAuthorityRows) {
			wifiImportDependencyOverflow()
		}
	}
} catch (_: ArithmeticException) {
	wifiImportDependencyOverflow()
}

private suspend inline fun <T> storedWifiImportValue(crossinline block: suspend () -> T): T = try {
	block()
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (abort: PortableWifiImportAbort) {
	throw abort
} catch (storage: SQLiteException) {
	throw storage
} catch (_: IllegalArgumentException) {
	wifiImportStoredCorrupt()
} catch (_: ArithmeticException) {
	wifiImportStoredCorrupt()
} catch (_: DateTimeException) {
	wifiImportStoredCorrupt()
}

private fun wifiImportDependencyOverflow(): Nothing =
	abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW)

private fun wifiImportStoredCorrupt(): Nothing =
	abortWifiImportUnverifiable(PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

private fun abortWifiImportBlocked(reason: PortableCapturedWifiImportBlockedReason): Nothing =
	throw PortableWifiImportAbort(ImportPortableCapturedWifiResult.Blocked(reason))

private fun abortWifiImportUnverifiable(reason: PortableCapturedWifiImportUnverifiableReason): Nothing =
	throw PortableWifiImportAbort(ImportPortableCapturedWifiResult.Unverifiable(reason))

private class PortableWifiImportAbort(
	val result: ImportPortableCapturedWifiResult,
) : RuntimeException(null, null, false, false)

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

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun ImportedWifiAuthorityOwner.isCompatibleExistingOwner(
	ownership: WifiPortableOpaqueOwnership,
): Boolean = when (ownerKind) {
	ImportedWifiAuthorityOwner.ENTRY -> ownership.identityOwners[protectedIdentity]?.let { owner ->
		owner.kind == PortableWifiIdentityKind.LOGICAL_ENTRY &&
			owner.entryIdentity == entryIdentity
	} == true
	ImportedWifiAuthorityOwner.RUN -> ownership.identityOwners[protectedIdentity]?.let { owner ->
		owner.kind == PortableWifiIdentityKind.PHYSICAL_RUN &&
			owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
			owner.deletionScopeDigest == deletionScopeDigest
	} == true
	ImportedWifiAuthorityOwner.OBSERVATION ->
		ownership.identityOwners[protectedIdentity]?.let { owner ->
			owner.kind == PortableWifiIdentityKind.OBSERVATION &&
				owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity
		} == true
	ImportedWifiAuthorityOwner.DELETION_SCOPE ->
		ownership.scopeOwners[protectedIdentity]?.let { owner ->
			owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
				owner.deletionScopeDigest == deletionScopeDigest
		} == true
	else -> false
}
