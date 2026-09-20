package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortableJsonBytesTest {
	@Test
	fun `byte array view retains the caller backing without copying`() {
		val bytes = byteArrayOf(1, 2, 3)
		val source = PortableJsonBytes.wrap(bytes)
		val input = source.inputStream()

		(source.backingArray === bytes) shouldBe true
		bytes[0] = 9
		input.read() shouldBe 9
	}

	@Test
	fun `maximum bounded Steps and Ambient reads request one hinted payload allocation`() = runTest {
		listOf(
			StepsPortableFormatV1.MAX_FILE_BYTES,
			AmbientStepsPortableFormatV1.MAX_FILE_BYTES,
		).forEach { maximumBytes ->
			val source = AllocationSourceSpy(maximumBytes.toInt())
			val requested = mutableListOf<Int>()

			shouldThrow<AllocationProbe> {
				readPortableBytes(
					FileImportStream(source, "maximum.trackersteps"),
					maximumBytes,
				) { size ->
					requested += size
					throw AllocationProbe()
				}
			}

			requested shouldContainExactly listOf(maximumBytes.toInt())
			source.readCalls shouldBe 0
		}
	}

	@Test
	fun `exact bounded read returns its only payload allocation without a final copy`() = runTest {
		val payload = ByteArray(64) { it.toByte() }
		val source = ExactBoundSource(payload)
		val allocations = mutableListOf<ByteArray>()

		val loaded = readPortableBytes(
			FileImportStream(source, "exact.trackersteps"),
			payload.size.toLong(),
		) { size -> ByteArray(size).also(allocations::add) }

		allocations.size shouldBe 1
		(loaded.backingArray === allocations.single()) shouldBe true
		loaded.size shouldBe payload.size
		loaded.inputStream().readBytes().contentEquals(payload) shouldBe true
	}
}

private class AllocationSourceSpy(
	private val availableBytes: Int,
) : InputStream() {
	var readCalls: Int = 0
		private set

	override fun available(): Int = availableBytes

	override fun read(): Int {
		readCalls++
		return -1
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		readCalls++
		return -1
	}
}

private class ExactBoundSource(
	private val payload: ByteArray,
) : InputStream() {
	private var position = 0

	override fun available(): Int = payload.size - position

	override fun read(): Int =
		if (position == payload.size) -1 else payload[position++].toInt() and 0xff

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (position == payload.size) return -1
		val count = minOf(length, payload.size - position)
		payload.copyInto(buffer, offset, position, position + count)
		position += count
		return count
	}
}

private class AllocationProbe : RuntimeException()
