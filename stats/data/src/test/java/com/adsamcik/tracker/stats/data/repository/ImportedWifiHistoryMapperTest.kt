package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import org.junit.Test

class ImportedWifiHistoryMapperTest {
	@Test
	fun `complete replacement hierarchy maps identity-free imported history`() {
		val entry = entry(
			listOf(
				run("run-a", observation("observation-a", 1_100L)),
				run("run-b", observation("observation-b", 2_100L), start = 2_000L, end = 3_000L),
			),
		)

		val public = readable(entry).toPublicWifiEntry()

		public.origin shouldBe WifiHistoryOrigin.IMPORTED
		public.importedSelection?.key?.value shouldBe entry.identity.value
		public.selection shouldBe com.adsamcik.tracker.stats.api.repository.WifiHistorySelection.Imported(
			readable(entry).candidate.selection,
		)
		public.state shouldBe WifiHistoryProductState.READY
		public.observations.map { it.observedTime.raw } shouldContainExactly listOf(1_200L, 2_200L)
		public.capturesOnlyWifi shouldBe false
	}

	@Test
	fun `partial retention and deleted run stay explicit without leaked values`() {
		val old = observation("old", 900L)
		val current = observation("current", 2_100L)
		val first = run("run-old", old)
		val second = run(
			"run-current",
			current,
			start = 2_000L,
			end = 3_000L,
			completeness = PortableWifiAcquisitionCompleteness.PARTIAL,
		)
		val entry = entry(listOf(first, second))
		val evaluation = readable(
			entry,
			deletedRuns = setOf(first.identity),
			retained = setOf(current.identity),
			retentionLimited = true,
		)

		val public = evaluation.toPublicWifiEntry()

		public.state shouldBe WifiHistoryProductState.PARTIAL
		public.causes shouldBe setOf(
			WifiHistoryCause.DELETED,
			WifiHistoryCause.ACQUISITION_INCOMPLETE,
			WifiHistoryCause.RETENTION_LIMIT,
		)
		public.observations.map { it.observedTime.raw } shouldBe listOf(2_200L)
	}

	@Test
	fun `visible empty run prevents deleted or ready fabrication`() {
		val deletedRun = run("deleted-run", observation("deleted-observation", 900L))
		val emptyRun = PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "empty-run"),
			deletionScopeDigest = PortableWifiDeletionScopeDigest(
				sha256("test-imported-wifi-scope:empty-run"),
			),
			startTimeMs = 2_000L,
			endTimeMs = 3_000L,
			storedZoneIds = listOf("Europe/Prague"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.NO_RETAINED_OBSERVATION,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = emptyList(),
		)
		val entry = entry(listOf(deletedRun, emptyRun))
		val noVisibleValues = readable(
			entry,
			deletedRuns = setOf(deletedRun.identity),
			retained = emptySet(),
		).toPublicWifiEntry()
		noVisibleValues.state shouldBe WifiHistoryProductState.MISSING
		noVisibleValues.causes shouldBe setOf(
			WifiHistoryCause.DELETED,
			WifiHistoryCause.NO_QUALIFIED_FACTS,
		)

