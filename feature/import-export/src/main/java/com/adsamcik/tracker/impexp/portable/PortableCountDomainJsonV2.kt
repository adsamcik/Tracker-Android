package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainCompletenessMarkerV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainCompletenessState
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainCoverage
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainDigest
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainFormatV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainGraphV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOperation
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainReceiptV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainRootV2

internal object PortableCountDomainJsonV2 {
	fun write(writer: JsonWriter, graph: PortableCountDomainGraphV2) {
		writer.beginObject()
		writer.name("identity").value(graph.identity.value)
		writer.name("contentChecksum").value(graph.contentChecksum.value)
		writer.name("receipts").beginArray()
		graph.receipts.forEach { receipt ->
			writer.beginObject()
			writer.name("identity").value(receipt.identity.value)
			writer.name("domainIdentity").value(receipt.domainIdentity.value)
			writer.name("ownerKind").value(receipt.ownerKind.name)
			writer.name("scopeIdentity").value(receipt.scopeIdentity.value)
			writer.name("ownerIdentity").value(receipt.ownerIdentity.value)
			writer.name("ownerRevision").value(receipt.ownerRevision)
			writer.name("registrationGeneration").value(receipt.registrationGeneration)
			writer.name("collectedDataEpoch").value(receipt.collectedDataEpoch)
			writer.name("authorityRevision").value(receipt.authorityRevision)
			writer.name("authorityFingerprint").value(receipt.authorityFingerprint.value)
			writer.name("coverage").value(receipt.coverage.name)
			writer.name("coverageVersion").value(receipt.coverageVersion.toLong())
			writer.name("countDomainVersion").value(receipt.countDomainVersion.toLong())
			writer.name("effectChecksum").value(receipt.effectChecksum.value)
			writer.name("completenessEvidenceChecksum")
			receipt.completenessEvidenceChecksum?.let { writer.value(it.value) } ?: writer.nullValue()
			writer.endObject()
		}
		writer.endArray()
		writer.name("ownerRevisions").beginArray()
		graph.ownerRevisions.forEach { owner ->
			writer.beginObject()
			writer.name("ownerKind").value(owner.ownerKind.name)
			writer.name("scopeIdentity").value(owner.scopeIdentity.value)
			writer.name("ownerIdentity").value(owner.ownerIdentity.value)
			writer.name("ownerRevision").value(owner.ownerRevision)
			writer.name("operation").value(owner.operation.name)
			writer.name("receiptIdentity")
			owner.receiptIdentity?.let { writer.value(it.value) } ?: writer.nullValue()
			writer.name("ownerEffectChecksum").value(owner.ownerEffectChecksum.value)
			writer.name("linkedAtMs").value(owner.linkedAtMs)
			writer.endObject()
		}
		writer.endArray()
		writer.name("completenessMarkers").beginArray()
		graph.completenessMarkers.forEach { marker ->
			writer.beginObject()
			writer.name("ownerIdentity").value(marker.ownerIdentity.value)
			writer.name("ownerRevision").value(marker.ownerRevision)
			writer.name("terminalState").value(marker.terminalState.name)
			writer.name("lastAdmissionOrdinal")
			marker.lastAdmissionOrdinal?.let { writer.value(it) } ?: writer.nullValue()
			writer.name("lastSourceSequence")
			marker.lastSourceSequence?.let { writer.value(it) } ?: writer.nullValue()
			writer.name("providerFlushOutcome").value(marker.providerFlushOutcome)
			writer.name("registrationRemovalOutcome").value(marker.registrationRemovalOutcome)
			writer.name("registrationTimelineChecksum")
				.value(marker.registrationTimelineChecksum.value)
			writer.name("evidenceChecksum").value(marker.evidenceChecksum.value)
			writer.endObject()
		}
		writer.endArray()
		writer.name("roots").beginArray()
		graph.roots.forEach { root ->
			writer.beginObject()
			writer.name("containerIdentity").value(root.containerIdentity.value)
			writer.name("productIdentity").value(root.productIdentity.value)
			writer.name("ownerKind").value(root.ownerKind.name)
			writer.name("ownerIdentity").value(root.ownerIdentity.value)
			writer.name("ownerRevision").value(root.ownerRevision)
			writer.endObject()
		}
		writer.endArray()
		writer.endObject()
	}

