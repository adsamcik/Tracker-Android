package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.RetainedImportedActivityIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedActivityHistoryMapperTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `complete imported evidence exposes exact nonzero product without live authority`() {
		val result = readable(entry(window()))

		val public = result.toPublicActivityEntry()

		public.origin shouldBe ActivityHistoryOrigin.IMPORTED
		public.state shouldBe ActivityHistoryProductState.READY
		public.coverage shouldBe ActivityHistoryCoverage.COMPLETE
		public.activeTime?.knownActiveDurationNanos shouldBe 100L
		public.fragments.single()::class shouldBe ActivityHistoryFragment.Band::class
		public.causes shouldBe emptySet()
	}

	@Test
	fun `gap-only imported evidence remains partial and never becomes fabricated active zero`() {
		val result = readable(entry(gapWindow()))

		val public = result.toPublicActivityEntry()

		public.state shouldBe ActivityHistoryProductState.PARTIAL
		public.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		public.activeTime?.knownActiveDurationNanos shouldBe 0L
		public.activeTime?.unobservedDurationNanos shouldBe 100L
		public.fragments.single()::class shouldBe ActivityHistoryFragment.Gap::class
		public.causes shouldBe setOf(
			ActivityHistoryCause.ACQUISITION_INCOMPLETE,
			ActivityHistoryCause.PROVIDER_GAP,
		)
	}

	@Test
	fun `explicit not captured replacement remains visible as partial coverage`() {
		val captured = entry(window()).runs.single()
		val runIdentity = opaque('5')
		val scope = PortableActivityDeletionScopeDigest("6".repeat(64))
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "UTC"))
		val notCaptured = PortableActivityRunV1(
			identity = runIdentity,
			deletionScopeDigest = scope,
			contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
				runIdentity, scope, 1_000L, 2_000L,
				PortableActivityCaptureCoverage.NOT_CAPTURED, zones, emptyList(),
			),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			captureCoverage = PortableActivityCaptureCoverage.NOT_CAPTURED,
			zoneEpochs = zones,
			windows = emptyList(),
		)
		val result = readable(entryFromRuns(opaque('1'), listOf(captured, notCaptured)))

		val public = result.toPublicActivityEntry()

		public.state shouldBe ActivityHistoryProductState.PARTIAL
		public.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		public.activeTime?.knownActiveDurationNanos shouldBe 100L
		public.causes shouldBe setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED)
	}

	@Test
	fun `partial run authority cannot become complete from its retained windows alone`() {
		val whole = entry(window())
		val run = whole.runs.single()
		val partialRun = run.copy(
			contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
				run.identity,
				run.deletionScopeDigest,
				run.startTimeMs,
				run.endTimeMs,
				PortableActivityCaptureCoverage.PARTIAL_RUN,
				run.zoneEpochs,
				run.windows,
			),
			captureCoverage = PortableActivityCaptureCoverage.PARTIAL_RUN,
		)

		val public = readable(entryFromRuns(whole.identity, listOf(partialRun)))
			.toPublicActivityEntry()

		public.state shouldBe ActivityHistoryProductState.PARTIAL
		public.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		public.activeTime?.knownActiveDurationNanos shouldBe 100L
		public.causes shouldBe setOf(ActivityHistoryCause.ACQUISITION_INCOMPLETE)
	}

	@Test
	fun `deleted retention and corrupt origins expose no numeric Activity value`() {
		val value = entry(window())
		val deleted = readable(value, entryDeleted = true).toPublicActivityEntry()
		val runDeleted = readable(
			value,
			deletedRunIdentities = setOf(value.runs.single().identity.value),
		).toPublicActivityEntry()
		val retained = retained(value).toPublicActivityEntry()
		val corrupt = ImportedActivityProductEvaluation.Unverifiable(
			candidate(value),
			ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
		).toPublicActivityEntry()

		listOf(deleted, runDeleted, retained, corrupt).forEach {
			it.activeTime shouldBe null
			it.fragments shouldBe emptyList()
			it.coverage shouldBe ActivityHistoryCoverage.NONE
		}
		deleted.causes shouldBe setOf(ActivityHistoryCause.DELETED)
		runDeleted.causes shouldBe setOf(ActivityHistoryCause.DELETED)
		retained.causes shouldBe setOf(ActivityHistoryCause.RETENTION_LIMIT)
		corrupt.state shouldBe ActivityHistoryProductState.FAILED
		corrupt.causes shouldBe setOf(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
	}

	@Test
	fun `origin composer suppresses only exact full v1 local duplicates`() {
		val logicalId = "same-device-logical"
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalId,
		)
		val exact = entry(window(), identity)
		val local = readable(exact).toPublicActivityEntry().copy(origin = ActivityHistoryOrigin.LOCAL)

		ActivityHistoryOriginComposer.compose(
			live = listOf(local),
			liveLogicalTrackingIds = setOf(logicalId),
			imported = listOf(readable(exact)),
			localPortableEntriesByIdentity = mapOf(identity.value to exact),
			limit = 10,
		) shouldBe listOf(local)
	}

	@Test
	fun `origin composer preserves a divergent same identity as a typed conflict`() {
		val logicalId = "same-device-logical"
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalId,
		)
		val localPortable = entry(window(), identity)
		val importedPortable = entry(gapWindow(), identity)
		val local = readable(localPortable).toPublicActivityEntry().copy(origin = ActivityHistoryOrigin.LOCAL)

		val composed = ActivityHistoryOriginComposer.compose(
			live = listOf(local),
			liveLogicalTrackingIds = setOf(logicalId),
			imported = listOf(readable(importedPortable)),
			localPortableEntriesByIdentity = mapOf(identity.value to localPortable),
			limit = 10,
		)

		composed.size shouldBe 2
		composed.single { it.origin == ActivityHistoryOrigin.IMPORTED }.causes shouldBe
			setOf(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
	}

	@Test
	fun `origin composer rejects a distinct imported entry reusing local child ownership`() {
		val logicalId = "local-owner"
		val localIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalId,
		)
		val localPortable = entry(window(), localIdentity)
		val importedIdentity = opaque('7')
		val importedPortable = entryFromRuns(importedIdentity, localPortable.runs)
		val local = readable(localPortable).toPublicActivityEntry().copy(
			origin = ActivityHistoryOrigin.LOCAL,
		)

		val composed = ActivityHistoryOriginComposer.compose(
			live = listOf(local),
			liveLogicalTrackingIds = setOf(logicalId),
			imported = listOf(readable(importedPortable)),
			localPortableEntriesByIdentity = mapOf(localIdentity.value to localPortable),
			limit = 10,
		)

		composed.size shouldBe 2
		composed.single { it.origin == ActivityHistoryOrigin.IMPORTED }.causes shouldBe
			setOf(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
	}

	@Test
	fun `origin composer preserves retained run window and scope collisions with local ownership`() {
		val logicalId = "local-retained-owner"
		val localIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalId,
		)
		val localPortable = entry(window(), localIdentity)
		val local = readable(localPortable).toPublicActivityEntry().copy(
			origin = ActivityHistoryOrigin.LOCAL,
		)
		val localRun = localPortable.runs.single()
		val importedWithRunCollision = entry(
			window = window(opaque('7')),
			identity = opaque('5'),
			runIdentity = localRun.identity,
			deletionScope = PortableActivityDeletionScopeDigest("6".repeat(64)),
		)
		val importedWithWindowCollision = entry(
			window = window(localRun.windows.single().identity),
			identity = opaque('8'),
			runIdentity = opaque('9'),
			deletionScope = PortableActivityDeletionScopeDigest("a".repeat(64)),
		)
		val importedWithScopeCollision = entry(
			window = window(opaque('d')),
			identity = opaque('b'),
			runIdentity = opaque('c'),
			deletionScope = localRun.deletionScopeDigest,
		)

		listOf(
			importedWithRunCollision,
			importedWithWindowCollision,
			importedWithScopeCollision,
		).forEach { imported ->
			val composed = ActivityHistoryOriginComposer.compose(
				live = listOf(local),
				liveLogicalTrackingIds = setOf(logicalId),
				imported = listOf(retained(imported)),
				localPortableEntriesByIdentity = mapOf(localIdentity.value to localPortable),
				limit = 10,
			)

			composed.single { it.origin == ActivityHistoryOrigin.IMPORTED }.causes shouldBe
				setOf(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
	}

	@Test
	fun `origin composer preserves distinct authenticated imported origins`() {
		val first = readable(entry(window(), opaque('1')))
		val second = readable(
			entry(
				window = window(opaque('9')),
				identity = opaque('7'),
				runIdentity = opaque('8'),
				deletionScope = PortableActivityDeletionScopeDigest("a".repeat(64)),
			),
		)

		val composed = ActivityHistoryOriginComposer.compose(
			live = emptyList(),
			liveLogicalTrackingIds = emptySet(),
			imported = listOf(first, second),
			localPortableEntriesByIdentity = emptyMap(),
			limit = 10,
		)

		composed.size shouldBe 2
		composed.all { it.origin == ActivityHistoryOrigin.IMPORTED } shouldBe true
		composed.map { it.key }.distinct().size shouldBe 2
	}

	@Test
	fun `corrupt imported shell authority fails the whole composition`() {
		val value = entry(window())
		val corrupt = ImportedActivityProductEvaluation.Unverifiable(
			candidate(value).copy(startTimeMs = -1L),
			ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)

		shouldThrow<ImportedActivityHistoryCompositionFailure> {
			ActivityHistoryOriginComposer.compose(
				live = emptyList(),
				liveLogicalTrackingIds = emptySet(),
				imported = listOf(corrupt),
				localPortableEntriesByIdentity = emptyMap(),
				limit = 10,
			)
		}
	}

	@Test
	fun `repository discovers imported Activity without any local session or provider authority`() = runTest {
		val portable = entry(window())
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(
			ImportPortableCapturedActivityRequest(
				entry = portable,
				receipt = PortableActivityImportReceipt(
					"job", "entry", "backup.trackeractivity", 3_000L,
				),
				expectedCollectedDataEpoch = 7L,
			),
		) shouldBe ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		val repository = DefaultActivityHistoryRepository(
			database,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		)

		val page = repository.recent(10) as ActivityHistoryPage.Available

		page.entries.single().origin shouldBe ActivityHistoryOrigin.IMPORTED
		page.entries.single().state shouldBe ActivityHistoryProductState.READY
		database.sourceSessionDao().session(portable.identity.value) shouldBe null
		database.sourceSessionDao().serviceRun(portable.runs.single().identity.value) shouldBe null
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	private fun readable(
		entry: PortableActivityEntryV1,
		entryDeleted: Boolean = false,
		deletedRunIdentities: Set<String> = emptySet(),
		retainedFromMs: Long? = null,
	) = ImportedActivityProductEvaluation.Readable(
		candidate = candidate(entry),
		entry = entry,
		entryDeleted = entryDeleted,
		deletedRunIdentities = deletedRunIdentities,
		retainedFromMs = retainedFromMs,
		retentionLimited = retainedFromMs != null,
	)

	private fun retained(entry: PortableActivityEntryV1) = ImportedActivityProductEvaluation.Retained(
		candidate = candidate(entry),
		retainedFromMs = 1_500L,
		retainedAtMs = 3_100L,
		latestMemberStartTimeMs = entry.runs.last().startTimeMs,
		latestMemberIdentity = entry.runs.last().identity,
		structuralZoneRanges = entry.runs.map { run ->
			com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedZoneRange(
				run.startTimeMs,
				run.endTimeMs,
				run.zoneEpochs.last().zoneId,
			)
		},
		structuralZoneCoverageComplete = true,
		protectedIdentities = buildList {
			add(RetainedImportedActivityIdentity.Entry(entry.identity))
			entry.runs.forEach { run ->
				add(RetainedImportedActivityIdentity.Run(run.identity))
				add(RetainedImportedActivityIdentity.DeletionScope(run.deletionScopeDigest))
				run.windows.forEach { window ->
					add(RetainedImportedActivityIdentity.Window(window.identity))
				}
			}
		},
	)

	private fun candidate(entry: PortableActivityEntryV1) = ImportedActivityHistoryCandidate(
		identity = entry.identity.value,
		importRevision = 1L,
		contentChecksum = entry.contentChecksum.value,
		startTimeMs = entry.startTimeMs,
		endTimeMs = entry.endTimeMs,
		receivedAtMs = 3_000L,
	)

	private fun entry(
		window: PortableActivityWindowV1,
		identity: PortableActivityOpaqueIdentity = opaque('1'),
		runIdentity: PortableActivityOpaqueIdentity = opaque('2'),
		deletionScope: PortableActivityDeletionScopeDigest = PortableActivityDeletionScopeDigest(
			"3".repeat(64),
		),
	): PortableActivityEntryV1 {
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "UTC"))
		val windows = listOf(window)
		val run = PortableActivityRunV1(
			identity = runIdentity,
			deletionScopeDigest = deletionScope,
			contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
				runIdentity,
				deletionScope,
				1_000L,
				2_000L,
				PortableActivityCaptureCoverage.WHOLE_RUN,
				zones,
				windows,
			),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			captureCoverage = PortableActivityCaptureCoverage.WHOLE_RUN,
			zoneEpochs = zones,
			windows = windows,
		)
		return entryFromRuns(identity, listOf(run))
	}

	private fun entryFromRuns(
		identity: PortableActivityOpaqueIdentity,
		runs: List<PortableActivityRunV1>,
	): PortableActivityEntryV1 {
		val ordered = runs.sortedWith(
			compareBy<PortableActivityRunV1>(PortableActivityRunV1::startTimeMs)
				.thenBy(PortableActivityRunV1::endTimeMs)
				.thenBy { it.identity.value },
		)
		return PortableActivityEntryV1(
			identity = identity,
			contentChecksum = ActivityCapturedPortableIntegrity.entryChecksum(
				identity,
				PortableActivitySessionMode.MANUAL,
				1_000L,
				2_000L,
				ordered,
			),
			sessionMode = PortableActivitySessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = ordered,
		)
	}

	private fun window(identity: PortableActivityOpaqueIdentity = opaque('4')): PortableActivityWindowV1 {
		val fragments = listOf(
			PortableActivityFragmentV1.Band(
				0L, 100L, "WALKING", "TRANSITION", null, "TRANSITION_SIGNAL",
				null, null, null, 1_000L, 0L, "EXACT_PROVIDER_OBSERVATION",
				1_001L, 0L, "SAME_CLOCK_EXTRAPOLATION", "SAME_ANCHOR",
			),
		)
		return window(PortableActivityWindowCoverage.COMPLETE, 100L, 0L, fragments, identity)
	}

	private fun gapWindow(): PortableActivityWindowV1 {
		val fragments = listOf(
			PortableActivityFragmentV1.Gap(0L, 100L, "NO_QUALIFIED_EVIDENCE"),
		)
		return window(PortableActivityWindowCoverage.NONE, 0L, 100L, fragments)
	}

	private fun window(
		coverage: PortableActivityWindowCoverage,
		activeNanos: Long,
		unobservedNanos: Long,
		fragments: List<PortableActivityFragmentV1>,
		identity: PortableActivityOpaqueIdentity = opaque('4'),
	): PortableActivityWindowV1 {
		val checksum: PortableActivityDigest = ActivityCapturedPortableIntegrity.windowChecksum(
			identity,
			0L,
			100L,
			"UTC",
			coverage,
			activeNanos,
			0L,
			0L,
			unobservedNanos,
			fragments,
		)
		return PortableActivityWindowV1(
			identity, checksum, 0L, 100L, "UTC", coverage,
			activeNanos, 0L, 0L, unobservedNanos, fragments,
		)
	}

	private fun opaque(character: Char) = PortableActivityOpaqueIdentity(character.toString().repeat(64))
}
