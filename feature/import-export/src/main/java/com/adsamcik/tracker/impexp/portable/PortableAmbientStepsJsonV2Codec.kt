package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadataV2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PortableAmbientStepsDecodedArchiveV2(
	val archive: PortableAmbientStepsArchiveV2,
	val metadata: PortableAmbientStepsImportMetadataV2,
)

/** Canonical v2 Ambient Steps codec with whole-archive validation before output. */
internal class PortableAmbientStepsJsonV2Codec {
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortableAmbientStepsArchiveV2Sink) -> ExportPortableAmbientStepsResult,
	): ExportPortableAmbientStepsResult {
		var archive: PortableAmbientStepsArchiveV2? = null
		val result = produce(
			PortableAmbientStepsArchiveV2Sink { candidate ->
				currentCoroutineContext().ensureActive()
				if (archive != null) ambientFail("Ambient Steps v2 emitted multiple archives")
				archive = PortableAmbientStepsArchiveV2(
					candidate.format,
					candidate.schemaVersion,
					candidate.contentChecksum,
					candidate.days.map(PortableAmbientStepsDayV2::snapshot),
				)
			},
		)
		return when (result) {
			is ExportPortableAmbientStepsResult.Exported -> {
				val value = archive ?: ambientFail("Ambient Steps v2 emitted no archive")
				if (result.dayCount != value.days.size ||
					result.factCount != value.days.sumOf { it.product.facts.size } ||
					result.gapCount != value.days.sumOf { it.product.gaps.size }
				) ambientFail("Ambient Steps v2 result count does not match its archive")
				write(outputStream, value)
				result
			}
			else -> {
				if (archive != null) ambientFail("Ambient Steps v2 emitted data for failure")
				result
			}
		}
	}

	suspend fun decode(inputStream: InputStream): PortableAmbientStepsDecodedArchiveV2 {
		val bytes = try {
			readPortableBytes(inputStream, AmbientStepsPortableFormatV2.MAX_FILE_BYTES)
		} catch (failure: PortableStepsJsonException) {
			throw PortableAmbientStepsFormatException(
				"Ambient Steps v2 exceeds its byte bound",
				failure,
			)
		}
		val reader = JsonReader(
			InputStreamReader(
				PortableJsonTokenLimitInputStream(
					ByteArrayInputStream(bytes),
					PortableJsonTokenLimits(
						maxNameBytes = 384,
						maxStringBytes = bytes.size.coerceAtLeast(768),
						maxNumberBytes = 64,
						maxNestingDepth = 32,
					),
				),
				Charsets.UTF_8,
			),
		).apply { isLenient = false }
		return try {
			reader.beginObject()
			var format: String? = null
			var schemaVersion: Int? = null
			var checksum: String? = null
			var days: List<PortableAmbientStepsDayV2>? = null
			val fields = hashSetOf<String>()
			while (reader.hasNext()) {
				val name = reader.nextName()
				if (!fields.add(name)) ambientFail("Duplicate Ambient Steps v2 field $name")
				when (name) {
					"format" -> format = reader.nextString()
					"schemaVersion" -> schemaVersion = reader.exactAmbientV2Int(name)
					"contentChecksum" -> checksum = reader.nextString()
					"days" -> days = reader.readV2Days()
					else -> ambientFail("Unknown Ambient Steps v2 field $name")
				}
			}
			reader.endObject()
			if (reader.peek() != JsonToken.END_DOCUMENT) ambientFail("Trailing Ambient Steps content")
			val archive = PortableAmbientStepsArchiveV2(
				format = format ?: ambientFail("Missing Ambient Steps v2 format"),
				schemaVersion = schemaVersion ?: ambientFail("Missing Ambient Steps v2 version"),
				contentChecksum = AmbientStepsPortableDigest(
					checksum ?: ambientFail("Missing Ambient Steps v2 checksum"),
				),
				days = days ?: ambientFail("Missing Ambient Steps v2 days"),
			)
			PortableAmbientStepsDecodedArchiveV2(archive, archive.metadata(bytes.size.toLong()))
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: PortableAmbientStepsFormatException) {
			throw failure
		} catch (failure: Exception) {
			throw PortableAmbientStepsFormatException("Invalid Ambient Steps v2 document", failure)
		}
	}

	private suspend fun write(outputStream: OutputStream, archive: PortableAmbientStepsArchiveV2) {
		val products = archive.days.associateWith { encodeV1Day(it) }
		currentCoroutineContext().ensureActive()
		val bounded = PortableV2BoundedAmbientOutputStream(
			outputStream,
			AmbientStepsPortableFormatV2.MAX_FILE_BYTES,
		)
		val writer = JsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8)).apply { isLenient = false }
		writer.beginObject()
		writer.name("format").value(archive.format)
		writer.name("schemaVersion").value(archive.schemaVersion.toLong())
		writer.name("contentChecksum").value(archive.contentChecksum.value)
		writer.name("days").beginArray()
		archive.days.forEach { day ->
			currentCoroutineContext().ensureActive()
			writer.beginObject()
			writer.name("productV1").value(products.getValue(day))
			writer.name("countDomainGraph")
			PortableCountDomainJsonV2.write(writer, day.countDomainGraph)
			writer.endObject()
		}
		writer.endArray()
		writer.endObject()
		writer.flush()
	}

	private suspend fun JsonReader.readV2Days(): List<PortableAmbientStepsDayV2> {
		if (peek() != JsonToken.BEGIN_ARRAY) ambientFail("Ambient Steps v2 days must be an array")
		beginArray()
		val result = mutableListOf<PortableAmbientStepsDayV2>()
		while (hasNext()) {
			currentCoroutineContext().ensureActive()
			if (result.size >= AmbientStepsPortableFormatV2.MAX_DAYS) {
				ambientFail("Ambient Steps v2 day count exceeds its bound")
			}
			beginObject()
			var product: com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1? =
				null
			var graph: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2? =
				null
			val fields = hashSetOf<String>()
			while (hasNext()) {
				val name = nextName()
				if (!fields.add(name)) ambientFail("Duplicate Ambient Steps v2 day field $name")
				when (name) {
					"productV1" -> product = decodeV1Day(nextString())
					"countDomainGraph" -> graph = PortableCountDomainJsonV2.read(this)
					else -> ambientFail("Unknown Ambient Steps v2 day field $name")
				}
			}
			endObject()
			if (fields != setOf("productV1", "countDomainGraph")) {
				ambientFail("Ambient Steps v2 day is incomplete")
			}
			result += PortableAmbientStepsDayV2(
				product ?: ambientFail("Ambient Steps v2 day product is missing"),
				graph ?: ambientFail("Ambient Steps v2 day graph is missing"),
			)
		}
		endArray()
		return result
	}

	private suspend fun encodeV1Day(day: PortableAmbientStepsDayV2): String {
		val archive =
			com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
				.create(listOf(day.product))
		val output = ByteArrayOutputStream()
		val result = PortableAmbientStepsJsonV1Codec().encode(output) { sink ->
			sink.emit(archive)
			ExportPortableAmbientStepsResult.Exported(
				1,
				day.product.facts.size,
				day.product.gaps.size,
			)
		}
		check(result is ExportPortableAmbientStepsResult.Exported)
		return output.toString(Charsets.UTF_8.name())
	}

	private suspend fun decodeV1Day(
		value: String,
	): com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1 =
		PortableAmbientStepsJsonV1Codec()
			.decode(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
			.archive.days.singleOrNull()
			?: ambientFail("Ambient Steps v2 product must contain one v1 day")

	private fun PortableAmbientStepsDayV2.snapshot(): PortableAmbientStepsDayV2 =
		PortableAmbientStepsDayV2(
			product = product.copy(
				partialCauses = product.partialCauses.toList(),
				facts = product.facts.map { it.copy() },
				gaps = product.gaps.map { it.copy() },
			),
			countDomainGraph = countDomainGraph.copy(
				receipts = countDomainGraph.receipts.map { it.copy() },
				ownerRevisions = countDomainGraph.ownerRevisions.map { it.copy() },
				completenessMarkers = countDomainGraph.completenessMarkers.map { it.copy() },
				roots = countDomainGraph.roots.map { it.copy() },
			),
		)

	private fun PortableAmbientStepsArchiveV2.metadata(
		byteCount: Long,
	): PortableAmbientStepsImportMetadataV2 = PortableAmbientStepsImportMetadataV2(
		encodedByteCount = byteCount,
		archiveContentChecksum = contentChecksum,
		dayCount = days.size,
		factCount = exactCount { it.product.facts.size },
		gapCount = exactCount { it.product.gaps.size },
		receiptCount = exactCount { it.countDomainGraph.receipts.size },
		ownerRevisionCount = exactCount { it.countDomainGraph.ownerRevisions.size },
		rootCount = exactCount { it.countDomainGraph.roots.size },
	)

	private fun PortableAmbientStepsArchiveV2.exactCount(
		count: (PortableAmbientStepsDayV2) -> Int,
	): Int = days.fold(0) { total, day -> Math.addExact(total, count(day)) }
}

private class PortableV2BoundedAmbientOutputStream(
	output: OutputStream,
	private val maximumBytes: Long,
) : java.io.FilterOutputStream(output) {
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
			throw PortableAmbientStepsEncodedSizeLimitException(
				"Ambient Steps v2 exceeds its byte bound",
			)
		}
	}
}

private fun JsonReader.exactAmbientV2Int(name: String): Int {
	if (peek() != JsonToken.NUMBER) ambientFail("Ambient field $name must be an integer")
	val token = nextString()
	if (!AMBIENT_V2_INTEGER_TOKEN.matches(token)) {
		ambientFail("Ambient field $name must be an integer")
	}
	return token.toIntOrNull() ?: ambientFail("Ambient field $name is out of range")
}

private fun ambientFail(message: String): Nothing =
	throw PortableAmbientStepsFormatException(message)

private val AMBIENT_V2_INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
