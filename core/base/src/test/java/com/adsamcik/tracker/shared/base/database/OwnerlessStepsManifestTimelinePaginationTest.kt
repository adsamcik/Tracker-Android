package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.RawSessionManifestVersion
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OwnerlessStepsManifestTimelinePaginationTest {
	@Test
	fun `65 manifests continue after the limit plus one lookahead`() = runTest {
		val rows = manifests(65)
		val requests = mutableListOf<Pair<Long, Int>>()

		val authenticated = authenticateOwnerlessStepsServiceRunManifestTimeline(run(65)) { afterRevision, limit ->
			requests += afterRevision to limit
			rows.filter { requireNotNull(it.manifestRevision) > afterRevision }.take(limit)
		}

		authenticated.map { it.manifestRevision } shouldBe (1L..65L).toList()
		requests shouldBe listOf(0L to 65, 64L to 65)
	}

	@Test
	fun `2048 manifests authenticate in bounded pages without per-manifest queries`() = runTest {
		val rows = manifests(2_048)
		val requests = mutableListOf<Pair<Long, Int>>()

		val authenticated = authenticateOwnerlessStepsServiceRunManifestTimeline(run(2_048)) {
				afterRevision, limit ->
			requests += afterRevision to limit
			rows.filter { requireNotNull(it.manifestRevision) > afterRevision }.take(limit)
		}

		authenticated.size shouldBe 2_048
		authenticated.first().manifestRevision shouldBe 1L
		authenticated.last().manifestRevision shouldBe 2_048L
		requests.size shouldBe 32
		requests.first() shouldBe (0L to 65)
		requests.last() shouldBe (1_984L to 65)
	}

	@Test
	fun `2049th manifest is a limit plus one overflow`() = runTest {
		val rows = manifests(2_049)
		var requests = 0

		shouldThrow<IllegalStateException> {
			authenticateOwnerlessStepsServiceRunManifestTimeline(run(2_049)) {
					afterRevision, limit ->
				requests += 1
				rows.filter { requireNotNull(it.manifestRevision) > afterRevision }.take(limit)
			}
		}

		requests shouldBe 32
	}

	@Test
	fun `out of order manifest page fails closed`() = runTest {
		val rows = manifests(2).reversed()

		shouldThrow<IllegalStateException> {
			authenticateOwnerlessStepsServiceRunManifestTimeline(run(2)) { _, _ -> rows }
		}
	}

	@Test
	fun `later manifest page cancellation propagates`() = runTest {
		val rows = manifests(65)
		var requests = 0

		shouldThrow<CancellationException> {
			authenticateOwnerlessStepsServiceRunManifestTimeline(run(65)) { afterRevision, limit ->
				requests += 1
				if (requests == 2) {
					throw CancellationException("cancel ownerless manifest continuation")
				}
				rows.filter { requireNotNull(it.manifestRevision) > afterRevision }.take(limit)
			}
		}

		requests shouldBe 2
	}

	@Test
	fun `transaction cache reuses one run timeline for 1000 candidates`() = runTest {
		val cache = OwnerlessStepsManifestTimelineCache()
		val key = OwnerlessStepsManifestRunKey(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		var loads = 0

		repeat(1_000) {
			cache.getOrLoad(key) {
				loads += 1
				timeline(key)
			}
		}

		loads shouldBe 1
		cache.invalidate()
	}

	@Test
	fun `transaction cache keeps distinct run timelines separate`() = runTest {
		val cache = OwnerlessStepsManifestTimelineCache()
		val keys = listOf(
			OwnerlessStepsManifestRunKey("logical-a", "run-a"),
			OwnerlessStepsManifestRunKey("logical-b", "run-b"),
		)
		var loads = 0

		keys.forEach { key ->
			repeat(2) {
				cache.getOrLoad(key) {
					loads += 1
					timeline(key)
				}
			}
		}

		loads shouldBe 2
		cache.invalidate()
	}

	@Test
	fun `transaction cache cannot transfer authenticated timelines after invalidation`() = runTest {
		val cache = OwnerlessStepsManifestTimelineCache()
		val key = OwnerlessStepsManifestRunKey(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		cache.getOrLoad(key) { timeline(key) }
		cache.invalidate()

		shouldThrow<IllegalStateException> {
			cache.getOrLoad(key) { timeline(key) }
		}
	}

	@Test
	fun `transaction cache reloads a run after mutation invalidation`() = runTest {
		val cache = OwnerlessStepsManifestTimelineCache()
		val key = OwnerlessStepsManifestRunKey(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		var loads = 0
		repeat(2) {
			cache.getOrLoad(key) {
				loads += 1
				timeline(key)
			}
			cache.clearForMutation()
		}

		loads shouldBe 2
		cache.invalidate()
	}

	@Test
	fun `transaction cache bounds distinct service runs`() = runTest {
		val cache = OwnerlessStepsManifestTimelineCache(maximumDistinctRuns = 1)
		val first = OwnerlessStepsManifestRunKey("logical-a", "run-a")
		val second = OwnerlessStepsManifestRunKey("logical-b", "run-b")
		cache.getOrLoad(first) { timeline(first) }

		shouldThrow<IllegalStateException> {
			cache.getOrLoad(second) { timeline(second) }
		}
		cache.invalidate()
	}

	@Test
	fun `1000 referenced revisions load source bindings in ten bounded chunks`() = runTest {
		val (timeline, rawSources) = sourceAuthenticatedTimeline(1_000)
		val requests = mutableListOf<Pair<List<Long>, Int>>()

		authenticateOwnerlessStepsManifestSourceBindings(
			timeline = timeline,
			manifestRevisions = (1L..1_000L).toList(),
		) { revisions, limit ->
			requests += revisions to limit
			revisions.map(rawSources::getValue)
		}

		requests.size shouldBe 10
		requests.all { (revisions, limit) ->
			revisions.size == 100 && limit == 1_601
		} shouldBe true
		timeline.sourcesByRevision.size shouldBe 1_000
		authenticateOwnerlessStepsManifestSourceBindings(
			timeline = timeline,
			manifestRevisions = (1L..1_000L).toList(),
		) { _, _ ->
			error("Cached manifest source bindings must not be reloaded")
		}
	}

	@Test
	fun `missing referenced source binding fails closed without caching a partial batch`() = runTest {
		val (timeline, rawSources) = sourceAuthenticatedTimeline(2)

		shouldThrow<IllegalStateException> {
			authenticateOwnerlessStepsManifestSourceBindings(
				timeline = timeline,
				manifestRevisions = listOf(1L, 2L),
			) { _, _ ->
				listOf(rawSources.getValue(1L))
			}
		}

		timeline.sourcesByRevision shouldBe emptyMap()
	}

	@Test
	fun `duplicate referenced source binding fails closed`() = runTest {
		val (timeline, rawSources) = sourceAuthenticatedTimeline(1)
		val duplicate = rawSources.getValue(1L)

		shouldThrow<IllegalStateException> {
			authenticateOwnerlessStepsManifestSourceBindings(
				timeline = timeline,
				manifestRevisions = listOf(1L),
			) { _, _ ->
				listOf(duplicate, duplicate)
			}
		}

		timeline.sourcesByRevision shouldBe emptyMap()
	}

	@Test
	fun `source binding limit plus one overflow fails closed`() = runTest {
		val (timeline, rawSources) = sourceAuthenticatedTimeline(1)
		val binding = rawSources.getValue(1L)

		shouldThrow<IllegalStateException> {
			authenticateOwnerlessStepsManifestSourceBindings(
				timeline = timeline,
				manifestRevisions = listOf(1L),
			) { _, limit ->
				List(limit) { binding }
			}
		}

		timeline.sourcesByRevision shouldBe emptyMap()
	}

	@Test
	fun `later source binding chunk cancellation propagates without caching`() = runTest {
		val (timeline, rawSources) = sourceAuthenticatedTimeline(101)
		var requests = 0

		shouldThrow<CancellationException> {
			authenticateOwnerlessStepsManifestSourceBindings(
				timeline = timeline,
				manifestRevisions = (1L..101L).toList(),
			) { revisions, _ ->
				requests += 1
				if (requests == 2) {
					throw CancellationException("cancel ownerless source binding continuation")
				}
				revisions.map(rawSources::getValue)
			}
		}

		requests shouldBe 2
		timeline.sourcesByRevision shouldBe emptyMap()
	}

	private fun manifests(count: Int): List<RawSessionManifestVersion> =
		(1L..count.toLong()).map(::manifest)

	private fun manifest(
		revision: Long,
		logicalTrackingId: String = LOGICAL_TRACKING_ID,
		serviceRunId: String = SERVICE_RUN_ID,
	) = RawSessionManifestVersion(
		storageClassSignature =
			"text|integer|text|text|integer|integer|integer|text|text|" +
				"integer|integer|text|null|text|text",
		logicalTrackingId = logicalTrackingId,
		manifestRevision = revision,
		serviceRunId = serviceRunId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = revision,
		acquisitionPlanRevision = revision,
		rolloutRevision = 2L,
		startOrigin = if (revision == 1L) {
			"MANUAL_FOREGROUND_START"
		} else {
			"POLICY_RECONCILIATION"
		},
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = revision - 1L,
		effectiveWallTimeMs = revision - 1L,
		zoneId = "UTC",
		automationEpoch = null,
		changeReason = if (revision == 1L) "TEST" else "POLICY_RECONCILIATION",
		manifestChecksum = "a".repeat(64),
	)

	private fun run(
		manifestCount: Int,
		logicalTrackingId: String = LOGICAL_TRACKING_ID,
		serviceRunId: String = SERVICE_RUN_ID,
	) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = logicalTrackingId,
		state = "FINALIZED",
		desiredPlanRevision = manifestCount.toLong(),
		rolloutRevision = 2L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 0L,
		startedElapsedNanos = 0L,
		completedAtMs = manifestCount.toLong(),
		completionReason = "TEST",
		bootId = "boot",
		leaseGeneration = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		desiredForegroundCapabilityFlags = 0L,
		appliedForegroundCapabilityFlags = 0L,
		runtimeAcknowledgement = "TERMINAL",
		runtimeFailureCode = null,
		runRevision = 1L,
		startDeliveryToken = "delivery",
		startCommandGeneration = 1L,
		preparedManifestRevision = 1L,
		preparedIntentRevision = 1L,
		androidDeliveryState = "TERMINAL",
		androidDeliveryUpdatedAtMs = manifestCount.toLong(),
		startIsUserInitiated = true,
	)

	private fun timeline(key: OwnerlessStepsManifestRunKey): OwnerlessStepsManifestRunTimeline =
		OwnerlessStepsManifestRunTimeline(
			key = key,
			run = run(
				manifestCount = 1,
				logicalTrackingId = key.logicalTrackingId,
				serviceRunId = key.serviceRunId,
			),
			manifests = listOf(
				requireNotNull(
					manifest(
						revision = 1L,
						logicalTrackingId = key.logicalTrackingId,
						serviceRunId = key.serviceRunId,
					).validatedOrNull(),
				),
			),
		)

	private fun sourceAuthenticatedTimeline(
		manifestCount: Int,
		key: OwnerlessStepsManifestRunKey =
			OwnerlessStepsManifestRunKey(LOGICAL_TRACKING_ID, SERVICE_RUN_ID),
	): Pair<
		OwnerlessStepsManifestRunTimeline,
		Map<Long, SessionManifestSourceEntity.RawSessionManifestSource>,
	> {
		val sources = (1L..manifestCount.toLong()).associateWith { revision ->
			manifestSource(revision, key)
		}
		val manifests = sources.map { (revision, source) ->
			val unsigned = requireNotNull(
				manifest(
					revision = revision,
					logicalTrackingId = key.logicalTrackingId,
					serviceRunId = key.serviceRunId,
				).validatedOrNull(),
			)
			unsigned.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source)),
			)
		}
		return OwnerlessStepsManifestRunTimeline(
			key = key,
			run = run(
				manifestCount = manifestCount,
				logicalTrackingId = key.logicalTrackingId,
				serviceRunId = key.serviceRunId,
			),
			manifests = manifests,
		) to sources.mapValues { (_, source) -> source.raw() }
	}

	private fun manifestSource(
		revision: Long,
		key: OwnerlessStepsManifestRunKey,
	) = SessionManifestSourceEntity(
		logicalTrackingId = key.logicalTrackingId,
		manifestRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
	)

	private fun SessionManifestSourceEntity.raw() =
		SessionManifestSourceEntity.RawSessionManifestSource(
			storageClassSignature =
				"text|integer|integer|text|integer|integer|integer|" +
					"text|text|integer|null|null|null",
			logicalTrackingId = logicalTrackingId,
			manifestRevision = manifestRevision,
			sourceKind = sourceKind.toLong(),
			purpose = purpose,
			consentEpoch = consentEpoch,
			persistenceEligible = if (persistenceEligible) 1L else 0L,
			qosCode = qosCode.toLong(),
			outputDestination = outputDestination,
			writerOwner = writerOwner,
			writerOwnerGeneration = writerOwnerGeneration,
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = writerProjectionVersion?.toLong(),
			writerBindingGeneration = writerBindingGeneration,
		)

	private companion object {
		const val LOGICAL_TRACKING_ID = "ownerless-steps-logical"
		const val SERVICE_RUN_ID = "ownerless-steps-run"
	}
}
