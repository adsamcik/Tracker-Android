package com.adsamcik.tracker.osm.io

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

@DisplayName("PolylineE7Codec round-trip")
class PolylineE7CodecTest {

	@Test
	fun `empty input round-trips to empty arrays`() {
		val encoded = PolylineE7Codec.encode(IntArray(0), IntArray(0))
		encoded.size shouldBe 1
		encoded[0] shouldBe 0.toByte()

		val (lats, lons) = PolylineE7Codec.decode(encoded)
		lats.size shouldBe 0
		lons.size shouldBe 0
	}

	@Test
	fun `single point round-trips losslessly`() {
		val lats = intArrayOf(500_000_000)
		val lons = intArrayOf(144_000_000)
		val (decLats, decLons) = PolylineE7Codec.decode(PolylineE7Codec.encode(lats, lons))
		decLats.toList() shouldBe lats.toList()
		decLons.toList() shouldBe lons.toList()
	}

	@Test
	fun `many points with small deltas round-trip and compress well`() {
		val lats = IntArray(50) { 500_000_000 + it * 100 }
		val lons = IntArray(50) { 144_000_000 + it * 100 }

		val encoded = PolylineE7Codec.encode(lats, lons)
		// Deltas of 100 (zigzag = 200) fit in 2 varint bytes max.
		// First point ~5 bytes per axis, then ~2 bytes each for 49 deltas.
		encoded.size shouldBeLessThanOrEqual (1 + 10 + 49 * 2 * 2)

		val (decLats, decLons) = PolylineE7Codec.decode(encoded)
		decLats.toList() shouldBe lats.toList()
		decLons.toList() shouldBe lons.toList()
	}

	@Test
	fun `large negative and positive coordinates round-trip`() {
		val lats = intArrayOf(-899_999_999, 0, 899_999_999)
		val lons = intArrayOf(-1_799_999_999, 0, 1_799_999_999)
		val (decLats, decLons) = PolylineE7Codec.decode(PolylineE7Codec.encode(lats, lons))
		decLats.toList() shouldBe lats.toList()
		decLons.toList() shouldBe lons.toList()
	}

	@Test
	fun `mismatched array sizes are rejected`() {
		assertThrows<IllegalArgumentException> {
			PolylineE7Codec.encode(intArrayOf(1, 2), intArrayOf(1))
		}
	}

	@Test
	fun `truncated blob throws on decode`() {
		val encoded = PolylineE7Codec.encode(
			intArrayOf(500_000_000, 500_001_000),
			intArrayOf(144_000_000, 144_001_000),
		)
		assertThrows<IllegalArgumentException> {
			PolylineE7Codec.decode(encoded.copyOfRange(0, encoded.size - 1))
		}
	}

	@Test
	fun `canonicalizes antimeridian and pole identity on decode`() {
		val (lats, lons) = PolylineE7Codec.decode(
			PolylineE7Codec.encode(
				intArrayOf(0, 900_000_000),
				intArrayOf(1_800_000_000, 123_000_000),
			),
		)

		lats.toList() shouldBe listOf(0, 900_000_000)
		lons.toList() shouldBe listOf(-1_800_000_000, 0)
	}

	@Test
	fun `rejects coordinates outside Earth range before encoding or narrowing`() {
		assertThrows<IllegalArgumentException> {
			PolylineE7Codec.encode(intArrayOf(900_000_001), intArrayOf(0))
		}
		assertThrows<IllegalArgumentException> {
			PolylineE7Codec.decode(packedVarints(1L, zigZag(900_000_001L), zigZag(0L)))
		}
	}

	@Test
	fun `rejects trailing and overflowing varints`() {
		val valid = PolylineE7Codec.encode(intArrayOf(0), intArrayOf(0))
		assertThrows<IllegalArgumentException> { PolylineE7Codec.decode(valid + 0x00.toByte()) }
		assertThrows<IllegalArgumentException> {
			PolylineE7Codec.decode(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
				0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x02))
		}
	}

	private fun packedVarints(vararg values: Long): ByteArray = ByteArrayOutputStream().use { out ->
		values.forEach { value ->
			var remaining = value
			while ((remaining and 0x7FL.inv()) != 0L) {
				out.write(((remaining and 0x7FL) or 0x80L).toInt())
				remaining = remaining ushr 7
			}
			out.write(remaining.toInt())
		}
		out.toByteArray()
	}

	private fun zigZag(value: Long): Long = (value shl 1) xor (value shr 63)
}
