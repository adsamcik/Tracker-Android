package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevelopmentV28DatabaseContainmentTest {
	private lateinit var context: Application

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		deleteFixture()
	}

	@After
	fun tearDown() {
		deleteFixture()
	}

	@Test
	fun `missing active database routes to fresh creation`() {
		preflight() shouldBe ActiveDatabasePreflightResult.Fresh
	}

	@Test
	fun `released v27 routes to the normal migration path`() {
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)

		preflight() shouldBe ActiveDatabasePreflightResult.ReleasedV27
	}

	@Test
	fun `fresh final v28 marker is accepted on every reopen`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.FinalV28
		preflight() shouldBe ActiveDatabasePreflightResult.FinalV28
	}

	@Test
	fun `development v28 without the final marker is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final table is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalColumn = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final column is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final index is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `unknown v28 schema is not mistaken for stale Tracker development data`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeBaseline = false,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.UNRECOGNIZED_DATABASE_SCHEMA,
		)
	}

	@Test
	fun `unreadable database is contained`() {
		databaseFile().apply {
			parentFile?.mkdirs()
			writeBytes(byteArrayOf(1, 2, 3, 4))
		}

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
		)
	}

	@Test
	fun `blocked preflight never opens or changes the database file`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)
		val before = databaseFile().readBytes()
		var delegateOpened = false
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						delegateOpened = true
					}

					override fun onOpen(db: SupportSQLiteDatabase) {
						delegateOpened = true
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenBlockedException> {
			helper.writableDatabase
		}

		failure.reason shouldBe ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28
		delegateOpened shouldBe false
		databaseFile().readBytes().contentEquals(before) shouldBe true
		helper.close()
	}

	@Test
	fun `released v27 migration failure is contained without replacing the source file`() {
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = Unit

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						throw IllegalStateException("simulated migration validation failure")
					}
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenBlockedException> {
			helper.writableDatabase
		}

		failure.reason shouldBe
			ActiveDatabaseBlockReason.RELEASED_V27_MIGRATION_VALIDATION_FAILED
		helper.close()
		preflight() shouldBe ActiveDatabasePreflightResult.ReleasedV27
	}

	private fun preflight(): ActiveDatabasePreflightResult =
		ActiveDatabasePreflight(databaseFile()).inspect()

	private fun createFixture(
		version: Int,
		includeBaseline: Boolean = true,
		includeMarker: Boolean = false,
		includeFinalTable: Boolean = false,
		includeFinalColumn: Boolean = false,
		includeFinalIndex: Boolean = false,
	) {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(version) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						if (includeBaseline) {
							db.execSQL(
								"CREATE TABLE room_master_table " +
									"(id INTEGER PRIMARY KEY, identity_hash TEXT)",
							)
							db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY)")
							db.execSQL("CREATE TABLE tracker_run (id INTEGER PRIMARY KEY)")
							val finalColumn = if (includeFinalColumn) {
								", pressure_writer_owner_generation INTEGER"
							} else {
								""
							}
							db.execSQL(
								"CREATE TABLE pending_signal (id INTEGER PRIMARY KEY$finalColumn)",
							)
						}
						if (includeFinalTable) {
							db.execSQL(
								"CREATE TABLE imported_wifi_deletion_generation (" +
									"run_identity TEXT PRIMARY KEY, " +
									"deletion_scope_digest TEXT NOT NULL)",
							)
							if (includeFinalIndex) {
								db.execSQL(
									"CREATE UNIQUE INDEX idx_imported_wifi_deletion_scope " +
										"ON imported_wifi_deletion_generation(deletion_scope_digest)",
								)
							}
						}
						if (includeMarker) {
							FinalV28SchemaAssemblyRoomCallback.onCreate(db)
						}
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		helper.writableDatabase
		helper.close()
	}

	private fun databaseFile(): File = context.getDatabasePath(DATABASE_NAME)

	private fun deleteFixture() {
		context.deleteDatabase(DATABASE_NAME)
	}

	private companion object {
		const val DATABASE_NAME = "development-v28-containment-test.db"
	}
}
