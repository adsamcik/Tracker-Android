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
	)

	private fun entry(runs: List<com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1>) =
		PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry"),
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
