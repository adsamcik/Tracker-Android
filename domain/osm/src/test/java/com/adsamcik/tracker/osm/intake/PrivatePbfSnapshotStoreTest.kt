package com.adsamcik.tracker.osm.intake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

class PrivatePbfSnapshotStoreTest {

	@TempDir
	lateinit var temporaryDirectory: Path

	@Test
	fun `copies source once then serves repeatable streams only from private storage`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 32L, snapshotDiskBytes = 32L))
		val root = temporaryDirectory.resolve("private-pbf").toFile()
		val store = PrivatePbfSnapshotStore(root, ledger, bufferSizeBytes = 8)
		val expected = byteArrayOf(4, 8, 15, 16, 23, 42)
		var sourceOpenCount = 0

		val snapshot = store.create {
			sourceOpenCount++
			ByteArrayInputStream(expected)
		}

		snapshot.byteCount shouldBe expected.size.toLong()
		sourceOpenCount shouldBe 1
		snapshot.openInputStream().use { it.readBytes().toList() } shouldBe expected.toList()
		snapshot.openInputStream().use { it.readBytes().toList() } shouldBe expected.toList()
		sourceOpenCount shouldBe 1
		ledger.used(PbfResource.SOURCE_BYTES) shouldBe expected.size.toLong()
		ledger.used(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe expected.size.toLong()

		snapshot.close()

		PbfResource.entries.forEach { resource -> ledger.used(resource) shouldBe 0L }
		filesIn(root) shouldBe emptyList()
	}

	@Test
	fun `accepts the exact byte boundary and rejects the first byte beyond it without a partial file`() {
		val exactLedger = PbfResourceLedger(limits(sourceBytes = 4L, snapshotDiskBytes = 4L))
		val exactRoot = temporaryDirectory.resolve("exact").toFile()
		val exactSnapshot = PrivatePbfSnapshotStore(exactRoot, exactLedger, bufferSizeBytes = 8)
			.create { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
		exactSnapshot.byteCount shouldBe 4L
		exactSnapshot.close()

		val oversizeLedger = PbfResourceLedger(limits(sourceBytes = 4L, snapshotDiskBytes = 4L))
		val oversizeRoot = temporaryDirectory.resolve("oversize").toFile()
		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			PrivatePbfSnapshotStore(oversizeRoot, oversizeLedger, bufferSizeBytes = 8).create {
				ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
			}
		}

		failure.resource shouldBe PbfResource.SOURCE_BYTES
		failure.limit shouldBe 4L
		filesIn(oversizeRoot) shouldBe emptyList()
		PbfResource.entries.forEach { resource -> oversizeLedger.used(resource) shouldBe 0L }
	}

	@Test
	fun `a tighter disk limit rejects before the source bytes can be written`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 10L, snapshotDiskBytes = 4L))
		val root = temporaryDirectory.resolve("disk-limit").toFile()

		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			PrivatePbfSnapshotStore(root, ledger, bufferSizeBytes = 8).create {
				ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
			}
		}

		failure.resource shouldBe PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES
		failure.limit shouldBe 4L
		filesIn(root) shouldBe emptyList()
		ledger.used(PbfResource.SOURCE_BYTES) shouldBe 0L
		ledger.used(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe 0L
	}

	@Test
	fun `fixed buffer memory is reserved before source access or private file creation`() {
		val ledger = PbfResourceLedger(limits(managedMemoryBytes = 7L))
		val root = temporaryDirectory.resolve("memory-preflight").toFile()
		var sourceOpenCount = 0

		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			PrivatePbfSnapshotStore(root, ledger, bufferSizeBytes = 8).create {
				sourceOpenCount++
				ByteArrayInputStream(byteArrayOf(1))
			}
		}

		failure.resource shouldBe PbfResource.MANAGED_MEMORY_BYTES
		sourceOpenCount shouldBe 0
		root.exists() shouldBe false
		ledger.used(PbfResource.MANAGED_MEMORY_BYTES) shouldBe 0L
	}

	@Test
	fun `source copy failure is redacted and removes both partial bytes and reservations`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 16L, snapshotDiskBytes = 16L))
		val root = temporaryDirectory.resolve("failing-source").toFile()
		val sensitiveSourceText = "content://private-provider/secret-route"

		val failure = shouldThrow<PbfIntakeFailure.SnapshotIoFailed> {
			PrivatePbfSnapshotStore(root, ledger, bufferSizeBytes = 8).create {
				FailAfterOneChunkInputStream(sensitiveSourceText)
			}
		}

		failure.isPermanent shouldBe true
		failure.code shouldBe PbfIntakeFailureCode.SNAPSHOT_IO_FAILED
		requireNotNull(failure.message) shouldNotContain sensitiveSourceText
		filesIn(root) shouldBe emptyList()
		PbfResource.entries.forEach { resource -> ledger.used(resource) shouldBe 0L }
	}

	@Test
	fun `invalid private directory fails before opening the selected source`() {
		val invalidRoot = temporaryDirectory.resolve("not-a-directory").toFile()
		invalidRoot.writeBytes(byteArrayOf(1))
		var sourceOpenCount = 0

		val failure = shouldThrow<PbfIntakeFailure.InvalidPrivateSnapshotDirectory> {
			PrivatePbfSnapshotStore(invalidRoot, PbfResourceLedger(limits()), bufferSizeBytes = 8).create {
				sourceOpenCount++
				ByteArrayInputStream(byteArrayOf(1))
			}
		}

		failure.isPermanent shouldBe true
		sourceOpenCount shouldBe 0
	}

	@Test
	fun `a live snapshot keeps its disk reservation so another snapshot cannot over-book it`() {
		val ledger = PbfResourceLedger(limits(sourceBytes = 20L, snapshotDiskBytes = 8L))
		val root = temporaryDirectory.resolve("shared-ledger").toFile()
		val store = PrivatePbfSnapshotStore(root, ledger, bufferSizeBytes = 8)
		val first = store.create { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }

		val failure = shouldThrow<PbfIntakeFailure.ResourceLimitExceeded> {
			store.create { ByteArrayInputStream(byteArrayOf(5, 6, 7, 8, 9)) }
		}

		failure.resource shouldBe PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES
		ledger.used(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES) shouldBe 4L
		first.openInputStream().use { it.readBytes().toList() } shouldBe listOf<Byte>(1, 2, 3, 4)
		first.close()
	}

	private fun filesIn(root: java.io.File): List<java.io.File> = root.listFiles()?.toList().orEmpty()

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

	private class FailAfterOneChunkInputStream(
		private val sensitiveText: String,
	) : InputStream() {
		private var emittedChunk = false

		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			if (!emittedChunk) {
				emittedChunk = true
				buffer[offset] = 1
				buffer[offset + 1] = 2
				buffer[offset + 2] = 3
				return 3
			}
			throw IOException(sensitiveText)
		}

		override fun read(): Int = throw IOException(sensitiveText)
	}
}
