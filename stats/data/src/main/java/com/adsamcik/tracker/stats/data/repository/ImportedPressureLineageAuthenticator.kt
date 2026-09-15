package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_WINDOW_ORDER
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1

/**
 * One Pressure-local interpretation of retained imported revisions.
 *
 * Admission, product history, and re-export all use this pure bounded authenticator so a correction
 * cannot become readable under weaker rules than the writer used to extend it.
 */
internal object ImportedPressureLineageAuthenticator {
	@Suppress("LongMethod", "ComplexCondition")
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedPressureEntryRevisionEntity>,
		receipts: List<ImportedPressureReceiptEntity>,
		runs: List<ImportedPressureRunEntity>,
		windows: List<ImportedPressureWindowEntity>,
	): AuthenticatedImportedPressureLineage = storedValue {
		if (headers.size > ImportedPressureDao.MAX_REVISIONS_PER_ENTRY) {
			fail(ImportedPressureLineageFailureReason.REVISION_OVERFLOW)
		}
		if (receipts.size > ImportedPressureDao.MAX_RECEIPTS_PER_ENTRY) {
			fail(ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW)
		}
		if (headers.isEmpty()) {
			require(receipts.isEmpty() && runs.isEmpty() && windows.isEmpty())
			return@storedValue AuthenticatedImportedPressureLineage(emptyList(), emptyList())
		}

		val orderedHeaders = headers.sortedBy(ImportedPressureEntryRevisionEntity::importRevision)
		orderedHeaders.forEachIndexed { index, header ->
			val revision = index.toLong() + 1L
			require(header.identity == identity)
			require(header.importRevision == revision)
			require(header.supersedesImportRevision == if (revision == 1L) null else revision - 1L)
			require(header.collectedDataEpoch == expectedCollectedDataEpoch)
			require(header.sourceFormat == PressurePortableFormatV1.FORMAT)
			require(header.sourceSchemaVersion == PressurePortableFormatV1.SCHEMA_VERSION)
		}
		require(orderedHeaders.zipWithNext().all { (previous, next) ->
			next.receivedAtMs >= previous.receivedAtMs
		})

		val headersByRevision = orderedHeaders.associateBy { it.importRevision }
		require(headersByRevision.size == orderedHeaders.size)
		require(receipts.map { it.importJobId to it.importEntryKey }.distinct().size == receipts.size)
		receipts.forEach { receipt ->
			val header = requireNotNull(headersByRevision[receipt.entryImportRevision])
			require(receipt.entryIdentity == header.identity)
			require(receipt.entryContentChecksum == header.contentChecksum)
			require(receipt.collectedDataEpoch == header.collectedDataEpoch)
		}
		orderedHeaders.forEach { header ->
			require(receipts.any { receipt -> receipt.exactlyOwns(header) })
		}

		if (runs.size > orderedHeaders.size * PressurePortableFormatV1.MAX_RUNS_PER_ENTRY) {
			fail(ImportedPressureLineageFailureReason.RUN_OVERFLOW)
		}
		if (windows.size > orderedHeaders.size * PressurePortableFormatV1.MAX_TOTAL_WINDOWS) {
			fail(ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW)
		}
		val revisionNumbers = orderedHeaders.mapTo(hashSetOf()) { it.importRevision }
		require(runs.all { it.entryImportRevision in revisionNumbers })
		require(windows.all { it.entryImportRevision in revisionNumbers })
		authenticateIdentityOwnership(identity, runs, windows)

		val runsByRevision = runs.groupBy(ImportedPressureRunEntity::entryImportRevision)
		val windowsByRevision = windows.groupBy(ImportedPressureWindowEntity::entryImportRevision)
		AuthenticatedImportedPressureLineage(
			revisions = orderedHeaders.map { header ->
				AuthenticatedImportedPressureRevision(
					header = header,
					entry = authenticateRevision(
						header,
						runsByRevision[header.importRevision].orEmpty(),
						windowsByRevision[header.importRevision].orEmpty(),
					),
				)
			},
			receipts = receipts.sortedWith(
				compareBy(
					ImportedPressureReceiptEntity::entryImportRevision,
					ImportedPressureReceiptEntity::importJobId,
					ImportedPressureReceiptEntity::importEntryKey,
				),
			),
		).also(::authenticateCorrectionHierarchy)
	}

	/**
	 * Revisions may correct values or replace physical members, but an opaque identity may never
	 * change its structural owner, intrinsic interval, acquisition plan, or stored civil zone.
	 */
	private fun authenticateCorrectionHierarchy(lineage: AuthenticatedImportedPressureLineage) {
		val runs = linkedMapOf<String, ImportedPressureRunStructure>()
		val windows = linkedMapOf<String, ImportedPressureWindowStructure>()
		lineage.revisions.forEach { revision ->
			require(revision.header.startTimeMs == revision.entry.startTimeMs)
			require(revision.header.endTimeMs == revision.entry.endTimeMs)
			revision.entry.runs.forEach { run ->
				val structure = ImportedPressureRunStructure(
					entryIdentity = revision.entry.identity.value,
					startTimeMs = run.startTimeMs,
					endTimeMs = run.endTimeMs,
				)
				val previousRun = runs.putIfAbsent(run.identity.value, structure)
				if (previousRun != null) require(previousRun == structure)
				run.windows.forEach { window ->
					val windowStructure = ImportedPressureWindowStructure(
						entryIdentity = revision.entry.identity.value,
						runIdentity = run.identity.value,
						intervalStartTimeMs = window.intervalStartTimeMs,
						intervalEndTimeMs = window.intervalEndTimeMs,
						observedDurationNanos = window.observedDurationNanos,
						effectiveSamplePeriodMicros = window.effectiveSamplePeriodMicros,
						effectiveMaximumReportLatencyMicros =
							window.effectiveMaximumReportLatencyMicros,
						targetWindowDurationNanos = window.targetWindowDurationNanos,
						zoneId = window.zoneId,
					)
					val previousWindow = windows.putIfAbsent(window.identity.value, windowStructure)
					if (previousWindow != null) require(previousWindow == windowStructure)
				}
			}
		}
	}

	private fun ImportedPressureReceiptEntity.exactlyOwns(
		header: ImportedPressureEntryRevisionEntity,
	): Boolean = importJobId == header.importJobId &&
		importEntryKey == header.importEntryKey &&
		importSourceName == header.importSourceName &&
		receivedAtMs == header.receivedAtMs &&
		entryIdentity == header.identity &&
		entryImportRevision == header.importRevision &&
		entryContentChecksum == header.contentChecksum &&
		collectedDataEpoch == header.collectedDataEpoch

	private fun authenticateIdentityOwnership(
		entryIdentity: String,
		runs: List<ImportedPressureRunEntity>,
		windows: List<ImportedPressureWindowEntity>,
	) {
		val owners = mutableMapOf<String, ImportedPressureIdentityOwner>()
		fun bind(identity: String, owner: ImportedPressureIdentityOwner) {
			val previous = owners[identity]
			if (previous == null) owners[identity] = owner else require(previous == owner)
		}
		bind(
			entryIdentity,
			ImportedPressureIdentityOwner(PortablePressureIdentityKind.LOGICAL_ENTRY, entryIdentity),
		)
		runs.forEach { run ->
			require(run.entryIdentity == entryIdentity)
			bind(
				run.identity,
				ImportedPressureIdentityOwner(
					kind = PortablePressureIdentityKind.PHYSICAL_RUN,
					entryIdentity = entryIdentity,
				),
			)
		}
		windows.forEach { window ->
			require(window.entryIdentity == entryIdentity)
			bind(
				window.identity,
				ImportedPressureIdentityOwner(
					kind = PortablePressureIdentityKind.WINDOW,
					entryIdentity = entryIdentity,
					runIdentity = window.runIdentity,
				),
			)
		}
	}

	@Suppress("LongMethod")
	private fun authenticateRevision(
		entry: ImportedPressureEntryRevisionEntity,
		runs: List<ImportedPressureRunEntity>,
		windows: List<ImportedPressureWindowEntity>,
	): PortablePressureEntryV1 {
		if (runs.size > PressurePortableFormatV1.MAX_RUNS_PER_ENTRY) {
			fail(ImportedPressureLineageFailureReason.RUN_OVERFLOW)
		}
		if (windows.size > PressurePortableFormatV1.MAX_TOTAL_WINDOWS) {
			fail(ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW)
		}
		require(runs.isNotEmpty())
		val windowsByRun = windows.groupBy(ImportedPressureWindowEntity::runIdentity)
		require(windowsByRun.keys.all { runIdentity -> runs.any { it.identity == runIdentity } })
		val portableRuns = runs.map { run ->
			require(run.entryIdentity == entry.identity)
			require(run.entryImportRevision == entry.importRevision)
			require(run.collectedDataEpoch == entry.collectedDataEpoch)
			require(run.scopeDeletionGeneration == 0L)
			val runWindows = windowsByRun[run.identity].orEmpty()
			if (runWindows.size > PressurePortableFormatV1.MAX_WINDOWS_PER_RUN) {
				fail(ImportedPressureLineageFailureReason.WINDOW_OVERFLOW)
			}
			PortablePressureRunV1(
				identity = PortablePressureOpaqueIdentity(run.identity),
				startTimeMs = run.startTimeMs,
				endTimeMs = run.endTimeMs,
				capturedForWholeRun = run.capturedForWholeRun,
				availability = PortablePressureAvailability.valueOf(run.availability),
				coverage = PortablePressureCoverage.valueOf(run.coverage),
				retentionLoss = run.retentionLoss,
				windows = runWindows.map { window ->
					require(window.entryIdentity == entry.identity)
					require(window.entryImportRevision == entry.importRevision)
					require(window.runIdentity == run.identity)
					require(window.intervalStartTimeMs >= run.startTimeMs)
					require(window.intervalEndTimeMs <= run.endTimeMs)
					window.toPortablePressureWindow()
				}.sortedWith(PORTABLE_PRESSURE_WINDOW_ORDER),
			)
		}.sortedWith(PORTABLE_PRESSURE_RUN_ORDER)
		val identities = buildList {
			add(entry.identity)
			portableRuns.forEach { run ->
				add(run.identity.value)
				addAll(run.windows.map { it.identity.value })
			}
		}
		require(identities.distinct().size == identities.size)
		return PortablePressureEntryV1(
			identity = PortablePressureOpaqueIdentity(entry.identity),
			contentChecksum = PortablePressureDigest(entry.contentChecksum),
			startTimeMs = entry.startTimeMs,
			endTimeMs = entry.endTimeMs,
			runs = portableRuns,
		)
	}

	private inline fun <T> storedValue(block: () -> T): T = try {
		block()
	} catch (failure: ImportedPressureLineageFailure) {
		throw failure
	} catch (_: IllegalArgumentException) {
		fail(ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE)
	}

	private fun fail(reason: ImportedPressureLineageFailureReason): Nothing =
		throw ImportedPressureLineageFailure(reason)
}

