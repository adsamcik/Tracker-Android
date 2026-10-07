package com.adsamcik.tracker.shared.base.database.data

/** Source-local recording boundaries over an already authenticated complete native run timeline. */
object StepsRecordingLifetime {
	/** Quality may change, but the recording's source, permission, writer, run and clock may not. */
	@Suppress("ComplexCondition")
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
		) return false
		val byRevision = ordered.associateBy(SessionManifestVersionEntity::manifestRevision)
		for ((index, manifest) in ordered.withIndex()) {
			val binding = bindings[manifest.manifestRevision] ?: continue
			if (!eligible(manifest, binding)) return false
			val originRevision = binding.stepsRecordingManifestRevision ?: continue
			if (originRevision == manifest.manifestRevision) continue
			val previous = ordered.getOrNull(index - 1) ?: return false
			if (bindings[previous.manifestRevision]?.stepsRecordingManifestRevision != originRevision) return false
			val origin = byRevision[originRevision] ?: return false
			val originBinding = bindings[originRevision] ?: return false
			if (!isContinuation(origin, originBinding, manifest, binding)) return false
		}
		return true
	}

	/** Null-origin legacy facts end at the next global manifest, exactly as before. */
	fun containsInterval(
		originRevision: Long,
		startElapsedNanos: Long,
		endElapsedNanos: Long,
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): Boolean {
		if (startElapsedNanos < 0L || endElapsedNanos < startElapsedNanos ||
			!hasValidReferences(manifests, bindings)
		) return false
		val ordered = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		val index = ordered.indexOfFirst { it.manifestRevision == originRevision }
		if (index < 0) return false
		val origin = ordered[index]
		val binding = bindings[originRevision] ?: return false
		if (binding.stepsRecordingManifestRevision != null &&
			binding.stepsRecordingManifestRevision != originRevision
		) return false
		if (startElapsedNanos < origin.effectiveElapsedRealtimeNanos) return false
		val boundary = ordered.drop(index + 1).firstOrNull { manifest ->
			binding.stepsRecordingManifestRevision == null ||
				bindings[manifest.manifestRevision]?.stepsRecordingManifestRevision != originRevision
		}
		return boundary == null || endElapsedNanos < boundary.effectiveElapsedRealtimeNanos
	}

	private fun eligible(manifest: SessionManifestVersionEntity, binding: SessionManifestSourceEntity): Boolean =
		binding.logicalTrackingId == manifest.logicalTrackingId &&
			binding.manifestRevision == manifest.manifestRevision &&
			binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			binding.purpose == SourceBrokerPurpose.SESSION_CAPTURE && binding.persistenceEligible
}
