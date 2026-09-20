package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortableCountDomainJsonV2CodecTest {
	@Test
	fun `steps v2 round trip preserves product and canonical source graph`() = runTest {
		val archive = PortableStepsArchiveV2.create(
			listOf(stepsEntry().withExplicitUnprovenCountDomain()),
		)
		val output = ByteArrayOutputStream()

		PortableStepsJsonV2Codec().encode(output) { sink ->
			sink.emit(archive)
			ExportPortableStepsResult.Exported(1)
		}
		val bytes = output.toByteArray()
		val decoded = PortableStepsJsonV2Codec().decode(bytes)

		decoded.archive shouldBe archive
		decoded.metadata.archiveContentChecksum shouldBe archive.contentChecksum
		portableStepsSchemaVersion(bytes) shouldBe 2
	}

	@Test
	fun `ambient v2 round trip preserves day graph and source identity`() = runTest {
		val v1 = ambientArchive(completeAmbientDay(LocalDate.of(2026, 1, 1), 7L))
		val archive = PortableAmbientStepsArchiveV2.create(
			v1.days.map { it.withExplicitUnprovenCountDomain() },
		)
		val output = ByteArrayOutputStream()

		PortableAmbientStepsJsonV2Codec().encode(output) { sink ->
			sink.emit(archive)
			ExportPortableAmbientStepsResult.Exported(1, 1, 0)
		}
		val bytes = output.toByteArray()
		val decoded = PortableAmbientStepsJsonV2Codec().decode(bytes)

		decoded.archive shouldBe archive
		decoded.archive.identity shouldBe archive.identity
		portableAmbientStepsSchemaVersion(bytes) shouldBe 2
	}

	@Test
	fun `byte array dispatch distinguishes v1 and v2 Steps and Ambient archives`() = runTest {
		val stepsV1 = ByteArrayOutputStream().also { output ->
			PortableStepsJsonV1Codec().encode(output) { sink ->
				sink.emit(stepsEntry())
				ExportPortableStepsResult.Exported(1)
			}
		}.toByteArray()
		val stepsV2 = ByteArrayOutputStream().also { output ->
			PortableStepsJsonV2Codec().encode(output) { sink ->
				sink.emit(
					PortableStepsArchiveV2.create(
						listOf(stepsEntry().withExplicitUnprovenCountDomain()),
					),
				)
				ExportPortableStepsResult.Exported(1)
			}
		}.toByteArray()
		val ambientV1Archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 7L),
		)
		val ambientV1 = encodeAmbientStepsArchive(ambientV1Archive)
		val ambientV2 = ByteArrayOutputStream().also { output ->
			PortableAmbientStepsJsonV2Codec().encode(output) { sink ->
				sink.emit(
					PortableAmbientStepsArchiveV2.create(
						ambientV1Archive.days.map { it.withExplicitUnprovenCountDomain() },
					),
				)
				ExportPortableAmbientStepsResult.Exported(1, 1, 0)
			}
		}.toByteArray()

		portableStepsSchemaVersion(stepsV1) shouldBe 1
		portableStepsSchemaVersion(stepsV2) shouldBe 2
		portableAmbientStepsSchemaVersion(ambientV1) shouldBe 1
		portableAmbientStepsSchemaVersion(ambientV2) shouldBe 2
	}

	@Test
	fun `unknown version field and graph corruption fail closed`() = runTest {
		val archive = PortableStepsArchiveV2.create(
			listOf(stepsEntry().withExplicitUnprovenCountDomain()),
		)
		val output = ByteArrayOutputStream()
		PortableStepsJsonV2Codec().encode(output) { sink ->
			sink.emit(archive)
			ExportPortableStepsResult.Exported(1)
		}
		val original = output.toString(Charsets.UTF_8.name())

		shouldThrow<PortableStepsJsonException> {
			portableStepsSchemaVersion(
				original.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":3")
					.encodeToByteArray(),
			)
		}
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV2Codec().decode(
				original.replaceFirst(
					"\"ownerEffectChecksum\":\"",
					"\"unexpected\":true,\"ownerEffectChecksum\":\"",
				).encodeToByteArray(),
			)
		}
	}

	@Test
	fun `version dispatch bounds field names and nesting before skipping unknown values`() {
		val longName = "n".repeat(385)
		val deepValue = "[".repeat(40) + "0" + "]".repeat(40)
		listOf(
			"""{"format":"tracker-portable-steps","schemaVersion":1,"$longName":true}""",
			"""{"format":"tracker-portable-steps","schemaVersion":1,"unknown":$deepValue}""",
		).forEach { document ->
			shouldThrow<PortableStepsJsonException> {
				portableStepsSchemaVersion(document.encodeToByteArray())
			}
		}
		listOf(
			"""{"format":"tracker-portable-ambient-steps","schemaVersion":1,"$longName":true}""",
			"""{"format":"tracker-portable-ambient-steps","schemaVersion":1,"unknown":$deepValue}""",
		).forEach { document ->
			shouldThrow<PortableAmbientStepsFormatException> {
				portableAmbientStepsSchemaVersion(document.encodeToByteArray())
			}
		}
	}

	@Test
	fun `v2 lexical and parser failures are permanent while source IO remains retryable`() =
		runTest {
			val longName = "n".repeat(385)
			shouldThrow<PortableStepsJsonException> {
				PortableStepsJsonV2Codec().decode(
					"""{"format":"tracker-portable-steps","schemaVersion":2,"$longName":true}"""
						.encodeToByteArray(),
				)
			}
			shouldThrow<PortableStepsJsonException> {
				PortableStepsJsonV2Codec().decode(
					"""{"format":"tracker-portable-steps","schemaVersion":2,"entries":[}"""
						.encodeToByteArray(),
				)
			}
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV2Codec().decode(
					"""{"format":"tracker-portable-ambient-steps","schemaVersion":2,"days":[}"""
						.encodeToByteArray(),
				)
			}

			val stepsIo = IOException("steps transport")
			val ambientIo = IOException("ambient transport")
			(shouldThrow<IOException> {
				PortableStepsJsonV2Codec().decode(FailingInputStream(stepsIo))
			} === stepsIo) shouldBe true
			(shouldThrow<IOException> {
				PortableAmbientStepsJsonV2Codec().decode(FailingInputStream(ambientIo))
			} === ambientIo) shouldBe true
		}

	@Test
	fun `non-successful source creates no bytes`() = runTest {
		val steps = ByteArrayOutputStream()
		PortableStepsJsonV2Codec().encode(steps) {
			ExportPortableStepsResult.NoEntries
		}
		steps.size() shouldBe 0

		val ambient = ByteArrayOutputStream()
		PortableAmbientStepsJsonV2Codec().encode(ambient) {
			ExportPortableAmbientStepsResult.NoData
		}
		ambient.size() shouldBe 0
	}

	private fun stepsEntry(): PortableStepsEntryV1 {
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("entry", "run"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			storedZoneId = "UTC",
			manifests = listOf(
				PortableStepsManifestV1(1L, 1_000L, 1L, 1L),
			),
			completeness = PortableStepsCompletenessV1(
				PortableStepsCaptureCoverage.WHOLE_RUN,
				PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = listOf(
				PortableStepsFactV1.create(
					identity(PortableStepsIdentityKind.FACT, "fact"),
					1L,
					1_000L,
					2_000L,
					0L,
					PortableStepsFactCoverage.COVERED,
					12L,
				),
			),
		)
		return PortableStepsEntryV1.create(
			identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "entry"),
			PortableStepsSessionMode.MANUAL,
			1_000L,
			2_000L,
			listOf(run),
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, value: String) =
		PortableStepsOpaqueIdentity.derive(kind, value)

	private class FailingInputStream(
		private val failure: IOException,
	) : InputStream() {
		override fun read(): Int = throw failure
		override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
	}
}
