package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.CellCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
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
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.DateTimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSourceFileSerializationCompatibilityTest {
	private val codec = RadioSourceFileJsonV2Codec()
	private val legacy = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

	@Test fun wifiLegacyVersionsAndBothWritersPreserveOwnerAndChecksums() = runBlocking {
		val first = wifiFile("first")
		val complete = first.copy(sessions = first.sessions + wifiFile("second").sessions)
		for (version in listOf(2, 3)) {
			val file = complete.copy(schemaVersion = version)
			val oldJson = legacy.toJsonTree(file).asJsonObject
			assertTrue(owner(oldJson).asJsonPrimitive.isString)
			assertEquals(file, codec.readWifi(ByteArrayInputStream(oldJson.toString().toByteArray())))
			val output = ByteArrayOutputStream().also { codec.writeWifi(it, file) }
			assertEquals(oldJson, JsonParser.parseString(output.toString(Charsets.UTF_8.name())))
		}
		val streamed = ByteArrayOutputStream()
		codec.wifiStreamWriter(streamed, null, null, true).apply {
			complete.sessions.forEach(::session)
			finish(null, emptyList(), 0)
		}
		assertEquals(legacy.toJsonTree(complete),
			JsonParser.parseString(streamed.toString(Charsets.UTF_8.name())))
		assertEquals(complete, codec.readWifi(ByteArrayInputStream(streamed.toByteArray())))
	}

	@Test fun cellLegacyVersionsAndBothWritersPreserveOwnerAndChecksums() = runBlocking {
		val first = cellFile("first")
		val complete = first.copy(sessions = first.sessions + cellFile("second").sessions)
		for (version in listOf(2, 3)) {
			val file = complete.copy(schemaVersion = version)
			val oldJson = legacy.toJsonTree(file).asJsonObject
			assertTrue(owner(oldJson).asJsonPrimitive.isString)
			assertEquals(file, codec.readCell(ByteArrayInputStream(oldJson.toString().toByteArray())))
			val output = ByteArrayOutputStream().also { codec.writeCell(it, file) }
			assertEquals(oldJson, JsonParser.parseString(output.toString(Charsets.UTF_8.name())))
		}
		val streamed = ByteArrayOutputStream()
		codec.cellStreamWriter(streamed, null, null, true).apply {
			complete.sessions.forEach(::session)
			finish(null, emptyList(), 0)
		}
		assertEquals(legacy.toJsonTree(complete),
			JsonParser.parseString(streamed.toString(Charsets.UTF_8.name())))
		assertEquals(complete, codec.readCell(ByteArrayInputStream(streamed.toByteArray())))
	}

	@Test fun typedDecoderRejectsMissingTagsInvalidValuesAndExcessiveNesting() = runBlocking {
		for (wifi in listOf(true, false)) {
			val valid = if (wifi) { legacy.toJsonTree(wifiFile("invalid")) }
				else { legacy.toJsonTree(cellFile("invalid")) }
			val invalid = invalidRadioDocuments(valid)
			for (document in invalid) {
				assertThrows(RadioSourceFileFormatException::class.java) {
					runBlocking { read(wifi, ByteArrayInputStream(document.toString().toByteArray())) }
				}
			}
			val tooDeep = valid.toString().dropLast(1) + ",\"ignored\":" +
				"[".repeat(33) + "0" + "]".repeat(33) + "}"
			assertThrows(RadioSourceFileFormatException::class.java) {
				runBlocking { read(wifi, ByteArrayInputStream(tooDeep.toByteArray())) }
			}
		}
	}

	@Test fun inputValidationLikeFailuresKeepTheirExactSourceIdentity() = runBlocking {
		val failures = listOf(
			IllegalArgumentException("source argument"),
			ArithmeticException("source arithmetic"),
			DateTimeException("source clock"),
			SerializationException("source serializer"),
			IllegalStateException("source state"),
		)
		for (wifi in listOf(true, false)) {
			for (expected in failures) {
				val input = object : ByteArrayInputStream(byteArrayOf()) {
					override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw expected
				}
				val actual = assertThrows(expected.javaClass) {
					runBlocking { read(wifi, input) }
				}
				assertSame(expected, actual)
			}
		}
	}

	@Test fun wholeAndStreamingWritersKeepExactTransportAndCancellationFailures() = runBlocking {
		for (wifi in listOf(true, false)) {
			for (streaming in listOf(true, false)) {
				for (expected in listOf(IOException("destination full"), CancellationException("export stopped"))) {
					var failWrites = !streaming
					val output = object : OutputStream() {
						override fun write(value: Int) {
							if (failWrites) { throw expected }
						}
						override fun write(buffer: ByteArray, offset: Int, length: Int) {
							if (failWrites) { throw expected }
						}
					}
					val actual = assertThrows(expected.javaClass) {
						runBlocking {
							if (wifi && streaming) {
								val writer = codec.wifiStreamWriter(output, null, null, true)
								failWrites = true
								writer.session(wifiFile("output").sessions.single())
							} else if (wifi) {
								codec.writeWifi(output, wifiFile("output"))
							} else if (streaming) {
								val writer = codec.cellStreamWriter(output, null, null, true)
								failWrites = true
								writer.session(cellFile("output").sessions.single())
							} else {
								codec.writeCell(output, cellFile("output"))
							}
						}
					}
					assertSame(expected, actual)
				}
			}
		}
	}

	private fun invalidRadioDocuments(valid: JsonElement): List<JsonObject> = listOf(
		valid.deepCopy().asJsonObject.apply { remove("format") },
		valid.deepCopy().asJsonObject.apply { remove("schemaVersion") },
		valid.deepCopy().asJsonObject.apply { addProperty("schemaVersion", 99) },
		valid.deepCopy().asJsonObject.apply { addProperty("observationsOutsideDates", -1) },
		valid.deepCopy().asJsonObject.apply {
			val actualOwner = owner(this)
			val boxedOwner = JsonObject().apply { add("value", actualOwner) }
			observation(this).add("aggregateOwnerIdentity", boxedOwner)
		},
		valid.deepCopy().asJsonObject.apply {
			getAsJsonArray("sessions")[0].asJsonObject.getAsJsonObject("entry").remove("format")
		},
		valid.deepCopy().asJsonObject.apply {
			observation(this).addProperty("storedZoneId", "Not/AZone")
		},
	)

	private suspend fun read(wifi: Boolean, input: ByteArrayInputStream) {
		if (wifi) { codec.readWifi(input) } else { codec.readCell(input) }
	}

	private fun observation(root: JsonObject) = root.getAsJsonArray("sessions")[0].asJsonObject
		.getAsJsonObject("entry").getAsJsonArray("runs")[0].asJsonObject
		.getAsJsonArray("observations")[1].asJsonObject

	private fun owner(root: JsonObject) = observation(root)["aggregateOwnerIdentity"]

	private fun wifiFile(seed: String): WifiSourceFileV2 {
		val entryId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, seed)
		val runId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN, seed)
		val ownerId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, "$seed-owner")
		val dependentId = PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, "$seed-child")
		fun observation(time: Long, dependent: Boolean) = PortableWifiIntegrity.createObservation(
			identity = if (dependent) { dependentId } else { ownerId },
			semanticRevision = 1L, supersedesSemanticRevision = null,
			aggregateOwnerIdentity = ownerId.takeIf { dependent },
			aggregateOwnerSemanticRevision = 1L.takeIf { dependent },
			coverageStartTimeMs = time, observedTimeMs = time, latestPossibleTimeMs = time,
			wallTimeUncertaintyMs = 0L, storedZoneId = "UTC", availability = PortableWifiAvailability.AVAILABLE,
			resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
			submittedResultCount = 1, acceptedResultCount = 1, staleResultCount = 0,
			clockUnverifiableResultCount = 0, malformedResultCount = 0, observationCount = 1,
			twoPointFourGhzCount = 1, fiveGhzCount = 0, sixGhzCount = 0, otherBandCount = 0,
			strongestSignalDbm = -60, weakestSignalDbm = -60, meanSignalDbm = -60.0,
			sourceQualityFlags = 0L, sourceQualityConfidence = null,
		)
		val run = PortableWifiIntegrity.createRun(runId, PortableWifiDeletionScopeDigest("a".repeat(64)),
			1_000L, 2_000L, listOf("UTC"), PortableWifiCaptureCoverage.WHOLE_RUN,
			PortableWifiRunAvailability.RETAINED, PortableWifiAcquisitionCompleteness.COMPLETE, false, false,
			listOf(observation(1_000L, false), observation(2_000L, true)))
		val entry = PortableWifiIntegrity.createEntry(entryId, PortableWifiSessionMode.MANUAL,
			1_000L, 2_000L, listOf(run))
		return WifiSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = listOf(WifiSessionFileV2(entry, emptyList())), background = null,
			backgroundChildren = emptyList())
	}

	/** Gson builds only the legacy fixture; copy invokes the real checksum-validating constructors. */
	private fun cellFile(seed: String): CellSourceFileV2 {
		fun id(kind: PortableCellIdentityKind, suffix: String = seed) =
			PortableCellOpaqueIdentity.derive(kind, suffix).value
		val emptyChecksum = "0".repeat(64)
		fun observation(time: Long, dependent: Boolean): PortableCapturedCellObservationV1 {
			val identity = id(PortableCellIdentityKind.OBSERVATION, if (dependent) { "$seed-child" } else { "$seed-owner" })
			val ownerId = id(PortableCellIdentityKind.OBSERVATION, "$seed-owner")
			val owner = if (dependent) { "\"$ownerId\"" } else { "null" }
			val ownerRevision = if (dependent) { "1" } else { "null" }
			val raw = """{
				"identity":"$identity","semanticRevision":1,"supersedesSemanticRevision":null,
				"aggregateOwnerIdentity":$owner,"aggregateOwnerSemanticRevision":$ownerRevision,
				"contentChecksum":"$emptyChecksum","coverageStartTimeMs":$time,"observedTimeMs":$time,
				"latestPossibleTimeMs":$time,"wallTimeUncertaintyMs":0,"storedZoneId":"UTC",
				"childCompleteness":"COMPLETE","subscriptionGrouping":"UNKNOWN",
				"submittedChildCount":1,"acceptedChildCount":1,"staleChildCount":0,"futureTimeChildCount":0,
				"missingTimeChildCount":0,"clockUnverifiableChildCount":0,"authorityMismatchChildCount":0,
				"unsupportedTechnologyChildCount":0,"observationCount":1,"registeredObservationCount":1,
				"gsmCount":0,"cdmaCount":0,"wcdmaCount":0,"tdscdmaCount":0,"lteCount":1,"nrCount":0,
				"qualityUnknownCount":1,"qualityNoneOrUnknownCount":0,"qualityPoorCount":0,
				"qualityModerateCount":0,"qualityGoodCount":0,"qualityGreatCount":0,
				"weakObservationCount":0,"knownQualityObservationCount":0,"allKnownQualityIsWeak":false,
				"qualityFlags":0,"qualityConfidence":null
			}"""
			val draft = legacy.fromJson(raw, PortableCapturedCellObservationV1::class.java)
			return draft.copy(contentChecksum = CellCapturedPortableIntegrity.observationChecksum(draft))
		}
		val observations = legacy.toJson(listOf(observation(1_000L, false), observation(2_000L, true)))
		val runId = id(PortableCellIdentityKind.PHYSICAL_RUN)
		val runJson = """{"identity":"$runId",
			"deletionScopeDigest":"$emptyChecksum","contentChecksum":"$emptyChecksum",
			"startTimeMs":1000,"endTimeMs":2000,"captureCoverage":"WHOLE_RUN","availability":"RETAINED",
			"acquisitionCompleteness":"COMPLETE","retentionLoss":false,"subscriptionGrouping":"UNKNOWN",
			"observations":$observations}"""
		val rawRun = legacy.fromJson(runJson, PortableCapturedCellRunV1::class.java)
		val run = rawRun.copy(contentChecksum = CellCapturedPortableIntegrity.runChecksum(rawRun))
		val entryId = id(PortableCellIdentityKind.LOGICAL_ENTRY)
		val runs = legacy.toJson(listOf(run))
		val entryJson = """{"format":"tracker-portable-captured-cell","schemaVersion":1,
			"identity":"$entryId","contentChecksum":"$emptyChecksum",
			"sessionMode":"MANUAL","startTimeMs":1000,"endTimeMs":2000,"subscriptionGrouping":"UNKNOWN",
			"runs":$runs}"""
		val rawEntry = legacy.fromJson(entryJson, PortableCapturedCellEntryV1::class.java)
		val entry = rawEntry.copy(contentChecksum = CellCapturedPortableIntegrity.entryChecksum(rawEntry))
		return CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = listOf(CellSessionFileV2(entry, emptyList())), background = null,
			backgroundChildren = emptyList())
	}
}