	fun read(reader: JsonReader): PortableCountDomainGraphV2 {
		reader.requireToken(JsonToken.BEGIN_OBJECT, "count-domain graph")
		reader.beginObject()
		var identity: String? = null
		var checksum: String? = null
		var receipts: List<PortableCountDomainReceiptV2>? = null
		var owners: List<PortableCountDomainOwnerRevisionV2>? = null
		var markers: List<PortableCountDomainCompletenessMarkerV2>? = null
		var roots: List<PortableCountDomainRootV2>? = null
		val fields = hashSetOf<String>()
		while (reader.hasNext()) {
			val name = reader.nextName()
			if (!fields.add(name)) fail("Duplicate count-domain field $name")
			when (name) {
				"identity" -> identity = reader.requiredString(name)
				"contentChecksum" -> checksum = reader.requiredString(name)
				"receipts" -> receipts = reader.readBoundedArray(
					PortableCountDomainFormatV2.MAX_RECEIPTS,
					::readReceipt,
				)
				"ownerRevisions" -> owners = reader.readBoundedArray(
					PortableCountDomainFormatV2.MAX_OWNER_REVISIONS,
					::readOwner,
				)
				"completenessMarkers" -> markers = reader.readBoundedArray(
					PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS,
					::readMarker,
				)
				"roots" -> roots = reader.readBoundedArray(
					PortableCountDomainFormatV2.MAX_ROOTS,
					::readRoot,
				)
				else -> fail("Unknown count-domain field $name")
			}
		}
		reader.endObject()
		return checked {
			PortableCountDomainGraphV2(
				PortableCountDomainOpaqueIdentity(identity ?: fail("Missing graph identity")),
				PortableCountDomainOpaqueIdentity(checksum ?: fail("Missing graph checksum")),
				receipts ?: fail("Missing graph receipts"),
				owners ?: fail("Missing graph owners"),
				markers ?: fail("Missing graph completeness markers"),
				roots ?: fail("Missing graph roots"),
			)
		}
	}

	private fun readReceipt(reader: JsonReader): PortableCountDomainReceiptV2 {
		val values = reader.readObject(
			setOf(
				"identity", "domainIdentity", "ownerKind", "scopeIdentity", "ownerIdentity",
				"ownerRevision", "registrationGeneration", "collectedDataEpoch",
				"authorityRevision", "authorityFingerprint", "coverage", "coverageVersion",
				"countDomainVersion", "effectChecksum", "completenessEvidenceChecksum",
			),
		)
		return checked {
			PortableCountDomainReceiptV2(
				identity = PortableCountDomainOpaqueIdentity(values.string("identity")),
				domainIdentity = PortableCountDomainOpaqueIdentity(values.string("domainIdentity")),
				ownerKind = PortableCountDomainOwnerKind.valueOf(values.string("ownerKind")),
				scopeIdentity = PortableCountDomainOpaqueIdentity(values.string("scopeIdentity")),
				ownerIdentity = PortableCountDomainOpaqueIdentity(values.string("ownerIdentity")),
				ownerRevision = values.long("ownerRevision"),
				registrationGeneration = values.long("registrationGeneration"),
				collectedDataEpoch = values.long("collectedDataEpoch"),
				authorityRevision = values.long("authorityRevision"),
				authorityFingerprint =
					PortableCountDomainDigest(values.string("authorityFingerprint")),
				coverage = PortableCountDomainCoverage.valueOf(values.string("coverage")),
				coverageVersion = values.int("coverageVersion"),
				countDomainVersion = values.int("countDomainVersion"),
				effectChecksum = PortableCountDomainDigest(values.string("effectChecksum")),
				completenessEvidenceChecksum = values.nullableString(
					"completenessEvidenceChecksum",
				)?.let(::PortableCountDomainDigest),
			)
		}
	}

	private fun readOwner(reader: JsonReader): PortableCountDomainOwnerRevisionV2 {
		val values = reader.readObject(
			setOf(
				"ownerKind", "scopeIdentity", "ownerIdentity", "ownerRevision", "operation",
				"receiptIdentity", "ownerEffectChecksum", "linkedAtMs",
			),
		)
		return checked {
			PortableCountDomainOwnerRevisionV2(
				ownerKind = PortableCountDomainOwnerKind.valueOf(values.string("ownerKind")),
				scopeIdentity = PortableCountDomainOpaqueIdentity(values.string("scopeIdentity")),
				ownerIdentity = PortableCountDomainOpaqueIdentity(values.string("ownerIdentity")),
				ownerRevision = values.long("ownerRevision"),
				operation = PortableCountDomainOperation.valueOf(values.string("operation")),
				receiptIdentity = values.nullableString("receiptIdentity")
					?.let(::PortableCountDomainOpaqueIdentity),
				ownerEffectChecksum =
					PortableCountDomainDigest(values.string("ownerEffectChecksum")),
				linkedAtMs = values.long("linkedAtMs"),
			)
		}
	}

	private fun readMarker(reader: JsonReader): PortableCountDomainCompletenessMarkerV2 {
		val values = reader.readObject(
			setOf(
				"ownerIdentity", "ownerRevision", "terminalState", "lastAdmissionOrdinal",
				"lastSourceSequence", "providerFlushOutcome", "registrationRemovalOutcome",
				"registrationTimelineChecksum", "evidenceChecksum",
			),
		)
		return checked {
			PortableCountDomainCompletenessMarkerV2(
				ownerIdentity = PortableCountDomainOpaqueIdentity(values.string("ownerIdentity")),
				ownerRevision = values.long("ownerRevision"),
				terminalState =
					PortableCountDomainCompletenessState.valueOf(values.string("terminalState")),
				lastAdmissionOrdinal = values.nullableLong("lastAdmissionOrdinal"),
				lastSourceSequence = values.nullableLong("lastSourceSequence"),
				providerFlushOutcome = values.string("providerFlushOutcome"),
				registrationRemovalOutcome = values.string("registrationRemovalOutcome"),
				registrationTimelineChecksum =
					PortableCountDomainDigest(values.string("registrationTimelineChecksum")),
				evidenceChecksum = PortableCountDomainDigest(values.string("evidenceChecksum")),
			)
		}
	}

