package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RadioSourceFileJsonV2CodecTest {
	private val codec = RadioSourceFileJsonV2Codec()

	@Test fun `Wi-Fi file round trips session inline identities and raw network detail`() = runBlocking {
		val entryId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry")
		val runId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN, "run")
		val observationId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, "fact")
		val observation = PortableWifiIntegrity.createObservation(
			identity = observationId, semanticRevision = 1L, supersedesSemanticRevision = null,
			aggregateOwnerIdentity = null, aggregateOwnerSemanticRevision = null,
			coverageStartTimeMs = 1_000L, observedTimeMs = 1_000L,
			latestPossibleTimeMs = 1_000L, wallTimeUncertaintyMs = 0L,
			storedZoneId = "UTC", availability = PortableWifiAvailability.AVAILABLE,
			resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
			submittedResultCount = 1, acceptedResultCount = 1, staleResultCount = 0,
			clockUnverifiableResultCount = 0, malformedResultCount = 0,
			observationCount = 1, twoPointFourGhzCount = 1, fiveGhzCount = 0,
			sixGhzCount = 0, otherBandCount = 0, strongestSignalDbm = -60,
			weakestSignalDbm = -60, meanSignalDbm = -60.0,
			sourceQualityFlags = 0L, sourceQualityConfidence = null,
		)
		val run = PortableWifiIntegrity.createRun(runId,
			PortableWifiDeletionScopeDigest("a".repeat(64)), 1_000L, 1_000L,
			listOf("UTC"), PortableWifiCaptureCoverage.WHOLE_RUN,
			PortableWifiRunAvailability.RETAINED,
			PortableWifiAcquisitionCompleteness.COMPLETE, false, false,
			listOf(observation))
		val entry = PortableWifiIntegrity.createEntry(entryId, PortableWifiSessionMode.MANUAL,
			1_000L, 1_000L, listOf(run))
		val checksum = radioChildDigest("wifi", listOf(entryId.value, runId.value, observationId.value),
			listOf(1L, 0, 1L, 1_000L, 0L, "aa:bb", "Cafe", 2_412, -60))
		val child = WifiSessionChildV2(entryId.value, runId.value, observationId.value,
			1L, 0, 1L, 1_000L, 0L, "aa:bb", "Cafe", 2_412, -60, checksum)
		val file = WifiSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = listOf(WifiSessionFileV2(entry, listOf(child))),
			background = null, backgroundChildren = emptyList())
		val bytes = ByteArrayOutputStream().also { codec.writeWifi(it, file) }.toByteArray()
		val decoded = codec.readWifi(ByteArrayInputStream(bytes))
		assertEquals(file, decoded)
		val streamed = ByteArrayOutputStream()
		codec.wifiStreamWriter(streamed, null, null, true).apply {
			session(file.sessions.single())
			finish(null, emptyList(), 0)
		}
		assertEquals(file, codec.readWifi(ByteArrayInputStream(streamed.toByteArray())))
		val corrupted = bytes.toString(Charsets.UTF_8).replace("Cafe", "Other")
		assertThrows(RadioSourceFileFormatException::class.java) {
			runBlocking { codec.readWifi(ByteArrayInputStream(corrupted.toByteArray())) }
		}
	}

	@Test fun `Wi-Fi background keeps a real network child separate from session`() = runBlocking {
		val fact = AmbientWifiPortableIntegrity.createFact(AmbientWifiFact(
			identity = "b".repeat(64), origin = AmbientWifiOrigin.LOCAL_DEVICE,
			coverageStartTimeMs = 1_000L, observedTimeMs = 1_000L,
			latestPossibleTimeMs = 1_000L, structuralEpochDay = 0L,
			storedZoneId = "UTC", coverage = AmbientWifiCoverage.COMPLETE,
			observationCount = 1, twoPointFourGhzCount = 1, fiveGhzCount = 0,
			sixGhzCount = 0, otherBandCount = 0, strongestSignalDbm = -60,
			weakestSignalDbm = -60, meanSignalDbm = -60.0,
		))
		val archive = AmbientWifiPortableIntegrity.createArchive("c".repeat(64),
			listOf(fact), emptyList())
		val child = WifiBackgroundChildV2(fact.identity, 1L, 0, 1L, 1_000L, 0L,
			"aa:bb", "Cafe", 2_412, -60,
			radioChildDigest("wifi", listOf(fact.identity),
				listOf(1L, 0, 1L, 1_000L, 0L, "aa:bb", "Cafe", 2_412, -60)))
		val file = WifiSourceFileV2(selectedFromMs = 0L, selectedToExclusiveMs = 86_400_000L,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = emptyList(), background = archive, backgroundChildren = listOf(child))
		val bytes = ByteArrayOutputStream().also { codec.writeWifi(it, file) }.toByteArray()
		assertEquals(file, codec.readWifi(ByteArrayInputStream(bytes)))
		val streamed = ByteArrayOutputStream()
		codec.wifiStreamWriter(streamed, 0L, 86_400_000L, true)
			.finish(archive, listOf(child), 0)
		assertEquals(file, codec.readWifi(ByteArrayInputStream(streamed.toByteArray())))
	}

	@Test fun `Cell gap-only file round trips without inventing a tower or coordinate`() = runBlocking {
		val gap = AmbientCellPortableIntegrity.createGap(AmbientCellGap(
			identity = "d".repeat(64), origin = AmbientCellOrigin.LOCAL_DEVICE,
			structuralEpochDay = 0L, startTimeMs = 1_000L, endTimeMs = 2_000L,
			storedZoneId = "UTC", reason = "provider unavailable"))
		val archive = AmbientCellPortableIntegrity.createArchive("e".repeat(64),
			emptyList(), listOf(gap))
		val file = CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = emptyList(), background = archive, backgroundChildren = emptyList())
		val bytes = ByteArrayOutputStream().also { codec.writeCell(it, file) }.toByteArray()
		assertEquals(file, codec.readCell(ByteArrayInputStream(bytes)))
		val streamed = ByteArrayOutputStream()
		codec.cellStreamWriter(streamed, null, null, true).finish(archive, emptyList(), 0)
		assertEquals(file, codec.readCell(ByteArrayInputStream(streamed.toByteArray())))
	}

	@Test fun `streaming reader rejects trailing content and oversized scalar before import`() = runBlocking {
		val gap = AmbientCellPortableIntegrity.createGap(AmbientCellGap(
			identity = "d".repeat(64), origin = AmbientCellOrigin.LOCAL_DEVICE,
			structuralEpochDay = 0L, startTimeMs = 1_000L, endTimeMs = 2_000L,
			storedZoneId = "UTC", reason = "provider unavailable"))
		val file = CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = emptyList(),
			background = AmbientCellPortableIntegrity.createArchive("e".repeat(64),
				emptyList(), listOf(gap)), backgroundChildren = emptyList())
		val json = ByteArrayOutputStream().also { codec.writeCell(it, file) }
			.toString(Charsets.UTF_8.name())
		listOf(json + " {}", json.dropLast(1) + ",\"ignored\":\"" + "x".repeat(16_385) + "\"}")
			.forEach { invalid ->
				assertThrows(RadioSourceFileFormatException::class.java) {
					runBlocking { codec.readCell(ByteArrayInputStream(invalid.toByteArray())) }
				}
			}
	}
}
