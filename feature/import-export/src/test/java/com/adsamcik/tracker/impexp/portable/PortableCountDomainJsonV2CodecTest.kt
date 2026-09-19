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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
		val decoded = PortableStepsJsonV2Codec().decode(
			ByteArrayInputStream(output.toByteArray()),
		)

		decoded.archive shouldBe archive
		decoded.metadata.archiveContentChecksum shouldBe archive.contentChecksum
		portableStepsSchemaVersion(output.toByteArray()) shouldBe 2
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
		val decoded = PortableAmbientStepsJsonV2Codec().decode(
			ByteArrayInputStream(output.toByteArray()),
		)

		decoded.archive shouldBe archive
		decoded.archive.identity shouldBe archive.identity
		portableAmbientStepsSchemaVersion(output.toByteArray()) shouldBe 2
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
				ByteArrayInputStream(
					original.replaceFirst(
						"\"ownerEffectChecksum\":\"",
						"\"unexpected\":true,\"ownerEffectChecksum\":\"",
					).encodeToByteArray(),
				),
			)
		}
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
}
