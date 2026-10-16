package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import kotlinx.coroutines.CancellationException

/** Optional native recording metadata. Names never admit, discard, clip or change a reading. */
internal class MeasuredNativeSessionNames private constructor(private val names: Map<String, Bounds>) {
    fun point(logicalId: String, timeMs: Long): String? = names[logicalId]?.takeIf {
        if (it.toMs == it.fromMs) {
            timeMs == it.fromMs
        } else {
            timeMs >= it.fromMs && (it.toMs == null || timeMs < it.toMs)
        }
    }?.name

    /** A crossing measured window keeps its original bounds; a label never prorates its value. */
    fun window(logicalId: String, startMs: Long, endMs: Long): String? =
        names[logicalId]?.takeIf {
            if (startMs == endMs) {
                if (it.toMs == it.fromMs) {
                startMs == it.fromMs
            } else {
                startMs >= it.fromMs && (it.toMs == null || startMs < it.toMs)
            }
            }
            else {
                maxOf(startMs, endMs) > it.fromMs && (it.toMs == null || minOf(startMs,
                    endMs) < if (it.toMs == it.fromMs && it.toMs < Long.MAX_VALUE) {
                it.toMs + 1L
            } else {
                it.toMs
            })
            }
        }?.name

    private data class Bounds(val name: String, val fromMs: Long, val toMs: Long?)

    companion object {
        suspend fun load(database: AppDatabase, logicalIds: Collection<String>): MeasuredNativeSessionNames {
            val ids = logicalIds.distinct()
            require(ids.size <= MAX_SESSION_NAME_SELECTION)
            if (ids.isEmpty()) {
                return MeasuredNativeSessionNames(emptyMap())
            }
            return try {
                val overrides = ids.map { "logical:$it" }.chunked(64)
                    .flatMap { database.organizationAnnotationDao().byAdapters(it) }
                    .associateBy { it.recordingAdapterKey }
                val sessions = ids.chunked(64).flatMap { database.sourceSessionDao().sessions(it) }
                val names = sessions.mapNotNull { session -> namedSession(session, overrides) }.toMap()
                MeasuredNativeSessionNames(names)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MeasuredNativeSessionNames(emptyMap()) }
        }

        // One immutable session/annotation projection keeps missing metadata and invalid saved bounds together.
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        private fun namedSession(
            session: LogicalTrackingSessionEntity,
            overrides: Map<String?, OrganizationAnnotationEntity>,
        ): Pair<String, Bounds>? {
            val annotation = overrides["logical:${session.logicalTrackingId}"] ?: return null
            val name = annotation.name?.takeIf(String::isNotBlank) ?: return null
            if (annotation.hidden || annotation.importedSourceSelection != null) {
                return null
            }
            // Same effective metadata bounds as OrganizationAnnotationDao.visible: override, then original session.
            val from = annotation.startTimeMs ?: session.startedAtMs
            val to = annotation.endTimeMs ?: session.cutoffAtMs
            val active = session.state !in setOf("FINALIZED", "CLOSED", "FAILED")
            @Suppress("ComplexCondition")
            if (from < 0L || to != null && to < from || to == null && !active) {
                return null
            }
            return session.logicalTrackingId to Bounds(name, from, to)
        }
    }
}

private const val MAX_SESSION_NAME_SELECTION = 100
