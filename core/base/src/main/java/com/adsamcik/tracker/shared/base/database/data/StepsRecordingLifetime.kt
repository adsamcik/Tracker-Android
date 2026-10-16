package com.adsamcik.tracker.shared.base.database.data

/** Source-local recording boundaries over an already authenticated complete native run timeline. */
object StepsRecordingLifetime {
	/** Quality may change, but the recording's source, permission, writer, run and clock may not. */
	// Keep this immutable pairwise field correspondence together; reference traversal remains separate.
	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	fun isContinuation(
		origin: SessionManifestVersionEntity,
		originBinding: SessionManifestSourceEntity,
		current: SessionManifestVersionEntity,
		currentBinding: SessionManifestSourceEntity,
	): Boolean = originBinding.stepsRecordingManifestRevision == origin.manifestRevision &&
		currentBinding.stepsRecordingManifestRevision == origin.manifestRevision &&
		eligible(origin, originBinding) && eligible(current, currentBinding) &&
		origin.logicalTrackingId == current.logicalTrackingId &&
		origin.serviceRunId == current.serviceRunId && origin.sessionMode == current.sessionMode &&
		origin.effectiveBootId == current.effectiveBootId && origin.zoneId == current.zoneId &&
		origin.rolloutRevision == current.rolloutRevision &&
		origin.effectiveElapsedRealtimeNanos <= current.effectiveElapsedRealtimeNanos &&
		originBinding.consentEpoch == currentBinding.consentEpoch &&
		originBinding.outputDestination == currentBinding.outputDestination &&
		originBinding.writerOwner == currentBinding.writerOwner &&
		originBinding.writerOwnerGeneration == currentBinding.writerOwnerGeneration &&
		originBinding.writerProjectionId == currentBinding.writerProjectionId &&
		originBinding.writerProjectionVersion == currentBinding.writerProjectionVersion &&
		originBinding.writerBindingGeneration == currentBinding.writerBindingGeneration

	/** Rejects an origin revived after a missing binding or any actual recording boundary. */
	fun hasValidReferences(
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): Boolean {
		val ordered = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (ordered.isEmpty() || ordered.map { it.manifestRevision }.distinct().size != ordered.size ||
			!ordered.zipWithNext().all { (left, right) ->
				left.manifestRevision != Long.MAX_VALUE && right.manifestRevision == left.manifestRevision + 1L
			}
		) {
			return false
		}
		val byRevision = ordered.associateBy(SessionManifestVersionEntity::manifestRevision)
		for ((index, manifest) in ordered.withIndex()) {
			if (!hasValidRecordingReference(manifest, index, ordered, byRevision, bindings)) {
				return false
			}
		}
		return true
	}

	/** Resolves one manifest's origin against its immediate predecessor and retained run. */
	// Ordered rejection guards keep each missing reference visible without nested branches.
	@Suppress("ReturnCount")
	private fun hasValidRecordingReference(
		manifest: SessionManifestVersionEntity,
		index: Int,
		ordered: List<SessionManifestVersionEntity>,
		byRevision: Map<Long, SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): Boolean {
		val binding = bindings[manifest.manifestRevision] ?: return true
		if (!eligible(manifest, binding)) {
			return false
		}
		val originRevision = binding.stepsRecordingManifestRevision ?: return true
		if (originRevision == manifest.manifestRevision) {
			return true
		}
		val previous = ordered.getOrNull(index - 1) ?: return false
		if (bindings[previous.manifestRevision]?.stepsRecordingManifestRevision != originRevision) {
			return false
		}
		val origin = byRevision[originRevision] ?: return false
		val originBinding = bindings[originRevision] ?: return false
		return isContinuation(origin, originBinding, manifest, binding)
	}

	/** Prepares source-local bounds once for a retained run; invalid references have no authority. */
	fun validatedIntervals(
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): Map<Long, LongRange>? {
		if (!hasValidReferences(manifests, bindings)) {
			return null
		}
		val intervals = linkedMapOf<Long, LongRange>()
		var nextManifest: SessionManifestVersionEntity? = null
		var nextOrigin: Long? = null
		var nextRecordingBoundary: Long? = null
		for (manifest in manifests.sortedByDescending(SessionManifestVersionEntity::manifestRevision)) {
			val binding = bindings[manifest.manifestRevision]
			val origin = binding?.stepsRecordingManifestRevision
			if (origin != nextOrigin) {
				nextRecordingBoundary = nextManifest?.effectiveElapsedRealtimeNanos
			}
			// Facts keep their original attribution; a continuation is not another origin.
			if (binding != null && (origin == null || origin == manifest.manifestRevision)) {
				val boundary = if (origin == null) {
					nextManifest?.effectiveElapsedRealtimeNanos
				} else {
					nextRecordingBoundary
				}
				intervals[manifest.manifestRevision] = manifest.effectiveElapsedRealtimeNanos..
					(boundary?.minus(1L) ?: Long.MAX_VALUE)
			}
			nextManifest = manifest
			nextOrigin = origin
		}
		return intervals
	}

	/** Null-origin legacy facts end at the next global manifest, exactly as before. */
	fun containsInterval(
		originRevision: Long,
		startElapsedNanos: Long,
		endElapsedNanos: Long,
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): Boolean {
		if (startElapsedNanos < 0L || endElapsedNanos < startElapsedNanos) {
			return false
		}
		val bounds = validatedIntervals(manifests, bindings)?.get(originRevision) ?: return false
		return startElapsedNanos in bounds && endElapsedNanos in bounds
	}

	private fun eligible(manifest: SessionManifestVersionEntity, binding: SessionManifestSourceEntity): Boolean =
		binding.logicalTrackingId == manifest.logicalTrackingId &&
			binding.manifestRevision == manifest.manifestRevision &&
			binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			binding.purpose == SourceBrokerPurpose.SESSION_CAPTURE && binding.persistenceEligible
}
