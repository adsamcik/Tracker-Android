package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity

/** Shared fail-closed reconstruction used before any imported Activity lineage is extended. */
internal object ImportedActivityLineageAuthenticator {
	@Suppress("LongMethod", "ComplexCondition")
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedActivityEntryRevisionEntity>,
		receipts: List<ImportedActivityReceiptEntity>,
		runs: List<ImportedActivityRunEntity>,
		zoneEpochs: List<ImportedActivityZoneEpochEntity>,
		windows: List<ImportedActivityWindowEntity>,
		fragments: List<ImportedActivityFragmentEntity>,
	): AuthenticatedImportedActivityLineage = storedValue {
		if (headers.size > ImportedActivityDao.MAX_REVISIONS_PER_ENTRY) fail(Reason.REVISION_OVERFLOW)
		if (receipts.size > ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY) fail(Reason.DEPENDENCY_OVERFLOW)
		if (runs.size > ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE) fail(Reason.RUN_OVERFLOW)
		if (zoneEpochs.size > ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE) {
			fail(Reason.ZONE_EPOCH_OVERFLOW)
		}
		if (windows.size > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE) fail(Reason.WINDOW_OVERFLOW)
		if (fragments.size > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE) {
			fail(Reason.FRAGMENT_OVERFLOW)
		}
		if (headers.isEmpty()) {
			require(
				receipts.isEmpty() && runs.isEmpty() && zoneEpochs.isEmpty() && windows.isEmpty() &&
					fragments.isEmpty(),
			)
			return@storedValue AuthenticatedImportedActivityLineage(emptyList(), emptyList())
		}

		val orderedHeaders = headers.sortedBy(ImportedActivityEntryRevisionEntity::importRevision)
		orderedHeaders.forEachIndexed { index, header ->
			val revision = index.toLong() + 1L
			require(header.identity == identity)
			require(header.importRevision == revision)
			require(header.supersedesImportRevision == if (revision == 1L) null else revision - 1L)
			require(header.collectedDataEpoch == expectedCollectedDataEpoch)
			require(header.sourceFormat == ActivityCapturedPortableFormatV1.FORMAT)
			require(header.sourceSchemaVersion == ActivityCapturedPortableFormatV1.SCHEMA_VERSION)
		}
		val headersByRevision = orderedHeaders.associateBy { it.importRevision }
		require(headersByRevision.size == orderedHeaders.size)
		require(orderedHeaders.map { it.sessionMode }.distinct().size == 1)
		require(orderedHeaders.map { it.contentChecksum }.distinct().size == orderedHeaders.size)
		require(receipts.map { it.importJobId to it.importEntryKey }.distinct().size == receipts.size)
		receipts.forEach { receipt ->
			val header = requireNotNull(headersByRevision[receipt.entryImportRevision])
			require(receipt.entryIdentity == header.identity)
			require(receipt.entryContentChecksum == header.contentChecksum)
			require(receipt.collectedDataEpoch == header.collectedDataEpoch)
		}
		orderedHeaders.forEach { header -> require(receipts.any { it.exactlyOwns(header) }) }

		val revisionNumbers = headersByRevision.keys
		require(runs.all { it.entryIdentity == identity && it.entryImportRevision in revisionNumbers })
		require(zoneEpochs.all {
			it.entryIdentity == identity && it.entryImportRevision in revisionNumbers
		})
		require(windows.all { it.entryIdentity == identity && it.entryImportRevision in revisionNumbers })
		require(fragments.all {
			it.entryIdentity == identity && it.entryImportRevision in revisionNumbers
		})
		authenticateIdentityOwnership(identity, runs, windows)

		val runsByRevision = runs.groupBy(ImportedActivityRunEntity::entryImportRevision)
		val zonesByRevision = zoneEpochs.groupBy(ImportedActivityZoneEpochEntity::entryImportRevision)
		val windowsByRevision = windows.groupBy(ImportedActivityWindowEntity::entryImportRevision)
		val fragmentsByRevision = fragments.groupBy(ImportedActivityFragmentEntity::entryImportRevision)
		val authenticatedRevisions = orderedHeaders.map { header ->
			AuthenticatedImportedActivityRevision(
				header,
				authenticateRevision(
					header,
					runsByRevision[header.importRevision].orEmpty(),
					zonesByRevision[header.importRevision].orEmpty(),
					windowsByRevision[header.importRevision].orEmpty(),
					fragmentsByRevision[header.importRevision].orEmpty(),
				),
			)
		}
		val firstStructure = authenticatedRevisions.first().entry
		require(authenticatedRevisions.drop(1).all { revision ->
			revision.entry.hasSameImportedActivityStructureAs(firstStructure)
		})
		AuthenticatedImportedActivityLineage(
			revisions = authenticatedRevisions,
			receipts = receipts.sortedWith(
				compareBy(
					ImportedActivityReceiptEntity::entryImportRevision,
					ImportedActivityReceiptEntity::importJobId,
					ImportedActivityReceiptEntity::importEntryKey,
				),
			),
		)
	}

	private fun ImportedActivityReceiptEntity.exactlyOwns(
		header: ImportedActivityEntryRevisionEntity,
	): Boolean = importJobId == header.importJobId && importEntryKey == header.importEntryKey &&
		importSourceName == header.importSourceName && receivedAtMs == header.receivedAtMs &&
		entryIdentity == header.identity && entryImportRevision == header.importRevision &&
		entryContentChecksum == header.contentChecksum &&
		collectedDataEpoch == header.collectedDataEpoch

	private fun authenticateIdentityOwnership(
		entryIdentity: String,
		runs: List<ImportedActivityRunEntity>,
		windows: List<ImportedActivityWindowEntity>,
	) {
		val owners = mutableMapOf<String, ImportedActivityIdentityOwner>()
		val deletionScopeOwners = mutableMapOf<String, String>()
		fun bind(identity: String, owner: ImportedActivityIdentityOwner) {
			val previous = owners[identity]
			if (previous == null) owners[identity] = owner else require(previous == owner)
		}
		bind(entryIdentity, ImportedActivityIdentityOwner(PortableActivityIdentityKind.LOGICAL_ENTRY))
		runs.forEach { run ->
			val previousScopeOwner = deletionScopeOwners[run.deletionScopeDigest]
			if (previousScopeOwner == null) {
				deletionScopeOwners[run.deletionScopeDigest] = run.identity
			} else {
				require(previousScopeOwner == run.identity)
			}
			bind(
				run.identity,
				ImportedActivityIdentityOwner(
					PortableActivityIdentityKind.PHYSICAL_RUN,
					entryIdentity,
					deletionScopeDigest = run.deletionScopeDigest,
				),
			)
		}
		windows.forEach { window ->
			bind(
				window.identity,
				ImportedActivityIdentityOwner(
					PortableActivityIdentityKind.CAPTURE_WINDOW,
					entryIdentity,
					window.runIdentity,
				),
			)
		}
		require(deletionScopeOwners.keys.none { it in owners })
	}

	@Suppress("LongMethod")
	private fun authenticateRevision(
		header: ImportedActivityEntryRevisionEntity,
		runs: List<ImportedActivityRunEntity>,
		zoneEpochs: List<ImportedActivityZoneEpochEntity>,
		windows: List<ImportedActivityWindowEntity>,
		fragments: List<ImportedActivityFragmentEntity>,
	): PortableActivityEntryV1 {
		if (runs.size > ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) fail(Reason.RUN_OVERFLOW)
		if (windows.size > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_ENTRY) fail(Reason.WINDOW_OVERFLOW)
		if (fragments.size > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_ENTRY) {
			fail(Reason.FRAGMENT_OVERFLOW)
		}
		require(runs.isNotEmpty())
		val runIds = runs.mapTo(hashSetOf()) { it.identity }
		require(zoneEpochs.all { it.runIdentity in runIds })
		require(windows.all { it.runIdentity in runIds })
		val zonesByRun = zoneEpochs.groupBy(ImportedActivityZoneEpochEntity::runIdentity)
		val windowsByOwner = windows.groupBy { it.runIdentity }
		val windowOwners = windows.associate { (it.runIdentity to it.identity) to it }
		require(fragments.all { (it.runIdentity to it.windowIdentity) in windowOwners })
		val fragmentsByWindow = fragments.groupBy { it.runIdentity to it.windowIdentity }

		val portableRuns = runs.map { run ->
			require(run.entryIdentity == header.identity)
			require(run.entryImportRevision == header.importRevision)
			require(run.collectedDataEpoch == header.collectedDataEpoch)
			require(run.scopeDeletionGeneration == 0L)
			val runZones = zonesByRun[run.identity].orEmpty()
			if (runZones.size > ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN) {
				fail(Reason.ZONE_EPOCH_OVERFLOW)
			}
			require(runZones.map { it.ordinal } == runZones.indices.toList())
			val portableZones = runZones.map {
				PortableActivityZoneEpochV1(it.effectiveWallTimeMs, it.zoneId)
			}

			val runWindows = windowsByOwner[run.identity].orEmpty()
			if (runWindows.size > ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN) {
				fail(Reason.WINDOW_OVERFLOW)
			}
			val portableWindows = runWindows.map { window ->
				require(window.entryIdentity == header.identity)
				require(window.entryImportRevision == header.importRevision)
				val windowFragments = fragmentsByWindow[run.identity to window.identity].orEmpty()
				if (windowFragments.size > ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW) {
					fail(Reason.FRAGMENT_OVERFLOW)
				}
				require(windowFragments.map { it.ordinal } == windowFragments.indices.toList())
				PortableActivityWindowV1(
					identity = PortableActivityOpaqueIdentity(window.identity),
					contentChecksum = PortableActivityDigest(window.contentChecksum),
					startOffsetNanos = window.startOffsetNanos,
					endOffsetNanos = window.endOffsetNanos,
					storedZoneId = window.storedZoneId,
					coverage = PortableActivityWindowCoverage.valueOf(window.coverage),
					knownActiveDurationNanos = window.knownActiveDurationNanos,
					knownInactiveDurationNanos = window.knownInactiveDurationNanos,
					unknownActivityDurationNanos = window.unknownActivityDurationNanos,
					unobservedDurationNanos = window.unobservedDurationNanos,
					fragments = windowFragments.map(ImportedActivityFragmentEntity::toPortable),
				)
			}.sortedWith(PORTABLE_ACTIVITY_WINDOW_ORDER)
			PortableActivityRunV1(
				identity = PortableActivityOpaqueIdentity(run.identity),
				deletionScopeDigest = PortableActivityDeletionScopeDigest(run.deletionScopeDigest),
				contentChecksum = PortableActivityDigest(run.contentChecksum),
				startTimeMs = run.startTimeMs,
				endTimeMs = run.endTimeMs,
				captureCoverage = PortableActivityCaptureCoverage.valueOf(run.captureCoverage),
				zoneEpochs = portableZones,
				windows = portableWindows,
			)
		}.sortedWith(PORTABLE_ACTIVITY_RUN_ORDER)
		return PortableActivityEntryV1(
			identity = PortableActivityOpaqueIdentity(header.identity),
			contentChecksum = PortableActivityDigest(header.contentChecksum),
			sessionMode = PortableActivitySessionMode.valueOf(header.sessionMode),
			startTimeMs = header.startTimeMs,
			endTimeMs = header.endTimeMs,
			runs = portableRuns,
		)
	}

	private inline fun <T> storedValue(block: () -> T): T = try {
		block()
	} catch (failure: ImportedActivityLineageFailure) {
		throw failure
	} catch (_: RuntimeException) {
		fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
	}

	private fun fail(reason: Reason): Nothing = throw ImportedActivityLineageFailure(reason)

	internal enum class Reason {
		STORED_EVIDENCE_UNVERIFIABLE,
		DEPENDENCY_OVERFLOW,
		RUN_OVERFLOW,
		WINDOW_OVERFLOW,
		FRAGMENT_OVERFLOW,
		ZONE_EPOCH_OVERFLOW,
		REVISION_OVERFLOW,
	}
}

