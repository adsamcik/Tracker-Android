package com.adsamcik.tracker.impexp.portable

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded logical byte range whose decoder streams share one caller-owned backing array. */
internal class PortableJsonBytes private constructor(
	internal val backingArray: ByteArray,
	internal val size: Int,
) {
	init {
		require(size in 0..backingArray.size)
	}

	internal fun inputStream(): ByteArrayInputStream =
		ByteArrayInputStream(backingArray, 0, size)

	internal companion object {
		fun wrap(bytes: ByteArray): PortableJsonBytes = PortableJsonBytes(bytes, bytes.size)

		fun loaded(bytes: ByteArray, size: Int): PortableJsonBytes =
			PortableJsonBytes(bytes, size)
	}
}

internal suspend fun readPortableBytes(
	input: InputStream,
	maximumBytes: Long,
	allocate: (Int) -> ByteArray = { ByteArray(it) },
): PortableJsonBytes {
	require(maximumBytes in 1..Int.MAX_VALUE.toLong())
	val maximumSize = maximumBytes.toInt()
	val available = input.available().coerceAtLeast(0)
	val initialCapacity = maxOf(
		available,
		DEFAULT_PORTABLE_BUFFER_BYTES.coerceAtMost(maximumSize),
	).coerceAtMost(maximumSize)
	var buffer = allocate(initialCapacity)
	require(buffer.size == initialCapacity)
	var size = 0
	while (true) {
		currentCoroutineContext().ensureActive()
		if (size == buffer.size) {
			val next = input.read()
			if (next < 0) break
			if (size == maximumSize) {
				portableBytesFailure("Portable document exceeds its byte bound")
			}
			val expandedSize = minOf(
				maximumBytes,
				maxOf(size.toLong() + 1L, size.toLong() * 2L),
			).toInt()
			val expanded = allocate(expandedSize)
			require(expanded.size == expandedSize)
			buffer.copyInto(expanded, endIndex = size)
			buffer = expanded
			buffer[size++] = next.toByte()
			continue
		}
		val read = input.read(buffer, size, buffer.size - size)
		when {
			read < 0 -> break
			read > 0 -> size = Math.addExact(size, read)
			else -> {
				val next = input.read()
				if (next < 0) break
				buffer[size++] = next.toByte()
			}
		}
	}
	if (size == 0) portableBytesFailure("Portable document is empty")
	return PortableJsonBytes.loaded(buffer, size)
}

private fun portableBytesFailure(message: String): Nothing =
	throw PortableStepsJsonException(message)

private const val DEFAULT_PORTABLE_BUFFER_BYTES = 8_192
