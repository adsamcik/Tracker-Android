package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Canonical integrity contract shared by manifest writers, history, and product readers. */
object SessionManifestIntegrity {
	private const val FORMAT = "session-manifest-v2"
	private const val POLICY_RECONCILIATION = "POLICY_RECONCILIATION"

	fun compute(
		manifest: SessionManifestVersionEntity,
		sources: Collection<SessionManifestSourceEntity>,
	): String {
		require(sources.all { source ->
			source.logicalTrackingId == manifest.logicalTrackingId &&
				source.manifestRevision == manifest.manifestRevision
		}) { "Manifest sources must belong to the exact manifest version" }
		val canonical = encode(
			listOf(
				FORMAT,
				manifest.logicalTrackingId,
				manifest.manifestRevision,
				manifest.serviceRunId,
				manifest.sessionMode,
				manifest.sourcePolicyRevision,
				manifest.acquisitionPlanRevision,
				manifest.rolloutRevision,
				manifest.startOrigin,
				manifest.effectiveBootId,
				manifest.effectiveElapsedRealtimeNanos,
				manifest.effectiveWallTimeMs,
				manifest.zoneId,
				manifest.automationEpoch,
				manifest.changeReason,
				sources.sortedBy(::sourceIdentity).map { source ->
					listOf(
						source.purpose,
						source.sourceKind,
						source.consentEpoch,
						source.persistenceEligible,
						source.qosCode,
						source.outputDestination,
						source.writerOwner,
						source.writerOwnerGeneration,
						source.writerProjectionId,
						source.writerProjectionVersion,
						source.writerBindingGeneration,
					)
				},
			),
		)
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	fun verify(
		manifest: SessionManifestVersionEntity,
		sources: Collection<SessionManifestSourceEntity>,
	): Boolean = runCatching { compute(manifest, sources) }
		.getOrNull() == manifest.manifestChecksum

	/**
	 * Verifies that immutable manifests form the exact timeline owned by one physical service run.
	 *
	 * Wall time is exact only at the first manifest and otherwise merely nonnegative because the
	 * system clock may change while a run remains active. Elapsed realtime is the ordering authority.
	 * Manifest checksums and source membership remain separate checks for callers with those rows.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	fun hasValidServiceRunTimeline(
		run: SourceServiceRunEntity,
		manifests: Collection<SessionManifestVersionEntity>,
	): Boolean {
		if (run.serviceRunId.isBlank() || run.logicalTrackingId.isBlank() || run.bootId.isBlank() ||
			run.startOrigin.isBlank() || run.startedAtMs < 0L || run.startedElapsedNanos < 0L ||
			run.preparedManifestRevision <= 0L || run.desiredPlanRevision <= 0L ||
			run.preparedIntentRevision <= 0L || run.rolloutRevision <= 0L ||
			run.leaseGeneration <= 0L || run.runRevision <= 0L ||
			run.startCommandGeneration <= 0L || run.startDeliveryToken.isNullOrBlank() ||
			manifests.isEmpty()
		) {
			return false
		}
		val ordered = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		val first = ordered.first()
		if (ordered.map(SessionManifestVersionEntity::manifestRevision).distinct().size != ordered.size ||
			first.manifestRevision != run.preparedManifestRevision ||
			first.effectiveWallTimeMs != run.startedAtMs ||
			first.effectiveElapsedRealtimeNanos != run.startedElapsedNanos ||
			first.effectiveBootId != run.bootId || first.startOrigin != run.startOrigin ||
			ordered.last().acquisitionPlanRevision != run.desiredPlanRevision
		) {
			return false
		}
		val sessionMode = first.sessionMode.takeIf(String::isNotBlank) ?: return false
		if (sessionModeForInitialOrigin(run.startOrigin) != sessionMode) {
			return false
		}
		return ordered.withIndex().all { (index, manifest) ->
			if (manifest.logicalTrackingId != run.logicalTrackingId ||
				manifest.serviceRunId != run.serviceRunId || manifest.sessionMode != sessionMode ||
				manifest.sourcePolicyRevision <= 0L || manifest.acquisitionPlanRevision <= 0L ||
				manifest.rolloutRevision != run.rolloutRevision || manifest.startOrigin.isBlank() ||
				manifest.effectiveBootId != run.bootId ||
				manifest.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
				manifest.effectiveWallTimeMs < 0L || manifest.zoneId.isBlank() ||
				manifest.changeReason.isBlank()
			) {
				false
			} else if (index == 0) {
				manifest.startOrigin == run.startOrigin
			} else {
				val previous = ordered[index - 1]
				val expectedRevision = try {
					Math.addExact(previous.manifestRevision, 1L)
				} catch (_: ArithmeticException) {
					return false
				}
				manifest.manifestRevision == expectedRevision &&
					manifest.startOrigin == POLICY_RECONCILIATION &&
					manifest.changeReason == POLICY_RECONCILIATION &&
					manifest.effectiveElapsedRealtimeNanos >=
					previous.effectiveElapsedRealtimeNanos
			}
		}
	}

	/**
	 * Verifies the exact logical manifest revision union across physical replacement runs.
	 *
	 * Revision identity is the cross-run authority: wall time can jump, while elapsed realtime is
	 * comparable only inside one boot/run timeline. Every run must therefore own one contiguous,
	 * noninterleaved revision slice and the global union must be exactly `1..maxRevision`.
	 */
	fun hasValidLogicalManifestRevisionUnion(
		manifestRevisionsByRun: Collection<Collection<Long>>,
	): Boolean {
		val slices = validLogicalManifestRevisionSlices(manifestRevisionsByRun) ?: return false
		return slices.first().first() == 1L
	}

	/**
	 * Verifies a contiguous, noninterleaved subset of logical manifest revisions.
	 *
	 * Day-local consumers may see only the replacement runs that contribute to that day, so the
	 * first retained revision need not be one. Gaps and interleaving inside the observed subset are
	 * still unverifiable.
	 */
	fun hasValidLogicalManifestRevisionSliceUnion(
		manifestRevisionsByRun: Collection<Collection<Long>>,
	): Boolean = validLogicalManifestRevisionSlices(manifestRevisionsByRun) != null

	@Suppress("ReturnCount")
	private fun validLogicalManifestRevisionSlices(
		manifestRevisionsByRun: Collection<Collection<Long>>,
	): List<List<Long>>? {
		if (manifestRevisionsByRun.isEmpty() || manifestRevisionsByRun.any { it.isEmpty() }) {
			return null
		}
		val slices = manifestRevisionsByRun.map { revisions ->
			val ordered = revisions.sorted()
			if (ordered.any { revision -> revision <= 0L } ||
				ordered.distinct().size != ordered.size || !hasExactSuccessors(ordered)
			) {
				return null
			}
			ordered
		}.sortedBy { it.first() }
		val union = slices.flatten().sorted()
		if (union.distinct().size != union.size || !hasExactSuccessors(union)) {
			return null
		}
		if (!slices.zipWithNext().all { (left, right) ->
			exactSuccessor(left.last()) == right.first()
		}) {
			return null
		}
		return slices
	}

	private fun hasExactSuccessors(ordered: List<Long>): Boolean =
		ordered.zipWithNext().all { (left, right) -> exactSuccessor(left) == right }

	private fun exactSuccessor(revision: Long): Long? = try {
		Math.addExact(revision, 1L)
	} catch (_: ArithmeticException) {
		null
	}

	private fun sessionModeForInitialOrigin(origin: String): String? = when (origin) {
		"MANUAL_FOREGROUND_START", "RECOVERY" -> "MANUAL"
		"AUTOMATIC_BACKGROUND_START" -> "AUTOMATIC"
		else -> null
	}

	private fun sourceIdentity(source: SessionManifestSourceEntity): String = encode(
		listOf(
			source.logicalTrackingId,
			source.manifestRevision,
			source.purpose,
			source.sourceKind,
			source.consentEpoch,
			source.persistenceEligible,
			source.qosCode,
			source.outputDestination,
			source.writerOwner,
			source.writerOwnerGeneration,
			source.writerProjectionId,
			source.writerProjectionVersion,
			source.writerBindingGeneration,
		),
	)

	private fun encode(value: Any?): String {
		val tagged = when (value) {
			null -> "N"
			is Iterable<*> -> "L${value.count()}:" + value.joinToString(separator = "") { item -> encode(item) }
			is Array<*> -> "L${value.size}:" + value.joinToString(separator = "") { item -> encode(item) }
			else -> "S${value}"
		}
		return "${tagged.length}:$tagged"
	}
}
