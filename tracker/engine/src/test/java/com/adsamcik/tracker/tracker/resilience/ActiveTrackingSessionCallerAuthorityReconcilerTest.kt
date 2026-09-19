package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.CurrentRecoverySourceCallerAuthority
import com.adsamcik.tracker.tracker.source.coordinator.CurrentRecoverySourceCallerAuthorityResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ActiveTrackingSessionCallerAuthorityReconcilerTest {
	private val coordinator = mockk<AuthoritativeSessionCoordinator>()
	private val dispatcher = mockk<SourceCallerDemandDispatcher>()

	@Test
	fun `crash after Room successor commit records debt replays successor and retires predecessor`() =
		runTest {
			val events = mutableListOf<String>()
			val predecessor = reference("caller-predecessor")
			val successor = reference("caller-successor")
			val initial = descriptor(predecessor)
			val store = RecordingStore(initial) { _, replacement ->
				events += if (replacement.pendingRetirementSourceCallerAuthorityReference != null) {
					"descriptor-successor"
				} else {
					"descriptor-debt-cleared"
				}
			}
			val authority = authority(successor)
			coEvery {
				coordinator.currentRecoverySourceCallerAuthority(LOGICAL_ID, SERVICE_RUN_ID)
			} coAnswers {
				events += "room-authenticated"
				CurrentRecoverySourceCallerAuthorityResult.Available(authority)
			}
			coEvery {
				dispatcher.replayPreparedSession(
					authority.manifestIdentity,
					successor,
					SourceCallerReplayKind.PROCESS_RECOVERY,
				)
			} coAnswers {
				events += "successor-replayed"
				permitted(successor)
			}
			coEvery {
				coordinator.retireSupersededSourceCallerAuthority(
					LOGICAL_ID,
					successor,
					predecessor,
					any(),
				)
			} coAnswers {
				events += "predecessor-retired"
				true
			}

			val result = reconciler(store).reconcile(initial)
				.shouldBeInstanceOf<ActiveTrackingCallerAuthorityReconciliation.Ready>()

			assertSoftly {
				result.descriptor.sourceCallerAuthorityReference shouldBe successor
				result.descriptor.pendingRetirementSourceCallerAuthorityReference shouldBe null
				store.current shouldBe result.descriptor
				events.shouldContainExactly(
					"room-authenticated",
					"descriptor-successor",
					"successor-replayed",
					"predecessor-retired",
					"descriptor-debt-cleared",
				)
			}
		}

	@Test
	fun `restart resumes recorded predecessor debt without reviving its authority`() = runTest {
		val predecessor = reference("caller-predecessor")
		val successor = reference("caller-successor")
		val recorded = descriptor(
			current = successor,
			pending = predecessor,
		)
		val store = RecordingStore(recorded)
		val authority = authority(successor)
		coEvery {
			coordinator.currentRecoverySourceCallerAuthority(LOGICAL_ID, SERVICE_RUN_ID)
		} returns CurrentRecoverySourceCallerAuthorityResult.Available(authority)
		coEvery {
			dispatcher.replayPreparedSession(
				authority.manifestIdentity,
				successor,
				SourceCallerReplayKind.PROCESS_RECOVERY,
			)
		} returns permitted(successor)
		coEvery {
			coordinator.retireSupersededSourceCallerAuthority(
				LOGICAL_ID,
				successor,
				predecessor,
				any(),
			)
		} returns true

		val result = reconciler(store).reconcile(recorded)
			.shouldBeInstanceOf<ActiveTrackingCallerAuthorityReconciliation.Ready>()

		result.descriptor.pendingRetirementSourceCallerAuthorityReference shouldBe null
		store.replacements.size shouldBe 1
		store.replacements.single().first shouldBe recorded
		coVerify(exactly = 1) {
			dispatcher.replayPreparedSession(
				authority.manifestIdentity,
				successor,
				SourceCallerReplayKind.PROCESS_RECOVERY,
			)
		}
		coVerify(exactly = 0) {
			dispatcher.replayPreparedSession(any(), predecessor, any())
		}
	}

	@Test
	fun `failed retirement preserves exact debt for an idempotent retry`() = runTest {
		val predecessor = reference("caller-predecessor")
		val successor = reference("caller-successor")
		val recorded = descriptor(successor, predecessor)
		val store = RecordingStore(recorded)
		val authority = authority(successor)
		coEvery {
			coordinator.currentRecoverySourceCallerAuthority(LOGICAL_ID, SERVICE_RUN_ID)
		} returns CurrentRecoverySourceCallerAuthorityResult.Available(authority)
		coEvery {
			dispatcher.replayPreparedSession(any(), successor, SourceCallerReplayKind.PROCESS_RECOVERY)
		} returns permitted(successor)
		coEvery {
			coordinator.retireSupersededSourceCallerAuthority(
				LOGICAL_ID,
				successor,
				predecessor,
				any(),
			)
		} returns false

		val result = reconciler(store).reconcile(recorded)
			.shouldBeInstanceOf<ActiveTrackingCallerAuthorityReconciliation.Blocked>()

		result.failureCode shouldBe "RECOVERY_SOURCE_CALLER_RETIREMENT_PENDING"
		store.current shouldBe recorded
		store.replacements shouldBe emptyList()
	}

	@Test
	fun `unrelated or malformed Room authority is rejected before descriptor adoption`() = runTest {
		listOf(
			"RECOVERY_SOURCE_CALLER_ROOM_AUTHORITY_INVALID",
			"RECOVERY_SOURCE_CALLER_REPLAY_AUTHORITY_CORRUPT",
		).forEach { failureCode ->
			val initial = descriptor(reference("caller-current"))
			val store = RecordingStore(initial)
			coEvery {
				coordinator.currentRecoverySourceCallerAuthority(LOGICAL_ID, SERVICE_RUN_ID)
			} returns CurrentRecoverySourceCallerAuthorityResult.Rejected(failureCode)

			val result = reconciler(store).reconcile(initial)
				.shouldBeInstanceOf<ActiveTrackingCallerAuthorityReconciliation.Blocked>()

			result.failureCode shouldBe failureCode
			store.current shouldBe initial
			store.replacements shouldBe emptyList()
			coVerify(exactly = 0) {
				dispatcher.replayPreparedSession(any(), any(), any())
			}
		}
	}

	@Test
	fun `second unrecorded predecessor makes successor adoption ambiguous`() = runTest {
		val current = reference("caller-current")
		val pending = reference("caller-pending")
		val successor = reference("caller-successor")
		val initial = descriptor(current, pending)
		val store = RecordingStore(initial)
		coEvery {
			coordinator.currentRecoverySourceCallerAuthority(LOGICAL_ID, SERVICE_RUN_ID)
		} returns CurrentRecoverySourceCallerAuthorityResult.Available(authority(successor))

		val result = reconciler(store).reconcile(initial)
			.shouldBeInstanceOf<ActiveTrackingCallerAuthorityReconciliation.Blocked>()

		result.failureCode shouldBe "RECOVERY_SOURCE_CALLER_RETIREMENT_AMBIGUOUS"
		store.current shouldBe initial
		store.replacements shouldBe emptyList()
		coVerify(exactly = 0) {
			dispatcher.replayPreparedSession(any(), any(), any())
		}
	}

	private fun reconciler(store: ActiveTrackingSessionStore) =
		ActiveTrackingSessionCallerAuthorityReconciler(store, coordinator, dispatcher)

	private fun authority(reference: SourceCallerReplayReference) =
		CurrentRecoverySourceCallerAuthority(
			manifestIdentity = SourceCallerManifestIdentity(LOGICAL_ID, MANIFEST_REVISION),
			reference = reference,
		)

	private fun permitted(reference: SourceCallerReplayReference) =
		SourceCallerGuardResult.Permitted(SourceCallerAcceptanceReceipt(reference, emptySet()))

	private fun descriptor(
		current: SourceCallerReplayReference,
		pending: SourceCallerReplayReference? = null,
	) = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = SERVICE_RUN_ID,
		restartBootId = "boot",
		restartToken = "restart-token",
		sourceCallerAuthorityReference = current,
		pendingRetirementSourceCallerAuthorityReference = pending,
	)

	private class RecordingStore(
		initial: ActiveTrackingSessionDescriptor?,
		private val onReplace: (
			ActiveTrackingSessionDescriptor,
			ActiveTrackingSessionDescriptor,
		) -> Unit = { _, _ -> },
	) : ActiveTrackingSessionStore {
		var current: ActiveTrackingSessionDescriptor? = initial
			private set
		val replacements =
			mutableListOf<Pair<ActiveTrackingSessionDescriptor, ActiveTrackingSessionDescriptor>>()

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(current)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			current = descriptor
			return ActiveTrackingSessionStoreResult.Success(current)
		}

		override suspend fun replaceExact(
			expected: ActiveTrackingSessionDescriptor,
			replacement: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			onReplace(expected, replacement)
			if (current == expected) {
				replacements += expected to replacement
				current = replacement
			}
			return ActiveTrackingSessionStoreResult.Success(current)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			current = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private companion object {
		const val LOGICAL_ID = "logical"
		const val SERVICE_RUN_ID = "service-run"
		const val MANIFEST_REVISION = 2L

		fun reference(value: String) = SourceCallerReplayReference(value)
	}
}
