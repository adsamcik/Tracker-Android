@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Bounded typed wire adapter; existing value constructors validate imported records. */
internal class RadioSourceFileJsonV2Codec {
	private val json = Json {
		encodeDefaults = true
		explicitNulls = true
		ignoreUnknownKeys = true
		isLenient = false
		coerceInputValues = false
		exceptionsWithDebugInfo = false
	}

	suspend fun writeWifi(output: OutputStream, value: WifiSourceFileV2) =
		write(output, value, WifiSourceFileV2.serializer())

	suspend fun writeCell(output: OutputStream, value: CellSourceFileV2) =
		write(output, value, CellSourceFileV2.serializer())

	/** Emits each completed session directly to the bounded output, without buffering the file. */
	suspend fun wifiStreamWriter(output: OutputStream, fromMs: Long?, toMs: Long?,
		includeAcrossDates: Boolean): WifiStreamWriter {
		val context = currentCoroutineContext()
		return WifiStreamWriter(json, boundedOutput(output, context), context, fromMs, toMs, includeAcrossDates)
	}

	suspend fun cellStreamWriter(output: OutputStream, fromMs: Long?, toMs: Long?,
		includeAcrossDates: Boolean): CellStreamWriter {
		val context = currentCoroutineContext()
		return CellStreamWriter(json, boundedOutput(output, context), context, fromMs, toMs, includeAcrossDates)
	}

	suspend fun readWifi(input: InputStream): WifiSourceFileV2 = read(input, WifiSourceFileV2.serializer())
	suspend fun readCell(input: InputStream): CellSourceFileV2 = read(input, CellSourceFileV2.serializer())

	private fun boundedOutput(output: OutputStream, context: CoroutineContext): OutputStream =
		RadioBoundedOutputStream(output) { context.ensureActive() }

	private suspend fun <T> write(output: OutputStream, value: T, serializer: SerializationStrategy<T>) {
		val context = currentCoroutineContext()
		context.ensureActive()
		val bounded = boundedOutput(output, context)
		json.encodeToStream(serializer, value, bounded)
		bounded.flush()
		context.ensureActive()
	}

	private suspend fun <T> read(input: InputStream, deserializer: DeserializationStrategy<T>): T {
		val context = currentCoroutineContext()
		val bounded = RadioBoundedInputStream(input) { context.ensureActive() }
		val lexical = PortableJsonTokenLimitInputStream(bounded, PortableJsonTokenLimits(
			maxNameBytes = 384,
			maxStringBytes = 16_384,
			maxNumberBytes = 64,
			maxNestingDepth = 32,
		))
		return try {
			json.decodeFromStream(deserializer, lexical).also { context.ensureActive() }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: PortableJsonTokenLimitException) {
			rejectRadioDocument(failure, context, bounded)
		} catch (failure: SerializationException) {
			rejectRadioDocument(failure, context, bounded)
		} catch (failure: IllegalArgumentException) {
			rejectRadioDocument(failure, context, bounded)
		} catch (failure: ArithmeticException) {
			rejectRadioDocument(failure, context, bounded)
		} catch (failure: DateTimeException) {
			rejectRadioDocument(failure, context, bounded)
		}
	}

	private fun rejectRadioDocument(
		failure: Exception, context: CoroutineContext, input: RadioBoundedInputStream,
	): Nothing {
		context.ensureActive()
		// A source failure is never reinterpreted as a failed value constructor.
		input.sourceFailure?.let { throw it }
		throw RadioSourceFileFormatException("Invalid radio source file", failure)
	}
}

/** A writer belongs to one export coroutine; the caller removes a partial document on failure. */
internal class WifiStreamWriter internal constructor(
	private val json: Json,
	private val output: OutputStream,
	private val context: CoroutineContext,
	fromMs: Long?, toMs: Long?, includeAcrossDates: Boolean,
) {
	private val entries = HashSet<String>()
	private var children = 0L
	private var finished = false
	val sessionCount: Int get() = entries.size

	init {
		output.beginRadioSourceFile(json, RadioSourceFileV2.WIFI_FORMAT, fromMs, toMs, includeAcrossDates)
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
		if (entries.size > 1) {
			output.write(','.code)
		}
		json.encodeToStream(WifiSessionFileV2.serializer(), value, output)
	}

	fun finish(background: PortableAmbientWifiArchiveV1?, backgroundChildren: List<WifiBackgroundChildV2>,
		observationsOutsideDates: Int) {
		context.ensureActive()
		check(!finished)
		require(entries.isNotEmpty() || background != null)
		require(observationsOutsideDates >= 0)
		validateWifiBackgroundChildren(background, backgroundChildren, RadioSourceFileV2.MAX_CHILDREN - children)
		output.jsonText("],\"background\":")
		json.encodeToStream(PortableAmbientWifiArchiveV1.serializer().nullable, background, output)
		output.jsonText(",\"backgroundChildren\":[")
		backgroundChildren.forEachIndexed { index, child ->
			context.ensureActive()
			if (index > 0) {
				output.write(','.code)
			}
			json.encodeToStream(WifiBackgroundChildV2.serializer(), child, output)
		}
		output.jsonText("],\"observationsOutsideDates\":")
		json.encodeToStream(Int.serializer(), observationsOutsideDates, output)
		output.write('}'.code)
		output.flush()
		finished = true
	}
}

