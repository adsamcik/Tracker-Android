package com.adsamcik.tracker.impexp.importer

import java.io.InputStream

/**
 * Provides stream with a filename.
 */
class FileImportStream(
		private val stream: InputStream,
		/**
		 * File name with extension.
		 */
		val fileName: String,
		private val onClose: () -> Unit = {}
) : InputStream() {
	private var closed: Boolean = false

	/**
	 * File extension.
	 */
	val extension: String get() = fileName.substringAfterLast('.', "")

	override fun read(): Int = stream.read()

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
			stream.read(buffer, offset, length)

	override fun close() {
		if (closed) return
		closed = true
		try {
			stream.close()
		} finally {
			onClose()
		}
	}
}
