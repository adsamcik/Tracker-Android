package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.identity
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class AmbientStepsPortableImportContractTest {
	@Test
	fun `receipt fields and encoded bytes are bounded before Room admission`() {
		shouldThrow<IllegalArgumentException> {
			PortableAmbientStepsImportReceipt(
				jobId = "x".repeat(AmbientStepsPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH + 1),
				archiveKey = "entry",
				sourceName = "backup.trackerambientsteps",
				receivedAtMs = 1L,
			)
		}

		shouldThrow<IllegalArgumentException> {
			PortableAmbientStepsImportMetadata(
				encodedByteCount = AmbientStepsPortableFormatV1.MAX_FILE_BYTES + 1L,
				archiveContentChecksum = TestAmbientStepsArchive.archive.contentChecksum,
				dayCount = 1,
				factCount = 1,
				gapCount = 0,
			)
		}
		shouldThrow<IllegalArgumentException> {
			PortableAmbientStepsImportMetadata(
				encodedByteCount = 1L,
				archiveContentChecksum = TestAmbientStepsArchive.archive.contentChecksum,
				dayCount = 1,
				factCount = AmbientStepsPortableFormatV1.MAX_FACTS + 1,
				gapCount = 0,
			)
		}
	}

	@Test
	fun `successful and duplicate outcomes cannot claim empty archives`() {
		shouldThrow<IllegalArgumentException> {
			ImportPortableAmbientStepsResult.Applied(
				TestAmbientStepsArchive.archive.identity,
				appendedDayRevisionCount = 0,
				dayCount = 0,
				factCount = 1,
				gapCount = 0,
			)
		}
		shouldThrow<IllegalArgumentException> {
			ImportPortableAmbientStepsResult.Duplicate(
				TestAmbientStepsArchive.archive.identity,
				dayCount = 0,
			)
		}
	}
}

private object TestAmbientStepsArchive {
	private const val DAY_END = 86_400_000L
	private val fact = PortableAmbientStepsFactV1.create(
		AmbientStepsPortableOpaqueIdentity.derive(AmbientStepsPortableIdentityKind.FACT, "fact"),
		0L,
		DAY_END,
		0L,
	)
	val archive = PortableAmbientStepsArchiveV1.create(
		listOf(
			PortableAmbientStepsDayV1.create(
				identity = AmbientStepsPortableOpaqueIdentity.derive(
					AmbientStepsPortableIdentityKind.DAY,
					"0|UTC|0|$DAY_END",
				),
				structuralEpochDay = 0L,
				storedZoneId = "UTC",
				structuralDayStartTimeMs = 0L,
				structuralDayEndTimeMs = DAY_END,
				retainedFromTimeMs = null,
				coverage = PortableAmbientStepsCoverage.COMPLETE,
				partialCauses = emptyList(),
				retainedStepCount = 0L,
				facts = listOf(fact),
				gaps = emptyList(),
			),
		),
	)
}
