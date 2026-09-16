package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

internal suspend fun encodeAmbientStepsArchive(
	archive: PortableAmbientStepsArchiveV1,
	codec: PortableAmbientStepsJsonV1Codec = PortableAmbientStepsJsonV1Codec(),
): ByteArray {
	val output = ByteArrayOutputStream()
	codec.encode(output) { sink ->
		sink.emit(archive)
		ExportPortableAmbientStepsResult.Exported(
			dayCount = archive.days.size,
			factCount = archive.days.sumOf { it.facts.size },
			gapCount = archive.days.sumOf { it.gaps.size },
		)
	}
	return output.toByteArray()
}

internal fun ambientArchive(
	vararg days: PortableAmbientStepsDayV1,
): PortableAmbientStepsArchiveV1 =
	PortableAmbientStepsArchiveV1.create(days.sortedBy { it.structuralDayStartTimeMs })

internal fun completeAmbientDay(
	date: LocalDate,
	stepCount: Long,
	zoneId: String = "UTC",
	seed: String = date.toString(),
): PortableAmbientStepsDayV1 {
	val (start, end) = ambientDayBounds(date, zoneId)
	val fact = PortableAmbientStepsFactV1.create(
		ambientIdentity(AmbientStepsPortableIdentityKind.FACT, "$seed-fact"),
		start,
		end,
		stepCount,
	)
	return PortableAmbientStepsDayV1.create(
		identity = ambientIdentity(AmbientStepsPortableIdentityKind.DAY, "$seed-day"),
		structuralEpochDay = date.toEpochDay(),
		storedZoneId = zoneId,
		structuralDayStartTimeMs = start,
		structuralDayEndTimeMs = end,
		retainedFromTimeMs = null,
		coverage = PortableAmbientStepsCoverage.COMPLETE,
		partialCauses = emptyList(),
		retainedStepCount = stepCount,
		facts = listOf(fact),
		gaps = emptyList(),
	)
}

internal fun partialAmbientDay(
	date: LocalDate,
	stepCount: Long,
	zoneId: String = "UTC",
	seed: String = date.toString(),
	cause: PortableAmbientStepsPartialCause = PortableAmbientStepsPartialCause.EXPLICIT_GAP,
): PortableAmbientStepsDayV1 {
	val (start, end) = ambientDayBounds(date, zoneId)
	val boundary = start + 60L * 60L * 1_000L
	val gaps = if (cause == PortableAmbientStepsPartialCause.EXPLICIT_GAP) {
		listOf(
			PortableAmbientStepsGapV1.create(
				ambientIdentity(AmbientStepsPortableIdentityKind.GAP, "$seed-gap"),
				start,
				boundary,
				PortableAmbientStepsGapReason.PROCESS_ABSENCE,
			),
		)
	} else {
		emptyList()
	}
	val factStart = when (cause) {
		PortableAmbientStepsPartialCause.EXPLICIT_GAP,
		PortableAmbientStepsPartialCause.RETENTION,
		-> boundary
		PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY -> start
	}
	return PortableAmbientStepsDayV1.create(
		identity = ambientIdentity(AmbientStepsPortableIdentityKind.DAY, "$seed-day"),
		structuralEpochDay = date.toEpochDay(),
		storedZoneId = zoneId,
		structuralDayStartTimeMs = start,
		structuralDayEndTimeMs = end,
		retainedFromTimeMs = boundary.takeIf {
			cause == PortableAmbientStepsPartialCause.RETENTION
		},
		coverage = PortableAmbientStepsCoverage.PARTIAL,
		partialCauses = listOf(cause),
		retainedStepCount = stepCount,
		facts = listOf(
			PortableAmbientStepsFactV1.create(
				ambientIdentity(AmbientStepsPortableIdentityKind.FACT, "$seed-fact"),
				factStart,
				end,
				stepCount,
			),
		),
		gaps = gaps,
	)
}

internal fun ambientIdentity(
	kind: AmbientStepsPortableIdentityKind,
	seed: String,
): AmbientStepsPortableOpaqueIdentity = AmbientStepsPortableOpaqueIdentity.derive(kind, seed)

internal fun ambientDayBounds(date: LocalDate, zoneId: String): Pair<Long, Long> {
	val zone = ZoneId.of(zoneId)
	return date.atStartOfDay(zone).toInstant().toEpochMilli() to
		date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
}

internal fun rehashedAmbientFactChecksum(
	fact: PortableAmbientStepsFactV1,
	stepCount: Long,
): AmbientStepsPortableDigest = ambientDigest(
	"tracker-portable-ambient-steps-fact-v1",
	listOf(fact.identity.value, fact.intervalStartTimeMs, fact.intervalEndTimeMs, stepCount),
)

internal fun rehashedAmbientDayChecksum(
	day: PortableAmbientStepsDayV1,
	retainedStepCount: Long,
	factChecksum: AmbientStepsPortableDigest,
): AmbientStepsPortableDigest = ambientDigest(
	"tracker-portable-ambient-steps-day-v1",
	listOf(
		day.identity.value,
		day.structuralEpochDay,
		day.storedZoneId,
		day.structuralDayStartTimeMs,
		day.structuralDayEndTimeMs,
		day.retainedFromTimeMs,
		day.coverage.name,
		day.partialCauses.map { it.name },
		retainedStepCount,
		listOf(listOf(day.facts.single().identity.value, factChecksum.value)),
		day.gaps.map { listOf(it.identity.value, it.contentChecksum.value) },
	),
)

internal fun rehashedAmbientArchiveChecksum(
	day: PortableAmbientStepsDayV1,
	dayChecksum: AmbientStepsPortableDigest,
): AmbientStepsPortableDigest = ambientDigest(
	"tracker-portable-ambient-steps-archive-v1",
	listOf(
		AmbientStepsPortableFormatV1.FORMAT,
		AmbientStepsPortableFormatV1.SCHEMA_VERSION,
		listOf(listOf(day.identity.value, dayChecksum.value)),
	),
)

private fun ambientDigest(namespace: String, values: Any?): AmbientStepsPortableDigest {
	val digest = MessageDigest.getInstance("SHA-256")
	digest.appendAmbientCanonical(listOf(namespace, values))
	return AmbientStepsPortableDigest("sha256:" + digest.digest().joinToString("") { byte ->
		(byte.toInt() and 0xff).toString(16).padStart(2, '0')
	})
}

private fun MessageDigest.appendAmbientCanonical(value: Any?) {
	when (value) {
		null -> update("N;".toByteArray(Charsets.UTF_8))
		is Int -> appendAmbientCanonical(value.toLong())
		is Long -> update("I$value;".toByteArray(Charsets.UTF_8))
		is String -> {
			val bytes = value.toByteArray(Charsets.UTF_8)
			update("S${bytes.size}:".toByteArray(Charsets.UTF_8))
			update(bytes)
			update(";".toByteArray(Charsets.UTF_8))
		}
		is Collection<*> -> {
			update("L${value.size}[".toByteArray(Charsets.UTF_8))
			value.forEach { item -> appendAmbientCanonical(item) }
			update("];".toByteArray(Charsets.UTF_8))
		}
		else -> error("Unsupported Ambient Steps test checksum value ${value::class.java.name}")
	}
}
