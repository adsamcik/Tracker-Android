package com.adsamcik.tracker.shared.preferences.retention

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import io.kotest.matchers.shouldBe
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After as AfterTest
import org.junit.Before as BeforeTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionAuthorityOperationLeaseRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var operationLease: RetentionAuthorityOperationLease
	private var lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)

	@BeforeTest
	fun setUp() = runTest {
		operationLease = RetentionAuthorityOperationLease()
		lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = lifecycle.epoch, updatedAtMs = 1L),
		)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `lease owned Room bridge supports the library transaction coroutine`() = runBlocking<Unit> {
		operationLease.withPermit { permit ->
			permit.commitRoomMutation {
				database.withTransaction {
					database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe
						lifecycle.epoch
				}
			}
		}
	}

	@Test
	fun `captured Room bridge cannot start after lease release`() = runBlocking<Unit> {
		lateinit var escaped: RetentionAuthorityOperationPermit
		var mutationStarted = false

		operationLease.withPermit { permit ->
			escaped = permit
		}

		assertFailsWith<IllegalArgumentException> {
			escaped.commitRoomMutation {
				mutationStarted = true
			}
		}
		mutationStarted shouldBe false
	}

	@Test
	fun `lease release waits through Room commit and bridge post check`() = runBlocking<Unit> {
		val commitEntered = CompletableDeferred<Unit>()
		val releaseCommit = CompletableDeferred<Unit>()
		var leaseReleased = false
		val owner = async {
			operationLease.withPermit { permit ->
				permit.commitRoomMutation {
					database.withTransaction {
						commitEntered.complete(Unit)
						releaseCommit.await()
						database.sourceEvidenceStateDao().get()
					}
				}
			}
			leaseReleased = true
		}

		commitEntered.await()
		yield()
		leaseReleased shouldBe false
		releaseCommit.complete(Unit)
		owner.await()
		leaseReleased shouldBe true
	}

	@Test
	fun `hung lease owned suspension fails within the bridge bound`() = runTest {
		val boundedLease = RetentionAuthorityOperationLease(ownedSuspensionTimeoutMs = 1L)
		val owner = async {
			assertFailsWith<RetentionAuthorityOwnedSuspensionTimeoutException> {
				boundedLease.withPermit { permit ->
					permit.commitRoomMutation {
						awaitCancellation()
					}
				}
			}
		}

		advanceTimeBy(2L)
		owner.await()
	}

	@Test
	fun `late DataStore commit stays lease fenced until exact acknowledgement`() = runTest {
		val commitEntered = CompletableDeferred<Unit>()
		val releaseCommit = CompletableDeferred<Unit>()
		var committed = false
		val boundedLease = RetentionAuthorityOperationLease(
			ownedSuspensionTimeoutMs = 1L,
			completionScope = backgroundScope,
		)
		val first = async {
			val unknown = assertFailsWith<RetentionAuthorityDataStoreCommitUnknownException> {
				boundedLease.withPermit { permit ->
					permit.commitDataStoreMutation("retention-floor:test:1500") {
						commitEntered.complete(Unit)
						withContext(NonCancellable) {
							releaseCommit.await()
						}
						committed = true
					}
				}
			}
			unknown.operationIdentity shouldBe "retention-floor:test:1500"
		}

		commitEntered.await()
		advanceTimeBy(2L)
		first.await()
		committed shouldBe false

		var nextOperationEntered = false
		val next = async {
			boundedLease.withPermit {
				nextOperationEntered = true
			}
		}
		runCurrent()
		nextOperationEntered shouldBe false

		releaseCommit.complete(Unit)
		next.await()
		committed shouldBe true
		nextOperationEntered shouldBe true
	}

	@Test
	fun `operation permit cannot transfer through shared NonCancellable context`() = runBlocking<Unit> {
		operationLease.withPermit { permit ->
			assertFailsWith<IllegalArgumentException> {
				withContext(NonCancellable) {
					permit.validate()
				}
			}
		}
	}

	@Test
	fun `shielded operation permit cannot transfer to a NonCancellable child`() = runBlocking<Unit> {
		operationLease.withPermit(cancellationShielded = true) { permit ->
			val child = async(NonCancellable) {
				permit.validate()
			}
			assertFailsWith<IllegalArgumentException> { child.await() }
		}
	}
}
