package com.adsamcik.tracker.points

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class PointsSessionReceiverTest {

	private lateinit var context: Application
	private val receiver = PointsSessionReceiver()

	@BeforeEach
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		val config = Configuration.Builder()
			.setMinimumLoggingLevel(android.util.Log.DEBUG)
			.build()
		WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `ignores intent with unrelated action`() {
		val intent = mockk<Intent>(relaxed = true)
		every { intent.action } returns "com.example.UNRELATED_ACTION"

		receiver.onReceive(context, intent)

		val workInfos = WorkManager.getInstance(context)
			.getWorkInfosByTag(PointsSessionReceiver.WORK_TAG)
			.get()
		workInfos.shouldBeEmpty()
	}

	@Test
	fun `ignores intent with null action`() {
		val intent = mockk<Intent>(relaxed = true)
		every { intent.action } returns null

		receiver.onReceive(context, intent)

		val workInfos = WorkManager.getInstance(context)
			.getWorkInfosByTag(PointsSessionReceiver.WORK_TAG)
			.get()
		workInfos.shouldBeEmpty()
	}

	@Test
	fun `session final with negative id does not enqueue work`() {
		val intent = mockk<Intent>(relaxed = true)
		every { intent.action } returns TrackerSession.ACTION_SESSION_FINAL
		every { intent.getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) } returns -1L
		every { intent.hasExtra(TrackerSession.RECEIVER_SESSION_ID) } returns false

		receiver.onReceive(context, intent)

		val workInfos = WorkManager.getInstance(context)
			.getWorkInfosByTag(PointsSessionReceiver.WORK_TAG)
			.get()
		workInfos.shouldBeEmpty()
	}

	@Test
	fun `session final with valid id enqueues work`() {
		val intent = mockk<Intent>(relaxed = true)
		every { intent.action } returns TrackerSession.ACTION_SESSION_FINAL
		every { intent.getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) } returns 42L

		receiver.onReceive(context, intent)

		val workInfos = WorkManager.getInstance(context)
			.getWorkInfosByTag(PointsSessionReceiver.WORK_TAG)
			.get()
		workInfos shouldHaveSize 1
		workInfos.first().state shouldBe WorkInfo.State.ENQUEUED
	}

	@Test
	fun `WORK_TAG is SessionPoints`() {
		PointsSessionReceiver.WORK_TAG shouldBe "SessionPoints"
	}

	@Test
	fun `multiple session finals enqueue multiple work items`() {
		repeat(3) { i ->
			val intent = mockk<Intent>(relaxed = true)
			every { intent.action } returns TrackerSession.ACTION_SESSION_FINAL
			every { intent.getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) } returns (i + 1).toLong()
			receiver.onReceive(context, intent)
		}

		val workInfos = WorkManager.getInstance(context)
			.getWorkInfosByTag(PointsSessionReceiver.WORK_TAG)
			.get()
		workInfos shouldHaveSize 3
	}
}