	private fun readRoot(reader: JsonReader): PortableCountDomainRootV2 {
		val values = reader.readObject(
			setOf(
				"containerIdentity", "productIdentity", "ownerKind", "ownerIdentity",
				"ownerRevision",
			),
		)
		return checked {
			PortableCountDomainRootV2(
				containerIdentity =
					PortableCountDomainOpaqueIdentity(values.string("containerIdentity")),
				productIdentity = PortableCountDomainOpaqueIdentity(values.string("productIdentity")),
				ownerKind = PortableCountDomainOwnerKind.valueOf(values.string("ownerKind")),
				ownerIdentity = PortableCountDomainOpaqueIdentity(values.string("ownerIdentity")),
				ownerRevision = values.long("ownerRevision"),
			)
		}
	}
}

private sealed interface PortableJsonValue {
	data class Text(val value: String) : PortableJsonValue
	data class Integer(val value: Long) : PortableJsonValue
	data object Null : PortableJsonValue
}

private fun JsonReader.readObject(allowed: Set<String>): Map<String, PortableJsonValue> {
	requireToken(JsonToken.BEGIN_OBJECT, "object")
	beginObject()
	val values = linkedMapOf<String, PortableJsonValue>()
	while (hasNext()) {
		val name = nextName()
		if (name !in allowed) fail("Unknown field $name")
		if (values.containsKey(name)) fail("Duplicate field $name")
		values[name] = when (peek()) {
			JsonToken.STRING -> PortableJsonValue.Text(nextString())
			JsonToken.NUMBER -> PortableJsonValue.Integer(exactLong(name))
			JsonToken.NULL -> {
				nextNull()
				PortableJsonValue.Null
			}
			else -> fail("Invalid value for $name")
		}
	}
	endObject()
	if (values.keys != allowed) fail("Missing object fields")
	return values
}

private fun Map<String, PortableJsonValue>.string(name: String): String =
	(this[name] as? PortableJsonValue.Text)?.value ?: fail("Invalid string $name")

private fun Map<String, PortableJsonValue>.nullableString(name: String): String? =
	when (val value = this[name]) {
		is PortableJsonValue.Text -> value.value
		PortableJsonValue.Null -> null
		else -> fail("Invalid nullable string $name")
	}

private fun Map<String, PortableJsonValue>.long(name: String): Long =
	(this[name] as? PortableJsonValue.Integer)?.value ?: fail("Invalid integer $name")

private fun Map<String, PortableJsonValue>.nullableLong(name: String): Long? =
	when (val value = this[name]) {
		is PortableJsonValue.Integer -> value.value
		PortableJsonValue.Null -> null
		else -> fail("Invalid nullable integer $name")
	}

private fun Map<String, PortableJsonValue>.int(name: String): Int =
	try {
		Math.toIntExact(long(name))
	} catch (_: ArithmeticException) {
		fail("Integer $name is out of range")
	}

private inline fun <T> JsonReader.readBoundedArray(
	maximum: Int,
	readValue: (JsonReader) -> T,
): List<T> {
	requireToken(JsonToken.BEGIN_ARRAY, "array")
	beginArray()
	val result = mutableListOf<T>()
	while (hasNext()) {
		if (result.size >= maximum) fail("Portable count-domain array exceeds its bound")
		result += readValue(this)
	}
	endArray()
	return result
}

private fun JsonReader.requiredString(name: String): String {
	requireToken(JsonToken.STRING, name)
	return nextString()
}

private fun JsonReader.exactLong(name: String): Long {
	requireToken(JsonToken.NUMBER, name)
	val token = nextString()
	if (!INTEGER_TOKEN.matches(token)) fail("Invalid integer $name")
	return token.toLongOrNull() ?: fail("Integer $name is out of range")
}

private fun JsonReader.requireToken(expected: JsonToken, name: String) {
	if (peek() != expected) fail("Invalid $name")
}

private inline fun <T> checked(block: () -> T): T = try {
	block()
} catch (failure: PortableStepsJsonException) {
	throw failure
} catch (failure: PortableAmbientStepsFormatException) {
	throw failure
} catch (_: IllegalArgumentException) {
	fail("Invalid portable count-domain graph")
} catch (_: ArithmeticException) {
	fail("Invalid portable count-domain graph")
}

private fun fail(message: String): Nothing = throw PortableCountDomainJsonException(message)

internal class PortableCountDomainJsonException(message: String) :
	IllegalArgumentException(message)

private val INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
