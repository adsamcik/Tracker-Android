package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Validates v27 data semantics through migration, a production Room reopen, and full deletion. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration27To28Test {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun deleteDatabaseBeforeTest() {
		context.deleteDatabase(TEST_DATABASE)
	}

	@After
	fun deleteDatabaseAfterTest() {
		context.deleteDatabase(TEST_DATABASE)
	}

	@Test
	fun populatedV27MigrationIsInertQueryableAndDeletionSafeAcrossReopen() {
		helper.createDatabase(TEST_DATABASE, 27).use(PopulatedV27Fixture::seed)

		helper.runMigrationsAndValidate(TEST_DATABASE, 28, true, MIGRATION_27_28).use { database ->
			assertMigrationState(database)
		}

		openProductionDatabase().let { database ->
			try {
				runBlocking {
					assertProductionQueriesPreserveV27Facts(database)
					assertFailClosedAuthorityAndNoGhostRuntime(database)
					assertForeignKeysEnabled(database)

					AppDatabase.deleteAllCollectedData(
						database = database,
						collectedDataEpoch = 8,
						retainedFromMs = null,
						updatedAtMs = PopulatedV27Fixture.END_MS + 1,
					)

					assertCollectedRowsDeleted(database)
				}
			} finally {
				database.close()
			}
		}

		// Closing and reopening the actual Room database proves neither migration nor recovery can
		// resurrect the terminalized runtime or a cascaded location-projection child.
		openProductionDatabase().let { database ->
			try {
				runBlocking {
					assertCollectedRowsDeleted(database)
					assertNull(database.sourceSessionDao().activeSession())
					assertNull(database.trackerRunDao().getActiveRun())
				}
			} finally {
				database.close()
			}
		}
	}

	private fun assertMigrationState(database: SupportSQLiteDatabase) {
		database.query(
			"SELECT revision, collected_data_epoch, retained_from_ms " +
				"FROM source_evidence_state WHERE id = 1",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(41L, cursor.getLong(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(123_456L, cursor.getLong(2))
		}
		database.query(
			"SELECT state, lifecycle_revision, completed_at_ms, failure_code, session_mode, " +
				"current_manifest_revision, current_intent_revision, lifecycle_lease_generation, " +
				"lifecycle_boot_id, automation_epoch FROM logical_tracking_session " +
				"WHERE logical_tracking_id = '${PopulatedV27Fixture.LOGICAL_TRACKING_ID}'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("FINALIZED", cursor.getString(0))
			assertEquals(4L, cursor.getLong(1))
			assertEquals(PopulatedV27Fixture.START_MS, cursor.getLong(2))
			assertEquals(V28_MIGRATION_INTERRUPTION_REASON, cursor.getString(3))
			assertEquals("LEGACY_UNKNOWN", cursor.getString(4))
			assertTrue(cursor.isNull(5))
			assertTrue(cursor.isNull(6))
			assertEquals(0L, cursor.getLong(7))
			assertTrue(cursor.isNull(8))
			assertTrue(cursor.isNull(9))
		}
		database.query(
			"SELECT state, completed_at_ms, completion_reason, boot_id, lease_generation, " +
				"start_origin, desired_foreground_capability_flags, " +
				"applied_foreground_capability_flags, runtime_acknowledgement, " +
				"runtime_failure_code, run_revision FROM source_service_run " +
				"WHERE service_run_id = '${PopulatedV27Fixture.SERVICE_RUN_ID}'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("FINALIZED", cursor.getString(0))
			assertEquals(PopulatedV27Fixture.START_MS, cursor.getLong(1))
			assertEquals(V28_MIGRATION_INTERRUPTION_REASON, cursor.getString(2))
			assertEquals("LEGACY_UNKNOWN", cursor.getString(3))
			assertEquals(0L, cursor.getLong(4))
			assertEquals("AUTOMATIC_BACKGROUND_START", cursor.getString(5))
			assertEquals(5L, cursor.getLong(6))
			assertEquals(5L, cursor.getLong(7))
			assertEquals("TERMINAL_FAILURE", cursor.getString(8))
			assertEquals(V28_MIGRATION_INTERRUPTION_REASON, cursor.getString(9))
			assertEquals(1L, cursor.getLong(10))
		}
		database.query("SELECT end_time_ms FROM tracker_run WHERE id = 1").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(PopulatedV27Fixture.START_MS, cursor.getLong(0))
		}
		assertTableCount(database, "source_coordinator_lease", 0)
		assertTableCount(database, "source_policy", 0)
		assertTableCount(database, "source_consent_epoch", 0)
		listOf(
			"session_manifest_version",
			"session_manifest_source",
			"session_lifecycle_intent_version",
			"lifecycle_desired_action",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
		).forEach { table -> assertTableCount(database, table, 0) }
		assertTableCount(database, "location_sample", 1)
		assertTableCount(database, "location_observation", 1)
		assertTableCount(database, "location_projection_observation", 1)
		assertTableCount(database, "location_projection_point", 1)
		assertTableCount(database, "step_interval", 2)
		assertTableCount(database, "activity_snapshot", 1)
		assertTableCount(database, "wifi_observation", 1)
		assertTableCount(database, "cell_sample", 1)
		assertTableCount(database, "pressure_sample", 1)
		assertTableCount(database, "session_segment", 1)
		assertTableCount(database, "daily_summary", 1)
		assertTableCount(database, "source_registration_state", 1)
		assertTableCount(database, "source_runtime_state", 1)
		assertTableCount(database, "source_event_wal", 1)
		assertTableCount(database, "source_projection_registration", 1)
		assertTableCount(database, "source_projection_checkpoint", 1)
		assertTableCount(database, "source_projection_outbox", 1)
		assertTableCount(database, "source_session_completeness", 1)
		assertTableCount(database, "pending_signal", 1)
		assertTableCount(database, "import_job_receipt", 1)
		assertTableCount(database, "import_entry_receipt", 1)
		database.query(
			"SELECT payload FROM source_runtime_state WHERE source_kind = 1 AND owner_scope = 'SESSION'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertArrayEquals(byteArrayOf(1, 2, 3), cursor.getBlob(0))
		}
	}

	private suspend fun assertProductionQueriesPreserveV27Facts(database: AppDatabase) {
		val location = database.locationSampleDao().getChunkBetweenOrdered(
			fromMs = PopulatedV27Fixture.START_MS,
			toMs = PopulatedV27Fixture.END_MS,
			afterTimeMs = null,
			afterId = null,
			limit = 10,
		).single()
		assertEquals(101L, location.id)
		assertEquals(PopulatedV27Fixture.LAT_E7, location.latE7)
		assertEquals(PopulatedV27Fixture.LON_E7, location.lonE7)
		assertEquals(PopulatedV27Fixture.LOCATION_EVENT_ID, location.sourceEventId)
		assertEquals("gps", location.provider)
		assertEquals("boot-v27", location.clockDomainId)
		assertEquals("boot-v27", location.bootClockDomainId)
		assertEquals(9L, location.sourceRevision)

		val rawLocation = database.locationObservationDao()
			.getBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
			.single()
		assertEquals(111L, rawLocation.id)
		assertEquals(PopulatedV27Fixture.LOCATION_EVENT_ID, rawLocation.sourceEventId)
		assertEquals(6f, rawLocation.hAccM ?: error("missing horizontal accuracy"), 0f)
		assertEquals("boot-v27", rawLocation.clockDomainId)
		assertEquals(9L, rawLocation.sourceRevision)

		val projectionObservation = database.locationProjectionDao()
			.observations(PopulatedV27Fixture.LOGICAL_TRACKING_ID)
			.single()
		val projectionPoint = database.locationProjectionDao()
			.points(PopulatedV27Fixture.LOGICAL_TRACKING_ID)
			.single()
		assertEquals(PopulatedV27Fixture.LOCATION_EVENT_ID, projectionObservation.eventId)
		assertEquals(1L, projectionObservation.admissionOrdinal)
		assertEquals(125.5, projectionPoint.cumulativeDistanceMeters, 0.0)
		assertTrue(projectionPoint.accepted)

		val steps = database.stepIntervalDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
		assertEquals(2, steps.size)
		assertEquals(
			23,
			database.stepIntervalDao().getTotalSteps(
				PopulatedV27Fixture.START_MS,
				PopulatedV27Fixture.END_MS,
			),
		)
		assertEquals(23, steps[0].stepCount)
		assertTrue(steps[1].sensorReset)
		assertEquals(12L, steps[0].observationStamp.sourceSequence)
		assertEquals("boot-v27", steps[0].observationStamp.clockDomainId)
		assertEquals("STEP_COUNTER_RESET", steps[1].observationStamp.capabilityFlags)

		val activity = database.activitySnapshotDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
			.single()
		assertEquals(7, activity.activityType)
		assertEquals(87, activity.confidence)
		assertTrue(activity.isTransition)
		assertEquals(15L, activity.observationStamp.sourceSequence)
		assertEquals("ACTIVITY_RECOGNITION", activity.observationStamp.permissionPrecision)

		val wifi = database.wifiObservationDao().getChunkBetweenOrdered(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
			null,
			null,
			10,
		).single()
		assertEquals("02:00:00:00:00:01", wifi.bssid)
		assertEquals("v27-fixture-network", wifi.ssid)
		assertEquals(5_180, wifi.frequency)
		assertEquals(PopulatedV27Fixture.LAT_E7, wifi.latE7)
		assertEquals("NEAREST_LOCATION", wifi.provenance.name)
		assertEquals(20L, wifi.observationStamp.sourceSequence)

		val cell = database.cellSampleDao().getChunkBetweenOrdered(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
			null,
			null,
			10,
		).single()
		assertEquals(987_654_321L, cell.cellId)
		assertEquals(13, cell.networkType)
		assertEquals(-95, cell.signalStrength)
		assertNull(cell.latE7)
		assertNull(cell.lonE7)
		assertEquals("UNKNOWN", cell.provenance.name)
		assertEquals(25L, cell.observationStamp.sourceSequence)

		val pressure = database.pressureSampleDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
			.single()
		assertEquals(1_001.25f, pressure.pressureHpa, 0f)
		assertEquals(8, pressure.sampleCount)
		assertEquals(1_001.0f, pressure.minPressureHpa ?: error("missing minimum"), 0f)
		assertEquals(1_001.5f, pressure.maxPressureHpa ?: error("missing maximum"), 0f)
		assertEquals(30L, pressure.observationStamp.sourceSequence)
		assertEquals("PRESSURE_SENSOR", pressure.observationStamp.capabilityFlags)

		val summary = requireNotNull(database.dailySummaryDao().getByDay(PopulatedV27Fixture.DAY_EPOCH))
		assertEquals(125.5f, summary.totalDistanceM, 0f)
		assertEquals(23, summary.totalSteps)
		assertEquals(60_000L, summary.totalDurationMs)
		val segment = database.sessionSegmentDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
			.single()
		assertEquals(125.5f, segment.distanceM, 0f)
		assertEquals(23, segment.steps)

		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(PopulatedV27Fixture.WAL_EVENT_ID))
		assertArrayEquals(PopulatedV27Fixture.expectedWalPayload(), wal.payload)
		assertEquals(SourceEventWalEntity.LEGACY_PENDING_CHECKSUM, wal.integrityIdentity)
		assertTrue(wal.hasPendingLegacyPayload())
		assertNull(wal.sourcePolicyRevision)
		assertNull(wal.captureConsentEpoch)
		assertNull(wal.sessionManifestRevision)
		assertNull(wal.lifecycleLeaseGeneration)
		assertEquals(0L, wal.authorizationPurposeEligibilityMask)

		assertEquals(1, database.pendingSignalDao().countAll())
		assertNotNull(database.importReceiptDao().getJob(PopulatedV27Fixture.IMPORT_JOB_ID))
		assertNotNull(database.importReceiptDao().getEntry(PopulatedV27Fixture.IMPORT_JOB_ID, "entry-1"))
		assertNotNull(database.sourceProjectionStateDao().registration("legacy-location", 1))
		assertNotNull(database.sourceProjectionStateDao().checkpoint("legacy-location", 1))
		assertEquals(1, database.sourceProjectionStateDao().pendingOutbox(10).size)
	}

	private suspend fun assertFailClosedAuthorityAndNoGhostRuntime(database: AppDatabase) {
		val authority = requireNotNull(database.sourcePolicyDao().authority())
		assertEquals("UNINITIALIZED", authority.bootstrapState)
		assertEquals(0L, authority.currentPolicyRevision)
		assertTrue(database.sourcePolicyDao().currentPolicies().isEmpty())
		assertTrue(database.sourcePolicyDao().policiesAtRevision(0).isEmpty())
		assertTrue(database.sourcePolicyDao().consentHistory(1, "SESSION_CAPTURE").isEmpty())
		assertTrue(database.sourceBrokerDao().currentDemands(PopulatedV27Fixture.LOGICAL_TRACKING_ID).isEmpty())
		assertNull(database.sourceBrokerDao().currentPhysicalRegistration(1))
		assertNull(database.sourceSessionDao().activeSession())

		val session = requireNotNull(database.sourceSessionDao().session(PopulatedV27Fixture.LOGICAL_TRACKING_ID))
		assertEquals("FINALIZED", session.state)
		assertEquals(V28_MIGRATION_INTERRUPTION_REASON, session.failureCode)
		assertEquals(PopulatedV27Fixture.START_MS, session.completedAtMs)
		assertTrue(database.sourceSessionDao().manifests(session.logicalTrackingId).isEmpty())
		assertTrue(database.sourceSessionDao().lifecycleIntents(session.logicalTrackingId).isEmpty())

		val service = requireNotNull(database.sourceSessionDao().serviceRun(PopulatedV27Fixture.SERVICE_RUN_ID))
		assertEquals("FINALIZED", service.state)
		assertEquals("AUTOMATIC_BACKGROUND_START", service.startOrigin)
		assertEquals(V28_MIGRATION_INTERRUPTION_REASON, service.completionReason)
		assertEquals(V28_MIGRATION_INTERRUPTION_REASON, service.runtimeFailureCode)
		assertTrue(database.sourceSessionDao().incompleteServiceRuns(session.logicalTrackingId).isEmpty())
		assertNull(database.trackerRunDao().getActiveRun())
		val trackerRun = database.trackerRunDao().getAllBetween(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
		).single()
		assertEquals(PopulatedV27Fixture.START_MS, trackerRun.endTimeMs)
		assertFalse(trackerRun.userInitiated)
	}

	private fun assertForeignKeysEnabled(database: AppDatabase) {
		database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(1L, cursor.getLong(0))
		}
	}

	private suspend fun assertCollectedRowsDeleted(database: AppDatabase) {
		assertEquals(0L, database.locationSampleDao().countAll())
		assertEquals(0L, database.locationObservationDao().countAll())
		assertTrue(database.locationProjectionDao()
			.observations(PopulatedV27Fixture.LOGICAL_TRACKING_ID).isEmpty())
		assertTrue(database.locationProjectionDao()
			.points(PopulatedV27Fixture.LOGICAL_TRACKING_ID).isEmpty())
		assertTrue(database.stepIntervalDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS).isEmpty())
		assertTrue(database.activitySnapshotDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS).isEmpty())
		assertTrue(database.wifiObservationDao().getChunkBetweenOrdered(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
			null,
			null,
			10,
		).isEmpty())
		assertTrue(database.cellSampleDao().getChunkBetweenOrdered(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
			null,
			null,
			10,
		).isEmpty())
		assertEquals(
			0,
			database.pressureSampleDao().countBetween(
				PopulatedV27Fixture.START_MS,
				PopulatedV27Fixture.END_MS,
			),
		)
		assertNull(database.dailySummaryDao().getByDay(PopulatedV27Fixture.DAY_EPOCH))
		assertEquals(0L, database.sourceEventWalDao().countAll())
		assertEquals(0, database.pendingSignalDao().countAll())
		assertNull(database.importReceiptDao().getJob(PopulatedV27Fixture.IMPORT_JOB_ID))
		assertNull(database.sourceSessionDao().session(PopulatedV27Fixture.LOGICAL_TRACKING_ID))
		assertNull(database.sourceSessionDao().serviceRun(PopulatedV27Fixture.SERVICE_RUN_ID))
		assertNull(database.trackerRunDao().getActiveRun())
		assertTrue(database.trackerRunDao().getAllBetween(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
		).isEmpty())
		val evidenceState = requireNotNull(database.sourceEvidenceStateDao().get())
		assertEquals(8L, evidenceState.collectedDataEpoch)
		// Full deletion cannot weaken a previously established retention floor.
		assertEquals(123_456L, evidenceState.retainedFromMs)
	}

	private fun assertTableCount(database: SupportSQLiteDatabase, table: String, expected: Long) {
		database.query("SELECT COUNT(*) FROM $table").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(table, expected, cursor.getLong(0))
		}
	}

	private fun openProductionDatabase(): AppDatabase = Room.databaseBuilder(
		context,
		AppDatabase::class.java,
		TEST_DATABASE,
	)
		.openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
		.addMigrations(MIGRATION_27_28)
		.allowMainThreadQueries()
		.build()

	private companion object {
		const val TEST_DATABASE = "migration-27-28-populated"
	}
}