internal class CellStreamWriter internal constructor(
	private val json: Json,
	private val output: OutputStream,
	private val context: CoroutineContext,
	fromMs: Long?, toMs: Long?, includeAcrossDates: Boolean,
) {
	private val entries = HashSet<String>()
	private var children = 0L
	private var finished = false
	val sessionCount: Int get() = entries.size

	init {
		output.beginRadioSourceFile(json, RadioSourceFileV2.CELL_FORMAT, fromMs, toMs, includeAcrossDates)
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
		if (entries.size > 1) {
			output.write(','.code)
		}
		json.encodeToStream(CellSessionFileV2.serializer(), value, output)
	}

	fun finish(background: PortableAmbientCellArchiveV1?, backgroundChildren: List<CellBackgroundChildV2>,
		observationsOutsideDates: Int) {
		context.ensureActive()
		check(!finished)
		require(entries.isNotEmpty() || background != null)
		require(observationsOutsideDates >= 0)
		validateCellBackgroundChildren(background, backgroundChildren, RadioSourceFileV2.MAX_CHILDREN - children)
		output.jsonText("],\"background\":")
		json.encodeToStream(PortableAmbientCellArchiveV1.serializer().nullable, background, output)
		output.jsonText(",\"backgroundChildren\":[")
		backgroundChildren.forEachIndexed { index, child ->
			context.ensureActive()
			if (index > 0) {
				output.write(','.code)
			}
			json.encodeToStream(CellBackgroundChildV2.serializer(), child, output)
		}
		output.jsonText("],\"observationsOutsideDates\":")
		json.encodeToStream(Int.serializer(), observationsOutsideDates, output)
		output.write('}'.code)
		output.flush()
		finished = true
	}
}

private fun OutputStream.beginRadioSourceFile(
	json: Json, format: String, fromMs: Long?, toMs: Long?, includeAcrossDates: Boolean,
) {
	require((fromMs == null) == (toMs == null))
	require(fromMs?.let { it >= 0L && requireNotNull(toMs) > it } != false)
	jsonText("{\"format\":")
	json.encodeToStream(String.serializer(), format, this)
	jsonText(",\"schemaVersion\":")
	json.encodeToStream(Int.serializer(), RadioSourceFileV2.VERSION, this)
	jsonText(",\"selectedFromMs\":")
	json.encodeToStream(Long.serializer().nullable, fromMs, this)
	jsonText(",\"selectedToExclusiveMs\":")
	json.encodeToStream(Long.serializer().nullable, toMs, this)
	jsonText(",\"includeSessionsAcrossDates\":")
	json.encodeToStream(Boolean.serializer(), includeAcrossDates, this)
	jsonText(",\"sessions\":[")
}

private fun OutputStream.jsonText(value: String) = write(value.toByteArray(Charsets.UTF_8))

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
	var sourceFailure: RuntimeException? = null
		private set

	override fun read(): Int {
		checkActive()
		val result = readSource { super.read() }
		if (result >= 0) {
			claim(1)
		}
		return result
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		checkActive()
		val count = readSource { super.read(buffer, offset, length) }
		if (count > 0) {
			claim(count)
		}
		return count
	}

	/** Preserve exact source exceptions that would otherwise resemble constructor validation. */
	private fun readSource(read: () -> Int): Int = try {
		read()
	} catch (failure: IllegalArgumentException) {
		sourceFailure = failure
		throw failure
	} catch (failure: ArithmeticException) {
		sourceFailure = failure
		throw failure
	} catch (failure: DateTimeException) {
		sourceFailure = failure
		throw failure
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
