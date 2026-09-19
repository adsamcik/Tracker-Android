package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.RawSessionManifestVersion
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

	private fun manifests(count: Int): List<RawSessionManifestVersion> =
		(1L..count.toLong()).map(::manifest)

	private fun manifest(revision: Long) = RawSessionManifestVersion(
		storageClassSignature =
			"text|integer|text|text|integer|integer|integer|text|text|" +
				"integer|integer|text|null|text|text",
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = revision,
		serviceRunId = SERVICE_RUN_ID,
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

	private fun run(manifestCount: Int) = SourceServiceRunEntity(
		serviceRunId = SERVICE_RUN_ID,
		logicalTrackingId = LOGICAL_TRACKING_ID,
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

	private companion object {
		const val LOGICAL_TRACKING_ID = "ownerless-steps-logical"
		const val SERVICE_RUN_ID = "ownerless-steps-run"
	}
}
