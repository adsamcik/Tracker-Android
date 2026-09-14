package com.adsamcik.tracker.stats.api.repository

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WifiCapturedPortableFormatV1Test {
	@Test
	fun `opaque Wi-Fi identities are stable namespaced hashes without local identity`() {
		val raw = "logical/run/provider-visible-identity"
		val entry = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, raw)
		val replay = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, raw)
		val run = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN, raw)

		assertEquals(entry, replay)
		assertNotEquals(entry, run)
		assertTrue(raw !in entry.value)
		assertTrue(Regex("[0-9a-f]{64}").matches(entry.value))
	}

	@Test
	fun `Wi-Fi checksums bind exact semantic content and deterministic replay`() {
		val observation = observation("observation-a")
		val run = retainedRun(observation)
		val entry = PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry-a"),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = listOf(run),
		)
		val replay = PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry-a"),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = listOf(retainedRun(observation("observation-a"))),
		)

		assertEquals(entry, replay)
		assertEquals(entry.contentChecksum, PortableWifiIntegrity.entryChecksum(entry))
		assertEquals(run.contentChecksum, PortableWifiIntegrity.runChecksum(run))
		assertEquals(observation.contentChecksum, PortableWifiIntegrity.observationChecksum(observation))
		assertFailsWith<IllegalArgumentException> {
			observation.copy(observationCount = observation.observationCount + 1)
		}
	}

	@Test
	fun `captured zero-callback run is explicit without fabricated observation`() {
		val run = PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "zero-run"),
			deletionScopeDigest = deletionDigest("zero-run"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			storedZoneIds = listOf("Europe/Prague"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.NO_RETAINED_OBSERVATION,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.PARTIAL,
			hasUnresolvedProviderRange = true,
			retentionLoss = false,
			observations = emptyList(),
		)
		val entry = PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "zero-entry"),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = listOf(run),
		)

		assertTrue(entry.runs.single().observations.isEmpty())
		assertEquals(PortableWifiRunAvailability.NO_RETAINED_OBSERVATION, run.availability)
		assertFailsWith<IllegalArgumentException> {
			run.copy(availability = PortableWifiRunAvailability.RETAINED)
		}
	}

	@Test
	fun `observation cannot encode fabricated empty provider result`() {
		assertFailsWith<IllegalArgumentException> {
			PortableWifiIntegrity.createObservation(
				identity = identity(PortableWifiIdentityKind.OBSERVATION, "empty"),
				semanticRevision = 1L,
				supersedesSemanticRevision = null,
				aggregateOwnerIdentity = null,
				aggregateOwnerSemanticRevision = null,
				coverageStartTimeMs = 1_000L,
				observedTimeMs = 1_000L,
				latestPossibleTimeMs = 1_000L,
				wallTimeUncertaintyMs = 0L,
				storedZoneId = "UTC",
				availability = PortableWifiAvailability.AVAILABLE,
				resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
				submittedResultCount = 0,
				acceptedResultCount = 0,
				staleResultCount = 0,
				clockUnverifiableResultCount = 0,
				malformedResultCount = 0,
				observationCount = 0,
				twoPointFourGhzCount = 0,
				fiveGhzCount = 0,
				sixGhzCount = 0,
				otherBandCount = 0,
				strongestSignalDbm = 0,
				weakestSignalDbm = 0,
				meanSignalDbm = 0.0,
				sourceQualityFlags = 0L,
				sourceQualityConfidence = null,
			)
		}
	}

	private fun retainedRun(observation: PortableCapturedWifiObservationV1) =
		PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "run-a"),
			deletionScopeDigest = deletionDigest("run-a"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			storedZoneIds = listOf("Europe/Prague"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.RETAINED,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = listOf(observation),
		)

	private fun observation(seed: String) = PortableWifiIntegrity.createObservation(
		identity = identity(PortableWifiIdentityKind.OBSERVATION, seed),
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		coverageStartTimeMs = 1_100L,
		observedTimeMs = 1_200L,
		latestPossibleTimeMs = 1_201L,
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
		strongestSignalDbm = -55,
		weakestSignalDbm = -75,
		meanSignalDbm = -65.0,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = null,
	)

	private fun identity(kind: PortableWifiIdentityKind, seed: String) =
		PortableWifiOpaqueIdentity.derive(kind, seed)

	private fun deletionDigest(seed: String) = PortableWifiDeletionScopeDigest(
		PortableWifiIntegrity.digest("test-wifi-deletion", listOf(seed)),
	)
}
