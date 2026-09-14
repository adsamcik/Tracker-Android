package com.adsamcik.tracker.stats.api.repository

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class PressurePortableImportContractTest {
	@Test
	fun `receipt provenance is explicit and bounded`() {
		shouldThrow<IllegalArgumentException> { receipt(jobId = "") }
		shouldThrow<IllegalArgumentException> {
			receipt(sourceName = "x".repeat(PressurePortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH + 1))
		}
		shouldThrow<IllegalArgumentException> { receipt(receivedAtMs = -1L) }
	}

	@Test
	fun `request requires an explicit nonnegative local epoch`() {
		shouldThrow<IllegalArgumentException> {
			ImportPortablePressureRequest(entry(), receipt(), -1L)
		}
	}

	@Test
	fun `successful import outcomes cannot claim invalid revision or counts`() {
		shouldThrow<IllegalArgumentException> {
			ImportPortablePressureResult.Applied(0L, 1, 1)
		}
		shouldThrow<IllegalArgumentException> {
			ImportPortablePressureResult.Applied(1L, 0, 1)
		}
		shouldThrow<IllegalArgumentException> {
			ImportPortablePressureResult.Applied(1L, 1, -1)
		}
		shouldThrow<IllegalArgumentException> {
			ImportPortablePressureResult.Duplicate(0L)
		}
	}

	private fun receipt(
		jobId: String = "job-1",
		entryKey: String = "entry-1",
		sourceName: String = "backup.trackerpressure",
		receivedAtMs: Long = 30L,
	) = PortablePressureImportReceipt(jobId, entryKey, sourceName, receivedAtMs)

	private fun entry(): PortablePressureEntryV1 {
		val run = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "run"),
			startTimeMs = 10L,
			endTimeMs = 20L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "entry"),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun identity(kind: PortablePressureIdentityKind, local: String) =
		PortablePressureOpaqueIdentity.derive(kind, local)
}
