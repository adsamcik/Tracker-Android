package com.adsamcik.tracker.impexp.portable

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Bounded wire adapter; source-owned value constructors recheck every imported lineage. */
internal class RadioSourceFileJsonV2Codec {
	private val gson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

	suspend fun writeWifi(output: OutputStream, value: WifiSourceFileV2) = write(output, value)
	suspend fun writeCell(output: OutputStream, value: CellSourceFileV2) = write(output, value)

	/** Emits completed sessions one at a time while preserving the v2 JSON field contract. */
	suspend fun wifiStreamWriter(output: OutputStream, fromMs: Long?, toMs: Long?,
		includeAcrossDates: Boolean): WifiStreamWriter = WifiStreamWriter(
		gson, newBoundedWriter(output, currentCoroutineContext()), currentCoroutineContext(),
		fromMs, toMs, includeAcrossDates)

	suspend fun cellStreamWriter(output: OutputStream, fromMs: Long?, toMs: Long?,
		includeAcrossDates: Boolean): CellStreamWriter = CellStreamWriter(
		gson, newBoundedWriter(output, currentCoroutineContext()), currentCoroutineContext(),
		fromMs, toMs, includeAcrossDates)

	private fun newBoundedWriter(output: OutputStream, context: CoroutineContext): JsonWriter =
		gson.newJsonWriter(OutputStreamWriter(RadioBoundedOutputStream(output) {
			context.ensureActive()
		}, Charsets.UTF_8))

	suspend fun readWifi(input: InputStream): WifiSourceFileV2 = read(input, WifiSourceFileV2::class.java) {
		it.copy(
			sessions = it.sessions.map { session -> session.copy(
				entry = session.entry.copy(runs = session.entry.runs.map { run ->
					run.copy(observations = run.observations.map { observation -> observation.copy() })
				}),
				children = session.children.map { child -> child.copy() },
			) },
			background = it.background?.let { background -> background.copy(
				facts = background.facts.map { fact -> fact.copy() },
				gaps = background.gaps.map { gap -> gap.copy() },
			) },
			backgroundChildren = it.backgroundChildren.map { child -> child.copy() },
		)
	}

	suspend fun readCell(input: InputStream): CellSourceFileV2 = read(input, CellSourceFileV2::class.java) {
		it.copy(
			sessions = it.sessions.map { session -> session.copy(
				entry = session.entry.copy(runs = session.entry.runs.map { run ->
					run.copy(observations = run.observations.map { observation -> observation.copy() })
				}),
				children = session.children.map { child -> child.copy() },
			) },
			background = it.background?.let { background -> background.copy(
				facts = background.facts.map { fact -> fact.copy() },
				gaps = background.gaps.map { gap -> gap.copy() },
			) },
			backgroundChildren = it.backgroundChildren.map { child -> child.copy() },
		)
	}

	private suspend fun write(output: OutputStream, value: Any) {
		val context = currentCoroutineContext()
		context.ensureActive()
		val bounded = RadioBoundedOutputStream(output) { context.ensureActive() }
		val writer = gson.newJsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8))
		gson.toJson(value, value.javaClass, writer)
		writer.flush()
		context.ensureActive()
	}

	private suspend fun <T : Any> read(
		input: InputStream,
		type: Class<T>,
		revalidate: (T) -> T,
	): T {
		val context = currentCoroutineContext()
		val bounded = RadioBoundedInputStream(input) { context.ensureActive() }
		val lexical = PortableJsonTokenLimitInputStream(bounded, PortableJsonTokenLimits(
			maxNameBytes = 384,
			maxStringBytes = 16_384,
			maxNumberBytes = 64,
			maxNestingDepth = 32,
		))
		val reader = JsonReader(InputStreamReader(lexical, Charsets.UTF_8)).apply { isLenient = false }
		return try {
			val decoded = gson.fromJson(reader, type)
				?: throw RadioSourceFileFormatException("Empty radio source file")
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw RadioSourceFileFormatException("Trailing radio source file content")
			}
			revalidate(decoded)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (format: RadioSourceFileFormatException) {
			throw format
		} catch (failure: RuntimeException) {
			throw RadioSourceFileFormatException("Invalid radio source file", failure)
		}
	}
}

