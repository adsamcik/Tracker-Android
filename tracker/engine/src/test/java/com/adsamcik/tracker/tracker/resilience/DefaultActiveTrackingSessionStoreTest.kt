package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultActiveTrackingSessionStoreTest {
	@Test
	fun `descriptor survives repository recreation and is removed on graceful clear`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val descriptor = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "catalog-logical",
			serviceRunId = "catalog-run",
			restartBootId = "boot:test",
			restartToken = "restart-token",
			sessionSegmentId = 42L,
			sourceCallerAuthorityReference = SourceCallerReplayReference("caller-authority"),
			pendingRetirementSourceCallerAuthorityReference =
				SourceCallerReplayReference("caller-authority-predecessor"),
			catalogReconfigurationDebt = catalogDebt(
				logicalTrackingId = "catalog-logical",
				serviceRunId = "catalog-run",
			),
		)

		store.save(descriptor) shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		val recreated = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		recreated.read() shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		recreated.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		recreated.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}

	@Test
	fun `graceful stop mirror preserves retirement debt across restart until exact retirement clears it`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Context>()
			val dispatcher = StandardTestDispatcher(testScheduler)
			val store = DefaultActiveTrackingSessionStore(
				context,
				TestDispatchersProvider(dispatcher),
			)
			store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
			val currentReference = SourceCallerReplayReference("caller-current")
			val predecessorReference = SourceCallerReplayReference("caller-predecessor")
			val activeWithDebt = ActiveTrackingSessionDescriptor(
				isUserInitiated = true,
				isAmbient = false,
				policyTier = PolicyTier.PRECISION,
				logicalTrackingId = "logical-debt",
				serviceRunId = "run-debt",
				sourceCallerAuthorityReference = currentReference,
				pendingRetirementSourceCallerAuthorityReference = predecessorReference,
			)
			store.save(activeWithDebt) shouldBe
				ActiveTrackingSessionStoreResult.Success(activeWithDebt)
			val staleServiceCandidate = activeWithDebt.copy(
				sourceCallerAuthorityReference = SourceCallerReplayReference("stale-service-copy"),
				pendingRetirementSourceCallerAuthorityReference = null,
			).proposeStop(
				TrackingStopCandidateReason.EXPLICIT_REQUEST,
				changedAtEpochMs = 500L,
			)
			val stoppingWithDebt = staleServiceCandidate.copy(
				sourceCallerAuthorityReference = currentReference,
				pendingRetirementSourceCallerAuthorityReference = predecessorReference,
			)

			store.mergeServiceDescriptor(staleServiceCandidate) shouldBe
				ActiveTrackingSessionStoreResult.Success(stoppingWithDebt)
			DefaultActiveTrackingSessionStore(
				context,
				TestDispatchersProvider(dispatcher),
			).read() shouldBe ActiveTrackingSessionStoreResult.Success(stoppingWithDebt)

			val retired = stoppingWithDebt.copy(
				pendingRetirementSourceCallerAuthorityReference = null,
			)
			store.replaceExact(stoppingWithDebt, retired) shouldBe
				ActiveTrackingSessionStoreResult.Success(retired)
			store.mergeServiceDescriptor(stoppingWithDebt) shouldBe
				ActiveTrackingSessionStoreResult.Success(retired)
			store.read() shouldBe ActiveTrackingSessionStoreResult.Success(retired)
		}

	@Test
	fun `conditional clear cannot erase a resumed or newer service run`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val firstRun = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "logical-session",
			serviceRunId = "service-run-one",
		).proposeStop(
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			changedAtEpochMs = 100L,
		)
		val replacement = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = firstRun.logicalTrackingId,
			serviceRunId = "service-run-two",
		)

		val resumed = firstRun.withdrawStopCandidate(changedAtEpochMs = 101L)
		store.save(resumed) shouldBe ActiveTrackingSessionStoreResult.Success(resumed)
		store.clearIfCurrent(firstRun) shouldBe ActiveTrackingSessionStoreResult.Success(resumed)
		store.save(replacement) shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
		store.clearIfCurrent(firstRun) shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
	}

	@Test
	fun `exact clear removes stale active descriptor but preserves any changed revision`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val staleAutomatic = ActiveTrackingSessionDescriptor(
			isUserInitiated = false,
			isAmbient = false,
			policyTier = PolicyTier.AMBIENT,
			logicalTrackingId = "stale-automatic",
			serviceRunId = "stale-run",
		)
		val changed = staleAutomatic.copy(lifecycleRevision = 1L)

		store.save(changed) shouldBe ActiveTrackingSessionStoreResult.Success(changed)
		store.clearExact(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(changed)
		store.save(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(staleAutomatic)
		store.clearExact(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(null)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}

	@Test
	fun `exact segment mirror is idempotent and cannot overwrite stop candidate`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val active = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "logical-session",
			serviceRunId = "service-run",
		)
		val bound = active.copy(sessionSegmentId = 42L)
		store.save(active) shouldBe ActiveTrackingSessionStoreResult.Success(active)

		store.bindSessionSegmentIfCurrent(active, 42L) shouldBe
			ActiveTrackingSessionStoreResult.Success(bound)
		store.bindSessionSegmentIfCurrent(active, 42L) shouldBe
			ActiveTrackingSessionStoreResult.Success(bound)

		val stopping = bound.proposeStop(
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			changedAtEpochMs = 200L,
		)
		store.save(stopping) shouldBe ActiveTrackingSessionStoreResult.Success(stopping)
		store.bindSessionSegmentIfCurrent(bound, 99L) shouldBe
			ActiveTrackingSessionStoreResult.Success(stopping)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(stopping)
	}

	@Test
	fun `stale tier replacement preserves segment binding and cannot revive active`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val bound = ActiveTrackingSessionDescriptor(
			isUserInitiated = false,
			isAmbient = false,
			policyTier = PolicyTier.ACTIVE,
			logicalTrackingId = "logical-session",
			serviceRunId = "service-run",
			sessionSegmentId = 73L,
		)
		val stopping = bound.proposeStop(
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			changedAtEpochMs = 300L,
		)
		store.save(stopping) shouldBe ActiveTrackingSessionStoreResult.Success(stopping)

		store.replaceExact(
			expected = bound,
			replacement = bound.copy(policyTier = PolicyTier.PRECISION),
		) shouldBe ActiveTrackingSessionStoreResult.Success(stopping)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(stopping)
	}

	@Test
	fun `reconfiguration atomically replaces the persisted caller authority reference`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear()
		val original = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "logical-reconfigure",
			serviceRunId = "run-reconfigure",
			restartBootId = "boot:test",
			restartToken = "restart-token",
			sourceCallerAuthorityReference = SourceCallerReplayReference("authority-1"),
		)
		val replacement = original.copy(
			sourceCallerAuthorityReference = SourceCallerReplayReference("authority-2"),
		)
		store.save(original)

		store.replaceExact(original, replacement) shouldBe
			ActiveTrackingSessionStoreResult.Success(replacement)
		DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		).read() shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
	}

	@Test
	fun `corrupt proto returns typed corruption instead of an empty descriptor`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val file = File(
		context.cacheDir,
		"active-tracking-corrupt-${java.util.UUID.randomUUID()}.pb",
		).apply {
		writeBytes(byteArrayOf(0x0A, 0x7F))
		}
		val dataStore = DataStoreFactory.create(
		serializer = ActiveTrackingSessionSerializer,
		scope = this,
		produceFile = { file },
		)
		val store = DefaultActiveTrackingSessionStore(
		dataStore,
		TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)

		val failure = store.read()
		.shouldBeInstanceOf<ActiveTrackingSessionStoreResult.Failure>()
		failure.kind shouldBe ActiveTrackingSessionStoreFailureKind.CORRUPT
	}

	@Test
	fun `corrupt proto reset is explicit successful and durable`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val file = File(
			context.cacheDir,
			"active-tracking-corrupt-reset-${java.util.UUID.randomUUID()}.pb",
		).apply {
			writeBytes(byteArrayOf(0x0A, 0x7F))
		}
		val dataStore = DataStoreFactory.create(
			serializer = ActiveTrackingSessionSerializer,
			corruptionHandler = activeTrackingSessionCorruptionHandler,
			scope = this,
			produceFile = { file },
		)
		val store = DefaultActiveTrackingSessionStore(
			dataStore,
			TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)

		store.read()
			.shouldBeInstanceOf<ActiveTrackingSessionStoreResult.Failure>()
			.kind shouldBe ActiveTrackingSessionStoreFailureKind.CORRUPT
		store.clear()
			.shouldBeInstanceOf<ActiveTrackingSessionStoreResult.Failure>()
			.kind shouldBe ActiveTrackingSessionStoreFailureKind.CORRUPT
		store.resetCorruptState() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}

	@Test
	fun `storage IOException returns typed unavailable instead of an empty descriptor`() = runTest {
		val unavailable = object : DataStore<ActiveTrackingSessionProto> {
			override val data: Flow<ActiveTrackingSessionProto> = flow {
				throw IOException("storage unavailable")
			}

			override suspend fun updateData(
				transform: suspend (t: ActiveTrackingSessionProto) -> ActiveTrackingSessionProto,
			): ActiveTrackingSessionProto = throw IOException("storage unavailable")
		}
		val store = DefaultActiveTrackingSessionStore(
			unavailable,
			TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)

		val failure = store.read()
			.shouldBeInstanceOf<ActiveTrackingSessionStoreResult.Failure>()
		failure.kind shouldBe ActiveTrackingSessionStoreFailureKind.UNAVAILABLE
		store.resetCorruptState()
			.shouldBeInstanceOf<ActiveTrackingSessionStoreResult.Failure>()
			.kind shouldBe ActiveTrackingSessionStoreFailureKind.UNAVAILABLE
	}

	@Test
	fun `new service run preserves current caller authority reference`() {
		val reference = SourceCallerReplayReference("current-authority")
		val descriptor = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		restartBootId = "boot:test",
		restartToken = "restart-token",
		sourceCallerAuthorityReference = reference,
		)

		val debt = catalogDebt(descriptor.logicalTrackingId, descriptor.serviceRunId)
		val descriptorWithDebt = descriptor.copy(catalogReconfigurationDebt = debt)
		val replacement = descriptorWithDebt.forNewServiceRun(100L)

		replacement.sourceCallerAuthorityReference shouldBe reference
		replacement.catalogReconfigurationDebt shouldBe
			debt.copy(serviceRunId = replacement.serviceRunId)
	}

	private fun catalogDebt(
		logicalTrackingId: String,
		serviceRunId: String,
	) = CatalogReconfigurationDebt(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sourcePolicyRevision = 7L,
		desiredPlanGeneration = 2L,
		desiredPlanFingerprint =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		requestedPlanRevision = 2L,
		requestedPlanId = "catalog-plan",
		requestedPlanCreatedAtMs = 100L,
		requestedPlans = listOf(
			CatalogReconfigurationSourcePlan(
				sourceStableCode = SourceKind.STEPS.stableCode,
				payloadVersion = 1,
				payloadBase64 = "AQID",
				payloadChecksum =
					"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			),
		),
		deferredSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
		clockDomainId = "boot:test",
		zoneId = "Europe/Prague",
		foregroundCapabilityFlags = 1L,
		controlDependencyMask = 0L,
	)
}
