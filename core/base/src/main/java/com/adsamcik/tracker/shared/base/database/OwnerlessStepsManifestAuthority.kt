package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.RawSessionManifestVersion
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class OwnerlessStepsManifestRunKey(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

internal class OwnerlessStepsManifestRunTimeline(
	val key: OwnerlessStepsManifestRunKey,
	val run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
) {
	val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
	val sourcesByRevision = mutableMapOf<Long, List<SessionManifestSourceEntity>>()
}


internal class OwnerlessStepsManifestTimelineCache(
	private val maximumDistinctRuns: Int = OWNERLESS_STEPS_MAX_DISTINCT_RUNS,
) {
	private var active = true
	private val timelines = linkedMapOf<OwnerlessStepsManifestRunKey, OwnerlessStepsManifestRunTimeline>()

	init {
		require(maximumDistinctRuns > 0)
	}

	suspend fun getOrLoad(
		key: OwnerlessStepsManifestRunKey,
		load: suspend () -> OwnerlessStepsManifestRunTimeline,
	): OwnerlessStepsManifestRunTimeline {
		check(active) { "Ownerless Steps manifest timeline cache is no longer active" }
		currentCoroutineContext().ensureActive()
		timelines[key]?.let { return it }
		if (timelines.size >= maximumDistinctRuns) {
			throw CountDomainMaintenanceOverflowException()
		}
		val loaded = load()
		currentCoroutineContext().ensureActive()
		if (loaded.key != key) {
			throw CountDomainStoredEvidenceException()
		}
		timelines[key] = loaded
		return loaded
	}

	fun clearForMutation() {
		check(active) { "Ownerless Steps manifest timeline cache is no longer active" }
		timelines.clear()
	}

	fun invalidate() {
		timelines.clear()
		active = false
	}
}


internal suspend fun authenticateOwnerlessStepsServiceRunManifestTimeline(
	run: SourceServiceRunEntity,
	loadPage: suspend (afterRevision: Long, limit: Int) -> List<RawSessionManifestVersion>,
): List<SessionManifestVersionEntity> {
	val manifests = ArrayList<SessionManifestVersionEntity>()
	var afterRevision = 0L
	var expectedContinuation: SessionManifestVersionEntity? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS - manifests.size
		requireOwnerlessManifestCapacity(remaining)
		val pageCapacity = minOf(OWNERLESS_STEPS_MANIFEST_PAGE_SIZE, remaining)
		val queryLimit = Math.addExact(pageCapacity, 1)
		val rawPage = loadPage(afterRevision, queryLimit)
		requireOwnerlessManifestPageShape(rawPage, queryLimit, expectedContinuation)
		if (rawPage.isEmpty()) {
			break
		}
		val page = authenticateOwnerlessManifestPage(run, rawPage, afterRevision, expectedContinuation)
		val accepted = page.take(pageCapacity)
		manifests += accepted
		val hasLookahead = page.size > pageCapacity
		if (!hasLookahead) {
			break
		}
		requireOwnerlessManifestCapacity(OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS - manifests.size)
		expectedContinuation = page[pageCapacity]
		afterRevision = accepted.last().manifestRevision
	}
	if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
		throw CountDomainStoredEvidenceException()
	}
	return manifests
}


