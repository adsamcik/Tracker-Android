package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableReadFailure
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableSnapshot
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RoomExportPortableAmbientStepsTest {
	@Test
	fun `unverifiable snapshot never reaches the artifact sink`() = runTest {
		var emitted = false
		val exporter = RoomExportPortableAmbientSteps(
			AmbientStepsPortableSnapshotSource {
				AmbientStepsPortableSnapshot.Unverifiable(
					AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
				)
			},
			StandardTestDispatcher(testScheduler),
		)

		val result = exporter.export(REQUEST) { emitted = true }

		result shouldBe ExportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE,
		)
		emitted shouldBe false
	}

	@Test
	fun `complete snapshot reaches the sink only after its reader returns`() = runTest {
		var readerActive = false
		var emitted: PortableAmbientStepsArchiveV1? = null
		val archive = archive()
		val exporter = RoomExportPortableAmbientSteps(
			AmbientStepsPortableSnapshotSource {
				readerActive = true
				try {
					AmbientStepsPortableSnapshot.Ready(archive)
				} finally {
					readerActive = false
				}
			},
			StandardTestDispatcher(testScheduler),
		)

		val result = exporter.export(
			REQUEST,
			PortableAmbientStepsArchiveSink { value ->
				readerActive shouldBe false
				emitted = value
			},
		)

		result shouldBe ExportPortableAmbientStepsResult.Exported(1, 1, 0)
		emitted shouldBe archive
	}

	private fun archive(): PortableAmbientStepsArchiveV1 {
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"local-fact",
			),
			0L,
			DAY_END,
			5L,
		)
		return PortableAmbientStepsArchiveV1.create(
			listOf(
				PortableAmbientStepsDayV1.create(
					identity = AmbientStepsPortableOpaqueIdentity.derive(
						AmbientStepsPortableIdentityKind.DAY,
						"local-day",
					),
					structuralEpochDay = 0L,
					storedZoneId = "UTC",
					structuralDayStartTimeMs = 0L,
					structuralDayEndTimeMs = DAY_END,
					retainedFromTimeMs = null,
					coverage = PortableAmbientStepsCoverage.COMPLETE,
					partialCauses = emptyList(),
					retainedStepCount = 5L,
					facts = listOf(fact),
					gaps = emptyList(),
				),
			),
		)
	}

	private companion object {
		val REQUEST = ExportPortableAmbientStepsRequest(0L, 1L)
		const val DAY_END = 86_400_000L
	}
}