internal data class AuthenticatedImportedActivityLineage(
	val revisions: List<AuthenticatedImportedActivityRevision>,
	val receipts: List<ImportedActivityReceiptEntity>,
)

internal data class AuthenticatedImportedActivityRevision(
	val header: ImportedActivityEntryRevisionEntity,
	val entry: PortableActivityEntryV1,
)

internal class ImportedActivityLineageFailure(
	val reason: ImportedActivityLineageAuthenticator.Reason,
) : RuntimeException(null, null, false, false)

private data class ImportedActivityIdentityOwner(
	val kind: PortableActivityIdentityKind,
	val entryIdentity: String? = null,
	val runIdentity: String? = null,
	val deletionScopeDigest: String? = null,
)

private fun ImportedActivityFragmentEntity.toPortable(): PortableActivityFragmentV1 =
	if (fragmentKind == ImportedActivityFragmentEntity.KIND_GAP) {
		PortableActivityFragmentV1.Gap(startOffsetNanos, endOffsetNanos, requireNotNull(gapReason))
	} else {
		PortableActivityFragmentV1.Band(
			startOffsetNanos = startOffsetNanos,
			endOffsetNanos = endOffsetNanos,
			activity = requireNotNull(activity),
			mechanism = requireNotNull(mechanism),
			refinedTransitionActivity = refinedTransitionActivity,
			confidenceKind = requireNotNull(confidenceKind),
			confidenceMinimumPercent = confidenceMinimumPercent,
			confidenceMaximumPercent = confidenceMaximumPercent,
			confidenceObservationCount = confidenceObservationCount,
			startWallTimeMs = requireNotNull(startWallTimeMs),
			startWallTimeUncertaintyMs = requireNotNull(startWallTimeUncertaintyMs),
			startBoundaryKind = requireNotNull(startBoundaryKind),
			endWallTimeMs = requireNotNull(endWallTimeMs),
			endWallTimeUncertaintyMs = requireNotNull(endWallTimeUncertaintyMs),
			endBoundaryKind = requireNotNull(endBoundaryKind),
			wallTimeContinuity = requireNotNull(wallTimeContinuity),
		)
	}

