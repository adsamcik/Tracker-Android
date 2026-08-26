package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
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
		context.deleteDatabase(UNSUPPORTED_PROJECTION_TEST_DATABASE)
		context.deleteDatabase(PRUNED_WAL_TEST_DATABASE)
		context.deleteDatabase(OUTBOX_ONLY_TEST_DATABASE)
		context.deleteDatabase(OUTBOX_BOUNDARY_TEST_DATABASE)
		context.deleteDatabase(EVENT_FRAME_CHECKPOINT_TEST_DATABASE)
		context.deleteDatabase(EVENT_FRAME_ACTIVATION_TEST_DATABASE)
		context.deleteDatabase(PRESERVED_FAILURE_TEST_DATABASE)
	}

	@After
	fun deleteDatabaseAfterTest() {
		context.deleteDatabase(TEST_DATABASE)
		context.deleteDatabase(UNSUPPORTED_PROJECTION_TEST_DATABASE)
		context.deleteDatabase(PRUNED_WAL_TEST_DATABASE)
		context.deleteDatabase(OUTBOX_ONLY_TEST_DATABASE)
		context.deleteDatabase(OUTBOX_BOUNDARY_TEST_DATABASE)
		context.deleteDatabase(EVENT_FRAME_CHECKPOINT_TEST_DATABASE)
		context.deleteDatabase(EVENT_FRAME_ACTIVATION_TEST_DATABASE)
		context.deleteDatabase(PRESERVED_FAILURE_TEST_DATABASE)
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
					seedMigratedStepFactRevision(database)
					assertEquals(1L, database.stepFactRevisionDao().countAll())

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

	@Test
	fun nonterminalV27FailureEvidenceIsPreservedWhileRuntimeIsFenced() {
		helper.createDatabase(PRESERVED_FAILURE_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL(
				"UPDATE logical_tracking_session SET failure_code = '$EXISTING_SESSION_FAILURE' " +
					"WHERE logical_tracking_id = '${PopulatedV27Fixture.LOGICAL_TRACKING_ID}'",
			)
			database.execSQL(
				"UPDATE source_service_run SET completion_reason = '$EXISTING_COMPLETION_REASON' " +
					"WHERE service_run_id = '${PopulatedV27Fixture.SERVICE_RUN_ID}'",
			)
		}

		helper.runMigrationsAndValidate(
			PRESERVED_FAILURE_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT state, failure_code FROM logical_tracking_session " +
					"WHERE logical_tracking_id = '${PopulatedV27Fixture.LOGICAL_TRACKING_ID}'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("FINALIZED", cursor.getString(0))
				assertEquals(EXISTING_SESSION_FAILURE, cursor.getString(1))
			}
			database.query(
				"SELECT state, completion_reason, runtime_failure_code FROM source_service_run " +
					"WHERE service_run_id = '${PopulatedV27Fixture.SERVICE_RUN_ID}'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("FINALIZED", cursor.getString(0))
				assertEquals(EXISTING_COMPLETION_REASON, cursor.getString(1))
				assertEquals(V28_MIGRATION_INTERRUPTION_REASON, cursor.getString(2))
			}
		}
	}

	@Test
	fun unexpectedV27ProjectionIsDurablyBlockedAndCannotRemainLive() {
		helper.createDatabase(UNSUPPORTED_PROJECTION_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL(
				"INSERT INTO source_projection_registration " +
					"(projection_id, projection_version, activation_ordinal, retention_required, " +
					"status, created_at_ms) VALUES ('unknown-release-projection', 9, 1, 1, " +
					"'ACTIVE', ${PopulatedV27Fixture.START_MS})",
			)
			database.execSQL(
				"INSERT INTO source_projection_checkpoint " +
					"(projection_id, projection_version, contiguous_admission_ordinal, " +
					"state_version, updated_at_ms) VALUES ('unknown-release-projection', 9, 0, 1, " +
					"${PopulatedV27Fixture.START_MS})",
			)
		}

		helper.runMigrationsAndValidate(
			UNSUPPORTED_PROJECTION_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT status, failure_code FROM legacy_v27_projection_drain WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("BLOCKED_UNSUPPORTED_TARGET", cursor.getString(0))
				assertEquals("UNSUPPORTED_LEGACY_PROJECTION", cursor.getString(1))
			}
			database.query(
				"SELECT disposition, failure_code FROM legacy_v27_projection_target " +
					"WHERE projection_id = 'unknown-release-projection' AND projection_version = 9",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("BLOCKED_UNSUPPORTED", cursor.getString(0))
				assertEquals("UNSUPPORTED_LEGACY_PROJECTION", cursor.getString(1))
			}
			database.query(
				"SELECT status FROM source_projection_registration " +
					"WHERE projection_id = 'unknown-release-projection' AND projection_version = 9",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("LEGACY_V27_PENDING", cursor.getString(0))
			}
		}
	}

	@Test
	fun prunedV27WalHighWatermarkStillFencesTheLiveGeneration() {
		helper.createDatabase(PRUNED_WAL_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL("DELETE FROM source_projection_outbox")
			database.execSQL("DELETE FROM source_event_wal")
		}

		helper.runMigrationsAndValidate(
			PRUNED_WAL_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT cutoff_admission_ordinal, status FROM legacy_v27_projection_drain WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1L, cursor.getLong(0))
				assertEquals("NOT_REQUIRED", cursor.getString(1))
			}
			database.query(
				"SELECT deleted_source_event_high_water_ordinal " +
					"FROM source_evidence_state WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				// v27 history is fenced by the retained legacy cutoff until a v28 full deletion
				// atomically advances this independent deletion boundary.
				assertEquals(0L, cursor.getLong(0))
			}
			assertTableCount(database, "legacy_v27_projection_target", 0)
			database.query(
				"SELECT status FROM source_projection_registration " +
					"WHERE projection_id = 'location-domain' AND projection_version = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("LEGACY_V27_PENDING", cursor.getString(0))
			}
		}
	}

	@Test
	fun pendingV27OutboxWithoutWalStillRequiresSuppressionRecovery() {
		helper.createDatabase(OUTBOX_ONLY_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL("DELETE FROM source_event_wal")
		}

		helper.runMigrationsAndValidate(
			OUTBOX_ONLY_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT cutoff_admission_ordinal, status FROM legacy_v27_projection_drain WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1L, cursor.getLong(0))
				assertEquals("PENDING", cursor.getString(1))
			}
			assertTableCount(database, "legacy_v27_projection_target", 4)
			database.query(
				"SELECT COUNT(*) FROM legacy_v27_projection_target " +
					"WHERE last_completed_ordinal < required_through_ordinal",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			assertTableCount(database, "source_projection_outbox", 1)
		}
	}

	@Test
	fun pendingV27OutboxDefinesTheImmutableDrainBoundaryWithoutWalHistory() {
		helper.createDatabase(OUTBOX_BOUNDARY_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL("DELETE FROM source_event_wal")
			database.execSQL("DELETE FROM sqlite_sequence WHERE name = 'source_event_wal'")
			database.execSQL(
				"UPDATE source_projection_outbox SET admission_ordinal = 7 " +
					"WHERE stable_id = 'v27-outbox'",
			)

			assertTableCount(database, "source_event_wal", 0)
			database.query(
				"SELECT seq FROM sqlite_sequence WHERE name = 'source_event_wal'",
			).use { cursor -> assertFalse(cursor.moveToFirst()) }
		}

		helper.runMigrationsAndValidate(
			OUTBOX_BOUNDARY_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT cutoff_admission_ordinal, status " +
					"FROM legacy_v27_projection_drain WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(7L, cursor.getLong(0))
				assertEquals("PENDING", cursor.getString(1))
			}
			database.query(
				"SELECT required_through_ordinal FROM legacy_v27_projection_target " +
					"WHERE projection_id = 'location-domain' AND projection_version = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(7L, cursor.getLong(0))
			}
		}
	}

	@Test
	fun eventFrameDestinationRecoveryStartsAtTheRetainedFloorNotTheProjectionCheckpoint() {
		helper.createDatabase(EVENT_FRAME_CHECKPOINT_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL(
				"INSERT INTO source_projection_registration " +
					"(projection_id, projection_version, activation_ordinal, retention_required, " +
					"status, created_at_ms) VALUES ('event-tracking-frame', 1, 1, 1, " +
					"'ACTIVE', ${PopulatedV27Fixture.START_MS})",
			)
			database.execSQL(
				"INSERT INTO source_projection_checkpoint " +
					"(projection_id, projection_version, contiguous_admission_ordinal, " +
					"state_version, updated_at_ms) VALUES ('event-tracking-frame', 1, 1, 1, " +
					"${PopulatedV27Fixture.START_MS})",
			)
		}

		helper.runMigrationsAndValidate(
			EVENT_FRAME_CHECKPOINT_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT initial_checkpoint_ordinal, last_completed_ordinal, " +
					"required_through_ordinal FROM legacy_v27_projection_target " +
					"WHERE projection_id = 'event-tracking-frame' AND projection_version = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1L, cursor.getLong(0))
				assertEquals(0L, cursor.getLong(1))
				assertEquals(1L, cursor.getLong(2))
			}
		}
	}

	@Test
	fun eventFrameDestinationRecoveryNeverCrossesItsOriginalActivationBoundary() {
		helper.createDatabase(EVENT_FRAME_ACTIVATION_TEST_DATABASE, 27).use { database ->
			PopulatedV27Fixture.seed(database)
			database.execSQL(
				"INSERT INTO source_projection_registration " +
					"(projection_id, projection_version, activation_ordinal, retention_required, " +
					"status, created_at_ms) VALUES ('event-tracking-frame', 1, 2, 1, " +
					"'ACTIVE', ${PopulatedV27Fixture.START_MS})",
			)
		}

		helper.runMigrationsAndValidate(
			EVENT_FRAME_ACTIVATION_TEST_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use { database ->
			database.query(
				"SELECT initial_activation_ordinal, last_completed_ordinal, " +
					"required_through_ordinal FROM legacy_v27_projection_target " +
					"WHERE projection_id = 'event-tracking-frame' AND projection_version = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2L, cursor.getLong(0))
				assertEquals(1L, cursor.getLong(1))
				assertEquals(1L, cursor.getLong(2))
			}
		}
	}

	private fun assertMigrationState(database: SupportSQLiteDatabase) {
		database.query(
			"SELECT revision, collected_data_epoch, retained_from_ms, " +
				"deleted_source_event_high_water_ordinal " +
				"FROM source_evidence_state WHERE id = 1",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(41L, cursor.getLong(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(123_456L, cursor.getLong(2))
			assertEquals(0L, cursor.getLong(3))
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
			assertTrue(cursor.isNull(2))
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
			assertTrue(cursor.isNull(1))
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
		database.query(
			"SELECT end_time_ms, legacy_runtime_fenced FROM tracker_run WHERE id = 1",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertTrue(cursor.isNull(0))
			assertEquals(1L, cursor.getLong(1))
		}
		assertTableCount(database, "source_coordinator_lease", 0)
		assertTableCount(database, "source_policy", 0)
		assertTableCount(database, "source_consent_epoch", 0)
		listOf(
			"session_manifest_version",
			"session_manifest_source",
			"session_lifecycle_intent_version",
			"lifecycle_desired_action",
			"activity_automatic_start_action",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
		).forEach { table -> assertTableCount(database, table, 0) }
		database.query("PRAGMA table_info(source_demand)").use { cursor ->
			val nameColumn = cursor.getColumnIndexOrThrow("name")
			val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
			val columns = buildMap {
				while (cursor.moveToNext()) put(cursor.getString(nameColumn), cursor.getInt(notNullColumn))
			}
			assertEquals(1, columns["minimum_acquisition_spec"])
			assertEquals(1, columns["adaptive_reduction_allowed"])
			assertEquals(0, columns["requested_delivery_latency_ms"])
		}
		database.query("PRAGMA table_info(provider_registration_generation)").use { cursor ->
			val nameColumn = cursor.getColumnIndexOrThrow("name")
			val typeColumn = cursor.getColumnIndexOrThrow("type")
			val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
			val defaultValueColumn = cursor.getColumnIndexOrThrow("dflt_value")
			var residencyType: String? = null
			var residencyNotNull: Int? = null
			var residencyDefault: String? = null
			var processType: String? = null
			var processNotNull: Int? = null
			var processDefault: String? = null
			var captureBarrierType: String? = null
			var captureBarrierNotNull: Int? = null
			var captureBarrierDefault: String? = null
			while (cursor.moveToNext()) {
				when (cursor.getString(nameColumn)) {
					"provider_residency" -> {
						residencyType = cursor.getString(typeColumn)
						residencyNotNull = cursor.getInt(notNullColumn)
						residencyDefault = if (cursor.isNull(defaultValueColumn)) {
							null
						} else cursor.getString(defaultValueColumn)
					}

					"provider_process_incarnation_id" -> {
						processType = cursor.getString(typeColumn)
						processNotNull = cursor.getInt(notNullColumn)
						processDefault = if (cursor.isNull(defaultValueColumn)) {
							null
						} else cursor.getString(defaultValueColumn)
					}

					"capture_callback_barrier_authorization_revision" -> {
						captureBarrierType = cursor.getString(typeColumn)
						captureBarrierNotNull = cursor.getInt(notNullColumn)
						captureBarrierDefault = if (cursor.isNull(defaultValueColumn)) {
							null
						} else cursor.getString(defaultValueColumn)
					}
				}
			}
			assertEquals("TEXT", residencyType)
			assertEquals(1, residencyNotNull)
			assertNull(residencyDefault)
			assertEquals("TEXT", processType)
			assertEquals(0, processNotNull)
			assertNull(processDefault)
			assertEquals("INTEGER", captureBarrierType)
			assertEquals(1, captureBarrierNotNull)
			assertEquals("0", captureBarrierDefault)
		}
		assertTableCount(database, "location_sample", 1)
		assertTableCount(database, "location_observation", 1)
		assertTableCount(database, "location_projection_observation", 1)
		assertTableCount(database, "location_projection_point", 1)
		assertTableCount(database, "step_interval", 2)
		// v27 observations remain byte-for-byte facts; migration must not invent semantics.
		assertTableCount(database, "step_fact_revision", 0)
		assertTableCount(database, "source_destination_owner", 1)
		database.query(
			"SELECT owner, owner_generation FROM source_destination_owner " +
				"WHERE source_kind = 3 AND destination = 'SESSION_STEPS'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("LEGACY_STEP_INTERVAL", cursor.getString(0))
			assertEquals(1L, cursor.getLong(1))
		}
		database.query("PRAGMA table_info(step_fact_revision)").use { cursor ->
			val columns = buildMap {
				while (cursor.moveToNext()) {
					put(
						cursor.getString(cursor.getColumnIndexOrThrow("name")),
						cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) to
							cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
					)
				}
			}
			assertEquals(0 to 0, columns["step_interval_id"])
			assertEquals(0 to 0, columns["interval_start_time_ms"])
			assertEquals(0 to 0, columns["effective_step_count"])
			assertEquals(1 to 0, columns["scope_deletion_generation"])
			assertEquals(1 to 1, columns["writer_projection_id"])
			assertEquals(1 to 2, columns["writer_projection_version"])
			assertEquals(1 to 3, columns["logical_fact_id"])
			assertEquals(1 to 4, columns["semantic_revision"])
		}
		database.query("PRAGMA foreign_key_list(step_fact_revision)").use { cursor ->
			assertFalse(cursor.moveToFirst())
		}
		assertTableCount(database, "activity_snapshot", 1)
		assertTableCount(database, "wifi_observation", 1)
		assertTableCount(database, "cell_sample", 1)
		assertTableCount(database, "pressure_sample", 1)
		assertTableCount(database, "session_segment", 1)
		database.query("PRAGMA index_list(session_segment)").use { cursor ->
			val indices = buildMap {
				while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(2))
			}
			assertEquals(0, indices["idx_session_segment_logical_tracking"])
			assertEquals(1, indices["idx_session_segment_service_run"])
		}
		assertTableCount(database, "daily_summary", 1)
		assertTableCount(database, "source_registration_state", 1)
		assertTableCount(database, "source_runtime_state", 1)
		assertTableCount(database, "source_event_wal", 1)
		database.query("PRAGMA index_list(source_event_wal)").use { cursor ->
			var sourceRetentionUnique: Int? = null
			while (cursor.moveToNext()) {
				if (cursor.getString(1) == "idx_source_event_wal_source_retention") {
					sourceRetentionUnique = cursor.getInt(2)
				}
			}
			assertEquals(0, sourceRetentionUnique)
		}
		assertTableCount(database, "source_product_projection_lane", 0)
		database.query("PRAGMA table_info(source_product_projection_lane)").use { cursor ->
			val columns = buildMap {
				while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(5))
			}
			assertEquals(1, columns["source_kind"])
			assertEquals(2, columns["binding_generation"])
			assertTrue("capture_mode_mask" in columns)
			assertTrue("capture_admission_cutoff_ordinal" in columns)
			assertTrue("terminal_disposition" in columns)
			assertTrue("terminal_at_ms" in columns)
		}
		database.query("PRAGMA index_list(source_product_projection_lane)").use { cursor ->
			var writerIdentityUnique: Int? = null
			while (cursor.moveToNext()) {
				if (cursor.getString(1) == "idx_source_product_projection_lane_identity") {
					writerIdentityUnique = cursor.getInt(2)
				}
			}
			assertEquals(0, writerIdentityUnique)
		}
		assertTableCount(database, "source_projection_registration", 1)
		assertTableCount(database, "source_projection_checkpoint", 1)
		assertTableCount(database, "source_projection_outbox", 1)
		assertTableCount(database, "legacy_v27_projection_drain", 1)
		assertTableCount(database, "legacy_v27_projection_target", 4)
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
		assertNull(wal.activityAutomationEpoch)
		assertNull(wal.captureConsentEpoch)
		assertNull(wal.sessionManifestRevision)
		assertNull(wal.lifecycleLeaseGeneration)
		assertEquals(0L, wal.authorizationPurposeEligibilityMask)
		assertNull(wal.deliveryIdentity)
		assertNull(wal.deliveryUnitIndex)
		assertNull(wal.deliveryUnitCount)
		assertNull(wal.observedIntervalStartNanos)

		assertEquals(1, database.pendingSignalDao().countAll())
		assertNotNull(database.importReceiptDao().getJob(PopulatedV27Fixture.IMPORT_JOB_ID))
		assertNotNull(database.importReceiptDao().getEntry(PopulatedV27Fixture.IMPORT_JOB_ID, "entry-1"))
		val legacyRegistration = requireNotNull(
			database.sourceProjectionStateDao().registration("location-domain", 1),
		)
		assertEquals("LEGACY_V27_PENDING", legacyRegistration.status)
		assertNotNull(database.sourceProjectionStateDao().checkpoint("location-domain", 1))
		assertEquals(1, database.sourceProjectionStateDao().pendingOutbox(10).size)

		val legacyDrain = requireNotNull(database.legacyV27ProjectionDrainDao().get())
		assertEquals(1L, legacyDrain.cutoffAdmissionOrdinal)
		assertEquals(7L, legacyDrain.collectedDataEpoch)
		assertEquals(LegacyV27ProjectionDrainEntity.STATUS_PENDING, legacyDrain.status)
		assertEquals(0L, legacyDrain.leaseGeneration)
		assertNull(legacyDrain.ownerBootId)
		assertNull(legacyDrain.ownerToken)
		assertNull(legacyDrain.leaseExpiresElapsedNanos)
		assertEquals(2L, database.legacyV27ProjectionDrainDao().liveActivationOrdinal())
		assertEquals(1L, database.legacyV27ProjectionDrainDao().minimumPendingOrdinal())
		val targets = database.legacyV27ProjectionDrainDao().targets()
		assertEquals(
			setOf(
				"activity-automation",
				"event-tracking-frame",
				"explicit-tracking-joins",
				"location-domain",
			),
			targets.map { it.projectionId }.toSet(),
		)
		val locationTarget = targets.single { it.projectionId == "location-domain" }
		assertEquals(0L, locationTarget.initialCheckpointOrdinal)
		assertEquals(0L, locationTarget.lastCompletedOrdinal)
		assertEquals(1L, locationTarget.requiredThroughOrdinal)
		assertEquals("ACTIVE", locationTarget.initialRegistrationStatus)
		assertTrue(targets.filterNot { it.projectionId == "location-domain" }.all {
			it.initialRegistrationStatus == "NOT_REGISTERED_AT_MIGRATION"
		})
	}

	private suspend fun assertFailClosedAuthorityAndNoGhostRuntime(database: AppDatabase) {
		val automationEpoch = requireNotNull(database.activityAutomationEpochDao().current())
		assertEquals(1L, automationEpoch.epoch)
		assertFalse(automationEpoch.automaticControlEnabled)
		assertFalse(automationEpoch.lockSuppressed)
		assertFalse(automationEpoch.powerSaverSuppressed)
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
		assertNull(session.completedAtMs)
		assertTrue(database.sourceSessionDao().manifests(session.logicalTrackingId).isEmpty())
		assertTrue(database.sourceSessionDao().lifecycleIntents(session.logicalTrackingId).isEmpty())

		val service = requireNotNull(database.sourceSessionDao().serviceRun(PopulatedV27Fixture.SERVICE_RUN_ID))
		assertEquals("FINALIZED", service.state)
		assertEquals("AUTOMATIC_BACKGROUND_START", service.startOrigin)
		assertEquals(V28_MIGRATION_INTERRUPTION_REASON, service.completionReason)
		assertEquals(V28_MIGRATION_INTERRUPTION_REASON, service.runtimeFailureCode)
		assertNull(service.completedAtMs)
		assertTrue(database.sourceSessionDao().incompleteServiceRuns(session.logicalTrackingId).isEmpty())
		assertNull(database.trackerRunDao().getActiveRun())
		assertEquals(
			0,
			database.trackerRunDao().closeOpenRuns(PopulatedV27Fixture.END_MS + 1),
		)
		val trackerRun = database.trackerRunDao().getAllBetween(
			PopulatedV27Fixture.START_MS,
			PopulatedV27Fixture.END_MS,
		).single()
		assertNull(trackerRun.endTimeMs)
		assertTrue(trackerRun.legacyRuntimeFenced)
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
		assertEquals(0L, database.stepFactRevisionDao().countAll())
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
		assertTrue(database.sourceProjectionStateDao().activeProductLanes().isEmpty())
		assertNull(database.legacyV27ProjectionDrainDao().get())
		assertTrue(database.legacyV27ProjectionDrainDao().targets().isEmpty())
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
		assertEquals(1L, evidenceState.deletedSourceEventHighWaterOrdinal)
		assertEquals(1L, requireNotNull(database.activityAutomationEpochDao().current()).epoch)
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(3, "SESSION_STEPS"))
		assertEquals("LEGACY_STEP_INTERVAL", owner.owner)
		assertEquals(1L, owner.ownerGeneration)
	}

	private suspend fun seedMigratedStepFactRevision(database: AppDatabase) {
		val interval = database.stepIntervalDao()
			.getAllBetween(PopulatedV27Fixture.START_MS, PopulatedV27Fixture.END_MS)
			.first()
		val inserted = database.stepFactRevisionDao().insert(
			StepFactRevisionEntity(
				logicalFactId = "migration-step-fact",
				semanticRevision = 1L,
				mutationId = "migration-step-mutation",
				stepIntervalId = interval.id,
				sourceEventId = "migration-step-event",
				sourceAdmissionOrdinal = 1L,
				originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
				originIdentity = "migration-step-event",
				writerProjectionId = "steps-session-facts",
				writerProjectionVersion = 1,
				writerBindingGeneration = 1L,
				operation = StepFactRevisionEntity.OPERATION_UPSERT,
				intervalStartTimeMs = interval.startTimeMs,
				intervalEndTimeMs = interval.endTimeMs,
				intervalStartElapsedRealtimeNanos = 1_000_000_000L,
				intervalEndElapsedRealtimeNanos = 2_000_000_000L,
				clockDomainId = "boot-v27",
				bootClockDomainId = "boot-v27",
				cumulativeStepCountStart = interval.sensorValueStart.toLong(),
				cumulativeStepCountEnd = interval.sensorValueEnd.toLong(),
				wallTimeUncertaintyMs = 0L,
				coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
				effectiveStepCount = interval.stepCount.toLong(),
				logicalTrackingId = PopulatedV27Fixture.LOGICAL_TRACKING_ID,
				serviceRunId = PopulatedV27Fixture.SERVICE_RUN_ID,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				manifestRevision = 1L,
				sourcePolicyRevision = 1L,
				captureConsentEpoch = 1L,
				collectedDataEpoch = 7L,
				scopeDeletionGeneration = 0L,
				effectChecksum = "migration-step-effect",
				appliedAtMs = PopulatedV27Fixture.END_MS,
			),
		)
		assertTrue(inserted != -1L)
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
		const val UNSUPPORTED_PROJECTION_TEST_DATABASE = "migration-27-28-unsupported-projection"
		const val PRUNED_WAL_TEST_DATABASE = "migration-27-28-pruned-wal"
		const val OUTBOX_ONLY_TEST_DATABASE = "migration-27-28-outbox-only"
		const val OUTBOX_BOUNDARY_TEST_DATABASE = "migration-27-28-outbox-boundary"
		const val EVENT_FRAME_CHECKPOINT_TEST_DATABASE = "migration-27-28-event-frame-checkpoint"
		const val EVENT_FRAME_ACTIVATION_TEST_DATABASE = "migration-27-28-event-frame-activation"
		const val PRESERVED_FAILURE_TEST_DATABASE = "migration-27-28-preserved-failure"
		const val EXISTING_SESSION_FAILURE = "V27_PROVIDER_REJECTED"
		const val EXISTING_COMPLETION_REASON = "V27_RUNTIME_STOP_REQUESTED"
	}
}