private fun requireOwnerlessManifestCapacity(remaining: Int) {
	if (remaining <= 0) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun requireOwnerlessManifestPageShape(
	rawPage: List<RawSessionManifestVersion>,
	queryLimit: Int,
	expectedContinuation: SessionManifestVersionEntity?,
) {
	if (rawPage.size > queryLimit) {
		throw CountDomainStoredEvidenceException()
	}
	if (rawPage.isEmpty() && expectedContinuation != null) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun authenticateOwnerlessManifestPage(
	run: SourceServiceRunEntity,
	rawPage: List<RawSessionManifestVersion>,
	afterRevision: Long,
	expectedContinuation: SessionManifestVersionEntity?,
): List<SessionManifestVersionEntity> {
	val page = rawPage.map { raw -> raw.validatedOrNull() ?: throw CountDomainStoredEvidenceException() }
	if (page.first().manifestRevision <= afterRevision || !page.belongsToStepsServiceRun(run) ||
		page.zipWithNext().any { (prior, next) -> next.manifestRevision <= prior.manifestRevision } ||
		expectedContinuation?.let { expected -> page.first() != expected } == true) {
		throw CountDomainStoredEvidenceException()
	}
	return page
}

private fun List<SessionManifestVersionEntity>.belongsToStepsServiceRun(run: SourceServiceRunEntity): Boolean =
	all { manifest -> manifest.logicalTrackingId == run.logicalTrackingId && manifest.serviceRunId == run.serviceRunId }

internal suspend fun authenticateOwnerlessStepsManifestSourceBindings(
	timeline: OwnerlessStepsManifestRunTimeline,
	manifestRevisions: Collection<Long>,
	loadChunk: suspend (
		manifestRevisions: List<Long>,
		limit: Int,
	) -> List<SessionManifestSourceEntity.RawSessionManifestSource>,
) {
	val revisions = manifestRevisions.distinct().sorted()
	if (revisions.isEmpty() || revisions.size > OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS ||
		revisions.any { revision -> revision !in timeline.manifestsByRevision }) {
		throw CountDomainStoredEvidenceException()
	}
	val uncachedRevisions = revisions.filterNot(timeline.sourcesByRevision::containsKey)
	val loadedByRevision = linkedMapOf<Long, List<SessionManifestSourceEntity>>()
	for (revisionChunk in uncachedRevisions.chunked(OWNERLESS_STEPS_MANIFEST_SOURCE_QUERY_CHUNK)) {
		currentCoroutineContext().ensureActive()
		val bindingLimit = Math.addExact(
			Math.multiplyExact(revisionChunk.size, OWNERLESS_STEPS_MAX_MANIFEST_SOURCES), 1,
		)
		val rawBindings = loadChunk(revisionChunk, bindingLimit)
		currentCoroutineContext().ensureActive()
		val bindingsByRevision = authenticateOwnerlessManifestBindingChunk(
			timeline.key.logicalTrackingId, revisionChunk, rawBindings, bindingLimit,
		)
		appendAuthenticatedOwnerlessManifestBindings(timeline, revisionChunk, bindingsByRevision, loadedByRevision)
	}
	currentCoroutineContext().ensureActive()
	timeline.sourcesByRevision.putAll(loadedByRevision)
	if (revisions.any { revision -> revision !in timeline.sourcesByRevision }) {
		throw CountDomainStoredEvidenceException()
	}
}

private suspend fun authenticateOwnerlessManifestBindingChunk(
	logicalTrackingId: String,
	revisionChunk: List<Long>,
	rawBindings: List<SessionManifestSourceEntity.RawSessionManifestSource>,
	bindingLimit: Int,
): Map<Long, LinkedHashMap<Pair<Int, String>, SessionManifestSourceEntity>> {
	if (rawBindings.size >= bindingLimit) {
		throw CountDomainStoredEvidenceException()
	}
	val bindingsByRevision = linkedMapOf<Long, LinkedHashMap<Pair<Int, String>, SessionManifestSourceEntity>>()
	rawBindings.forEachIndexed { index, rawBinding ->
		if (index % OWNERLESS_STEPS_CANCELLATION_STRIDE == 0) {
			currentCoroutineContext().ensureActive()
		}
		val binding = authenticateOwnerlessManifestSourceBinding(rawBinding, logicalTrackingId, revisionChunk)
		val revisionBindings = bindingsByRevision.getOrPut(binding.manifestRevision) { linkedMapOf() }
		val bindingKey = binding.sourceKind to binding.purpose
		if (revisionBindings.put(bindingKey, binding) != null ||
			revisionBindings.size > OWNERLESS_STEPS_MAX_MANIFEST_SOURCES) {
			throw CountDomainStoredEvidenceException()
		}
	}
	return bindingsByRevision
}

private fun authenticateOwnerlessManifestSourceBinding(
	rawBinding: SessionManifestSourceEntity.RawSessionManifestSource,
	logicalTrackingId: String,
	revisionChunk: List<Long>,
): SessionManifestSourceEntity {
	val binding = rawBinding.validatedOrNull()
	if (binding == null || binding.logicalTrackingId != logicalTrackingId || binding.manifestRevision !in revisionChunk) {
		throw CountDomainStoredEvidenceException()
	}
	return binding
}

private suspend fun appendAuthenticatedOwnerlessManifestBindings(
	timeline: OwnerlessStepsManifestRunTimeline,
	revisionChunk: List<Long>,
	bindingsByRevision: Map<Long, LinkedHashMap<Pair<Int, String>, SessionManifestSourceEntity>>,
	loadedByRevision: MutableMap<Long, List<SessionManifestSourceEntity>>,
) {
	revisionChunk.forEachIndexed { index, revision ->
		if (index % OWNERLESS_STEPS_CANCELLATION_STRIDE == 0) {
			currentCoroutineContext().ensureActive()
		}
		val manifest = timeline.manifestsByRevision[revision] ?: throw CountDomainStoredEvidenceException()
		val bindings = bindingsByRevision[revision]?.values?.toList() ?: throw CountDomainStoredEvidenceException()
		rememberAuthenticatedOwnerlessManifestBindings(revision, manifest, bindings, loadedByRevision)
	}
}

private fun rememberAuthenticatedOwnerlessManifestBindings(
	revision: Long,
	manifest: SessionManifestVersionEntity,
	bindings: List<SessionManifestSourceEntity>,
	loadedByRevision: MutableMap<Long, List<SessionManifestSourceEntity>>,
) {
	if (!SessionManifestIntegrity.verify(manifest, bindings) || loadedByRevision.put(revision, bindings) != null) {
		throw CountDomainStoredEvidenceException()
	}
}


internal const val OWNERLESS_STEPS_MAX_DISTINCT_RUNS = 64
private const val OWNERLESS_STEPS_MANIFEST_PAGE_SIZE = 64
private const val OWNERLESS_STEPS_MAX_MANIFEST_REVISIONS = 2_048
private const val OWNERLESS_STEPS_MAX_MANIFEST_SOURCES = 16
private const val OWNERLESS_STEPS_MANIFEST_SOURCE_QUERY_CHUNK = 100
private const val OWNERLESS_STEPS_CANCELLATION_STRIDE = 64