/** A single writer is confined to the export coroutine; the caller removes any partial document on failure. */
internal class WifiStreamWriter internal constructor(
	private val gson: Gson,
	private val writer: JsonWriter,
	private val context: CoroutineContext,
	fromMs: Long?, toMs: Long?, includeAcrossDates: Boolean,
) {
	private val entries = HashSet<String>()
	private var children = 0L
	private var finished = false
	val sessionCount: Int get() = entries.size

	init {
		require((fromMs == null) == (toMs == null))
		require(fromMs?.let { it >= 0L && requireNotNull(toMs) > it } != false)
		writer.beginObject()
		writer.name("format").value(RadioSourceFileV2.WIFI_FORMAT)
		writer.name("schemaVersion").value(RadioSourceFileV2.VERSION)
		writer.name("selectedFromMs").writeNullable(fromMs)
		writer.name("selectedToExclusiveMs").writeNullable(toMs)
		writer.name("includeSessionsAcrossDates").value(includeAcrossDates)
		writer.name("sessions").beginArray()
	}

	fun session(value: WifiSessionFileV2) {
		context.ensureActive()
		check(!finished)
		if (!entries.add(value.entry.identity.value) || entries.size > RadioSourceFileV2.MAX_SESSIONS) {
			throw RadioSourceFileFormatException("Too many or duplicate Wi-Fi sessions")
		}
		children = Math.addExact(children, value.children.size.toLong())
		if (children > RadioSourceFileV2.MAX_CHILDREN) {
			throw RadioSourceFileFormatException("Too many Wi-Fi details")
		}
		gson.toJson(value, WifiSessionFileV2::class.java, writer)
	}

	fun finish(background: com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1?,
		backgroundChildren: List<WifiBackgroundChildV2>, observationsOutsideDates: Int) {
		context.ensureActive()
		check(!finished)
		require(entries.isNotEmpty() || background != null)
		require(observationsOutsideDates >= 0)
		validateWifiBackgroundChildren(background, backgroundChildren,
			RadioSourceFileV2.MAX_CHILDREN - children)
		writer.endArray()
		writer.name("background")
		gson.toJson(background,
			com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1::class.java, writer)
		writer.name("backgroundChildren").beginArray()
		backgroundChildren.forEach { child ->
			context.ensureActive()
			gson.toJson(child, WifiBackgroundChildV2::class.java, writer)
		}
		writer.endArray()
		writer.name("observationsOutsideDates").value(observationsOutsideDates)
		writer.endObject()
		writer.flush()
		finished = true
	}
}

internal class CellStreamWriter internal constructor(
	private val gson: Gson,
	private val writer: JsonWriter,
	private val context: CoroutineContext,
	fromMs: Long?, toMs: Long?, includeAcrossDates: Boolean,
) {
	private val entries = HashSet<String>()
	private var children = 0L
	private var finished = false
	val sessionCount: Int get() = entries.size

	init {
		require((fromMs == null) == (toMs == null))
		require(fromMs?.let { it >= 0L && requireNotNull(toMs) > it } != false)
		writer.beginObject()
		writer.name("format").value(RadioSourceFileV2.CELL_FORMAT)
		writer.name("schemaVersion").value(RadioSourceFileV2.VERSION)
		writer.name("selectedFromMs").writeNullable(fromMs)
		writer.name("selectedToExclusiveMs").writeNullable(toMs)
		writer.name("includeSessionsAcrossDates").value(includeAcrossDates)
		writer.name("sessions").beginArray()
	}

	fun session(value: CellSessionFileV2) {
		context.ensureActive()
		check(!finished)
		if (!entries.add(value.entry.identity.value) || entries.size > RadioSourceFileV2.MAX_SESSIONS) {
			throw RadioSourceFileFormatException("Too many or duplicate Cell sessions")
		}
		children = Math.addExact(children, value.children.size.toLong())
		if (children > RadioSourceFileV2.MAX_CHILDREN) {
			throw RadioSourceFileFormatException("Too many Cell details")
		}
		gson.toJson(value, CellSessionFileV2::class.java, writer)
	}

	fun finish(background: com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1?,
		backgroundChildren: List<CellBackgroundChildV2>, observationsOutsideDates: Int) {
		context.ensureActive()
		check(!finished)
		require(entries.isNotEmpty() || background != null)
		require(observationsOutsideDates >= 0)
		validateCellBackgroundChildren(background, backgroundChildren,
			RadioSourceFileV2.MAX_CHILDREN - children)
		writer.endArray()
		writer.name("background")
		gson.toJson(background,
			com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1::class.java, writer)
		writer.name("backgroundChildren").beginArray()
		backgroundChildren.forEach { child ->
			context.ensureActive()
			gson.toJson(child, CellBackgroundChildV2::class.java, writer)
		}
		writer.endArray()
		writer.name("observationsOutsideDates").value(observationsOutsideDates)
		writer.endObject()
		writer.flush()
		finished = true
	}
}

