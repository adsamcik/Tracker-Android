package com.adsamcik.tracker.shared.base.database.migration

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationBackupStoreTest {
	private lateinit var context: Application
	private lateinit var backupDirectory: File

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
		backupDirectory = File(
			checkNotNull(context.getDatabasePath(DATABASE_NAME).parentFile),
			"migration-backup-test",
		).apply {
			deleteRecursively()
		}
	}

	@After
	fun tearDown() {
		context.deleteDatabase(DATABASE_NAME)
		backupDirectory.deleteRecursively()
	}

	@Test
	fun `full backup precedes upgrade`() {
		copyReleaseDatabase()
		val store = createStore()
		var upgradeObservedBackup = false
		val helper = MigrationBackupOpenHelperFactory(
			delegate = FrameworkSQLiteOpenHelperFactory(),
			backupStore = store,
			databaseName = DATABASE_NAME,
			targetVersion = TARGET_VERSION,
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(TARGET_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = error("Expected release fixture")

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						oldVersion shouldBe RELEASE_VERSION
						newVersion shouldBe TARGET_VERSION
						val backup = store.latestBackup()
						backup?.sourceVersion shouldBe RELEASE_VERSION
						backup?.targetVersion shouldBe TARGET_VERSION
						openDatabase(checkNotNull(backup).file).use { source ->
							source.version shouldBe RELEASE_VERSION
							source.count("location_data") shouldBe 6
							source.count("tracker_session") shouldBe 37
							source.count("wifi_data") shouldBe 2
							source.count("cell_location") shouldBe 3
							source.rawQuery(
								"SELECT lat, lon, alt, activity, confidence FROM location_data WHERE id = 1",
								null,
							).use { cursor ->
								cursor.moveToFirst() shouldBe true
								cursor.getDouble(0) shouldBe 48.1234567
								cursor.getDouble(1) shouldBe 17.9876543
								cursor.getDouble(2) shouldBe 200.5
								cursor.getInt(3) shouldBe 0
								cursor.getInt(4) shouldBe 80
							}
						}
						upgradeObservedBackup = true
					}
				})
				.build(),
		)

		helper.writableDatabase.close()
		upgradeObservedBackup shouldBe true
	}

	@Test
	fun `readable open is guarded`() {
		copyReleaseDatabase()
		val store = createStore()
		var upgradeObservedBackup = false
		val helper = migrationHelper(store) { _, _, _ ->
			upgradeObservedBackup = store.latestBackup() != null
		}

		helper.readableDatabase.close()

		upgradeObservedBackup shouldBe true
	}

	@Test
	fun `valid backup is reused`() {
		copyReleaseDatabase()
		val store = createStore()

		val first = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		val originalBytes = first.file.readBytes()
		originalBytes.contentEquals(context.getDatabasePath(DATABASE_NAME).readBytes()) shouldBe true

		val second = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))

		second.file shouldBe first.file
		second.file.readBytes().contentEquals(originalBytes) shouldBe true
		second.file.lastModified() shouldBe first.file.lastModified()
	}

	@Test
	fun `reuse fsync failure`() {
		copyReleaseDatabase()
		val first = checkNotNull(createStore().createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		val failingStore = DatabaseMigrationBackupStore(
			context = context,
			backupDirectory = backupDirectory,
			directorySync = { throw java.io.IOException("fsync failed") },
			scheduleExpiry = {},
			cancelExpiry = {},
		)

		shouldThrow<DatabaseMigrationBackupException> {
			failingStore.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION)
		}

		first.file.exists() shouldBe true
	}

	@Test
	fun `failure prevents upgrade`() {
		copyReleaseDatabase()
		val blockedPath = File(context.cacheDir, "blocked-backup-path").apply {
			deleteRecursively()
			writeText("not a directory")
		}
		val store = createStore(blockedPath)
		var upgradeStarted = false
		val helper = MigrationBackupOpenHelperFactory(
			delegate = FrameworkSQLiteOpenHelperFactory(),
			backupStore = store,
			databaseName = DATABASE_NAME,
			targetVersion = TARGET_VERSION,
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(TARGET_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = error("Expected release fixture")

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						upgradeStarted = true
					}
				})
				.build(),
		)

		shouldThrow<DatabaseMigrationBackupException> {
			helper.writableDatabase
		}
		upgradeStarted shouldBe false
		openDatabase(context.getDatabasePath(DATABASE_NAME)).use {
			it.version shouldBe RELEASE_VERSION
		}
		blockedPath.delete()
	}

	@Test
	fun `backup exports exact bytes`() {
		copyReleaseDatabase()
		val store = createStore()
		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		val output = ByteArrayOutputStream()

		val exported = store.exportLatest(output)

		exported shouldBe backup
		output.toByteArray().contentEquals(backup.file.readBytes()) shouldBe true
	}

	@Test
	fun `wal rows are backed up`() {
		copyReleaseDatabase()
		val source = context.getDatabasePath(DATABASE_NAME)
		val walSource = File(context.cacheDir, "migration-wal-source.db").apply {
			delete()
		}
		source.copyTo(walSource, overwrite = true)
		val walDatabase = SQLiteDatabase.openDatabase(
			walSource.path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		)
		try {
			walDatabase.enableWriteAheadLogging() shouldBe true
			walDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close()
			val checkpointedMain = walSource.readBytes()
			walDatabase.execSQL("UPDATE location_data SET confidence = 77 WHERE id = 1")
			walSource.readBytes().contentEquals(checkpointedMain) shouldBe true
			(File("${walSource.path}-wal").length() > 0) shouldBe true

			walSource.copyTo(source, overwrite = true)
			File("${walSource.path}-wal").copyTo(
				File("${source.path}-wal"),
				overwrite = true,
			)
			File("${walSource.path}-shm").copyTo(
				File("${source.path}-shm"),
				overwrite = true,
			)
		} finally {
			walDatabase.close()
			walSource.delete()
			File("${walSource.path}-wal").delete()
			File("${walSource.path}-shm").delete()
		}

		val backup = checkNotNull(createStore().createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))

		openDatabase(backup.file).use { database ->
			database.rawQuery(
				"SELECT confidence FROM location_data WHERE id = 1",
				null,
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getInt(0) shouldBe 77
			}
		}
	}

	@Test
	fun `delete removes private backup`() {
		copyReleaseDatabase()
		val store = createStore()
		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))

		store.deleteAll()

		backup.file.exists() shouldBe false
		store.latestBackup() shouldBe null
	}

	@Test
	fun `expiry callbacks`() {
		copyReleaseDatabase()
		var scheduledBackup: DatabaseMigrationBackup? = null
		var cancellationCount = 0
		val store = DatabaseMigrationBackupStore(
			context = context,
			backupDirectory = backupDirectory,
			directorySync = {},
			scheduleExpiry = { scheduledBackup = it },
			cancelExpiry = { cancellationCount++ },
		)

		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		scheduledBackup shouldBe backup

		store.deleteAll()

		cancellationCount shouldBe 1
	}

	@Test
	fun `backup flow updates`(): Unit = runBlocking {
		copyReleaseDatabase()
		val store = createStore()
		val emissions = mutableListOf<DatabaseMigrationBackup?>()
		val collection = launch(start = CoroutineStart.UNDISPATCHED) {
			store.backups.take(3).toList(emissions)
		}

		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		yield()
		store.deleteAll()
		withTimeout(5_000) {
			collection.join()
		}

		emissions shouldBe listOf(null, backup, null)
	}

	@Test
	fun `retry reuses backup`() {
		copyReleaseDatabase()
		val store = createStore()
		val firstHelper = migrationHelper(store) { _, _, _ ->
			error("simulated migration failure")
		}

		shouldThrow<IllegalStateException> {
			firstHelper.writableDatabase
		}
		firstHelper.close()
		val firstBackup = checkNotNull(store.latestBackup())
		val firstBytes = firstBackup.file.readBytes()
		var retryStarted = false
		val retryHelper = migrationHelper(store) { _, oldVersion, newVersion ->
			oldVersion shouldBe RELEASE_VERSION
			newVersion shouldBe TARGET_VERSION
			retryStarted = true
		}

		retryHelper.writableDatabase.close()

		retryStarted shouldBe true
		val retryBackup = checkNotNull(store.latestBackup())
		retryBackup.file.readBytes().contentEquals(firstBytes) shouldBe true
	}

	@Test
	fun `pending delete recovers`() {
		copyReleaseDatabase()
		val store = createStore()
		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		store.markDeletionPending()

		val recoveredStore = createStore()

		recoveredStore.latestBackup() shouldBe null
		backup.file.exists() shouldBe false
	}

	@Test
	fun `expired backup is removed`() {
		copyReleaseDatabase()
		var now = 1_700_000_000_000L
		val store = DatabaseMigrationBackupStore(
			context = context,
			backupDirectory = backupDirectory,
			nowMillis = { now },
			directorySync = {},
			scheduleExpiry = {},
			cancelExpiry = {},
		)
		val backup = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))

		now += 31L * 24 * 60 * 60 * 1000

		store.latestBackup() shouldBe null
		backup.file.exists() shouldBe false
	}

	@Test
	fun `corrupt newest is ignored`() {
		copyReleaseDatabase()
		val store = createStore()
		val valid = checkNotNull(store.createBackupIfNeeded(DATABASE_NAME, TARGET_VERSION))
		val corrupt = File(
			backupDirectory,
			"main_database-v10-pre-v35-0000000000000000.db",
		).apply {
			writeText("corrupt")
			setLastModified(valid.createdAtMs + 1)
		}
		store.latestBackup()?.file shouldBe valid.file
		store.latestBackup()?.file shouldBe valid.file
	}

	private fun copyReleaseDatabase() {
		val target = context.getDatabasePath(DATABASE_NAME)
		target.parentFile?.mkdirs()
		checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)).use { input ->
			target.outputStream().use(input::copyTo)
		}
	}

	private fun createStore(directory: File = backupDirectory) =
		DatabaseMigrationBackupStore(
			context = context,
			backupDirectory = directory,
			directorySync = {},
			scheduleExpiry = {},
			cancelExpiry = {},
		)

	private fun migrationHelper(
		store: DatabaseMigrationBackupStore,
		onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit,
	): SupportSQLiteOpenHelper = MigrationBackupOpenHelperFactory(
		delegate = FrameworkSQLiteOpenHelperFactory(),
		backupStore = store,
		databaseName = DATABASE_NAME,
		targetVersion = TARGET_VERSION,
	).create(
		SupportSQLiteOpenHelper.Configuration.builder(context)
			.name(DATABASE_NAME)
			.callback(object : SupportSQLiteOpenHelper.Callback(TARGET_VERSION) {
				override fun onCreate(db: SupportSQLiteDatabase) = error("Expected release fixture")

				override fun onUpgrade(
					db: SupportSQLiteDatabase,
					oldVersion: Int,
					newVersion: Int,
				) = onUpgrade(db, oldVersion, newVersion)
			})
			.build(),
	)

	private fun openDatabase(file: File): SQLiteDatabase =
		SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

	private fun SQLiteDatabase.count(table: String): Int =
		rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
			cursor.moveToFirst()
			cursor.getInt(0)
		}

	private companion object {
		const val DATABASE_NAME = "main_database"
		const val RELEASE_VERSION = 10
		const val TARGET_VERSION = 35
		const val FIXTURE = "baseline/2024.1/main_database.db"
	}
}
