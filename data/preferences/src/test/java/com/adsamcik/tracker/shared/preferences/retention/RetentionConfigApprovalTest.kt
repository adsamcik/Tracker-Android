package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionConfigApprovalTest {

	private lateinit var context: Context
	private lateinit var store: RetentionConfigStore

	@Before
	fun setup() {
		context = prepareRetentionConfigTestContext()
		store = RetentionConfigStore(context, Dispatchers.IO)
	}

	@Test
	fun `default retention configuration is not approved`() = runTest {
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED
	}

	@Test
	fun `explicit saved configuration remains pending until exact approval CAS`() = runTest {
		val staged = store.update { copy(dataRetentionYears = 2, rawDataRetentionDays = 730) }
		staged.approvalRequired shouldBe true
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL
		store.markPolicyApproved(staged.policy) shouldNotBe null
		val first = assertIs<ApprovedRetentionPolicyRead.Available>(
			store.currentApprovedPolicy(),
		).policy

		val unchanged = store.update { this }
		unchanged.approvalRequired shouldBe false
		assertIs<ApprovedRetentionPolicyRead.Available>(
			store.currentApprovedPolicy(),
		).policy shouldBe first

		val rotated = store.update { copy(dataRetentionYears = 3, rawDataRetentionDays = 1_095) }
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL
		store.markPolicyApproved(rotated.policy) shouldNotBe null
		val second = assertIs<ApprovedRetentionPolicyRead.Available>(
			store.currentApprovedPolicy(),
		).policy
		second.revision shouldBe first.revision + 1L
		(second.opaquePolicyId == first.opaquePolicyId) shouldBe false
	}

	@Test
	fun returningToApprovedSettingsPublishesItWithoutNewApproval() = runTest {
		val original = store.config.first()
		val initialStage = store.update { this }
		val approved = requireNotNull(store.markPolicyApproved(initialStage.policy))
		val pending = store.updateWithApproval(
			block = { copy(dataRetentionYears = 2, rawDataRetentionDays = 730) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			},
		)
		pending.published shouldBe true
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.PENDING
		store.config.first().dataRetentionYears shouldBe 2

		val reverted = store.updateWithApproval(
			block = { original },
			prepare = { error("Existing approval does not need preparation") },
			approve = { error("Existing approval must keep its original identity") },
		)
		reverted.published shouldBe true
		assertIs<RetentionConfigurationApprovalResult.AlreadyApproved>(reverted.approval)
			.policy shouldBe approved
		store.config.first() shouldBe original
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.APPROVED
		assertIs<ApprovedRetentionPolicyRead.Available>(store.currentApprovedPolicy())
			.policy shouldBe approved

		val unchanged = store.updateWithApproval(
			block = { this },
			prepare = { error("Unchanged settings do not need preparation") },
			approve = { error("Unchanged settings do not need approval") },
		)
		unchanged.published shouldBe false
		assertIs<RetentionConfigurationApprovalResult.AlreadyApproved>(unchanged.approval)
			.policy shouldBe approved
	}

	@Test
	fun `failed preparation keeps old config and durable pending generation for retry`() = runTest {
		val failed = store.updateWithApproval(
			block = { copy(dataRetentionYears = 4, rawDataRetentionDays = 1_460) },
			prepare = {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			},
			approve = { error("Approval must not run after failed preparation") },
		)

		failed.published shouldBe false
		store.config.first().dataRetentionYears shouldBe
			RetentionConfigState.DEFAULT_RETENTION_YEARS
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.UNAPPROVED
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED

		val retried = store.updateWithApproval(
			block = { copy(dataRetentionYears = 4, rawDataRetentionDays = 1_460) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				val approved = requireNotNull(store.markPolicyApproved(it.policy))
				RetentionConfigurationApprovalResult.Approved(approved)
			},
		)

		retried.published shouldBe true
		store.config.first().dataRetentionYears shouldBe 4
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.APPROVED
	}

	@Test
	fun `settings candidate rejected by active settlement preserves the approved worker policy`() =
		runTest {
			val approved = store.updateWithApproval(
				block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 30) },
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = {
					RetentionConfigurationApprovalResult.Approved(
						requireNotNull(store.markPolicyApproved(it.policy)),
					)
				},
			)
			val activePolicy = assertIs<RetentionConfigurationApprovalResult.Approved>(
				approved.approval,
			).policy

			val rejected = store.updateWithApproval(
				block = { copy(rawDataRetentionDays = 7) },
				prepare = {
					RetentionConfigurationApprovalResult.Unavailable(
						RetentionAuthorityUnavailableReason.RETENTION_FLOOR_SETTLEMENT_PENDING,
					)
				},
				approve = { error("Active settlement rejection must not approve") },
			)

			rejected.published shouldBe false
			rejected.approval shouldBe RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.RETENTION_FLOOR_SETTLEMENT_PENDING,
			)
			store.config.first().rawDataRetentionDays shouldBe 30
			store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.APPROVED
			assertIs<ApprovedRetentionPolicyRead.Available>(
				store.currentApprovedPolicy(),
			).policy shouldBe activePolicy

			val resumed = store.withExactApprovedOperation { it.policy }
			assertIs<ExactApprovedRetentionOperationResult.Completed<*>>(resumed)
				.value shouldBe activePolicy
		}

	@Test
	fun `failed Room approval leaves published config pending and retryable`() = runTest {
		val failed = store.updateWithApproval(
			block = { copy(dataRetentionYears = 5, rawDataRetentionDays = 1_825) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			},
		)

		failed.published shouldBe true
		store.config.first().dataRetentionYears shouldBe 5
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.PENDING
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL

		val retried = store.updateWithApproval(
			block = { this },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				val approved = requireNotNull(store.markPolicyApproved(it.policy))
				RetentionConfigurationApprovalResult.Approved(approved)
			},
		)

		retried.published shouldBe true
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.APPROVED
	}

	@Test
	fun `genuine pending to approved transition preserves stable identity with a new status checksum`() =
		runTest {
			val applied = store.updateWithApproval(
				block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 45) },
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = { stage ->
					val approved = requireNotNull(store.markPolicyApproved(stage.policy))
					approved.hasAuthenticChecksum(RetentionPolicyApprovalStatus.APPROVED) shouldBe true
					(approved.integrityChecksum == stage.policy.integrityChecksum) shouldBe false
					RetentionConfigurationApprovalResult.Approved(approved)
				},
			)

			val approved = assertIs<RetentionConfigurationApprovalResult.Approved>(applied.approval)
			approved.policy.hasSameStableIdentity(applied.stage.policy) shouldBe true
			store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.APPROVED
		}

	@Test
	fun `approved callback with the pending status checksum fails closed`() = runTest {
		val applied = store.updateWithApproval(
			block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 46) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = { stage ->
				RetentionConfigurationApprovalResult.Approved(stage.policy)
			},
		)

		assertIs<RetentionConfigurationApprovalResult.Unavailable>(applied.approval).reason shouldBe
			RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION
		store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.PENDING
	}

	@Test
	fun `status-authentic approved callback with tampered stable policy fields fails closed`() =
		runTest {
			val applied = store.updateWithApproval(
				block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 47) },
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = { stage ->
					val revision = stage.policy.revision + 1L
					val forged = stage.policy.copy(
						revision = revision,
						integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
							RetentionPolicyApprovalStatus.APPROVED,
							stage.policy.configurationGeneration,
							revision,
							stage.policy.opaquePolicyId,
							stage.policy.configurationChecksum,
						),
					)
					RetentionConfigurationApprovalResult.Approved(forged)
				},
			)

			assertIs<RetentionConfigurationApprovalResult.Unavailable>(applied.approval).reason shouldBe
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION
			store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.PENDING
		}

	@Test
	fun `exact approved read waits across publication and approval`() = runTest {
		val atomicStore = RetentionConfigStore(context, Dispatchers.Unconfined)
		val approvalEntered = CompletableDeferred<Unit>()
		val releaseApproval = CompletableDeferred<Unit>()
		val update = async {
			atomicStore.updateWithApproval(
				block = {
					copy(
						autoPurgeEnabled = true,
						rawDataRetentionDays = 30,
					)
				},
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = {
					approvalEntered.complete(Unit)
					releaseApproval.await()
					val approved = requireNotNull(atomicStore.markPolicyApproved(it.policy))
					RetentionConfigurationApprovalResult.Approved(approved)
				},
			)
		}
		approvalEntered.await()
		val read = async { atomicStore.currentExactApprovedConfig() }
		runCurrent()

		read.isCompleted shouldBe false
		releaseApproval.complete(Unit)
		val applied = update.await()
		val exact = assertIs<ExactApprovedRetentionConfigRead.Approved>(read.await())
		exact.configuration.rawDataRetentionDays shouldBe 30
		exact.policy.configurationGeneration shouldBe
			applied.stage.policy.configurationGeneration
		exact.policy.revision shouldBe applied.stage.policy.revision
		exact.policy.opaquePolicyId shouldBe applied.stage.policy.opaquePolicyId
		exact.policy.configurationChecksum shouldBe
			applied.stage.policy.configurationChecksum
	}

	@Test
	fun `schedule reconciliation holds its exact preference revision until it returns`() = runTest {
		val approved = store.updateWithApproval(
			block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 30) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				RetentionConfigurationApprovalResult.Approved(
					requireNotNull(store.markPolicyApproved(it.policy)),
				)
			},
		)
		val reconciliationEntered = CompletableDeferred<ApprovedRetentionPolicy>()
		val releaseReconciliation = CompletableDeferred<Unit>()
		val reconciliation = async {
			store.withExactApprovedConfigReconciliation { authority ->
				val exact = assertIs<ExactApprovedRetentionConfigRead.Approved>(authority)
				reconciliationEntered.complete(exact.policy)
				releaseReconciliation.await()
			}
		}
		reconciliationEntered.await() shouldBe
			assertIs<RetentionConfigurationApprovalResult.Approved>(approved.approval).policy
		val replacement = async {
			store.updateWithApproval(
				block = { copy(autoPurgeEnabled = false, rawDataRetentionDays = 7) },
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = {
					RetentionConfigurationApprovalResult.Approved(
						requireNotNull(store.markPolicyApproved(it.policy)),
					)
				},
			)
		}
		runCurrent()

		replacement.isCompleted shouldBe false
		releaseReconciliation.complete(Unit)
		reconciliation.await()
		replacement.await().stage.policy.configurationGeneration shouldBe
			approved.stage.policy.configurationGeneration + 1L
	}

	@Test
	fun `approved destructive operation serializes every boundary against policy replacement`() =
		runTest {
			val approved = store.updateWithApproval(
				block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 30) },
				prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
				approve = {
					RetentionConfigurationApprovalResult.Approved(
						requireNotNull(store.markPolicyApproved(it.policy)),
					)
				},
			)
			val operationEntered = CompletableDeferred<ApprovedRetentionOperation>()
			val releaseOperation = CompletableDeferred<Unit>()
			val operation = async {
				store.withExactApprovedOperation { admission ->
					operationEntered.complete(admission)
					releaseOperation.await()
					admission.requireIdentity()
				}
			}
			val admitted = operationEntered.await()
			val replacement = async {
				store.updateWithApproval(
					block = { copy(rawDataRetentionDays = 7) },
					prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
					approve = {
						RetentionConfigurationApprovalResult.Approved(
							requireNotNull(store.markPolicyApproved(it.policy)),
						)
					},
				)
			}
			runCurrent()

			replacement.isCompleted shouldBe false
			admitted.policy shouldBe
				assertIs<RetentionConfigurationApprovalResult.Approved>(approved.approval).policy
			releaseOperation.complete(Unit)
			assertIs<ExactApprovedRetentionOperationResult.Completed<*>>(operation.await())
			val replaced = replacement.await()
			replaced.stage.policy.configurationGeneration shouldBe
				approved.stage.policy.configurationGeneration + 1L
		}

	@Test
	fun `failed approval leaves exact worker configuration pending`() = runTest {
		val failed = store.updateWithApproval(
			block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 7) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			},
		)

		val exact = assertIs<ExactApprovedRetentionConfigRead.Pending>(
			store.currentExactApprovedConfig(),
		)
		exact.configuration.rawDataRetentionDays shouldBe 7
		exact.policy shouldBe failed.stage.policy
	}

	@Test
	fun `unpublished checksum candidate is invalid for destructive work`() = runTest {
		store.updateWithApproval(
			block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 14) },
			prepare = {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			},
			approve = { error("Approval must not run") },
		)

		assertIs<ExactApprovedRetentionConfigRead.Invalid>(
			store.currentExactApprovedConfig(),
		).reason shouldBe ExactApprovedRetentionConfigInvalidReason.CONFIGURATION_MISMATCH
	}

	@Test
	fun `approval revision mismatch cannot authenticate the published configuration`() = runTest {
		val staged = store.update {
			copy(autoPurgeEnabled = true, rawDataRetentionDays = 21)
		}

		store.markPolicyApproved(
			staged.policy.copy(revision = staged.policy.revision + 1L),
		) shouldBe null
		val exact = assertIs<ExactApprovedRetentionConfigRead.Pending>(
			store.currentExactApprovedConfig(),
		)
		exact.policy.revision shouldBe staged.policy.revision
	}

	@Test
	fun `pending approval rejects an otherwise stable policy carrying the approved status checksum`() =
		runTest {
			val staged = store.update {
				copy(autoPurgeEnabled = true, rawDataRetentionDays = 22)
			}
			val approvedChecksum = RetentionPolicyApprovalIntegrity.checksum(
				RetentionPolicyApprovalStatus.APPROVED,
				staged.policy.configurationGeneration,
				staged.policy.revision,
				staged.policy.opaquePolicyId,
				staged.policy.configurationChecksum,
			)

			store.markPolicyApproved(
				staged.policy.copy(integrityChecksum = approvedChecksum),
			) shouldBe null
			store.approvalStatus.first() shouldBe RetentionPolicyApprovalStatus.PENDING
		}

	@Test
	fun `debug reset stages defaults without preserving stale live authority`() = runTest {
		val approved = store.updateWithApproval(
			block = { copy(autoPurgeEnabled = true, rawDataRetentionDays = 30) },
			prepare = { RetentionConfigurationApprovalResult.Prepared(it.policy) },
			approve = {
				RetentionConfigurationApprovalResult.Approved(
					requireNotNull(store.markPolicyApproved(it.policy)),
				)
			},
		)

		val reset = store.resetToDefaultsForDebug()

		store.config.first() shouldBe RetentionConfigState()
		reset.approvalRequired shouldBe true
		reset.policy shouldNotBe approved.stage.policy
		assertIs<ApprovedRetentionPolicyRead.Unavailable>(
			store.currentApprovedPolicy(),
		).reason shouldBe ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL
		val pending = assertIs<ExactApprovedRetentionConfigRead.Pending>(
			store.currentExactApprovedConfig(),
		)
		pending.configuration shouldBe RetentionConfigState()
		pending.policy shouldBe reset.policy
	}
}
