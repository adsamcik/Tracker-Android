package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.projection.PressureSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.PressureSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass", "TooManyFunctions")
class RoomPressureSelectedSessionDeletionServiceTest {
	private lateinit var database: AppDatabase
	private lateinit var dirtyTracker: MetricDirtyTracker
	private var drainRequests = 0

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dirtyTracker = mockk(relaxed = true)
		drainRequests = 0
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(candidatePressureOwner())
		database.sourcePolicyDao().insertPolicies(listOf(pressurePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(pressureConsent()))
	}

	@After
	fun tearDown() = database.close()

	@Test
	@Suppress("LongMethod")
	fun `terminal pending Pressure deletion is atomic idempotent repairs stored-zone day and cannot resurrect`() =
		runTest {
			val day = LocalDate.of(2026, 4, 2).toEpochDay()
			val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
			val selected = insertPressureSession(
				key = "selected",
				admissionOrdinal = 1L,
				startMs = dayStart + HOUR_MS,
				endMs = dayStart + 2L * HOUR_MS,
				distanceM = 10f,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			)
			val sibling = insertPressureSession(
				key = "sibling",
				admissionOrdinal = 2L,
				startMs = dayStart + 3L * HOUR_MS,
				endMs = dayStart + 4L * HOUR_MS,
				distanceM = 20f,
				insertCompleteness = true,
			)
			database.dailySummaryDao().upsert(
				dateEpochDay = day,
				totalDistanceM = 30f,
				totalSteps = 0,
				totalDurationMs = 2L * HOUR_MS,
				tripCount = 2,
				activeTrackingMs = 0L,
				lastUpdatedMs = dayStart + 4L * HOUR_MS,
				calendarZoneId = ZONE.id,
			)
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { summary ->
				summary.totalDistanceM shouldBe 30f
				summary.tripCount shouldBe 2
			}
			val originalTimeZone = TimeZone.getDefault()
			try {
				TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
				subject().deleteSelectedSession(selected.segmentId) shouldBe
					PressureSessionDeletionResult.Deleted
			} finally {
				TimeZone.setDefault(originalTimeZone)
			}

			database.sessionSegmentDao().getById(selected.segmentId) shouldBe null
			factsFor(selected, 2).shouldHaveSize(0)
			database.sessionSegmentDao().getById(sibling.segmentId).shouldNotBeNull()
			factsFor(sibling, 2) shouldBe listOf(sibling.fact)
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { summary ->
				summary.totalDistanceM shouldBe 20f
				summary.totalDurationMs shouldBe HOUR_MS
				summary.tripCount shouldBe 1
				summary.calendarZoneId shouldBe ZONE.id
			}
			exactFence(selected).shouldNotBeNull().also { fence ->
				fence.fenceGeneration shouldBe 1L
				fence.collectedDataEpoch shouldBe COLLECTED_DATA_EPOCH
			}
			database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
			verify(exactly = 1) {
				dirtyTracker.markDirty(
					setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
				)
			}
			drainRequests shouldBe 1

			subject().deleteSelectedSession(selected.segmentId) shouldBe
				PressureSessionDeletionResult.NotFound
			drainRequests shouldBe 1

			installCanonicalPressureLane()
			val lateIngress = mockk<DurableSourceIngress>()
			coEvery {
				lateIngress.committedSourceBatch(SourceKind.PRESSURE, 0L, 3L, 64)
			} returns listOf(latePressureEvent(selected, admissionOrdinal = 3L))
			PressureSessionFactProjectionLane(database, lateIngress).drainThrough(3L) shouldBe
				PressureSessionFactDrainResult.Complete(
					lastCompletedOrdinal = 3L,
					factsInserted = 0,
					eventsValidated = 1,
				)
			factsFor(selected, 2).shouldHaveSize(0)

			// The second same-day deletion must tolerate the first run's now-missing physical segment
			// only because its immutable Pressure manifest and exact Pressure fence agree.
			subject().deleteSelectedSession(sibling.segmentId) shouldBe
				PressureSessionDeletionResult.Deleted
			database.sessionSegmentDao().getById(sibling.segmentId) shouldBe null
			database.pressureFactRevisionDao().count() shouldBe 0L
			database.dailySummaryDao().getByDay(day) shouldBe null
			database.sourceDeletionFenceDao().countAll() shouldBe 2L
			database.sourceEvidenceStateDao().get()?.revision shouldBe 2L
			drainRequests shouldBe 2
		}

