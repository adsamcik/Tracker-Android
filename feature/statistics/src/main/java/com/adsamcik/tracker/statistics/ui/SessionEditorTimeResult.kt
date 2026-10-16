package com.adsamcik.tracker.statistics.ui

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal sealed interface SessionEditorTimeResult {
	data class Exact(val epochMs: Long) : SessionEditorTimeResult
	data class ChooseOffset(val offsets: List<ZoneOffset>) : SessionEditorTimeResult
	data object Nonexistent : SessionEditorTimeResult
	data object Invalid : SessionEditorTimeResult
}

/** Never lets java.time choose an offset or normalize a nonexistent local time silently. */
internal fun resolveSessionEditorTime(date: String, time: String, zoneId: String,
	selectedOffset: String?): SessionEditorTimeResult = try {
	val local = LocalDate.parse(date).atTime(LocalTime.parse(time))
	val offsets = ZoneId.of(zoneId).rules.getValidOffsets(local)
	when {
		offsets.isEmpty() -> SessionEditorTimeResult.Nonexistent
		offsets.size > 1 && offsets.none { it.id == selectedOffset } -> SessionEditorTimeResult.ChooseOffset(offsets)
		else -> SessionEditorTimeResult.Exact(local.toInstant(
			offsets.firstOrNull { it.id == selectedOffset } ?: offsets.single()).toEpochMilli())
	}
} catch (_: DateTimeException) { SessionEditorTimeResult.Invalid }
catch (_: ArithmeticException) { SessionEditorTimeResult.Invalid }
