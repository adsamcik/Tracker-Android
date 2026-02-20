package com.adsamcik.tracker.stats.engine.exploration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Bloom filter optimised for O(1) "seen-before?" checks on S2 cell tokens.
 *
 * Uses MurmurHash3 (x86, 32-bit) with double-hashing scheme:
 *   hash_i = h1 + i * h2, for i in 0 until numHashFunctions
 *
 * Thread-safety: NOT thread-safe. External synchronisation is required
 * if shared across coroutines.
 */
class ExplorationBloomFilter private constructor(
	private val bits: BitSet,
	val numHashFunctions: Int,
	val numBits: Int,
) {
	/** Number of elements that have been added. */
	var count: Int = 0
		private set

	/**
	 * Add [element] to the filter.
	 */
	fun add(element: String) {
		val (h1, h2) = murmurHash3Pair(element)
		for (i in 0 until numHashFunctions) {
			val combinedHash = h1 + i * h2
			val idx = Math.floorMod(combinedHash, numBits)
			bits.set(idx)
		}
		count++
	}

	/**
	 * Returns `true` if [element] **might** have been added (probabilistic).
	 * Returns `false` if it was **definitely not** added.
	 */
	fun mightContain(element: String): Boolean {
		val (h1, h2) = murmurHash3Pair(element)
		for (i in 0 until numHashFunctions) {
			val combinedHash = h1 + i * h2
			val idx = Math.floorMod(combinedHash, numBits)
			if (!bits.get(idx)) return false
		}
		return true
	}

	/**
	 * Serialize to a byte array for persistence.
	 * Format: [numBits:Int][numHashFunctions:Int][bitSetSize:Int][bitSetBytes:ByteArray]
	 */
	fun serialize(): ByteArray {
		val baos = ByteArrayOutputStream()
		DataOutputStream(baos).use { dos ->
			dos.writeInt(numBits)
			dos.writeInt(numHashFunctions)
			val bitBytes = bits.toByteArray()
			dos.writeInt(bitBytes.size)
			dos.write(bitBytes)
		}
		return baos.toByteArray()
	}

	companion object {

		/**
		 * Create a new bloom filter sized for [expectedInsertions] with
		 * the given [falsePositiveRate].
		 */
		fun create(
			expectedInsertions: Int,
			falsePositiveRate: Double = 0.01,
		): ExplorationBloomFilter {
			require(expectedInsertions > 0) { "expectedInsertions must be > 0" }
			require(falsePositiveRate > 0.0 && falsePositiveRate < 1.0) {
				"falsePositiveRate must be in (0, 1)"
			}
			val m = optimalNumBits(expectedInsertions, falsePositiveRate)
			val k = optimalNumHashFunctions(expectedInsertions, m)
			return ExplorationBloomFilter(BitSet(m), k, m)
		}

		/**
		 * Deserialize from a byte array previously produced by [serialize].
		 */
		fun deserialize(data: ByteArray): ExplorationBloomFilter {
			DataInputStream(ByteArrayInputStream(data)).use { dis ->
				val numBits = dis.readInt()
				val numHash = dis.readInt()
				val byteLen = dis.readInt()
				val bytes = ByteArray(byteLen)
				dis.readFully(bytes)
				val bitSet = BitSet.valueOf(bytes)
				return ExplorationBloomFilter(bitSet, numHash, numBits)
			}
		}

		/** Optimal number of bits: m = -n * ln(p) / (ln(2)^2). */
		internal fun optimalNumBits(n: Int, p: Double): Int {
			val m = -n.toDouble() * ln(p) / (ln(2.0) * ln(2.0))
			return ceil(m).toInt().coerceAtLeast(64)
		}

		/** Optimal number of hash functions: k = (m/n) * ln(2). */
		internal fun optimalNumHashFunctions(n: Int, m: Int): Int {
			val k = (m.toDouble() / n.toDouble()) * ln(2.0)
			return k.roundToInt().coerceIn(1, 30)
		}

		// ---- MurmurHash3 (x86, 32-bit) ----

		private const val C1 = 0xcc9e2d51.toInt()
		private const val C2 = 0x1b873593

		/**
		 * Returns a pair (h1, h2) from MurmurHash3 with two different seeds.
		 */
		internal fun murmurHash3Pair(key: String): Pair<Int, Int> {
			val bytes = key.encodeToByteArray()
			val h1 = murmurHash3x86(bytes, 0)
			val h2 = murmurHash3x86(bytes, h1)
			return h1 to h2
		}

		internal fun murmurHash3x86(data: ByteArray, seed: Int): Int {
			val len = data.size
			var h = seed
			val nBlocks = len / 4

			// Body
			for (i in 0 until nBlocks) {
				val idx = i * 4
				var k = (data[idx].toInt() and 0xFF) or
					((data[idx + 1].toInt() and 0xFF) shl 8) or
					((data[idx + 2].toInt() and 0xFF) shl 16) or
					((data[idx + 3].toInt() and 0xFF) shl 24)

				k *= C1
				k = Integer.rotateLeft(k, 15)
				k *= C2

				h = h xor k
				h = Integer.rotateLeft(h, 13)
				h = h * 5 + 0xe6546b64.toInt()
			}

			// Tail
			val tailIdx = nBlocks * 4
			var k1 = 0
			when (len - tailIdx) {
				3 -> {
					k1 = k1 xor ((data[tailIdx + 2].toInt() and 0xFF) shl 16)
					k1 = k1 xor ((data[tailIdx + 1].toInt() and 0xFF) shl 8)
					k1 = k1 xor (data[tailIdx].toInt() and 0xFF)
					k1 *= C1
					k1 = Integer.rotateLeft(k1, 15)
					k1 *= C2
					h = h xor k1
				}
				2 -> {
					k1 = k1 xor ((data[tailIdx + 1].toInt() and 0xFF) shl 8)
					k1 = k1 xor (data[tailIdx].toInt() and 0xFF)
					k1 *= C1
					k1 = Integer.rotateLeft(k1, 15)
					k1 *= C2
					h = h xor k1
				}
				1 -> {
					k1 = k1 xor (data[tailIdx].toInt() and 0xFF)
					k1 *= C1
					k1 = Integer.rotateLeft(k1, 15)
					k1 *= C2
					h = h xor k1
				}
			}

			// Finalisation
			h = h xor len
			h = fmix32(h)
			return h
		}

		private fun fmix32(h: Int): Int {
			var x = h
			x = x xor (x ushr 16)
			x *= 0x85ebca6b.toInt()
			x = x xor (x ushr 13)
			x *= 0xc2b2ae35.toInt()
			x = x xor (x ushr 16)
			return x
		}
	}
}
