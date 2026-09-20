package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreFailureKind
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationDebt
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationSourcePlan
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.tracker.source.coordinator.PreparedCallerAuthorityRebind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PreparedCallerAuthorityDescriptorRebindTest {
	@Test
	fun `exact prepared rebind atomically replaces only the caller reference`() = runTest {
		val original = descriptor(OLD_REFERENCE)
		val store = RecordingStore(original)

		val result = rebindPreparedCallerAuthorityDescriptor(store, original, REBIND)
			.shouldBeInstanceOf<PreparedCallerAuthorityDescriptorRebindResult.Rebound>()

		result.descriptor shouldBe original.copy(sourceCallerAuthorityReference = NEW_REFERENCE)
		store.current shouldBe result.descriptor
		store.replaceCount shouldBe 1
	}

	@Test
	fun `transient descriptor failure defers and retries the exact prepared rebind`() = runTest {
		val original = descriptor(OLD_REFERENCE)
		val store = RecordingStore(
			current = original,
			failNextReplace = ActiveTrackingSessionStoreFailureKind.UNAVAILABLE,
		)

		rebindPreparedCallerAuthorityDescriptor(store, original, REBIND) shouldBe
			PreparedCallerAuthorityDescriptorRebindResult.Deferred
		store.current shouldBe original

		val retried = rebindPreparedCallerAuthorityDescriptor(store, original, REBIND)
			.shouldBeInstanceOf<PreparedCallerAuthorityDescriptorRebindResult.Rebound>()
		retried.descriptor shouldBe original.copy(sourceCallerAuthorityReference = NEW_REFERENCE)
		store.replaceCount shouldBe 2
	}

	@Test
	fun `stale descriptor mismatch fails closed without replacing current authority`() = runTest {
		val stale = descriptor(SourceCallerReplayReference("other-authority"))
		val store = RecordingStore(stale)

		rebindPreparedCallerAuthorityDescriptor(store, stale, REBIND) shouldBe
			PreparedCallerAuthorityDescriptorRebindResult.Stale
		store.current shouldBe stale
		store.replaceCount shouldBe 0
	}

	@Test
	fun `restart accepts an already rebound descriptor without another CAS`() = runTest {
		val rebound = descriptor(NEW_REFERENCE)
		val store = RecordingStore(rebound)

		val result = rebindPreparedCallerAuthorityDescriptor(store, rebound, REBIND)
			.shouldBeInstanceOf<PreparedCallerAuthorityDescriptorRebindResult.Rebound>()

		result.descriptor shouldBe rebound
		store.replaceCount shouldBe 0
	}

	private fun descriptor(reference: SourceCallerReplayReference) =
		ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			lifecycleRevision = 7L,
			restartBootId = "boot-1",
			restartToken = "restart-token",
			sessionSegmentId = 41L,
			sourceCallerAuthorityReference = reference,
			pendingRetirementSourceCallerAuthorityReference =
				SourceCallerReplayReference("older-authority"),
			catalogReconfigurationDebt = CatalogReconfigurationDebt(
				logicalTrackingId = "logical",
				serviceRunId = "run",
				sourcePolicyRevision = 3L,
				desiredPlanGeneration = 5L,
				desiredPlanFingerprint = FINGERPRINT,
				requestedPlanRevision = 5L,
				requestedPlanId = "desired-plan",
				requestedPlanCreatedAtMs = 1_000L,
				requestedPlans = listOf(
					CatalogReconfigurationSourcePlan(
						sourceStableCode = 1,
						payloadVersion = 1,
						payloadBase64 = "YQ==",
						payloadChecksum = FINGERPRINT,
					),
				),
				deferredSourceMask = 1L,
				clockDomainId = "boot-1",
				zoneId = "UTC",
				foregroundCapabilityFlags = 1L,
				controlDependencyMask = 0L,
			),
			appliedSourcePlanIdentity = SourcePlanIdentity(
				generation = 4L,
				inputsFingerprint = FINGERPRINT,
				planFingerprint = FINGERPRINT,
			),
			desiredSourcePlanIdentity = SourcePlanIdentity(
				generation = 5L,
				inputsFingerprint = FINGERPRINT,
				planFingerprint = FINGERPRINT,
			),
		)

	private class RecordingStore(
		var current: ActiveTrackingSessionDescriptor?,
		var failNextReplace: ActiveTrackingSessionStoreFailureKind? = null,
	) : ActiveTrackingSessionStore {
		var replaceCount = 0

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(current)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			current = descriptor
			return ActiveTrackingSessionStoreResult.Success(descriptor)
		}

		override suspend fun replaceExact(
			expected: ActiveTrackingSessionDescriptor,
			replacement: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			replaceCount += 1
			failNextReplace?.let { kind ->
				failNextReplace = null
				return ActiveTrackingSessionStoreResult.Failure(IOException("unavailable"), kind)
			}
			if (current == expected) current = replacement
			return ActiveTrackingSessionStoreResult.Success(current)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			current = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private companion object {
		val OLD_REFERENCE = SourceCallerReplayReference("old-authority")
		val NEW_REFERENCE = SourceCallerReplayReference("new-authority")
		val REBIND = PreparedCallerAuthorityRebind(
			logicalTrackingId = "logical",
			serviceRunId = "run",
			manifestRevision = 3L,
			previousIntentRevision = 4L,
			currentIntentRevision = 5L,
			previousLeaseGeneration = 6L,
			currentLeaseGeneration = 7L,
			previousReference = OLD_REFERENCE,
			currentReference = NEW_REFERENCE,
		)
		const val FINGERPRINT =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	}
}