		val retainedRun = run(
			"retained-run",
			observation("retained-observation", 3_100L),
			start = 3_000L,
			end = 4_000L,
		)
		val partiallyEmpty = entry(listOf(emptyRun, retainedRun))
		val public = readable(partiallyEmpty).toPublicWifiEntry()
		public.state shouldBe WifiHistoryProductState.PARTIAL
		public.causes shouldBe setOf(WifiHistoryCause.NO_QUALIFIED_FACTS)
	}

	@Test
	fun `entry deletion and stale epoch are distinct value-free states`() {
		val entry = entry(listOf(run("run", observation("observation", 1_100L))))
		val deleted = readable(
			entry,
			deletedRuns = entry.runs.mapTo(linkedSetOf()) { it.identity },
			entryDeleted = true,
		).toPublicWifiEntry()
		deleted.state shouldBe WifiHistoryProductState.DELETED
		deleted.causes shouldBe setOf(WifiHistoryCause.DELETED)
		deleted.observations shouldBe emptyList()

		val stale = ImportedWifiProductEvaluation.Unverifiable(
			candidate(entry),
			ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH,
		).toPublicWifiEntry()
		stale.state shouldBe WifiHistoryProductState.UNAVAILABLE
		stale.causes shouldBe setOf(WifiHistoryCause.PRIVACY_EPOCH_MISMATCH)
		stale.selection shouldBe null
	}

	@Test
	fun `corrupt or overflowing imported graph maps typed failed shells`() {
		val entry = entry(listOf(run("run", observation("observation", 1_100L))))
		listOf(
			ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE to
				WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT to
				WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT,
			ImportedWifiProductFailure.DEPENDENCY_OVERFLOW to WifiHistoryCause.READ_BUDGET_EXCEEDED,
			ImportedWifiProductFailure.VALUE_OVERFLOW to WifiHistoryCause.VALUE_OVERFLOW,
		).forEach { (failure, cause) ->
			val public = ImportedWifiProductEvaluation.Unverifiable(candidate(entry), failure)
				.toPublicWifiEntry()
			public.state shouldBe WifiHistoryProductState.FAILED
			public.causes shouldBe setOf(cause)
			public.selection shouldBe null
		}
	}

	@Test
	fun `failed shell preserves only an independently authenticated current selector`() {
		val entry = entry(listOf(run("run", observation("observation", 1_100L))))
		val candidate = candidate(entry)
		val selected = ImportedWifiProductEvaluation.Unverifiable(
			candidate = candidate,
			reason = ImportedWifiProductFailure.VALUE_OVERFLOW,
			authenticatedSelection = candidate.selection,
		).toPublicWifiEntry()
		selected.state shouldBe WifiHistoryProductState.FAILED
		selected.selection shouldBe com.adsamcik.tracker.stats.api.repository.WifiHistorySelection.Imported(
			candidate.selection,
		)

		val corrupt = ImportedWifiProductEvaluation.Unverifiable(
			candidate,
			ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
		).toPublicWifiEntry()
		corrupt.selection shouldBe null
	}

	@Test
	fun `imported structural projection keeps empty run ownership and exact portable bounds`() {
		val observation = observation("structural-observation", 2_000L)
		val factRun = run("structural-fact", observation, 1_500L, 2_500L)
		val emptyRun = PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "structural-empty"),
			deletionScopeDigest = PortableWifiDeletionScopeDigest(
				sha256("test-imported-wifi-scope:structural-empty"),
			),
			startTimeMs = 86_400_000L,
			endTimeMs = 86_401_000L,
			storedZoneIds = listOf("UTC"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.NO_RETAINED_OBSERVATION,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = emptyList(),
		)
		val imported = entry(listOf(factRun, emptyRun))
		val evaluation = readable(imported)
		val public = evaluation.toPublicWifiEntry()

		val runs = evaluation.authenticatedStructuralRuns(public)

		runs.size shouldBe 2
		runs.single { it.storedZoneIds == setOf("UTC") }.observations shouldBe emptyList()
		runs.single { it.storedZoneIds == setOf("Europe/Prague") }
			.observations.single() shouldBe WifiHistoryStructuralObservationProjection(
			earliestPossibleTimeMs = observation.coverageStartTimeMs,
			latestPossibleTimeMs = observation.latestPossibleTimeMs,
			storedZoneId = observation.storedZoneId,
		)
	}

	@Test
	fun `exact full portable duplicate may collapse but payload mismatch fails typed`() {
		val portable = entry(listOf(run("run", observation("observation", 1_100L))))
		val imported = readable(portable, collidingLocalLogicalId = "entry")
		val localPublic = imported.toPublicWifiEntry().copy(
			origin = WifiHistoryOrigin.LOCAL,
			importedSelection = null,
			localSelection = com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey(
				portable.identity.value,
			),
			capturesOnlyWifi = true,
		)
		val local = ComposedWifiEntry("entry", 1_000L, 1L, listOf(1L), localPublic)

		WifiHistoryOriginComposer.compose(
			live = listOf(local),
			imported = listOf(imported),
			localPortableByLogicalId = mapOf("entry" to portable),
			limit = 10,
		).single().origin shouldBe WifiHistoryOrigin.LOCAL
		WifiHistoryOriginComposer.composeSourceRecent(
			live = listOf(local),
			imported = listOf(imported),
			localPortableByLogicalId = mapOf("entry" to portable),
			limit = 10,
		) shouldBe WifiSourceRecentPage.Available(
			listOf(WifiSourceRecentEntry.Local(local)),
		)

		val mismatched = PortableWifiIntegrity.createEntry(
			identity = portable.identity,
			sessionMode = PortableWifiSessionMode.AUTOMATIC,
			startTimeMs = portable.startTimeMs,
			endTimeMs = portable.endTimeMs,
			runs = portable.runs,
		)
		val failed = WifiHistoryOriginComposer.compose(
			live = listOf(local),
			imported = listOf(imported),
			localPortableByLogicalId = mapOf("entry" to mismatched),
			limit = 10,
		).single { it.origin == WifiHistoryOrigin.IMPORTED }
		failed.state shouldBe WifiHistoryProductState.FAILED
		failed.causes shouldBe setOf(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		WifiHistoryOriginComposer.composeSourceRecent(
			live = listOf(local),
			imported = listOf(imported),
			localPortableByLogicalId = mapOf("entry" to mismatched),
			limit = 10,
		) shouldBe WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
	}

	@Test
	fun `origin composition orders and limits by newest physical member rather than entry start`() {
		val importedOlderMember = entryFor(
			"imported-older-member",
			listOf(run("imported-old-run", observation("imported-old-observation", 50L), 50L, 60L)),
		)
		val importedNewerMember = entryFor(
			"imported-newer-member",
			listOf(
				run("imported-new-first", observation("imported-new-first-observation", 10L), 0L, 20L),
				run("imported-new-last", observation("imported-new-last-observation", 100L), 100L, 120L),
			),
		)
		val importedWinner = WifiHistoryOriginComposer.compose(
			live = emptyList(),
			imported = listOf(readable(importedOlderMember), readable(importedNewerMember)),
			localPortableByLogicalId = emptyMap(),
			limit = 1,
		).single()
		importedWinner.importedSelection?.key?.value shouldBe importedNewerMember.identity.value

		val localPublic = readable(importedNewerMember).toPublicWifiEntry().copy(
			origin = WifiHistoryOrigin.LOCAL,
			importedSelection = null,
			localSelection = com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey(
				identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "local-recency").value,
			),
		)
		val local = ComposedWifiEntry(
			logicalTrackingId = "local-recency",
			recencyStartTimeMs = 100L,
			recencySegmentId = 9L,
			physicalSegmentIds = listOf(8L, 9L),
			entry = localPublic,
		)
		WifiHistoryOriginComposer.compose(
			live = listOf(local),
			imported = listOf(readable(importedOlderMember)),
			localPortableByLogicalId = emptyMap(),
			limit = 1,
		).single().origin shouldBe WifiHistoryOrigin.LOCAL
		val localOlder = local.copy(
			logicalTrackingId = "local-older",
			recencyStartTimeMs = 50L,
			recencySegmentId = 7L,
			physicalSegmentIds = listOf(7L),
			entry = localPublic.copy(
				localSelection = com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey(
					identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "local-older").value,
				),
			),
		)
		WifiHistoryOriginComposer.compose(
			live = listOf(localOlder, local),
			imported = emptyList(),
			localPortableByLogicalId = emptyMap(),
			limit = 1,
		).single().localSelection shouldBe local.entry.localSelection
	}

	private fun readable(
		entry: PortableCapturedWifiEntryV1,
		entryDeleted: Boolean = false,
		deletedRuns: Set<PortableWifiOpaqueIdentity> = emptySet(),
		retained: Set<PortableWifiOpaqueIdentity> = entry.runs.flatMapTo(linkedSetOf()) { run ->
			run.observations.map { it.identity }
		},
		retentionLimited: Boolean = false,
		collidingLocalLogicalId: String? = null,
	) = ImportedWifiProductEvaluation.Readable(
		candidate = candidate(entry),
		entry = entry,
		entryDeleted = entryDeleted,
		deletedRunIdentities = deletedRuns,
		retainedObservationIdentities = retained,
		retentionLimited = retentionLimited,
		collidingLocalLogicalTrackingId = collidingLocalLogicalId,
	)

	private fun candidate(entry: PortableCapturedWifiEntryV1) = ImportedWifiProductCandidate(
		identity = entry.identity,
		importRevision = 1L,
		contentChecksum = entry.contentChecksum,
		startTimeMs = entry.startTimeMs,
		endTimeMs = entry.endTimeMs,
		receivedAtMs = 4_000L,
		newestMemberStartTimeMs = entry.runs.maxOf { it.startTimeMs },
		newestMemberIdentity = entry.runs.maxWith(
			compareBy<com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1>(
				{ it.startTimeMs },
				{ it.identity.value },
			),
		).identity,
	)

	private fun entry(runs: List<com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1>) =
		entryFor("entry", runs)

	private fun entryFor(
		seed: String,
		runs: List<com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1>,
	) =
		PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, seed),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = runs.minOf { it.startTimeMs },
			endTimeMs = runs.maxOf { it.endTimeMs },
			runs = runs,
		)

	private fun run(
		seed: String,
		observation: PortableCapturedWifiObservationV1,
		start: Long = 1_000L,
		end: Long = 2_000L,
		completeness: PortableWifiAcquisitionCompleteness =
			PortableWifiAcquisitionCompleteness.COMPLETE,
	) = PortableWifiIntegrity.createRun(
		identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, seed),
		deletionScopeDigest = PortableWifiDeletionScopeDigest(
			sha256("test-imported-wifi-scope:$seed"),
		),
		startTimeMs = start,
		endTimeMs = end,
		storedZoneIds = listOf("Europe/Prague"),
		captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
		availability = PortableWifiRunAvailability.RETAINED,
		acquisitionCompleteness = completeness,
		hasUnresolvedProviderRange = completeness != PortableWifiAcquisitionCompleteness.COMPLETE,
		retentionLoss = false,
		observations = listOf(observation),
	)

	private fun observation(seed: String, coverageStart: Long) = PortableWifiIntegrity.createObservation(
		identity = identity(PortableWifiIdentityKind.OBSERVATION, seed),
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		coverageStartTimeMs = coverageStart,
		observedTimeMs = coverageStart + 100L,
		latestPossibleTimeMs = coverageStart + 101L,
		wallTimeUncertaintyMs = 1L,
		storedZoneId = "Europe/Prague",
		availability = PortableWifiAvailability.AVAILABLE,
		resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
		submittedResultCount = 2,
		acceptedResultCount = 2,
		staleResultCount = 0,
		clockUnverifiableResultCount = 0,
		malformedResultCount = 0,
		observationCount = 2,
		twoPointFourGhzCount = 1,
		fiveGhzCount = 1,
		sixGhzCount = 0,
		otherBandCount = 0,
		strongestSignalDbm = -50,
		weakestSignalDbm = -70,
		meanSignalDbm = -60.0,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
	)

	private fun identity(kind: PortableWifiIdentityKind, seed: String) =
		PortableWifiOpaqueIdentity.derive(kind, seed)

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray())
		.joinToString("") { "%02x".format(it) }
}
