package com.adsamcik.tracker.points.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release2024_1PointsDatabaseTest {
	private lateinit var context: Application
	private var database: PointsDatabase? = null

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(TEST_DATABASE)
		val target = context.getDatabasePath(TEST_DATABASE)
		target.parentFile?.mkdirs()
		checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
			"Missing release fixture: $FIXTURE"
		}.use { input ->
			target.outputStream().use(input::copyTo)
		}
	}

	@After
	fun tearDown() {
		database?.close()
		context.deleteDatabase(TEST_DATABASE)
	}

	@Test
	fun `2024_1 points database opens through current Room without schema drift`() {
		val opened = Room.databaseBuilder(context, PointsDatabase::class.java, TEST_DATABASE)
			.allowMainThreadQueries()
			.build()
		database = opened
		val raw = opened.openHelper.writableDatabase

		raw.version shouldBe 1
		raw.query("SELECT id, time, value, source FROM points_awarded ORDER BY id").use { cursor ->
			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 1L
			cursor.getDouble(2) shouldBe 1.25
			cursor.getString(3) shouldBe "walk"
			cursor.moveToNext() shouldBe true
			cursor.getDouble(2) shouldBe -5.5
			cursor.getString(3) shouldBe "correction"
			cursor.moveToNext() shouldBe true
			cursor.getLong(1) shouldBe 9_223_372_036_854_000_000L
			cursor.getDouble(2) shouldBe Double.MAX_VALUE
			cursor.getString(3) shouldBe "boundary"
			cursor.moveToNext() shouldBe false
		}
	}

	private companion object {
		const val TEST_DATABASE = "release_2024_1_points.db"
		const val FIXTURE = "baseline/2024.1/points_database.db"
	}
}
