package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationFileIdentityDao
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Current session interval or exact imported-file membership; never requests a provider. */
internal data class SessionLocationFileRequest(
	val sessionId: OrganizationSessionId,
	val expectedOrganizationRevision: Long,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedOrganizationRevision >= 0L && expectedCollectedDataEpoch >= 0L)
	}
}

/** All calls run on the exporter's IO dispatcher, inside its existing Room snapshot. */
internal class SessionLocationFileReader(private val database: AppDatabase) {
	internal data class Snapshot(val organization: OrganizationSessionRow, val collectedDataEpoch: Long,
		val retainedFromMs: Long?, val retentionStamp: String)

	suspend fun snapshot(request: SessionLocationFileRequest): Snapshot {
		currentCoroutineContext().ensureActive()
		val state = snapshotState(request)
		val row = snapshotOrganization(request)
		if (row.isActive || !row.isCompleted) {
			throw InvalidLocationFile("End the session before exporting it")
		}
		return Snapshot(row, state.collectedDataEpoch, state.retainedFromMs,
			database.locationPurposeDao().savedFileExportAuthorityStamp())
	}

	private suspend fun snapshotState(request: SessionLocationFileRequest): SourceEvidenceState {
		val state = database.sourceEvidenceStateDao().get()
			?: throw InvalidLocationFile("Saved Location state is unavailable")
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
			throw InvalidLocationFile("Saved data changed; select the session again")
		}
		return state
	}

	private suspend fun snapshotOrganization(request: SessionLocationFileRequest): OrganizationSessionRow {
		val row = database.organizationAnnotationDao().visible(null, null, 1, 0,
			(request.sessionId as? OrganizationSessionId.Annotation)?.localId,
			(request.sessionId as? OrganizationSessionId.Recording)?.adapterKey).singleOrNull()
			?: throw InvalidLocationFile("The selected session is unavailable")
		if (row.revision != request.expectedOrganizationRevision) {
			throw InvalidLocationFile("The selected session changed")
		}
		return row
	}

	suspend fun verify(request: SessionLocationFileRequest, expected: Snapshot) {
		if (snapshot(request) != expected) {
			throw InvalidLocationFile("Saved data changed during session export")
		}
	}

	suspend fun read(snapshot: Snapshot, verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		val row = snapshot.organization
		val from = row.fromMs ?: throw InvalidLocationFile("Session dates are unavailable")
		checkBounds(from, row.toMs)
		val imported = row.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
		if (imported != null) {
			if (row.adapterKey != null) {
				throw InvalidLocationFile("Imported session membership is invalid")
			}
			readArchives(imported.filterIsInstance<OrganizationSourceSelector.LocationFile>(), snapshot, verifyAccess, emit)
			return
		}
		readInterval(from, row.toMs, verifyAccess, emit)
	}

	private suspend fun readInterval(from: Long, to: Long?, verifyAccess: () -> Unit,
		emit: suspend (LocationPathSampleRow) -> Unit) {
		val end = to ?: throw InvalidLocationFile("Session dates are unavailable")
		checkBounds(from, end)
		// Ordinary sessions organize the same independently saved data as History. No source is
		// borrowed through another recording, and turning collection off never hides saved points.
		readPages(verifyAccess, emit) { time, id ->
			database.locationPurposeDao().savedFileChunk(from, if (end == from) {
				end
			} else {
				end - 1L
			}, time, id, PAGE_SIZE)
		}
	}

	private suspend fun readArchives(selection: List<OrganizationSourceSelector.LocationFile>, snapshot: Snapshot,
		verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		val identities = database.locationFileIdentityDao()
		val seen = hashSetOf<Long>()
		for (selected in selection) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val archive = identities.retainedArchive(selected.archiveIdentity)
			val counts = identities.archivePointCounts(archive.identity, snapshot.retainedFromMs)
			if (counts.membershipCount != archive.pointCount) {
				throw InvalidLocationFile("A selected Location file has unavailable points")
			}
			identities.readArchivePages(archive, snapshot, seen, verifyAccess, emit)
		}
	}

	private suspend fun LocationFileIdentityDao.retainedArchive(identity: String): LocationFileArchiveEntity {
		val archive = archive(identity) ?: throw InvalidLocationFile("A selected Location file is unavailable")
		if (database.sourceEvidenceStateDao().get()?.acceptsRetainedEpoch(archive.collectedDataEpoch) != true) {
			throw InvalidLocationFile("Selected Location data is from an older data set")
		}
		return archive
	}

	private suspend fun LocationFileIdentityDao.readArchivePages(
		archive: LocationFileArchiveEntity, snapshot: Snapshot, seen: MutableSet<Long>,
		verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit,
	) {
		var ordinal = 0
		while (ordinal < archive.pointCount) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val members = archivePointPage(archive.identity, ordinal - 1, PAGE_SIZE)
			if (members.isEmpty()) {
				throw InvalidLocationFile("Location file membership is incomplete")
			}
			ordinal = readArchivePage(members, ordinal, snapshot, seen, verifyAccess, emit)
		}
		if (ordinal != archive.pointCount) {
			throw InvalidLocationFile("Location file membership is incomplete")
		}
	}

	private suspend fun LocationFileIdentityDao.readArchivePage(
		members: List<LocationFileArchivePointEntity>, startOrdinal: Int, snapshot: Snapshot,
		seen: MutableSet<Long>, verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit,
	): Int {
		val samples = database.locationPurposeDao().savedFileSamples(members.mapNotNull { it.sampleId })
			.associateBy { it.sample.id }
		val bindings = byPortableIds(members.map { it.portableId }).associateBy { it.portableId }
		var ordinal = startOrdinal
		for (member in members) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val row = samples[member.sampleId]
			if (member.ordinal != ordinal++) {
				throw InvalidLocationFile("Location file membership is incomplete")
			}
			// Deleted or expired members still advance ordinal; coincident points never substitute.
			if (row == null) {
				continue
			}
			emitArchivePoint(row, bindings[member.portableId], snapshot, seen, emit)
		}
		return ordinal
	}

	private suspend fun emitArchivePoint(row: LocationPathSampleRow, binding: LocationFileIdentityEntity?,
		snapshot: Snapshot, seen: MutableSet<Long>, emit: suspend (LocationPathSampleRow) -> Unit) {
		if (binding?.sampleId != row.sample.id) {
			throw InvalidLocationFile("Location file membership is incomplete")
		}
		val from = requireNotNull(snapshot.organization.fromMs)
		val to = requireNotNull(snapshot.organization.toMs)
		if (row.sample.timeMs < from || if (to == from) {
			row.sample.timeMs != from
		} else {
			row.sample.timeMs >= to
		}) {
			return
		}
		if (row.sample.id !in seen) {
			if (seen.size >= LocationFileJsonV1.MAX_POINTS) {
				throw InvalidLocationFile("Too many Location points")
			}
			seen += row.sample.id
			emit(row)
		}
	}

	private suspend fun readPages(verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit,
		page: suspend (Long?, Long?) -> List<LocationPathSampleRow>) {
		var time: Long? = null
		var id: Long? = null
		while (true) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val rows = page(time, id)
			for (row in rows) { currentCoroutineContext().ensureActive(); verifyAccess(); emit(row) }
			if (rows.size < PAGE_SIZE) {
				break
			}
			time = rows.last().sample.timeMs; id = rows.last().sample.id
		}
	}

	private fun checkBounds(from: Long, to: Long?) {
		if (from < 0L || to == null || to < from) {
			throw InvalidLocationFile("Original session dates are unavailable")
		}
	}

	private companion object { const val PAGE_SIZE = LocationFileExporter.PAGE_SIZE }
}
