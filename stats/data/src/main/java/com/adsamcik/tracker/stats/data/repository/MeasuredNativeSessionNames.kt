package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import kotlinx.coroutines.CancellationException

/** Optional native recording metadata. Names never admit, discard, clip or change a reading. */
internal class MeasuredNativeSessionNames private constructor(private val names: Map<String, Bounds>) {
    fun point(logicalId: String, timeMs: Long): String? = names[logicalId]?.takeIf {
        timeMs >= it.fromMs && (it.toMs == null || timeMs < it.toMs)
    }?.name

    /** Keep the existing full-window interval rule; Pressure's original end must be strictly inside. */
    fun window(logicalId: String, startMs: Long, endMs: Long, strictEnd: Boolean = false): String? =
        names[logicalId]?.takeIf {
            startMs >= it.fromMs && endMs >= startMs && (it.toMs == null ||
                if (strictEnd) endMs < it.toMs else endMs <= it.toMs && (endMs > startMs || startMs < it.toMs))
        }?.name

    private data class Bounds(val name: String, val fromMs: Long, val toMs: Long?)

    companion object {
        suspend fun load(database: AppDatabase, logicalIds: Collection<String>): MeasuredNativeSessionNames {
            val ids = logicalIds.distinct()
            require(ids.size <= 100)
            if (ids.isEmpty()) return MeasuredNativeSessionNames(emptyMap())
            return try {
                val overrides = ids.map { "logical:$it" }.chunked(64)
                    .flatMap { database.organizationAnnotationDao().byAdapters(it) }.associateBy { it.recordingAdapterKey }
                val sessions = ids.chunked(64).flatMap { database.sourceSessionDao().sessions(it) }
                val names = sessions.mapNotNull { session ->
                    val annotation = overrides["logical:${session.logicalTrackingId}"] ?: return@mapNotNull null
                    val name = annotation.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    if (annotation.hidden || annotation.importedSourceSelection != null) return@mapNotNull null
                    // Same effective metadata bounds as OrganizationAnnotationDao.visible: override, then original session.
                    val from = annotation.startTimeMs ?: session.startedAtMs
                    val to = annotation.endTimeMs ?: session.cutoffAtMs
                    val active = session.state !in setOf("FINALIZED", "CLOSED", "FAILED")
                    if (from < 0L || to != null && to <= from || to == null && !active) return@mapNotNull null
                    session.logicalTrackingId to Bounds(name, from, to)
                }.toMap()
                MeasuredNativeSessionNames(names)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MeasuredNativeSessionNames(emptyMap()) }
        }
    }
}