	@Test
	fun `projector produced Pressure fact is accepted by exact deletion integrity`() = runTest {
		val fixture = insertPressureSession("projected", 1L, 1_000L, 2_000L)
		database.pressureFactRevisionDao().deleteExactServiceRunPageThrough(
			logicalTrackingId = fixture.logicalId,
			serviceRunId = fixture.runId,
			writerProjectionId = fixture.fact.writerProjectionId,
			writerProjectionVersion = fixture.fact.writerProjectionVersion,
			throughLogicalFactId = fixture.fact.logicalFactId,
			throughSemanticRevision = fixture.fact.semanticRevision,
		) shouldBe 1
		installCanonicalPressureLane()
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.PRESSURE, 0L, 1L, 64)
		} returns listOf(latePressureEvent(fixture, admissionOrdinal = 1L))

		PressureSessionFactProjectionLane(database, ingress).drainThrough(1L) shouldBe
			PressureSessionFactDrainResult.Complete(
				lastCompletedOrdinal = 1L,
				factsInserted = 1,
				eventsValidated = 1,
			)
		val projected = factsFor(fixture, 2).single()
		PressureFactRevisionIntegrity.hasValidEffectChecksum(projected, fixture.binding) shouldBe true

		subject().deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.Deleted
		factsFor(fixture, 2) shouldBe emptyList()
	}

	@Test
	fun `partial Pressure window remains truthfully deletable`() = runTest {
		val fixture = insertPressureSession("partial", 1L, 1_000L, 2_000L)
		val partial = fixture.fact.copy(
			closureKind = PressureFactRevisionEntity.CLOSURE_SOURCE_BOUNDARY,
			qualification = PressureFactRevisionEntity.QUALIFICATION_PARTIAL,
			effectChecksum = "pending-partial-effect",
		).let { candidate ->
			candidate.copy(effectChecksum = pressureEffectChecksum(candidate, fixture.binding))
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_revision SET closure_kind = ?, qualification = ?, " +
				"effect_checksum = ? WHERE service_run_id = ?",
			arrayOf<Any>(
				partial.closureKind,
				partial.qualification,
				partial.effectChecksum,
				fixture.runId,
			),
		)

		subject().deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.Deleted
		factsFor(fixture, 2) shouldBe emptyList()
	}

	@Test
	fun `production multi manifest Pressure chain deletes facts from each exact slice`() = runTest {
		val fixture = insertPressureSession("multi", 1L, 1_000L, 3_000L)
		database.sourcePolicyDao().insertPolicies(
			listOf(pressurePolicy(revision = 2L, consentEpoch = 2L)),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(pressureConsent(epoch = 2L, policyRevision = 2L)),
		)
		val (secondManifest, secondBinding) = insertAdditionalPressureManifest(
			fixture = fixture,
			effectiveWallTimeMs = 2_000L,
			effectiveElapsedRealtimeNanos = 2L * SECOND_NANOS,
			sourcePolicyRevision = 2L,
			consentEpoch = 2L,
		)
		val secondFact = pressureFact(
			fixture = fixture,
			admissionOrdinal = 2L,
			eventId = "multi-second",
			manifest = secondManifest,
			binding = secondBinding,
		)
		database.pressureFactRevisionDao().insert(secondFact).shouldBeGreaterThanZero()

		subject().deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.Deleted
		factsFor(fixture, 3) shouldBe emptyList()
	}

	@Test
	fun `all real Pressure capture qos tiers remain deletable`() = runTest {
		listOf(1, 3).forEachIndexed { index, qosCode ->
			val fixture = insertPressureSession(
				key = "valid-qos-$qosCode",
				admissionOrdinal = 30L + index,
				startMs = 30_000L + index * 10_000L,
				endMs = 31_000L + index * 10_000L,
			)
			rewriteManifestAndPolicyQos(fixture, qosCode)

			subject().deleteSelectedSession(fixture.segmentId) shouldBe
				PressureSessionDeletionResult.Deleted
			database.sessionSegmentDao().getById(fixture.segmentId) shouldBe null
		}
		verify(exactly = 2) {
			dirtyTracker.markDirty(
				setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
			)
		}
		drainRequests shouldBe 2
	}

	@Test
	fun `ineligible exact Pressure consent rejects without mutation`() = runTest {
		val fixture = insertPressureSession("ineligible-consent", 40L, 50_000L, 51_000L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_consent_epoch SET eligible = 0, persistence_eligible = 0 " +
				"WHERE source_kind = ? AND purpose = ? AND epoch = ?",
			arrayOf<Any>(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				CAPTURE_CONSENT_EPOCH,
			),
		)
		val factCount = database.pressureFactRevisionDao().count()
		val evidence = database.sourceEvidenceStateDao().get()

		subject().deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.UnsupportedScope(
				PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
			)
		database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
		database.pressureFactRevisionDao().count() shouldBe factCount
		exactFence(fixture) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe evidence
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `disabled and unknown Pressure capture qos reject even when policy and binding agree`() =
		runTest {
			listOf(0, 4).forEachIndexed { index, qosCode ->
				val fixture = insertPressureSession(
					key = "invalid-qos-$qosCode",
					admissionOrdinal = 20L + index,
					startMs = 20_000L + index * 10_000L,
					endMs = 21_000L + index * 10_000L,
				)
				rewriteManifestAndPolicyQos(fixture, qosCode)
				val factCount = database.pressureFactRevisionDao().count()
				val evidence = database.sourceEvidenceStateDao().get()

				withClue("qosCode=$qosCode") {
					subject().deleteSelectedSession(fixture.segmentId) shouldBe
						PressureSessionDeletionResult.UnsupportedScope(
							PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
						)
					database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
					database.pressureFactRevisionDao().count() shouldBe factCount
					exactFence(fixture) shouldBe null
					database.sourceEvidenceStateDao().get() shouldBe evidence
				}
				setPressurePolicyQos(PRESSURE_CAPTURE_QOS_CODE)
			}
			verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
			drainRequests shouldBe 0
		}

	@Test
	@Suppress("LongMethod")
	fun `malformed production manifest run chains fail closed before fencing`() = runTest {
		val cases = listOf(
			ManifestChainCase("prepared revision") { fixture ->
				val run = requireNotNull(database.sourceSessionDao().serviceRun(fixture.runId))
				database.sourceSessionDao().updateServiceRun(
					run.copy(preparedManifestRevision = 2L),
				) shouldBe 1
			},
			ManifestChainCase("first wall time") { fixture ->
				rewriteManifestVersion(fixture) { manifest ->
					manifest.copy(effectiveWallTimeMs = manifest.effectiveWallTimeMs + 1L)
				}
			},
			ManifestChainCase("first elapsed time") { fixture ->
				rewriteManifestVersion(fixture) { manifest ->
					manifest.copy(effectiveElapsedRealtimeNanos = 1L)
				}
			},
			ManifestChainCase("first boot") { fixture ->
				rewriteManifestVersion(fixture) { manifest -> manifest.copy(effectiveBootId = "boot-2") }
			},
			ManifestChainCase("revision gap") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					manifestRevision = 3L,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 2_000L,
					effectiveElapsedRealtimeNanos = 2L * SECOND_NANOS,
				)
			},
			ManifestChainCase("wall time regression") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 2_000L,
					effectiveElapsedRealtimeNanos = 2L * SECOND_NANOS,
				)
				insertAdditionalPressureManifest(
					fixture = fixture,
					manifestRevision = 3L,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = 3L * SECOND_NANOS,
				)
			},
			ManifestChainCase("elapsed time regression") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = 2L * SECOND_NANOS,
				)
				insertAdditionalPressureManifest(
					fixture = fixture,
					manifestRevision = 3L,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 2_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
				)
			},
			ManifestChainCase("rollout disagreement") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
					rolloutRevision = 3L,
				)
			},
			ManifestChainCase("mode disagreement") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
					sessionMode = "AUTOMATIC",
				)
			},
			ManifestChainCase("successor origin") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
					startOrigin = "MANUAL_FOREGROUND_START",
				)
			},
			ManifestChainCase("current run plan") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
				)
				val run = requireNotNull(database.sourceSessionDao().serviceRun(fixture.runId))
				database.sourceSessionDao().updateServiceRun(run.copy(desiredPlanRevision = 1L)) shouldBe 1
			},
			ManifestChainCase("logical session rollout") { fixture ->
				val session = requireNotNull(database.sourceSessionDao().session(fixture.logicalId))
				database.sourceSessionDao().updateSession(session.copy(rolloutRevision = 3L)) shouldBe 1
			},
			ManifestChainCase("logical session plan") { fixture ->
				insertAdditionalPressureManifest(
					fixture = fixture,
					effectiveWallTimeMs = fixture.fact.intervalStartTimeMs + 1_000L,
					effectiveElapsedRealtimeNanos = SECOND_NANOS,
				)
				val session = requireNotNull(database.sourceSessionDao().session(fixture.logicalId))
				database.sourceSessionDao().updateSession(session.copy(desiredPlanRevision = 1L)) shouldBe 1
			},
		)

		for ((index, case) in cases.withIndex()) {
			val startMs = 10_000L + index * 10_000L
			val fixture = insertPressureSession(
				key = "manifest-chain-$index",
				admissionOrdinal = 100L + index,
				startMs = startMs,
				endMs = startMs + 5_000L,
			)
			case.mutate(fixture)

			withClue(case.name) {
				subject().deleteSelectedSession(fixture.segmentId) shouldBe
					PressureSessionDeletionResult.UnsupportedScope(
						PressureSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED,
					)
				database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
				factsFor(fixture, 2) shouldBe listOf(fixture.fact)
				exactFence(fixture) shouldBe null
			}
		}
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `fact crossing its referenced manifest slice fails closed before fencing`() = runTest {
		val fixture = insertPressureSession("slice-crossing", 1L, 1_000L, 3_000L)
		insertAdditionalPressureManifest(
			fixture = fixture,
			effectiveWallTimeMs = 1_100L,
			effectiveElapsedRealtimeNanos = 2L * SECOND_NANOS,
		)

		subject().deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.UnsupportedScope(
				PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
			)
		database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
		factsFor(fixture, 2) shouldBe listOf(fixture.fact)
		exactFence(fixture) shouldBe null
	}

	@Test
	fun `prior exactly fenced Steps run remains accepted by shared day repair`() = runTest {
		val selected = insertPressureSession("selected", 1L, 1_000L, 2_000L)
		insertMissingFencedStepsRun()

		subject().deleteSelectedSession(selected.segmentId) shouldBe
			PressureSessionDeletionResult.Deleted

		database.sessionSegmentDao().getById(selected.segmentId) shouldBe null
		exactFence(selected).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 2L
	}

	@Test
	fun `materializing Steps survivor returns retryable before Pressure deletion authority`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selected = insertPressureSession(
			key = "selected-materializing",
			admissionOrdinal = 1L,
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = day,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = HOUR_MS,
			tripCount = 1,
			activeTrackingMs = 0L,
			lastUpdatedMs = dayStart + 2L * HOUR_MS,
			calendarZoneId = ZONE.id,
		)
		insertMaterializingStepsSurvivor(
			startMs = dayStart + 3L * HOUR_MS,
			endMs = dayStart + 4L * HOUR_MS,
		)

		subject().deleteSelectedSession(selected.segmentId) shouldBe
			PressureSessionDeletionResult.RetryableFailure(
				PressureSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)
		database.sessionSegmentDao().getById(selected.segmentId).shouldNotBeNull()
		factsFor(selected, 2) shouldBe listOf(selected.fact)
		exactFence(selected) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `control-only missing segment cannot borrow a Pressure fence for day repair`() = runTest {
		val selected = insertPressureSession("selected-control", 1L, 1_000L, 2_000L)
		insertInvalidMissingRun(
			key = "control-only",
			sources = { logicalId -> listOf(controlManifestSource(logicalId)) },
		)
		val evidence = database.sourceEvidenceStateDao().get()

		subject().deleteSelectedSession(selected.segmentId) shouldBe
			PressureSessionDeletionResult.UnsupportedScope(
				PressureSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		database.sessionSegmentDao().getById(selected.segmentId).shouldNotBeNull()
		factsFor(selected, 2) shouldBe listOf(selected.fact)
		exactFence(selected) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe evidence
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `mixed-source missing segment cannot be authorized by one source fence`() = runTest {
		val selected = insertPressureSession("selected-mixed", 1L, 1_000L, 2_000L)
		insertInvalidMissingRun(
			key = "mixed-capture",
			sources = { logicalId ->
				listOf(pressureManifestSource(logicalId), stepsManifestSource(logicalId))
			},
		)
		val evidence = database.sourceEvidenceStateDao().get()

		subject().deleteSelectedSession(selected.segmentId) shouldBe
			PressureSessionDeletionResult.UnsupportedScope(
				PressureSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		database.sessionSegmentDao().getById(selected.segmentId).shouldNotBeNull()
		factsFor(selected, 2) shouldBe listOf(selected.fact)
		exactFence(selected) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe evidence
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `invalid identity and released attribution return typed non-mutating outcomes`() = runTest {
		subject().deleteSelectedSession(0L) shouldBe PressureSessionDeletionResult.UnsupportedScope(
			PressureSessionDeletionUnsupportedReason.INVALID_SEGMENT_ID,
		)
		val legacyId = database.sessionSegmentDao().insert(segment(1_000L, 2_000L, 0f))

		subject().deleteSelectedSession(legacyId) shouldBe
			PressureSessionDeletionResult.LegacyUnverifiable

		database.sessionSegmentDao().getById(legacyId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.pressureFactRevisionDao().count() shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `active lifecycle demand and pending action each block without using quiescence as proof`() =
		runTest {
			val active = insertPressureSession(
				key = "active",
				admissionOrdinal = 1L,
				startMs = 1_000L,
				endMs = 2_000L,
				logicalState = "ACTIVE",
				runState = "ACTIVE",
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			)
			subject().deleteSelectedSession(active.segmentId) shouldBe
				PressureSessionDeletionResult.BlockedActive

			val demanded = insertPressureSession("demanded", 2L, 3_000L, 4_000L)
			database.sourceBrokerDao().insertDemands(listOf(activeDemand(demanded)))
			subject().deleteSelectedSession(demanded.segmentId) shouldBe
				PressureSessionDeletionResult.BlockedActive

			val pending = insertPressureSession("pending-action", 3L, 5_000L, 6_000L)
			database.sourceSessionDao().insertLifecycleActions(listOf(pendingLifecycleAction(pending)))
			subject().deleteSelectedSession(pending.segmentId) shouldBe
				PressureSessionDeletionResult.BlockedActive

			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.pressureFactRevisionDao().count() shouldBe 3L
			verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
			drainRequests shouldBe 0
		}

	@Test
	@Suppress("LongMethod")
	fun `invalid authority fact integrity and corrections reject without mutation`() = runTest {
		val cases = listOf<NegativeCase>(
			NegativeCase(
				name = "fact checksum",
				expected = PressureSessionDeletionUnsupportedReason.FACT_INTEGRITY_FAILED,
				mutate = { fixture ->
					database.openHelper.writableDatabase.execSQL(
						"UPDATE pressure_fact_revision SET effect_checksum = 'corrupt' " +
							"WHERE service_run_id = ?",
						arrayOf(fixture.runId),
					)
				},
			),
			NegativeCase(
				name = "manifest checksum",
				expected = PressureSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED,
				mutate = { fixture ->
					database.openHelper.writableDatabase.execSQL(
						"UPDATE session_manifest_version SET manifest_checksum = 'corrupt' " +
							"WHERE service_run_id = ?",
						arrayOf(fixture.runId),
					)
				},
			),
			NegativeCase(
				name = "fact manifest attribution",
				expected = PressureSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
				mutate = { fixture ->
					database.openHelper.writableDatabase.execSQL(
						"UPDATE pressure_fact_revision SET source_policy_revision = 2 " +
							"WHERE service_run_id = ?",
						arrayOf(fixture.runId),
					)
				},
			),
			NegativeCase(
				name = "source policy revision",
				expected = PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				mutate = { fixture -> rewriteManifestPolicy(fixture, 9L) },
			),
			NegativeCase(
				name = "capture consent epoch",
				expected = PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				mutate = { fixture -> rewriteManifestConsent(fixture, 9L) },
			),
			NegativeCase(
				name = "capture qos",
				expected = PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				mutate = ::rewriteManifestQosMismatch,
			),
			NegativeCase(
				name = "automatic candidate binding",
				expected = PressureSessionDeletionUnsupportedReason.CANDIDATE_WRITER_BINDING_INVALID,
				mutate = ::rewriteManifestAutomatic,
			),
			NegativeCase(
				name = "mixed capture set",
				expected = PressureSessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				mutate = ::addLocationCapture,
			),
			NegativeCase(
				name = "legacy writer",
				expected = PressureSessionDeletionUnsupportedReason.LEGACY_WRITER,
				mutate = ::rewriteManifestLegacyWriter,
			),
			NegativeCase(
				name = "reverse service-run binding",
				expected = PressureSessionDeletionUnsupportedReason.SERVICE_RUN_BINDING_MISMATCH,
				mutate = ::breakReverseBinding,
			),
			NegativeCase(
				name = "stale collected-data epoch",
				expected = PressureSessionDeletionUnsupportedReason.STALE_COLLECTED_DATA_EPOCH,
				mutate = ::rewriteFactToStaleEpoch,
			),
			NegativeCase(
				name = "correction without revision one",
				expected = PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				mutate = ::replaceFirstFactWithCorrection,
			),
			NegativeCase(
				name = "noncontiguous correction revision",
				expected = PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				mutate = ::insertRevisionGap,
			),
			NegativeCase(
				name = "nonmonotonic correction admission",
				expected = PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				mutate = ::insertNonmonotonicCorrection,
			),
			NegativeCase(
				name = "changed correction source-event lineage",
				expected = PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				mutate = ::insertChangedSourceEventCorrection,
			),
			NegativeCase(
				name = "cross-scope correction",
				expected = PressureSessionDeletionUnsupportedReason.FACT_CORRECTION_SCOPE_UNVERIFIABLE,
				mutate = ::insertCrossScopeCorrection,
			),
			// This mutation changes the shared consent row, so it must remain last in this table.
			NegativeCase(
				name = "consent from a future policy",
				expected = PressureSessionDeletionUnsupportedReason.SOURCE_POLICY_ATTRIBUTION_MISMATCH,
				mutate = ::rewriteConsentToFuturePolicy,
			),
		)

		for ((index, case) in cases.withIndex()) {
			val fixture = insertPressureSession(
				key = "negative-$index",
				admissionOrdinal = 10L + index * 3L,
				startMs = 10_000L + index * 10_000L,
				endMs = 11_000L + index * 10_000L,
			)
			case.mutate(fixture)
			val factCount = database.pressureFactRevisionDao().count()
			val evidence = database.sourceEvidenceStateDao().get()

			withClue(case.name) {
				subject().deleteSelectedSession(fixture.segmentId) shouldBe
					PressureSessionDeletionResult.UnsupportedScope(case.expected)
				database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
				database.pressureFactRevisionDao().count() shouldBe factCount
				exactFence(fixture) shouldBe null
				database.sourceEvidenceStateDao().get() shouldBe evidence
			}
		}
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	@Suppress("LongMethod")
	fun `large scope correction crossing page cancels transactionally then deletes idempotently`() =
		runTest {
			val fixture = insertPressureSession("large", 1L, 1_000L, 2_000L)
			var correction: PressureFactRevisionEntity? = null
			database.withTransaction {
				for (ordinal in 2L..LARGE_SESSION_FACT_COUNT) {
					val fact = when {
						ordinal <= FACT_DELETE_PAGE_SIZE -> pressureFact(
							fixture = fixture,
							admissionOrdinal = ordinal,
							eventId = "000-large-$ordinal",
						)
						ordinal == FACT_DELETE_PAGE_SIZE + 1L -> pressureFact(
							fixture = fixture,
							admissionOrdinal = ordinal,
							eventId = fixture.fact.sourceEventId,
							semanticRevision = 2L,
						).also { correction = it }
						else -> pressureFact(
							fixture = fixture,
							admissionOrdinal = ordinal,
							eventId = "zzz-large-$ordinal",
						)
					}
					database.pressureFactRevisionDao().insert(fact).shouldBeGreaterThanZero()
				}
			}
			val exactCorrection = requireNotNull(correction)
			database.pressureFactRevisionDao().count() shouldBe LARGE_SESSION_FACT_COUNT
			val firstPage = factsFor(fixture, FACT_DELETE_PAGE_SIZE.toInt())
			firstPage.last() shouldBe fixture.fact
			database.pressureFactRevisionDao().exactServiceRunPageAfter(
				logicalTrackingId = fixture.logicalId,
				serviceRunId = fixture.runId,
				afterWriterId = fixture.fact.writerProjectionId,
				afterWriterVersion = fixture.fact.writerProjectionVersion,
				afterLogicalFactId = fixture.fact.logicalFactId,
				afterSemanticRevision = fixture.fact.semanticRevision,
				limit = 1,
			) shouldBe listOf(exactCorrection)
			var cancellationObserved = false
			try {
				subject(
					afterFactBatchDeleted = { deletedCount ->
						if (deletedCount == FACT_DELETE_PAGE_SIZE) {
							throw CancellationException("cancel after first fact batch")
						}
					},
				).deleteSelectedSession(fixture.segmentId)
			} catch (_: CancellationException) {
				cancellationObserved = true
			}

			cancellationObserved shouldBe true
			database.pressureFactRevisionDao().count() shouldBe LARGE_SESSION_FACT_COUNT
			database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
			exactFence(fixture) shouldBe null
			database.sourceEvidenceStateDao().get()?.revision shouldBe 0L

			subject().deleteSelectedSession(fixture.segmentId) shouldBe
				PressureSessionDeletionResult.Deleted
			database.pressureFactRevisionDao().count() shouldBe 0L
			subject().deleteSelectedSession(fixture.segmentId) shouldBe
				PressureSessionDeletionResult.NotFound
		}

	@Test
	fun `fact append after day locks fails scope CAS before any deletion mutation`() = runTest {
		val fixture = insertPressureSession("scope-cas", 1L, 1_000L, 2_000L)
		val concurrentFact = pressureFact(
			fixture = fixture,
			admissionOrdinal = 2L,
			eventId = "scope-cas-late",
		)

		subject(
			afterDayLocksAcquired = {
				database.pressureFactRevisionDao().insert(concurrentFact).shouldBeGreaterThanZero()
			},
		).deleteSelectedSession(fixture.segmentId) shouldBe
			PressureSessionDeletionResult.RetryableFailure(
				PressureSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)

		factsFor(fixture, 3) shouldBe listOf(fixture.fact, concurrentFact).sortedBy { it.logicalFactId }
		database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
		exactFence(fixture) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `cancellation after fence installation rolls back every durable mutation`() = runTest {
		val fixture = insertPressureSession("cancel", 1L, 1_000L, 2_000L)
		val evidence = database.sourceEvidenceStateDao().get()
		var cancellationObserved = false
		try {
			subject(afterFenceInstalled = { throw CancellationException("test cancellation") })
				.deleteSelectedSession(fixture.segmentId)
		} catch (_: CancellationException) {
			cancellationObserved = true
		}

		cancellationObserved shouldBe true
		database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
		factsFor(fixture, 2) shouldBe listOf(fixture.fact)
		exactFence(fixture) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe evidence
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `concurrent row loss after fence and fact deletion rolls back the transaction`() = runTest {
		val fixture = insertPressureSession("concurrent", 1L, 1_000L, 2_000L)
		val evidence = database.sourceEvidenceStateDao().get()

		val result = subject(
			afterFenceInstalled = {
				database.sessionSegmentDao().deleteExact(
					fixture.segmentId,
					fixture.logicalId,
					fixture.runId,
				) shouldBe 1
			},
		).deleteSelectedSession(fixture.segmentId)

		result shouldBe PressureSessionDeletionResult.RetryableFailure(
			PressureSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		)
		database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
		factsFor(fixture, 2) shouldBe listOf(fixture.fact)
		exactFence(fixture) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe evidence
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `SQLite mutation failure rolls back fence facts evidence and cursor then retry commits`() =
		runTest {
			val fixture = insertPressureSession("sqlite-retry", 1L, 1_000L, 2_000L)
			installCanonicalPressureLane()
			val evidence = database.sourceEvidenceStateDao().get()
			database.openHelper.writableDatabase.execSQL(
				"CREATE TRIGGER pressure_deletion_transient_failure " +
					"BEFORE DELETE ON session_segment WHEN OLD.id = ${fixture.segmentId} BEGIN " +
					"SELECT RAISE(ABORT, 'transient Pressure deletion failure'); END",
			)

			subject().deleteSelectedSession(fixture.segmentId) shouldBe
				PressureSessionDeletionResult.RetryableFailure(
					PressureSessionDeletionRetryableReason.DATABASE_UNAVAILABLE,
				)
			database.sessionSegmentDao().getById(fixture.segmentId).shouldNotBeNull()
			factsFor(fixture, 2) shouldBe listOf(fixture.fact)
			exactFence(fixture) shouldBe null
			database.sourceEvidenceStateDao().get() shouldBe evidence
			activePressureLane()?.contiguousAdmissionOrdinal shouldBe 0L
			verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
			drainRequests shouldBe 0

			database.openHelper.writableDatabase.execSQL(
				"DROP TRIGGER pressure_deletion_transient_failure",
			)
			subject().deleteSelectedSession(fixture.segmentId) shouldBe
				PressureSessionDeletionResult.Deleted

			database.sessionSegmentDao().getById(fixture.segmentId) shouldBe null
			factsFor(fixture, 2).shouldHaveSize(0)
			exactFence(fixture).shouldNotBeNull()
			database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
			activePressureLane()?.contiguousAdmissionOrdinal shouldBe 0L
			verify(exactly = 1) { dirtyTracker.markDirty(any<Set<String>>()) }
			drainRequests shouldBe 1
		}

	private fun subject(
		afterDayLocksAcquired: suspend () -> Unit = {},
		beforeMutation: suspend () -> Unit = {},
		afterFenceInstalled: suspend () -> Unit = {},
		afterFactBatchDeleted: suspend (deletedCount: Long) -> Unit = {},
	) = RoomPressureSelectedSessionDeletionService(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = { DELETED_AT_MS },
		requestPressureDrain = { drainRequests += 1 },
		afterDayLocksAcquired = afterDayLocksAcquired,
		beforeMutation = beforeMutation,
		afterFenceInstalled = afterFenceInstalled,
		afterFactBatchDeleted = afterFactBatchDeleted,
	)

	private suspend fun factsFor(
		fixture: PressureFixture,
		limit: Int,
	): List<PressureFactRevisionEntity> = database.pressureFactRevisionDao()
		.firstExactServiceRunPage(fixture.logicalId, fixture.runId, limit)

	@Suppress("LongMethod", "LongParameterList")
	private suspend fun insertPressureSession(
		key: String,
		admissionOrdinal: Long,
		startMs: Long,
		endMs: Long,
		distanceM: Float = 0f,
		logicalState: String = "FINALIZED",
		runState: String = "FINALIZED",
		presentationAcknowledgement: String = SourceServiceRunEntity.PRESENTATION_QUIESCED,
		insertCompleteness: Boolean = false,
	): PressureFixture {
		val logicalId = "logical-pressure-$key"
		val runId = "run-pressure-$key"
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, distanceM).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		val terminal = logicalState in TERMINAL_STATES
		database.sourceSessionDao().insertSession(
			logicalSession(
				logicalId = logicalId,
				state = logicalState,
				startMs = startMs,
				endMs = endMs,
				currentServiceRunId = runId.takeUnless { terminal },
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = segmentId,
				state = runState,
				startMs = startMs,
				endMs = endMs.takeIf { runState in TERMINAL_STATES },
				presentationAcknowledgement = presentationAcknowledgement,
			),
		)
		val binding = pressureManifestSource(logicalId)
		val manifest = insertManifest(
			logicalId = logicalId,
			runId = runId,
			startMs = startMs,
			sources = listOf(binding),
		)
		val incomplete = PressureFixture(
			segmentId = segmentId,
			logicalId = logicalId,
			runId = runId,
			manifest = manifest,
			binding = binding,
			fact = placeholderFact(logicalId, runId, admissionOrdinal, startMs),
		)
		val fact = pressureFact(incomplete, admissionOrdinal, "pressure-$key")
		database.pressureFactRevisionDao().insert(fact).shouldBeGreaterThanZero()
		if (insertCompleteness) {
			database.sourceSessionDao().saveCompleteness(
				SourceSessionCompletenessEntity(
					logicalTrackingId = logicalId,
					serviceRunId = runId,
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					sourceInstanceId = "pressure-provider-$key",
					registrationGeneration = 1L,
					lastAdmissionOrdinal = admissionOrdinal,
					lastSourceSequence = admissionOrdinal,
					appDrainComplete = true,
					providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
					stopStatus = "COMPLETE",
					unresolvedSequenceStart = null,
					unresolvedSequenceEnd = null,
					updatedAtMs = endMs,
				),
			)
		}
		return incomplete.copy(fact = fact)
	}

	private fun Long.shouldBeGreaterThanZero() {
		(this > 0L) shouldBe true
	}

	private fun placeholderFact(
		logicalId: String,
		runId: String,
		admissionOrdinal: Long,
		startMs: Long,
	) = PressureFactRevisionEntity(
		logicalFactId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:placeholder",
		semanticRevision = 1L,
		mutationId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:placeholder:1",
		sourceEventId = "placeholder",
		sourceAdmissionOrdinal = admissionOrdinal,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
		intervalStartTimeMs = startMs,
		intervalEndTimeMs = startMs + PRESSURE_WINDOW_MS,
		windowStartElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS,
		windowEndElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS + PRESSURE_WINDOW_NANOS,
		clockDomainId = "boot-1",
		wallTimeUncertaintyMs = 1L,
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstProviderSequence = admissionOrdinal * 10L + 1L,
		lastProviderSequence = admissionOrdinal * 10L + 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = SourceQualityFlag.BATCHED.bit,
		sourceQualityConfidence = 0.75f,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = MANIFEST_REVISION,
		sourcePolicyRevision = SOURCE_POLICY_REVISION,
		captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
		collectedDataEpoch = COLLECTED_DATA_EPOCH,
		effectChecksum = "placeholder",
		appliedAtMs = startMs + PRESSURE_WINDOW_MS,
	)

	private fun pressureFact(
		fixture: PressureFixture,
		admissionOrdinal: Long,
		eventId: String,
		semanticRevision: Long = 1L,
		logicalId: String = fixture.logicalId,
		runId: String = fixture.runId,
		collectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		manifest: SessionManifestVersionEntity = fixture.manifest,
		binding: SessionManifestSourceEntity = fixture.binding,
	): PressureFactRevisionEntity {
		val logicalFactId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$eventId"
		val candidate = placeholderFact(logicalId, runId, admissionOrdinal, manifest.effectiveWallTimeMs)
			.copy(
				logicalFactId = logicalFactId,
				semanticRevision = semanticRevision,
				mutationId = "$logicalFactId:$semanticRevision",
				sourceEventId = eventId,
				manifestRevision = manifest.manifestRevision,
				sourcePolicyRevision = manifest.sourcePolicyRevision,
				captureConsentEpoch = binding.consentEpoch,
				collectedDataEpoch = collectedDataEpoch,
				effectChecksum = "pending",
			)
		return candidate.copy(effectChecksum = pressureEffectChecksum(candidate, binding))
	}

	private suspend fun insertAdditionalPressureManifest(
		fixture: PressureFixture,
		manifestRevision: Long = 2L,
		effectiveWallTimeMs: Long,
		effectiveElapsedRealtimeNanos: Long,
		acquisitionPlanRevision: Long = 2L,
		rolloutRevision: Long = fixture.manifest.rolloutRevision,
		sessionMode: String = fixture.manifest.sessionMode,
		startOrigin: String = "POLICY_RECONCILIATION",
		sourcePolicyRevision: Long = fixture.manifest.sourcePolicyRevision,
		consentEpoch: Long = fixture.binding.consentEpoch,
	): Pair<SessionManifestVersionEntity, SessionManifestSourceEntity> {
		val binding = fixture.binding.copy(
			manifestRevision = manifestRevision,
			consentEpoch = consentEpoch,
		)
		val unsigned = fixture.manifest.copy(
			manifestRevision = manifestRevision,
			sessionMode = sessionMode,
			sourcePolicyRevision = sourcePolicyRevision,
			acquisitionPlanRevision = acquisitionPlanRevision,
			rolloutRevision = rolloutRevision,
			startOrigin = startOrigin,
			effectiveWallTimeMs = effectiveWallTimeMs,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			changeReason = "POLICY_RECONCILIATION",
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(binding)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(fixture.runId))
		database.sourceSessionDao().updateServiceRun(
			run.copy(desiredPlanRevision = acquisitionPlanRevision),
		)
		val session = requireNotNull(database.sourceSessionDao().session(fixture.logicalId))
		database.sourceSessionDao().updateSession(
			session.copy(
				desiredPlanRevision = acquisitionPlanRevision,
				currentManifestRevision = manifestRevision,
			),
		)
		return manifest to binding
	}

	private suspend fun rewriteManifestVersion(
		fixture: PressureFixture,
		manifestRevision: Long = MANIFEST_REVISION,
		transform: (SessionManifestVersionEntity) -> SessionManifestVersionEntity,
	) {
		val current = database.sourceSessionDao().manifestsForServiceRun(fixture.runId)
			.single { manifest -> manifest.manifestRevision == manifestRevision }
		val sources = database.sourceSessionDao().manifestSources(fixture.logicalId, manifestRevision)
		val unsigned = transform(current).copy(manifestChecksum = "")
		check(unsigned.logicalTrackingId == current.logicalTrackingId &&
			unsigned.manifestRevision == current.manifestRevision &&
			unsigned.serviceRunId == current.serviceRunId)
		val changed = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET session_mode = ?, source_policy_revision = ?, " +
				"acquisition_plan_revision = ?, rollout_revision = ?, start_origin = ?, " +
				"effective_boot_id = ?, effective_elapsed_realtime_nanos = ?, " +
				"effective_wall_time_ms = ?, zone_id = ?, automation_epoch = ?, change_reason = ?, " +
				"manifest_checksum = ? WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(
				changed.sessionMode,
				changed.sourcePolicyRevision,
				changed.acquisitionPlanRevision,
				changed.rolloutRevision,
				changed.startOrigin,
				changed.effectiveBootId,
				changed.effectiveElapsedRealtimeNanos,
				changed.effectiveWallTimeMs,
				changed.zoneId,
				changed.automationEpoch,
				changed.changeReason,
				changed.manifestChecksum,
				changed.logicalTrackingId,
				changed.manifestRevision,
			),
		)
	}

	private fun pressureEffectChecksum(
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
	): String = PressureFactRevisionIntegrity.effectChecksum(fact, binding)

	private fun logicalSession(
		logicalId: String,
		state: String,
		startMs: Long,
		endMs: Long,
		currentServiceRunId: String?,
	) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalId,
		state = state,
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = "boot-1",
		startedAtMs = startMs,
		startedElapsedNanos = 0L,
		cutoffAtMs = endMs.takeIf { state in TERMINAL_STATES },
		cutoffElapsedNanos = endMs.takeIf { state in TERMINAL_STATES },
		completedAtMs = endMs.takeIf { state in TERMINAL_STATES },
		finalAdmissionOrdinal = 1L.takeIf { state in TERMINAL_STATES },
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = MANIFEST_REVISION,
		currentIntentRevision = 1L,
		currentServiceRunId = currentServiceRunId,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = "boot-1",
		automationEpoch = null,
	)

	@Suppress("LongParameterList")
	private fun serviceRun(
		logicalId: String,
		runId: String,
		segmentId: Long,
		state: String,
		startMs: Long,
		endMs: Long?,
		presentationAcknowledgement: String,
	) = SourceServiceRunEntity(
		serviceRunId = runId,
		logicalTrackingId = logicalId,
		state = state,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = startMs,
		startedElapsedNanos = 0L,
		completedAtMs = endMs,
		completionReason = "USER_STOP".takeIf { endMs != null },
		bootId = "boot-1",
		leaseGeneration = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		desiredForegroundCapabilityFlags = 0L,
		appliedForegroundCapabilityFlags = 0L,
		runtimeAcknowledgement = if (endMs == null) {
			"START_ACCEPTED"
		} else {
			"STOP_ACCEPTED"
		},
		runtimeFailureCode = null,
		runRevision = 2L,
		startDeliveryToken = "delivery-$runId",
		startCommandGeneration = 1L,
		preparedManifestRevision = MANIFEST_REVISION,
		preparedIntentRevision = 1L,
		androidDeliveryState = "FOREGROUND_ACCEPTED",
		androidDeliveryUpdatedAtMs = startMs,
		startIsUserInitiated = true,
		startIsAmbient = false,
		sessionSegmentId = segmentId,
		presentationAcknowledgement = presentationAcknowledgement,
		presentationAcknowledgedAtMs = endMs.takeIf {
			presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED
		} ?: startMs.takeIf {
			presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED
		},
	)

	private suspend fun insertManifest(
		logicalId: String,
		runId: String,
		startMs: Long,
		sources: List<SessionManifestSourceEntity>,
	): SessionManifestVersionEntity {
		val unsigned = unsignedManifest(logicalId, runId, startMs)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(sources)
		return manifest
	}

	private fun unsignedManifest(
		logicalId: String,
		runId: String,
		startMs: Long,
	) = SessionManifestVersionEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		serviceRunId = runId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = SOURCE_POLICY_REVISION,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = startMs,
		zoneId = ZONE.id,
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "",
	)

	private fun pressureManifestSource(logicalId: String) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = PRESSURE_CAPTURE_QOS_CODE,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	private fun stepsManifestSource(logicalId: String) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = STEPS_CAPTURE_QOS_CODE,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	)

	private fun controlManifestSource(logicalId: String) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceKind.ACTIVITY.stableCode,
		purpose = SessionManifestPurposeCode.CONTROL,
		consentEpoch = 1L,
		persistenceEligible = false,
		qosCode = 0,
	)

	private fun segment(startMs: Long, endMs: Long, distanceM: Float) = SessionSegment(
		startTimeMs = startMs,
		endTimeMs = endMs,
		distanceM = distanceM,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = endMs,
	)

	private fun pressurePolicy(
		revision: Long = SOURCE_POLICY_REVISION,
		consentEpoch: Long = CAPTURE_CONSENT_EPOCH,
	) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		enabled = true,
		qosCode = PRESSURE_CAPTURE_QOS_CODE,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = consentEpoch,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun pressureConsent(
		epoch: Long = CAPTURE_CONSENT_EPOCH,
		policyRevision: Long = SOURCE_POLICY_REVISION,
	) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = epoch,
		eligible = true,
		persistenceEligible = true,
		policyRevision = policyRevision,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun stepsPolicy() = pressurePolicy().copy(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		qosCode = STEPS_CAPTURE_QOS_CODE,
	)

	private fun stepsConsent() = pressureConsent().copy(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
	)

	private fun candidatePressureOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		updatedAtMs = 1_000L,
	)

	private suspend fun exactFence(fixture: PressureFixture) = database.sourceDeletionFenceDao().get(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = fixture.logicalId,
			serviceRunId = fixture.runId,
		),
	)

	private fun activeDemand(fixture: PressureFixture) = SourceDemandEntity(
		demandId = "demand-${fixture.runId}",
		consumerId = "session:${fixture.logicalId}",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = fixture.logicalId,
		serviceRunId = fixture.runId,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = SOURCE_POLICY_REVISION,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = PRESSURE_CAPTURE_QOS_CODE,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun pendingLifecycleAction(fixture: PressureFixture) = LifecycleDesiredActionEntity(
		actionId = "pending-${fixture.runId}",
		logicalTrackingId = fixture.logicalId,
		serviceRunId = fixture.runId,
		manifestRevision = MANIFEST_REVISION,
		actionRevision = 1L,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		desiredState = "FINALIZED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = SOURCE_POLICY_REVISION,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		startOrigin = "MANUAL_FOREGROUND_START",
		bootId = "boot-1",
		leaseGeneration = 1L,
		requestedAtMs = 1L,
		requestedElapsedRealtimeNanos = 1L,
		status = "PENDING",
		attemptCount = 0,
		acknowledgedAtMs = null,
		acknowledgedElapsedRealtimeNanos = null,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = null,
		registrationGeneration = null,
	)

	private suspend fun rewriteManifestPolicy(fixture: PressureFixture, revision: Long) {
		val changed = fixture.manifest.copy(sourcePolicyRevision = revision, manifestChecksum = "")
		val checksum = SessionManifestIntegrity.compute(changed, listOf(fixture.binding))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET source_policy_revision = ?, manifest_checksum = ? " +
				"WHERE service_run_id = ?",
			arrayOf<Any>(revision, checksum, fixture.runId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_revision SET source_policy_revision = ? WHERE service_run_id = ?",
			arrayOf<Any>(revision, fixture.runId),
		)
	}

	private suspend fun rewriteManifestConsent(fixture: PressureFixture, epoch: Long) {
		val changedSource = fixture.binding.copy(consentEpoch = epoch)
		val changedManifest = fixture.manifest.copy(manifestChecksum = "")
		val checksum = SessionManifestIntegrity.compute(changedManifest, listOf(changedSource))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET consent_epoch = ? WHERE logical_tracking_id = ?",
			arrayOf<Any>(epoch, fixture.logicalId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, fixture.runId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_revision SET capture_consent_epoch = ? WHERE service_run_id = ?",
			arrayOf<Any>(epoch, fixture.runId),
		)
	}

	private suspend fun rewriteManifestAutomatic(fixture: PressureFixture) {
		val changed = fixture.manifest.copy(
			sessionMode = "AUTOMATIC",
			startOrigin = "AUTOMATIC_BACKGROUND_START",
			automationEpoch = 1L,
			manifestChecksum = "",
		)
		val checksum = SessionManifestIntegrity.compute(changed, listOf(fixture.binding))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET session_mode = 'AUTOMATIC', " +
				"start_origin = 'AUTOMATIC_BACKGROUND_START', automation_epoch = 1, " +
				"manifest_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, fixture.runId),
		)
		val run = requireNotNull(database.sourceSessionDao().serviceRun(fixture.runId))
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				startIsUserInitiated = false,
				startIsAmbient = true,
			),
		) shouldBe 1
		val session = requireNotNull(database.sourceSessionDao().session(fixture.logicalId))
		database.sourceSessionDao().updateSession(
			session.copy(
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				sessionMode = "AUTOMATIC",
				automationEpoch = 1L,
			),
		) shouldBe 1
	}

	private suspend fun rewriteManifestQosMismatch(fixture: PressureFixture) {
		rewriteManifestQos(fixture, fixture.binding.qosCode + 1)
	}

	private suspend fun rewriteManifestAndPolicyQos(fixture: PressureFixture, qosCode: Int) {
		rewriteManifestQos(fixture, qosCode)
		setPressurePolicyQos(qosCode)
	}

	private suspend fun rewriteManifestQos(fixture: PressureFixture, qosCode: Int) {
		val changedSource = fixture.binding.copy(qosCode = qosCode)
		val checksum = SessionManifestIntegrity.compute(
			fixture.manifest.copy(manifestChecksum = ""),
			listOf(changedSource),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = ? WHERE logical_tracking_id = ? " +
				"AND manifest_revision = ? AND source_kind = ? AND purpose = ?",
			arrayOf<Any>(
				changedSource.qosCode,
				fixture.logicalId,
				fixture.manifest.manifestRevision,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, fixture.runId),
		)
	}

	private fun setPressurePolicyQos(qosCode: Int) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_policy SET qos_code = ? WHERE policy_revision = ? AND source_kind = ?",
			arrayOf<Any>(
				qosCode,
				SOURCE_POLICY_REVISION,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			),
		)
	}

	private suspend fun rewriteConsentToFuturePolicy(fixture: PressureFixture) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_consent_epoch SET policy_revision = ? WHERE source_kind = ? " +
				"AND purpose = ? AND epoch = ?",
			arrayOf<Any>(
				fixture.manifest.sourcePolicyRevision + 1L,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				fixture.binding.consentEpoch,
			),
		)
	}

	private suspend fun addLocationCapture(fixture: PressureFixture) {
		val location = SessionManifestSourceEntity(
			logicalTrackingId = fixture.logicalId,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 0,
		)
		database.sourceSessionDao().insertManifestSources(listOf(location))
		val checksum = SessionManifestIntegrity.compute(
			fixture.manifest.copy(manifestChecksum = ""),
			listOf(fixture.binding, location),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, fixture.runId),
		)
	}

	private suspend fun rewriteManifestLegacyWriter(fixture: PressureFixture) {
		val legacy = fixture.binding.copy(
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
			writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			writerProjectionId = null,
			writerProjectionVersion = null,
			writerBindingGeneration = null,
		)
		val checksum = SessionManifestIntegrity.compute(
			fixture.manifest.copy(manifestChecksum = ""),
			listOf(legacy),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET writer_owner = ?, writer_owner_generation = ?, " +
				"writer_projection_id = NULL, writer_projection_version = NULL, " +
				"writer_binding_generation = NULL WHERE logical_tracking_id = ?",
			arrayOf<Any>(
				SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				fixture.logicalId,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, fixture.runId),
		)
	}

	private suspend fun breakReverseBinding(fixture: PressureFixture) {
		val otherSegment = database.sessionSegmentDao().insert(segment(500_000L, 501_000L, 0f))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET session_segment_id = ? WHERE service_run_id = ?",
			arrayOf<Any>(otherSegment, fixture.runId),
		)
	}

	private suspend fun rewriteFactToStaleEpoch(fixture: PressureFixture) {
		val stale = fixture.fact.copy(collectedDataEpoch = COLLECTED_DATA_EPOCH - 1L, effectChecksum = "pending")
		val checksum = pressureEffectChecksum(stale, fixture.binding)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_revision SET collected_data_epoch = ?, effect_checksum = ? " +
				"WHERE service_run_id = ?",
			arrayOf<Any>(COLLECTED_DATA_EPOCH - 1L, checksum, fixture.runId),
		)
	}

	private suspend fun replaceFirstFactWithCorrection(fixture: PressureFixture) {
		val correction = pressureFact(
			fixture = fixture,
			admissionOrdinal = fixture.fact.sourceAdmissionOrdinal + 1L,
			eventId = fixture.fact.sourceEventId,
			semanticRevision = 2L,
		)
		database.pressureFactRevisionDao().insert(correction).shouldBeGreaterThanZero()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM pressure_fact_revision WHERE writer_projection_id = ? " +
				"AND writer_projection_version = ? AND logical_fact_id = ? AND semantic_revision = 1",
			arrayOf<Any>(
				fixture.fact.writerProjectionId,
				fixture.fact.writerProjectionVersion,
				fixture.fact.logicalFactId,
			),
		)
	}

	private suspend fun insertRevisionGap(fixture: PressureFixture) {
		database.pressureFactRevisionDao().insert(
			pressureFact(
				fixture = fixture,
				admissionOrdinal = fixture.fact.sourceAdmissionOrdinal + 1L,
				eventId = fixture.fact.sourceEventId,
				semanticRevision = 3L,
			),
		).shouldBeGreaterThanZero()
	}

	private suspend fun insertNonmonotonicCorrection(fixture: PressureFixture) {
		database.pressureFactRevisionDao().insert(
			pressureFact(
				fixture = fixture,
				admissionOrdinal = fixture.fact.sourceAdmissionOrdinal - 1L,
				eventId = fixture.fact.sourceEventId,
				semanticRevision = 2L,
			),
		).shouldBeGreaterThanZero()
	}

	private suspend fun insertChangedSourceEventCorrection(fixture: PressureFixture) {
		val unsigned = pressureFact(
			fixture = fixture,
			admissionOrdinal = fixture.fact.sourceAdmissionOrdinal + 1L,
			eventId = fixture.fact.sourceEventId,
			semanticRevision = 2L,
		).copy(
			sourceEventId = "changed-${fixture.fact.sourceEventId}",
			effectChecksum = "pending-changed-lineage",
		)
		val correction = unsigned.copy(effectChecksum = pressureEffectChecksum(unsigned, fixture.binding))
		database.pressureFactRevisionDao().insert(correction).shouldBeGreaterThanZero()
	}

	private suspend fun insertCrossScopeCorrection(fixture: PressureFixture) {
		val correction = pressureFact(
			fixture = fixture,
			admissionOrdinal = fixture.fact.sourceAdmissionOrdinal + 1L,
			eventId = fixture.fact.sourceEventId,
			semanticRevision = 2L,
			logicalId = "outside-${fixture.logicalId}",
			runId = "outside-${fixture.runId}",
		)
		database.pressureFactRevisionDao().insert(correction).shouldBeGreaterThanZero()
	}

	private suspend fun insertMissingFencedStepsRun() {
		val logicalId = "logical-prior-steps-delete"
		val runId = "run-prior-steps-delete"
		database.sourceSessionDao().insertSession(
			logicalSession(logicalId, "FINALIZED", 3_000L, 4_000L, null),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = MISSING_SEGMENT_ID,
				state = "FINALIZED",
				startMs = 3_000L,
				endMs = 4_000L,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			),
		)
		val source = stepsManifestSource(logicalId)
		insertManifest(logicalId, runId, 3_000L, listOf(source))
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				fenceGeneration = 1L,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				deletedAtMs = DELETED_AT_MS,
			),
		).shouldBeGreaterThanZero()
	}

	private suspend fun insertInvalidMissingRun(
		key: String,
		sources: (logicalId: String) -> List<SessionManifestSourceEntity>,
	) {
		val logicalId = "logical-missing-$key"
		val runId = "run-missing-$key"
		database.sourceSessionDao().insertSession(
			logicalSession(logicalId, "FINALIZED", 3_000L, 4_000L, null),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = MISSING_SEGMENT_ID,
				state = "FINALIZED",
				startMs = 3_000L,
				endMs = 4_000L,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			),
		)
		insertManifest(logicalId, runId, 3_000L, sources(logicalId))
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				fenceGeneration = 1L,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				deletedAtMs = DELETED_AT_MS,
			),
		).shouldBeGreaterThanZero()
	}

	private suspend fun insertMaterializingStepsSurvivor(startMs: Long, endMs: Long) {
		val logicalId = "logical-materializing-steps-survivor"
		val runId = "run-materializing-steps-survivor"
		database.sourcePolicyDao().insertPolicies(listOf(stepsPolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(stepsConsent()))
		database.sourceDestinationOwnerDao().insertIfAbsent(candidateStepsOwner())
		installCanonicalStepsLane()
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, 0f).copy(
				steps = 5,
				sampleCount = 0,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertSession(
			logicalSession(logicalId, "ACTIVE", startMs, endMs, runId),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = segmentId,
				state = "ACTIVE",
				startMs = startMs,
				endMs = null,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			),
		)
		insertManifest(logicalId, runId, startMs, listOf(stepsManifestSource(logicalId)))
	}

	private fun candidateStepsOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		updatedAtMs = 1_000L,
	)

	private suspend fun installCanonicalPressureLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				captureModeMask = PressureSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private suspend fun installCanonicalStepsLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				captureModeMask = StepsSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private suspend fun activePressureLane(): SourceProductProjectionLaneEntity? =
		database.sourceProjectionStateDao().activeProductLane(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		)

	private fun latePressureEvent(
		fixture: PressureFixture,
		admissionOrdinal: Long,
	): AdmittedSourceEvent<PressureWindowPayload> {
		val startNanos = admissionOrdinal * SECOND_NANOS
		val endNanos = startNanos + PRESSURE_WINDOW_NANOS
		return AdmittedSourceEvent(
			eventId = SourceEventId("late-pressure-$admissionOrdinal"),
			admissionOrdinal = admissionOrdinal,
			evidence = SourceEvidenceCandidate(
				providerDedupKey = "late-pressure-dedup-$admissionOrdinal",
				logicalTrackingId = LogicalTrackingId(fixture.logicalId),
				serviceRunId = ServiceRunId(fixture.runId),
				source = SourceKind.PRESSURE,
				sourceInstanceId = SourceInstanceId("pressure-provider"),
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "pressure-config",
				authorizationRevision = 1L,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				registrationEligibilityFingerprint = "pressure-capture",
				sourceSequence = admissionOrdinal,
				configRevision = 1L,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
				clockDomainId = "boot-1",
				observedElapsedRealtimeNanos = endNanos,
				receivedElapsedRealtimeNanos = endNanos + 1_000L,
				wallTimeMs = fixture.fact.intervalEndTimeMs,
				wallTimeUncertaintyMs = 1L,
				capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				sourcePolicyRevision = SOURCE_POLICY_REVISION,
				captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
				sessionManifestRevision = MANIFEST_REVISION,
				lifecycleLeaseGeneration = 1L,
				acquiredAtMs = fixture.fact.intervalEndTimeMs,
				quality = SourceQuality(0.75f, setOf(SourceQualityFlag.BATCHED)),
				payloadVersion = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				payload = PressureWindowPayload(
					sampleCount = 4,
					meanHectopascals = 1_001.5,
					sumSquaredDeviations = 5.0,
					minimumHectopascals = 1_000f,
					maximumHectopascals = 1_003f,
					windowStartElapsedRealtimeNanos = startNanos,
					windowEndElapsedRealtimeNanos = endNanos,
					firstProviderSequence = admissionOrdinal * 10L + 1L,
					lastProviderSequence = admissionOrdinal * 10L + 4L,
					firstHectopascals = 1_000f,
					lastHectopascals = 1_003f,
					slopeHectopascalsPerSecond = 20.0,
					rSquared = 1.0,
					sensorAccuracy = PressureSensorAccuracy.HIGH,
					effectiveSamplePeriodMicros = 50_000,
					effectiveMaximumReportLatencyMicros = 200_000,
					targetWindowDurationNanos = 200_000_000L,
					expectedSampleCount = 4,
					maximumInterSampleGapNanos = 50_000_000L,
					closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
				),
			),
		)
	}

	private data class PressureFixture(
		val segmentId: Long,
		val logicalId: String,
		val runId: String,
		val manifest: SessionManifestVersionEntity,
		val binding: SessionManifestSourceEntity,
		val fact: PressureFactRevisionEntity,
	)

	private data class NegativeCase(
		val name: String,
		val expected: PressureSessionDeletionUnsupportedReason,
		val mutate: suspend (PressureFixture) -> Unit,
	)

	private data class ManifestChainCase(
		val name: String,
		val mutate: suspend (PressureFixture) -> Unit,
	)

	private companion object {
		val ZONE: ZoneId = ZoneId.of("America/New_York")
		const val HOUR_MS = 60L * 60_000L
		const val PRESSURE_WINDOW_MS = 150L
		const val PRESSURE_WINDOW_NANOS = 150_000_000L
		const val SECOND_NANOS = 1_000_000_000L
		const val MANIFEST_REVISION = 1L
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val STEPS_CAPTURE_QOS_CODE = 1
		const val PRESSURE_CAPTURE_QOS_CODE = 2
		const val COLLECTED_DATA_EPOCH = 2L
		const val DELETED_AT_MS = 9_000_000L
		const val MISSING_SEGMENT_ID = 99_999L
		const val LARGE_SESSION_FACT_COUNT = 2_051L
		const val FACT_DELETE_PAGE_SIZE = 256L
		val TERMINAL_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	}
}
