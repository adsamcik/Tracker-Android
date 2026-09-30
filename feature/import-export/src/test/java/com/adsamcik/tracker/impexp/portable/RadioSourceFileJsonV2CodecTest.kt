package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellFact
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellSubscriptionCompleteness
import com.adsamcik.tracker.stats.api.repository.AmbientCellTechnologyMix
import com.adsamcik.tracker.stats.api.repository.AmbientCellQualityDistribution
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
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.google.gson.JsonParser
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
		// Both a point outside its parent's range and uncertainty extending past it are invalid,
		// even when the detail's own checksum was correctly recomputed by a file editor.
		listOf(999L to 0L, 1_001L to 0L, 1_000L to 1L).forEach { (time, uncertainty) ->
			val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
			val encodedChild = root.getAsJsonArray("sessions")[0].asJsonObject.getAsJsonArray("children")[0].asJsonObject
			encodedChild.addProperty("observedWallTimeMs", time)
			encodedChild.addProperty("wallTimeUncertaintyMs", uncertainty)
			encodedChild.addProperty("contentChecksum", radioChildDigest("wifi",
				listOf(entryId.value, runId.value, observationId.value),
				listOf(1L, 0, 1L, time, uncertainty, "aa:bb", "Cafe", 2_412, -60)))
			assertThrows(RadioSourceFileFormatException::class.java) {
				runBlocking { codec.readWifi(ByteArrayInputStream(root.toString().toByteArray())) }
			}
		}
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
		assertThrows(IllegalArgumentException::class.java) {
			file.copy(backgroundChildren = listOf(child.copy(observedWallTimeMs = 999L,
				contentChecksum = radioChildDigest("wifi", listOf(fact.identity),
					listOf(1L, 0, 1L, 999L, 0L, "aa:bb", "Cafe", 2_412, -60)))))
		}
	}

	@Test fun `Cell background child time and uncertainty stay inside exact parent coverage`() = runBlocking {
		val fact = AmbientCellPortableIntegrity.createFact(AmbientCellFact(
			identity = "f".repeat(64), origin = AmbientCellOrigin.LOCAL_DEVICE,
			coverageStartTimeMs = 900L, observedTimeMs = 1_000L, latestPossibleTimeMs = 1_100L,
			structuralEpochDay = 0L, storedZoneId = "UTC", coverage = AmbientCellCoverage.COMPLETE,
			subscriptionCompleteness = AmbientCellSubscriptionCompleteness.UNKNOWN,
			observationCount = 1, registeredObservationCount = 1,
			technologyMix = AmbientCellTechnologyMix(0, 0, 0, 0, 1, 0),
			qualityDistribution = AmbientCellQualityDistribution(1, 0, 0, 0, 0, 0)))
		val archive = AmbientCellPortableIntegrity.createArchive("e".repeat(64), listOf(fact), emptyList())
		fun child(time: Long, uncertainty: Long) = CellBackgroundChildV2(fact.identity, 1L, 0, 1L,
			time, uncertainty, "tower", "LTE", true, -60,
			radioChildDigest("cell", listOf(fact.identity),
				listOf(1L, 0, 1L, time, uncertainty, "tower", "LTE", true, -60)))
		val file = CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0, sessions = emptyList(),
			background = archive, backgroundChildren = listOf(child(1_000L, 100L)))
		val bytes = ByteArrayOutputStream().also { codec.writeCell(it, file) }.toByteArray()
		assertEquals(file, codec.readCell(ByteArrayInputStream(bytes)))
		listOf("FUTURE_RADIO", "lte", "").forEach { technology ->
			val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
			val edited = root.getAsJsonArray("backgroundChildren")[0].asJsonObject
			edited.addProperty("radioType", technology)
			edited.addProperty("contentChecksum", radioChildDigest("cell", listOf(fact.identity),
				listOf(1L, 0, 1L, 1_000L, 100L, "tower", technology, true, -60)))
			assertThrows(RadioSourceFileFormatException::class.java) {
				runBlocking { codec.readCell(ByteArrayInputStream(root.toString().toByteArray())) }
			}
		}
		listOf(899L to 0L, 1_101L to 0L, 1_000L to 101L).forEach { (time, uncertainty) ->
			assertThrows(IllegalArgumentException::class.java) {
				file.copy(backgroundChildren = listOf(child(time, uncertainty)))
			}
			assertThrows(IllegalArgumentException::class.java) {
				runBlocking { codec.cellStreamWriter(ByteArrayOutputStream(), null, null, true)
					.finish(archive, listOf(child(time, uncertainty)), 0) }
			}
		}
		// A legacy summary with no detailed child rows keeps its existing meaning.
		assertEquals(emptyList<CellBackgroundChildV2>(), file.copy(backgroundChildren = emptyList()).backgroundChildren)
		val twoReadingFact = AmbientCellPortableIntegrity.createFact(AmbientCellFact(
			identity = fact.identity, origin = AmbientCellOrigin.LOCAL_DEVICE,
			coverageStartTimeMs = 900L, observedTimeMs = 1_000L, latestPossibleTimeMs = 1_100L,
			structuralEpochDay = 0L, storedZoneId = "UTC", coverage = AmbientCellCoverage.COMPLETE,
			subscriptionCompleteness = AmbientCellSubscriptionCompleteness.UNKNOWN,
			observationCount = 2, registeredObservationCount = 2,
			technologyMix = AmbientCellTechnologyMix(0, 0, 0, 0, 2, 0),
			qualityDistribution = AmbientCellQualityDistribution(2, 0, 0, 0, 0, 0)))
		assertThrows(IllegalArgumentException::class.java) {
			// A declared detailed group cannot silently contain only half its readings.
			file.copy(background = AmbientCellPortableIntegrity.createArchive(archive.archiveId,
				listOf(twoReadingFact), emptyList()))
		}
	}

	@Test fun `child bounds never wrap a future uncertainty into an earlier timestamp`() {
		assertEquals(false, radioChildFitsCoverage(Long.MAX_VALUE, 1, 0, Long.MAX_VALUE))
		assertEquals(true, radioChildFitsCoverage(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE))
	}

	@Test fun `session and background Cell children use the same six supported technologies as history`() {
		val entry = "a".repeat(64)
		val run = "b".repeat(64)
		val observation = "c".repeat(64)
		fun session(technology: String) = CellSessionChildV2(entry, run, observation, 1L, 0, 1L,
			1_000L, 0L, "tower", technology, true, -60,
			radioChildDigest("cell", listOf(entry, run, observation),
				listOf(1L, 0, 1L, 1_000L, 0L, "tower", technology, true, -60)))
		fun background(technology: String) = CellBackgroundChildV2(observation, 1L, 0, 1L,
			1_000L, 0L, "tower", technology, true, -60,
			radioChildDigest("cell", listOf(observation),
				listOf(1L, 0, 1L, 1_000L, 0L, "tower", technology, true, -60)))
		CellHistoryTechnology.entries.forEach { technology ->
			assertEquals(technology.name, session(technology.name).radioType)
			assertEquals(technology.name, background(technology.name).radioType)
		}
		listOf("FUTURE_RADIO", "lte", "").forEach { technology ->
			assertThrows(IllegalArgumentException::class.java) { session(technology) }
			assertThrows(IllegalArgumentException::class.java) { background(technology) }
		}
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
