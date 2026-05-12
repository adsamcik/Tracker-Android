package com.adsamcik.tracker.impexp.importer

import java.io.IOException
import java.io.InputStream

/**
 * Provides stream with a filename.
 */
class FileImportStream private constructor(
		private val streamProvider: () -> InputStream,
		/**
		 * File name with extension.
		 */
		val fileName: String,
		private val onClose: () -> Unit = {},
		initialStream: InputStream? = null
) : InputStream() {
	constructor(
			stream: InputStream,
			fileName: String,
			onClose: () -> Unit = {}
	) : this({ stream }, fileName, onClose, stream)

	constructor(
			fileName: String,
			streamProvider: () -> InputStream,
			onClose: () -> Unit = {}
	) : this(streamProvider, fileName, onClose)

	private var stream: InputStream? = initialStream
	private var closed: Boolean = false

	/**
	 * File extension.
	 */
	val extension: String get() = fileName.substringAfterLast('.', "")

	private fun currentStream(): InputStream {
		if (closed) throw IOException("Stream is closed")
		return stream ?: streamProvider().also { stream = it }
	}

	override fun read(): Int = currentStream().read()

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
			currentStream().read(buffer, offset, length)

	override fun close() {
		if (closed) return
		closed = true
		try {
			stream?.close()
		} finally {
			onClose()
		}
	}
}