private val PORTABLE_ACTIVITY_WINDOW_ORDER = compareBy<PortableActivityWindowV1>(
	PortableActivityWindowV1::startOffsetNanos,
).thenBy(PortableActivityWindowV1::endOffsetNanos).thenBy { it.identity.value }

private val PORTABLE_ACTIVITY_RUN_ORDER = compareBy<PortableActivityRunV1>(
	PortableActivityRunV1::startTimeMs,
).thenBy(PortableActivityRunV1::endTimeMs).thenBy { it.identity.value }

internal fun PortableActivityEntryV1.hasSameImportedActivityStructureAs(
	other: PortableActivityEntryV1,
): Boolean = identity == other.identity && sessionMode == other.sessionMode &&
	startTimeMs == other.startTimeMs && endTimeMs == other.endTimeMs &&
	runs.size == other.runs.size && runs.zip(other.runs).all { (leftRun, rightRun) ->
		leftRun.identity == rightRun.identity &&
			leftRun.deletionScopeDigest == rightRun.deletionScopeDigest &&
			leftRun.startTimeMs == rightRun.startTimeMs && leftRun.endTimeMs == rightRun.endTimeMs &&
			leftRun.captureCoverage == rightRun.captureCoverage &&
			leftRun.zoneEpochs == rightRun.zoneEpochs &&
			leftRun.windows.size == rightRun.windows.size &&
			leftRun.windows.zip(rightRun.windows).all { (leftWindow, rightWindow) ->
				leftWindow.identity == rightWindow.identity &&
					leftWindow.startOffsetNanos == rightWindow.startOffsetNanos &&
					leftWindow.endOffsetNanos == rightWindow.endOffsetNanos &&
					leftWindow.storedZoneId == rightWindow.storedZoneId
			}
	}
