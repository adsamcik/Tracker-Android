package com.adsamcik.tracker.osm.intake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PbfResourceLedgerTest {

	@Test
	fun `combined reservation is atomic when one dimension exceeds its bound`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 10L, snapshotDiskBytes = 4L))
		val lease = ledger.openLease()

		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			lease.reserveAll(
				mapOf(
					PbfResource.SOURCE_BYTES to 4L,
					PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES to 5L,
				),
			)
		}

		failure.resource shouldBe PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES
		failure.limit shouldBe 4L
		failure.requested shouldBe 5L
		failure.isPermanent shouldBe true
		ledger.used(PbfResource.SOURCE_BYTES) shouldBe 0L
		ledger.used(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe 0L
		lease.reserved(PbfResource.SOURCE_BYTES) shouldBe 0L
		lease.reserved(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe 0L
	}

	@Test
	fun `leases hold resources until explicitly released or closed`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 12L, snapshotDiskBytes = 12L))
		val lease = ledger.openLease()

		lease.reserveAll(
			mapOf(
				PbfResource.SOURCE_BYTES to 8L,
				PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES to 8L,
			),
		)
		lease.release(PbfResource.SOURCE_BYTES, 3L)

		lease.reserved(PbfResource.SOURCE_BYTES) shouldBe 5L
		ledger.used(PbfResource.SOURCE_BYTES) shouldBe 5L
		ledger.remaining(PbfResource.SOURCE_BYTES) shouldBe 7L
		ledger.used(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe 8L

		lease.close()

		PbfResource.entries.forEach { resource -> ledger.used(resource) shouldBe 0L }
	}

	@Test
	fun `arithmetic overflow is a typed permanent resource failure`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = Long.MAX_VALUE))
		val lease = ledger.openLease()
		lease.reserve(PbfResource.SOURCE_BYTES, Long.MAX_VALUE)

		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			lease.reserve(PbfResource.SOURCE_BYTES, 1L)
		}

		failure.resource shouldBe PbfResource.SOURCE_BYTES
		failure.code shouldBe PbfIntakeFailureCode.RESOURCE_LIMIT_EXCEEDED
		failure.isPermanent shouldBe true
		ledger.used(PbfResource.SOURCE_BYTES) shouldBe Long.MAX_VALUE
		lease.close()
	}

	@Test
	fun `negative limits and reservation amounts fail before accounting changes`() {
		shouldThrow<IllegalArgumentException> {
			limits(sourceBytes = -1L)
		}

		val ledger = PbfResourceLedger(limits())
		val lease = ledger.openLease()
		shouldThrow<IllegalArgumentException> {
			lease.reserve(PbfResource.WORK_UNITS, -1L)
		}
		ledger.used(PbfResource.WORK_UNITS) shouldBe 0L
	}

	private fun limits(
		sourceBytes: Long = 1_024L,
		snapshotDiskBytes: Long = 1_024L,
		managedMemoryBytes: Long = 1_024L,
		retainedGraphBytes: Long = 1_024L,
		outputBytes: Long = 1_024L,
		workUnits: Long = 1_024L,
	): PbfResourceLimits = PbfResourceLimits(
		sourceBytes = sourceBytes,
		privateSnapshotDiskBytes = snapshotDiskBytes,
		managedMemoryBytes = managedMemoryBytes,
		retainedGraphBytes = retainedGraphBytes,
		outputBytes = outputBytes,
		workUnits = workUnits,
	)
}
