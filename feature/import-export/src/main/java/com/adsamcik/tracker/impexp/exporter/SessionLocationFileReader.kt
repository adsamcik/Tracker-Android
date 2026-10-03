package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact saved selection for the approved bundle backend; no date fallback or provider demand. */
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
		val state = database.sourceEvidenceStateDao().get()
			?: throw InvalidLocationFile("Saved Location state is unavailable")
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
			throw InvalidLocationFile("Saved data changed; select the session again")
		}
		val row = database.organizationAnnotationDao().visible(null, null, 1, 0,
			(request.sessionId as? OrganizationSessionId.Annotation)?.localId,
			(request.sessionId as? OrganizationSessionId.Recording)?.adapterKey).singleOrNull()
			?: throw InvalidLocationFile("The selected session is unavailable")
		if (row.revision != request.expectedOrganizationRevision) {
			throw InvalidLocationFile("The selected session changed")
		}
		if (row.isActive || !row.isCompleted) throw InvalidLocationFile("End the session before exporting it")
		return Snapshot(row, state.collectedDataEpoch, state.retainedFromMs,
			database.locationPurposeDao().savedFileExportAuthorityStamp())
	}

	suspend fun verify(request: SessionLocationFileRequest, expected: Snapshot) {
		if (snapshot(request) != expected) throw InvalidLocationFile("Saved data changed during session export")
	}

	suspend fun read(snapshot: Snapshot, verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		val row = snapshot.organization
		val adapterKey = row.adapterKey
		val imported = row.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
		when {
			imported != null -> {
				if (adapterKey != null) throw InvalidLocationFile("Imported session membership is invalid")
				readArchives(imported.filterIsInstance<OrganizationSourceSelector.LocationFile>(), snapshot,
					verifyAccess, emit)
			}
			adapterKey?.startsWith("logical:") == true -> readLogical(adapterKey.removePrefix("logical:"), verifyAccess, emit)
			adapterKey?.startsWith("segment:") == true -> {
				val id = adapterKey.removePrefix("segment:").toLongOrNull()
					?: throw InvalidLocationFile("Saved session membership is invalid")
				val segment = database.sessionSegmentDao().getById(id)
					?: throw InvalidLocationFile("The original recording is unavailable")
				if (segment.logicalTrackingId != null || segment.serviceRunId != null ||
					segment.source == SegmentSource.PORTABLE_STEPS_IMPORT) {
					throw InvalidLocationFile("This recording has no legacy Location membership")
				}
				readLegacy(segment.startTimeMs, segment.endTimeMs, verifyAccess, emit)
			}
			adapterKey == null -> {
				val from = row.fromMs ?: throw InvalidLocationFile("Session dates are unavailable")
				val to = row.toMs ?: throw InvalidLocationFile("Session dates are unavailable")
				checkBounds(from, to)
				if (to == from) throw InvalidLocationFile("Session dates are empty")
				// A retrospective annotation intentionally groups actual saved points in [from, to).
				readPages(verifyAccess, emit) { time, id ->
					database.locationPurposeDao().savedFileChunk(from, to - 1L, time, id, PAGE_SIZE)
				}
			}
			else -> throw InvalidLocationFile("Unsupported saved session membership")
		}
	}

	private suspend fun readLogical(logicalId: String, verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		val session = database.sourceSessionDao().session(logicalId)
			?: throw InvalidLocationFile("The original recording is unavailable")
		checkBounds(session.startedAtMs, session.cutoffAtMs)
		if (session.state !in setOf("FINALIZED", "CLOSED") || session.completedAtMs == null) {
			throw InvalidLocationFile("The original recording is not complete")
		}
		val history = database.trackingHistoryReadDao()
		var runCount = 0L
		var afterRunStart: Long? = null
		var afterRunId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val runs = history.logicalEntryServiceRunPage(listOf(logicalId), PAGE_SIZE,
				afterRunId?.let { logicalId }, afterRunStart, afterRunId)
			for (run in runs) {
				val completedAt = run.completedAtMs
				if (run.logicalTrackingId != logicalId || completedAt == null ||
					completedAt < run.startedAtMs ||
					(run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED &&
						run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE)) {
					throw InvalidLocationFile("Original recording membership is incomplete")
				}
				runCount++
			}
			if (runs.size < PAGE_SIZE) break
			afterRunStart = runs.last().startedAtMs; afterRunId = runs.last().serviceRunId
		}
		var segmentCount = 0L
		var afterStart: Long? = null
		var afterId: Long? = null
		var precedingLegacyEnd: Long? = null
		while (true) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val segments = history.rawLogicalEntrySegmentPage(logicalId, emptyList(), PAGE_SIZE, afterStart, afterId)
			val runs = history.serviceRuns(segments.mapNotNull { it.serviceRunId }).associateBy { it.serviceRunId }
			for (segment in segments) {
				currentCoroutineContext().ensureActive(); verifyAccess()
				val run = runs[segment.serviceRunId]
				if (segment.logicalTrackingId != logicalId || run == null || run.logicalTrackingId != logicalId ||
					(run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE &&
						run.sessionSegmentId != segment.id)) {
					throw InvalidLocationFile("Original recording membership is incomplete")
				}
				segmentCount++
				checkBounds(segment.startTimeMs, segment.endTimeMs)
				if (database.sourceDeletionFenceDao().contains(LOCATION, SourceBrokerPurpose.SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					SourceDeletionFenceEntity.logicalServiceRunIdentity(LOCATION, SourceBrokerPurpose.SESSION_CAPTURE,
						logicalId, run.serviceRunId))) continue
				if (run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE) {
					if (precedingLegacyEnd?.let { it >= segment.startTimeMs } == true) {
						throw InvalidLocationFile("Original legacy recording intervals overlap")
					}
					precedingLegacyEnd = segment.endTimeMs
					readLegacy(segment.startTimeMs, segment.endTimeMs, verifyAccess, emit)
				} else readPages(verifyAccess, emit) { time, id ->
					database.locationPurposeDao().savedLogicalTripPointPage(logicalId, listOf(run.serviceRunId), time, id, PAGE_SIZE)
				}
			}
			if (segments.size < PAGE_SIZE) break
			afterStart = segments.last().startTimeMs; afterId = segments.last().id
		}
		// service_run_id is unique on segments. Both complete paged sides must agree, including empty runs.
		if (runCount == 0L || runCount != segmentCount) throw InvalidLocationFile("Original recording membership is incomplete")
	}

	private suspend fun readLegacy(from: Long, to: Long, verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		checkBounds(from, to)
		readPages(verifyAccess, { row ->
			// Importing a later overlapping file must not lend its new points to an older recording.
			if (database.locationFileIdentityDao().bySampleId(row.sample.id)?.imported != true) emit(row)
		}) { time, id -> database.locationPurposeDao().savedLegacyTripPointPage(from, to, time, id, PAGE_SIZE) }
	}

	private suspend fun readArchives(selection: List<OrganizationSourceSelector.LocationFile>, snapshot: Snapshot,
		verifyAccess: () -> Unit, emit: suspend (LocationPathSampleRow) -> Unit) {
		val identities = database.locationFileIdentityDao()
		val seen = hashSetOf<Long>()
		for (selected in selection) {
			currentCoroutineContext().ensureActive(); verifyAccess()
			val archive = identities.archive(selected.archiveIdentity)
				?: throw InvalidLocationFile("A selected Location file is unavailable")
			if (archive.collectedDataEpoch != snapshot.collectedDataEpoch) throw InvalidLocationFile("Selected Location data is from an older data set")
			val counts = identities.archivePointCounts(archive.identity, snapshot.retainedFromMs)
			if (counts.membershipCount != archive.pointCount || counts.availableCount != archive.pointCount) {
				throw InvalidLocationFile("A selected Location file has unavailable points")
			}
			var ordinal = 0
			while (ordinal < archive.pointCount) {
				currentCoroutineContext().ensureActive(); verifyAccess()
				val members = identities.archivePointPage(archive.identity, ordinal - 1, PAGE_SIZE)
				if (members.isEmpty()) throw InvalidLocationFile("Location file membership is incomplete")
				val samples = database.locationPurposeDao().savedFileSamples(members.mapNotNull { it.sampleId })
					.associateBy { it.sample.id }
				val bindings = identities.byPortableIds(members.map { it.portableId }).associateBy { it.portableId }
				for (member in members) {
					currentCoroutineContext().ensureActive(); verifyAccess()
					val row = samples[member.sampleId]
					if (member.ordinal != ordinal++ || row == null || bindings[member.portableId]?.sampleId != row.sample.id) {
						throw InvalidLocationFile("Location file membership is incomplete")
					}
					if (row.sample.id !in seen) {
						if (seen.size >= LocationFileJsonV1.MAX_POINTS) throw InvalidLocationFile("Too many Location points")
						seen += row.sample.id
						emit(row)
					}
				}
			}
			if (ordinal != archive.pointCount) throw InvalidLocationFile("Location file membership is incomplete")
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
			if (rows.size < PAGE_SIZE) break
			time = rows.last().sample.timeMs; id = rows.last().sample.id
		}
	}

	private fun checkBounds(from: Long, to: Long?) {
		if (from < 0L || to == null || to < from) throw InvalidLocationFile("Original session dates are unavailable")
	}

	private companion object { const val PAGE_SIZE = LocationFileExporter.PAGE_SIZE; const val LOCATION = 1 }
}
