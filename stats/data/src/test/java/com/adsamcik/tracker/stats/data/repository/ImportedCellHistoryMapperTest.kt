package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableCellDigest
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellRunAvailability
import com.adsamcik.tracker.shared.base.database.PortableCellSessionMode
import com.adsamcik.tracker.shared.base.database.PortableCellSubscriptionGrouping
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedCellHistoryMapperTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported-only repository exposes opaque origin and exact identity-free quality truth`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val value = cellEntry(
			logicalLocal = "imported-only",
			runLocal = "run",
			observation = cellObservation(
				localId = "observation",
				childCompleteness = PortableCellChildCompleteness.PARTIAL,
			),
			acquisition = PortableCellAcquisitionCompleteness.PARTIAL,
		)
		RoomImportPortableCapturedCell(database, Dispatchers.Unconfined).importEntry(
			importRequest(value),
		) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val repository = repository()

		val page = repository.recent(1) as CellHistoryPage.Available
		val public = page.entries.single()
		val origin = public.origin as CellHistoryOrigin.Imported
		origin.selection.identity.value shouldBe value.identity.value
		origin.selection.importRevision shouldBe 1L
		origin.selection.contentChecksum.value shouldBe value.contentChecksum.value
		public.state shouldBe CellHistoryProductState.PARTIAL
		public.causes shouldBe setOf(
			CellHistoryCause.ACQUISITION_INCOMPLETE,
			CellHistoryCause.CHILDREN_PARTIAL,
			CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN,
		)
		public.observations.single().let { observation ->
			observation.technologyMix.shouldContainExactly(
				mapOf(CellHistoryTechnology.LTE to 1, CellHistoryTechnology.NR to 1),
			)
			observation.signalQuality.unknownCount shouldBe 1
			observation.signalQuality.noneOrUnknownCount shouldBe 1
			observation.signalQuality.knownCount shouldBe 1
			observation.weakObservationCount shouldBe 1
			observation.allKnownQualityIsWeak shouldBe true
			observation.submittedChildCount shouldBe 3
			observation.acceptedChildCount shouldBe 2
			observation.rejectedChildCount shouldBe 1
		}
		repository.imported(origin.selection) shouldBe CellHistoryQuery.Found(public)
		repository.detail(requireNotNull(public.selection)) shouldBe CellHistoryQuery.Found(public)
		listOf(
			"source_event_wal",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
			"logical_tracking_session",
			"session_manifest_version",
			"cell_captured_fact_revision",
		).forEach { table -> rowCount(table) shouldBe 0L }
	}

	@Test
	fun `public imported states distinguish retention deletion and unverifiable evidence`() {
		val value = cellEntry()
		val candidate = candidate(value)
		readable(value, candidate, retentionLimited = true).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.UNAVAILABLE
			public.causes shouldBe setOf(CellHistoryCause.RETENTION_LIMIT)
			public.observations shouldBe emptyList()
		}
		readable(value, candidate, entryDeleted = true).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.DELETED
			public.causes shouldBe setOf(CellHistoryCause.DELETED)
			public.observations shouldBe emptyList()
		}
		ImportedCellProductEvaluation.Unverifiable(
			candidate,
			com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
				.STORED_EVIDENCE_UNVERIFIABLE,
		).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.UNVERIFIABLE
			public.causes shouldBe setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			public.observations shouldBe emptyList()
		}
	}

	@Test
	fun `mixed-origin composition collapses only exact full-v1 duplicates`() {
		val exact = cellEntry(logicalLocal = "same", runLocal = "same-run")
		val imported = readable(exact, candidate(exact))
		val local = imported.toPublicCellEntry().copy(
			key = CellHistoryEntryKey("cell-logical:same"),
			origin = CellHistoryOrigin.Local,
			selection = LocalCellHistorySelection(
				LocalCellHistoryIdentity(exact.identity.value),
			),
		)
		val exactPage = CellHistoryOriginComposer.compose(
			live = listOf(local),
			visibleLocalEntryIdentities = setOf(exact.identity.value),
			localCollisionIdentities = setOf(exact.identity.value),
			imported = listOf(imported),
			localPortableEntriesByIdentity = mapOf(exact.identity.value to exact),
			limit = 2,
		)
		exactPage shouldBe listOf(local)

		val overlapping = cellEntry(logicalLocal = "other", runLocal = "other-run")
		val distinctPage = CellHistoryOriginComposer.compose(
			live = listOf(local),
			visibleLocalEntryIdentities = setOf(exact.identity.value),
			localCollisionIdentities = emptySet(),
			imported = listOf(readable(overlapping, candidate(overlapping))),
			localPortableEntriesByIdentity = mapOf(exact.identity.value to exact),
			limit = 2,
		)
		distinctPage shouldHaveSize 2
		distinctPage.map(CellHistoryEntry::origin).toSet() shouldBe setOf(
			CellHistoryOrigin.Local,
			readable(overlapping, candidate(overlapping)).toPublicCellEntry().origin,
		)
	}

	@Test
	fun `wrong child owner collision is typed instead of collapsed by time or counts`() {
		val sharedObservation = cellObservation(localId = "shared")
		val localPortable = cellEntry(
			logicalLocal = "local",
			runLocal = "local-run",
			observation = sharedObservation,
		)
		val importedPortable = cellEntry(
			logicalLocal = "imported",
			runLocal = "imported-run",
			observation = sharedObservation,
		)
		val localPublic = CellHistoryEntry(
			key = CellHistoryEntryKey("local"),
			startTime = EpochMs(localPortable.startTimeMs),
			endTime = EpochMs(localPortable.endTimeMs),
			storedZoneIds = setOf("UTC"),
			state = CellHistoryProductState.PARTIAL,
			coverage = com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage.PARTIAL,
			observations = readable(localPortable, candidate(localPortable)).toPublicCellEntry().observations,
			causes = setOf(CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN),
		)

		val result = CellHistoryOriginComposer.compose(
			live = listOf(localPublic),
			visibleLocalEntryIdentities = setOf(localPortable.identity.value),
			localCollisionIdentities = emptySet(),
			imported = listOf(readable(importedPortable, candidate(importedPortable))),
			localPortableEntriesByIdentity = mapOf(localPortable.identity.value to localPortable),
			limit = 2,
		)

		result.single { it.origin is CellHistoryOrigin.Imported }.let { imported ->
			imported.state shouldBe CellHistoryProductState.UNVERIFIABLE
			imported.causes shouldBe setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			imported.observations shouldBe emptyList()
		}
	}

	private fun repository() = DefaultCellHistoryRepository(
		database,
		SourceProductLaneExecutionAuthority { false },
		Dispatchers.Unconfined,
	)

	private fun importRequest(entry: PortableCapturedCellEntryV1) =
		ImportPortableCapturedCellRequest(
			entry = entry,
			receipt = PortableCellImportReceipt(
				jobId = "job",
				entryKey = "entry",
				sourceName = "backup.trackercell",
				receivedAtMs = 500L,
			),
			expectedCollectedDataEpoch = EPOCH,
		)

	private fun rowCount(table: String): Long = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private companion object {
		const val EPOCH = 4L
	}
}

private fun candidate(entry: PortableCapturedCellEntryV1) = ImportedCellHistoryCandidate(
	identity = entry.identity.value,
	importRevision = 1L,
	contentChecksum = entry.contentChecksum.value,
	startTimeMs = entry.startTimeMs,
	endTimeMs = entry.endTimeMs,
	receivedAtMs = 500L,
)

private fun readable(
	entry: PortableCapturedCellEntryV1,
	candidate: ImportedCellHistoryCandidate,
	entryDeleted: Boolean = false,
	deletedRunIdentities: Set<String> = emptySet(),
	retentionLimited: Boolean = false,
) = ImportedCellProductEvaluation.Readable(
	candidate = candidate,
	entry = entry,
	entryDeleted = entryDeleted,
	deletedRunIdentities = deletedRunIdentities,
	retainedFromMs = null,
	retentionLimited = retentionLimited,
	localOriginHandle = null,
)

private fun cellEntry(
	logicalLocal: String = "logical",
	runLocal: String = "run",
	observation: PortableCapturedCellObservationV1 = cellObservation(),
	acquisition: PortableCellAcquisitionCompleteness = PortableCellAcquisitionCompleteness.COMPLETE,
): PortableCapturedCellEntryV1 {
	val runIdentity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.PHYSICAL_RUN,
		runLocal,
	)
	val runChecksum = portableCellDigest("tracker-portable-cell-run-v1") {
		writeCellString(runIdentity.value)
		writeCellString(PortableCellDeletionScopeDigest.derive(logicalLocal, runLocal).value)
		writeLong(10L)
		writeLong(20L)
		writeCellString(PortableCellCaptureCoverage.WHOLE_RUN.name)
		writeCellString(PortableCellRunAvailability.RETAINED.name)
		writeCellString(acquisition.name)
		writeBoolean(false)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(1)
		writeCellString(observation.identity.value)
		writeCellString(observation.contentChecksum.value)
	}
	val run = PortableCapturedCellRunV1(
		identity = runIdentity,
		deletionScopeDigest = PortableCellDeletionScopeDigest.derive(logicalLocal, runLocal),
		contentChecksum = PortableCellDigest(runChecksum),
		startTimeMs = 10L,
		endTimeMs = 20L,
		captureCoverage = PortableCellCaptureCoverage.WHOLE_RUN,
		availability = PortableCellRunAvailability.RETAINED,
		acquisitionCompleteness = acquisition,
		retentionLoss = false,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		observations = listOf(observation),
	)
	val entryIdentity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.LOGICAL_ENTRY,
		logicalLocal,
	)
	val entryChecksum = portableCellDigest("tracker-portable-cell-entry-v1") {
		writeCellString("tracker-portable-captured-cell")
		writeInt(1)
		writeCellString(entryIdentity.value)
		writeCellString(PortableCellSessionMode.MANUAL.name)
		writeLong(10L)
		writeLong(20L)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(1)
		writeCellString(run.identity.value)
		writeCellString(run.contentChecksum.value)
	}
	return PortableCapturedCellEntryV1(
		identity = entryIdentity,
		contentChecksum = PortableCellDigest(entryChecksum),
		sessionMode = PortableCellSessionMode.MANUAL,
		startTimeMs = 10L,
		endTimeMs = 20L,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		runs = listOf(run),
	)
}

private fun cellObservation(
	localId: String = "observation",
	childCompleteness: PortableCellChildCompleteness = PortableCellChildCompleteness.COMPLETE,
): PortableCapturedCellObservationV1 {
	val identity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, localId)
	val submittedChildCount =
		if (childCompleteness == PortableCellChildCompleteness.COMPLETE) 2 else 3
	val staleChildCount = submittedChildCount - 2
	val checksum = portableCellDigest("tracker-portable-cell-observation-v1") {
		writeCellString(identity.value)
		writeLong(1L)
		writeNullableLong(null)
		writeCellString(null)
		writeNullableLong(null)
		writeLong(100L)
		writeLong(110L)
		writeLong(112L)
		writeLong(2L)
		writeCellString("UTC")
		writeCellString(childCompleteness.name)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		listOf(
			submittedChildCount, 2, staleChildCount, 0, 0, 0, 0, 0,
			2, 2, 0, 0, 0, 0, 1, 1,
			1, 1, 0, 0, 0, 0, 1, 1,
		).forEach(::writeInt)
		writeBoolean(true)
		writeLong(0L)
		writeNullableDouble(0.5)
	}
	return PortableCapturedCellObservationV1(
		identity = identity,
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		contentChecksum = PortableCellDigest(checksum),
		coverageStartTimeMs = 100L,
		observedTimeMs = 110L,
		latestPossibleTimeMs = 112L,
		wallTimeUncertaintyMs = 2L,
		storedZoneId = "UTC",
		childCompleteness = childCompleteness,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		submittedChildCount = submittedChildCount,
		acceptedChildCount = 2,
		staleChildCount = staleChildCount,
		futureTimeChildCount = 0,
		missingTimeChildCount = 0,
		clockUnverifiableChildCount = 0,
		authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0,
		observationCount = 2,
		registeredObservationCount = 2,
		gsmCount = 0,
		cdmaCount = 0,
		wcdmaCount = 0,
		tdscdmaCount = 0,
		lteCount = 1,
		nrCount = 1,
		qualityUnknownCount = 1,
		qualityNoneOrUnknownCount = 1,
		qualityPoorCount = 0,
		qualityModerateCount = 0,
		qualityGoodCount = 0,
		qualityGreatCount = 0,
		weakObservationCount = 1,
		knownQualityObservationCount = 1,
		allKnownQualityIsWeak = true,
		qualityFlags = 0L,
		qualityConfidence = 0.5,
	)
}

private fun portableCellDigest(
	namespace: String,
	body: DataOutputStream.() -> Unit,
): String {
	val bytes = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
}
