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
			buffer = buffer.readAndGrow(input, size, maximumBytes, allocate) ?: break
			size++
			continue
		}
		val read = input.readPortableChunk(buffer, size)
		if (read < 0) {
			break
		}
		size = Math.addExact(size, read)
	}
	if (size == 0) {
		portableBytesFailure("Portable document is empty")
	}
	return PortableJsonBytes.loaded(buffer, size)
}

/** Progresses even when a source returns zero without reaching its end. */
private fun InputStream.readPortableChunk(buffer: ByteArray, size: Int): Int {
	val read = read(buffer, size, buffer.size - size)
	if (read != 0) {
		return read
	}
	val next = read()
	if (next < 0) {
		return -1
	}
	buffer[size] = next.toByte()
	return 1
}

private fun ByteArray.readAndGrow(
	input: InputStream,
	size: Int,
	maximumBytes: Long,
	allocate: (Int) -> ByteArray,
): ByteArray? {
	val next = input.read()
	if (next < 0) {
		return null
	}
	if (size == maximumBytes.toInt()) {
		portableBytesFailure("Portable document exceeds its byte bound")
	}
	val expandedSize = minOf(
		maximumBytes,
		maxOf(size.toLong() + 1L, size.toLong() * 2L),
	).toInt()
	val expanded = allocate(expandedSize)
	require(expanded.size == expandedSize)
	copyInto(expanded, endIndex = size)
	expanded[size] = next.toByte()
	return expanded
}

private fun portableBytesFailure(message: String): Nothing =
	throw PortableStepsJsonException(message)

private const val DEFAULT_PORTABLE_BUFFER_BYTES = 8_192