internal data class AuthenticatedImportedPressureLineage(
	val revisions: List<AuthenticatedImportedPressureRevision>,
	val receipts: List<ImportedPressureReceiptEntity>,
) {
	val latest: AuthenticatedImportedPressureRevision?
		get() = revisions.lastOrNull()
}

internal data class AuthenticatedImportedPressureRevision(
	val header: ImportedPressureEntryRevisionEntity,
	val entry: PortablePressureEntryV1,
)

internal enum class ImportedPressureLineageFailureReason {
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	WINDOW_OVERFLOW,
	TOTAL_WINDOW_OVERFLOW,
	REVISION_OVERFLOW,
}

internal class ImportedPressureLineageFailure(
	val reason: ImportedPressureLineageFailureReason,
) : RuntimeException(null, null, false, false)

private data class ImportedPressureIdentityOwner(
	val kind: PortablePressureIdentityKind,
	val entryIdentity: String,
	val runIdentity: String? = null,
)

private data class ImportedPressureRunStructure(
	val entryIdentity: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
)

private data class ImportedPressureWindowStructure(
	val entryIdentity: String,
	val runIdentity: String,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val observedDurationNanos: Long,
	val effectiveSamplePeriodMicros: Int,
	val effectiveMaximumReportLatencyMicros: Int,
	val targetWindowDurationNanos: Long,
	val zoneId: String,
)