private fun JsonWriter.writeNullable(value: Long?) {
	if (value == null) nullValue() else value(value)
}

private fun validateWifiBackgroundChildren(
	background: com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1?,
	children: List<WifiBackgroundChildV2>, remaining: Long,
) {
	require(children.size <= remaining)
	val facts = background?.facts?.associateBy { it.identity to it.semanticRevision }.orEmpty()
	val seen = HashSet<Triple<String, Long, Int>>()
	val counts = HashMap<Pair<String, Long>, Int>()
	children.forEach { child ->
		val parent = requireNotNull(facts[child.factIdentity to child.semanticRevision])
		require(radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
			parent.coverageStartTimeMs, parent.latestPossibleTimeMs))
		require(seen.add(Triple(child.factIdentity, child.semanticRevision, child.itemIndex)))
		val key = child.factIdentity to child.semanticRevision
		counts[key] = (counts[key] ?: 0) + 1
	}
	background?.facts.orEmpty().forEach { fact ->
		require(counts[fact.identity to fact.semanticRevision]?.let {
			it == fact.observationCount
		} != false)
	}
}

private fun validateCellBackgroundChildren(
	background: com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1?,
	children: List<CellBackgroundChildV2>, remaining: Long,
) {
	require(children.size <= remaining)
	val facts = background?.facts?.associateBy { it.identity to it.semanticRevision }.orEmpty()
	val seen = HashSet<Triple<String, Long, Int>>()
	val counts = HashMap<Pair<String, Long>, Int>()
	children.forEach { child ->
		val parent = requireNotNull(facts[child.factIdentity to child.semanticRevision])
		require(radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
			parent.coverageStartTimeMs, parent.latestPossibleTimeMs))
		require(seen.add(Triple(child.factIdentity, child.semanticRevision, child.itemIndex)))
		val key = child.factIdentity to child.semanticRevision
		counts[key] = (counts[key] ?: 0) + 1
	}
	background?.facts.orEmpty().forEach { fact ->
		require(counts[fact.identity to fact.semanticRevision]?.let {
			it == fact.observationCount
		} != false)
	}
}

private class RadioBoundedInputStream(
	input: InputStream,
	private val checkActive: () -> Unit,
) : FilterInputStream(input) {
	private var consumed = 0L

	override fun read(): Int {
		checkActive()
		val result = super.read()
		if (result >= 0) claim(1)
		return result
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		checkActive()
		val count = super.read(buffer, offset, length)
		if (count > 0) claim(count)
		return count
	}

	private fun claim(count: Int) {
		consumed = Math.addExact(consumed, count.toLong())
		if (consumed > RadioSourceFileV2.MAX_FILE_BYTES) {
			throw RadioSourceFileFormatException("Radio file is too large")
		}
	}
}

private class RadioBoundedOutputStream(
	output: OutputStream,
	private val checkActive: () -> Unit,
) : FilterOutputStream(output) {
	private var written = 0L

	override fun write(value: Int) {
		checkActive()
		claim(1)
		out.write(value)
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		checkActive()
		claim(length)
		out.write(buffer, offset, length)
	}

	private fun claim(count: Int) {
		written = Math.addExact(written, count.toLong())
		if (written > RadioSourceFileV2.MAX_FILE_BYTES) {
			throw RadioSourceFileFormatException("Radio file is too large; select fewer dates")
		}
	}
}

internal class RadioSourceFileFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)
