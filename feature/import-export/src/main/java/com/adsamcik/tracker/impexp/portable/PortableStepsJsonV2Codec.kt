package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableStepsDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportMetadataV2
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PortableStepsDecodedArchiveV2(
	val archive: PortableStepsArchiveV2,
	val metadata: PortableStepsImportMetadataV2,
)

/** Canonical v2 codec. The complete archive is authenticated before the first destination byte. */
internal class PortableStepsJsonV2Codec {
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortableStepsArchiveV2Sink) -> ExportPortableStepsResult,
	): ExportPortableStepsResult {
		var archive: PortableStepsArchiveV2? = null
		val result = produce(
			PortableStepsArchiveV2Sink { candidate ->
				currentCoroutineContext().ensureActive()
				if (archive != null) fail("Portable Steps v2 exporter emitted multiple archives")
				archive = PortableStepsArchiveV2(
					candidate.format,
					candidate.schemaVersion,
					candidate.contentChecksum,
					candidate.entries.map(PortableStepsEntryV2::snapshot),
				)
			},
		)
		return when (result) {
			is ExportPortableStepsResult.Exported -> {
				val value = archive ?: fail("Portable Steps v2 exporter emitted no archive")
				if (value.entries.size != result.entryCount) {
					fail("Portable Steps v2 result count does not match its archive")
				}
				write(outputStream, value)
				result
			}
			else -> {
				if (archive != null) fail("Portable Steps v2 emitted data for a non-success result")
				result
			}
		}
	}

	suspend fun decode(inputStream: InputStream): PortableStepsDecodedArchiveV2 {
		val bytes = readPortableBytes(inputStream, StepsPortableFormatV2.MAX_FILE_BYTES)
		return decode(bytes)
	}

	suspend fun decode(bytes: ByteArray): PortableStepsDecodedArchiveV2 =
		decode(PortableJsonBytes.wrap(bytes))

	internal suspend fun decode(bytes: PortableJsonBytes): PortableStepsDecodedArchiveV2 {
		if (bytes.size.toLong() > StepsPortableFormatV2.MAX_FILE_BYTES) {
			fail("Portable document exceeds its byte bound")
		}
		val reader = JsonReader(
			InputStreamReader(
				PortableJsonTokenLimitInputStream(
					bytes.inputStream(),
					portableJsonDocumentTokenLimits(bytes.size),
				),
				Charsets.UTF_8,
			),
		).apply { isLenient = false }
		return try {
			reader.beginObject()
			var format: String? = null
			var schemaVersion: Int? = null
			var checksum: String? = null
			var entries: List<PortableStepsEntryV2>? = null
			val fields = hashSetOf<String>()
			while (reader.hasNext()) {
				val name = reader.nextName()
				if (!fields.add(name)) fail("Duplicate Portable Steps v2 field $name")
				when (name) {
					"format" -> format = reader.nextString()
					"schemaVersion" -> schemaVersion = reader.exactV2Int(name)
					"contentChecksum" -> checksum = reader.nextString()
					"entries" -> entries = reader.readV2Entries()
					else -> fail("Unknown Portable Steps v2 field $name")
				}
			}
			reader.endObject()
			if (reader.peek() != JsonToken.END_DOCUMENT) fail("Trailing Portable Steps v2 content")
			val archive = PortableStepsArchiveV2(
				format = format ?: fail("Missing Portable Steps v2 format"),
				schemaVersion = schemaVersion ?: fail("Missing Portable Steps v2 schema version"),
				contentChecksum = PortableStepsDigest(
					checksum ?: fail("Missing Portable Steps v2 checksum"),
				),
				entries = entries ?: fail("Missing Portable Steps v2 entries"),
			)
			PortableStepsDecodedArchiveV2(archive, archive.metadata(bytes.size.toLong()))
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: PortableStepsJsonException) {
			throw failure
		} catch (failure: PortableJsonTokenLimitException) {
			throw PortableStepsJsonException(
				"Portable Steps v2 token exceeds its lexical bound",
				failure,
			)
		} catch (failure: IOException) {
			throw PortableStepsJsonException("Invalid Portable Steps v2 JSON", failure)
		} catch (failure: Exception) {
			throw PortableStepsJsonException("Invalid Portable Steps v2 document", failure)
		}
	}

	private suspend fun write(outputStream: OutputStream, archive: PortableStepsArchiveV2) {
		val products = archive.entries.associateWith { encodeV1Product(it.product) }
		currentCoroutineContext().ensureActive()
		val bounded = PortableV2BoundedOutputStream(outputStream, StepsPortableFormatV2.MAX_FILE_BYTES)
		val writer = JsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8)).apply { isLenient = false }
		writer.beginObject()
		writer.name("format").value(archive.format)
		writer.name("schemaVersion").value(archive.schemaVersion.toLong())
		writer.name("contentChecksum").value(archive.contentChecksum.value)
		writer.name("entries").beginArray()
		archive.entries.forEach { entry ->
			currentCoroutineContext().ensureActive()
			writer.beginObject()
			writer.name("productV1").value(products.getValue(entry))
			writer.name("countDomainGraph")
			PortableCountDomainJsonV2.write(writer, entry.countDomainGraph)
			writer.endObject()
		}
		writer.endArray()
		writer.endObject()
		writer.flush()
	}

	private suspend fun JsonReader.readV2Entries(): List<PortableStepsEntryV2> {
		if (peek() != JsonToken.BEGIN_ARRAY) fail("Portable Steps v2 entries must be an array")
		beginArray()
		val result = mutableListOf<PortableStepsEntryV2>()
		while (hasNext()) {
			currentCoroutineContext().ensureActive()
			if (result.size >= StepsPortableFormatV2.MAX_ENTRIES) {
				fail("Portable Steps v2 entry count exceeds its bound")
			}
			beginObject()
			var product: PortableStepsEntryV1? = null
			var graph: com.adsamcik.tracker.stats.api.repository.PortableCountDomainGraphV2? = null
			val fields = hashSetOf<String>()
			while (hasNext()) {
				val name = nextName()
				if (!fields.add(name)) fail("Duplicate Portable Steps v2 entry field $name")
				when (name) {
					"productV1" -> product = decodeV1Product(nextString())
					"countDomainGraph" -> graph = PortableCountDomainJsonV2.read(this)
					else -> fail("Unknown Portable Steps v2 entry field $name")
				}
			}
			endObject()
			if (fields != setOf("productV1", "countDomainGraph")) {
				fail("Portable Steps v2 entry is incomplete")
			}
			result += PortableStepsEntryV2(
				product ?: fail("Portable Steps v2 product is missing"),
				graph ?: fail("Portable Steps v2 count-domain graph is missing"),
			)
		}
		endArray()
		return result
	}

	private suspend fun encodeV1Product(entry: PortableStepsEntryV1): String {
		val output = ByteArrayOutputStream()
		val result = PortableStepsJsonV1Codec().encode(output) { sink ->
			sink.emit(entry)
			ExportPortableStepsResult.Exported(1)
		}
		check(result is ExportPortableStepsResult.Exported)
		return output.toString(Charsets.UTF_8.name())
	}

	private suspend fun decodeV1Product(value: String): PortableStepsEntryV1 {
		val entries = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(
			ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)),
		) { entry -> entries += entry }
		return entries.singleOrNull() ?: fail("Portable Steps v2 product must contain one v1 entry")
	}

	private fun PortableStepsEntryV2.snapshot(): PortableStepsEntryV2 = PortableStepsEntryV2(
		product = product.copy(
			runs = product.runs.map { run ->
				run.copy(
					manifests = run.manifests.map { it.copy() },
					facts = run.facts.map { it.copy() },
				)
			},
		),
		countDomainGraph = countDomainGraph.copy(
			receipts = countDomainGraph.receipts.map { it.copy() },
			ownerRevisions = countDomainGraph.ownerRevisions.map { it.copy() },
			completenessMarkers = countDomainGraph.completenessMarkers.map { it.copy() },
			roots = countDomainGraph.roots.map { it.copy() },
		),
	)

	private fun PortableStepsArchiveV2.metadata(byteCount: Long): PortableStepsImportMetadataV2 =
		PortableStepsImportMetadataV2(
			encodedByteCount = byteCount,
			archiveContentChecksum = contentChecksum,
			entryCount = entries.size,
			receiptCount = exactCount { it.countDomainGraph.receipts.size },
			ownerRevisionCount = exactCount { it.countDomainGraph.ownerRevisions.size },
			completenessMarkerCount = exactCount {
				it.countDomainGraph.completenessMarkers.size
			},
			rootCount = exactCount { it.countDomainGraph.roots.size },
		)

	private fun PortableStepsArchiveV2.exactCount(
		count: (PortableStepsEntryV2) -> Int,
	): Int = entries.fold(0) { total, entry -> Math.addExact(total, count(entry)) }
}

private class PortableV2BoundedOutputStream(
	output: OutputStream,
	private val maximumBytes: Long,
) : FilterOutputStream(output) {
	private var written = 0L

	override fun write(value: Int) {
		claim(1)
		out.write(value)
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		claim(length)
		out.write(buffer, offset, length)
	}

	private fun claim(count: Int) {
		written = Math.addExact(written, count.toLong())
		if (written > maximumBytes) {
			throw PortableStepsEncodedSizeLimitException(
				"Portable document exceeds its byte bound",
			)
		}
	}
}

private fun JsonReader.exactV2Int(name: String): Int {
	if (peek() != JsonToken.NUMBER) fail("Portable field $name must be an integer")
	val token = nextString()
	if (!V2_INTEGER_TOKEN.matches(token)) fail("Portable field $name must be an integer")
	return token.toIntOrNull() ?: fail("Portable field $name is out of range")
}

private fun fail(message: String): Nothing = throw PortableStepsJsonException(message)
private val V2_INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
