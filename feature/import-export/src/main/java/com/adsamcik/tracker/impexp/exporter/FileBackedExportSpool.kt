package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/** Bounded cache-backed staging for codecs whose non-success path may leave incomplete output. */
internal class FileBackedExportSpool(
	context: Context,
	prefix: String,
	private val maximumBytes: Long,
) : Closeable {
	private val file: File
	private var writtenBytes = 0L
	private var writeCompleted = false

	init {
		require(prefix.length >= 3)
		require(maximumBytes > 0L)
		val directory = File(context.cacheDir, DIRECTORY_NAME)
		if (!directory.isDirectory && !directory.mkdirs()) {
			throw IOException("Unable to create portable export spool directory")
		}
		file = File.createTempFile(prefix, FILE_SUFFIX, directory)
	}

	suspend fun <T> write(block: suspend (OutputStream) -> T): T {
		check(!writeCompleted)
		val result = FileOutputStream(file).buffered().use { output ->
			block(BoundedSpoolOutputStream(output))
		}
		writeCompleted = true
		return result
	}

	fun copyTo(destination: OutputStream) {
		check(writeCompleted)
		check(file.length() == writtenBytes)
		file.inputStream().buffered().use { input ->
			val buffer = ByteArray(COPY_BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				if (read == 0) continue
				destination.write(buffer, 0, read)
			}
		}
	}

	override fun close() {
		if (!file.delete() && file.exists()) file.deleteOnExit()
	}

	private inner class BoundedSpoolOutputStream(
		output: OutputStream,
	) : FilterOutputStream(output) {
		override fun write(value: Int) {
			claim(1)
			out.write(value)
		}

		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			claim(length)
			out.write(buffer, offset, length)
		}

		private fun claim(count: Int) {
			val next = Math.addExact(writtenBytes, count.toLong())
			if (next > maximumBytes) throw IOException("Portable export spool exceeds its byte bound")
			writtenBytes = next
		}
	}

	private companion object {
		const val DIRECTORY_NAME = "portable-export-spool"
		const val FILE_SUFFIX = ".tmp"
		const val COPY_BUFFER_SIZE = 8 * 1_024
	}
}

/** Observes codec output without closing the caller-owned destination. */
internal class CountingExportOutputStream(
	output: OutputStream,
) : FilterOutputStream(output) {
	var writtenBytes: Long = 0L
		private set

	override fun write(value: Int) {
		writtenBytes = Math.addExact(writtenBytes, 1L)
		out.write(value)
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		writtenBytes = Math.addExact(writtenBytes, length.toLong())
		out.write(buffer, offset, length)
	}
}