@Suppress("LongMethod")
internal fun ImportedPressureWindowEntity.toPortablePressureWindow() = PortablePressureWindowV1(
	identity = PortablePressureOpaqueIdentity(identity),
	contentChecksum = PortablePressureDigest(contentChecksum),
	intervalStartTimeMs = intervalStartTimeMs,
	intervalEndTimeMs = intervalEndTimeMs,
	wallTimeUncertaintyMs = wallTimeUncertaintyMs,
	observedDurationNanos = observedDurationNanos,
	sampleCount = sampleCount,
	expectedSampleCount = expectedSampleCount,
	meanHectopascals = meanHectopascals,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimumHectopascals,
	maximumHectopascals = maximumHectopascals,
	firstHectopascals = firstHectopascals,
	latestHectopascals = latestHectopascals,
	slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
	rSquared = rSquared,
	sensorAccuracy = PortablePressureSensorAccuracy.valueOf(sensorAccuracy),
	effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
	effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
	targetWindowDurationNanos = targetWindowDurationNanos,
	maximumInterSampleGapNanos = maximumInterSampleGapNanos,
	closure = PortablePressureWindowClosure.valueOf(closureKind),
	qualification = PortablePressureWindowQualification.valueOf(qualification),
	sourceQualityFlags = sourceQualityFlags,
	sourceQualityConfidence = sourceQualityConfidence,
	zoneId = storedZoneId,
)
