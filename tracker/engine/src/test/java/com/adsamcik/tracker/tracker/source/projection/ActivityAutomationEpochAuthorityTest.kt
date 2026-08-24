package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.tracker.controller.LockManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAutomationEpochAuthorityTest {
	private lateinit var application: Application
	private lateinit var database: AppDatabase
	private lateinit var lockManager: LockManager
	private lateinit var clock: FixedClock
	private var locked = false

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		val lockState = MutableStateFlow(false)
		lockManager = mockk(relaxed = true)
		clock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 100L)
		every { lockManager.isLocked } answers { locked }
		every { lockManager.isLockedFlow } returns lockState
		shadowOf(application.getSystemService(PowerManager::class.java))
			.setIsPowerSaveMode(false)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `lock and power entry and exit rotate durably across authority recreation`() = runTest {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = 17,
				automaticControlEnabled = true,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 0L,
				lastRotationReason = "TEST_SEED",
			),
		)
		var authority = activityAutomationEpochAuthority()

		authority.currentForValidation().epoch shouldBe 17L
		clock.advance(1L)
		locked = true
		authority.currentForValidation().let {
			Triple(it.epoch, it.lockSuppressed, it.effectiveElapsedRealtimeNanos)
		} shouldBe Triple(18L, true, 1_000_100L)
		authority.currentForValidation().epoch shouldBe 18L
		locked = false
		authority.currentForValidation().let { it.epoch to it.lockSuppressed } shouldBe
			(19L to false)

		val powerManager = application.getSystemService(PowerManager::class.java)
		shadowOf(powerManager).setIsPowerSaveMode(true)
		authority.currentForValidation().let { it.epoch to it.powerSaverSuppressed } shouldBe
			(20L to true)
		shadowOf(powerManager).setIsPowerSaveMode(false)
		authority.currentForValidation().let { it.epoch to it.powerSaverSuppressed } shouldBe
			(21L to false)

		authority = activityAutomationEpochAuthority()
		authority.currentForValidation().let {
			Triple(it.epoch, it.automaticControlEnabled, it.lastRotationReason)
		} shouldBe Triple(
			21L,
			true,
			ActivityAutomationEpochAuthority.RUNTIME_VALIDATION_BOUNDARY,
		)
	}

	@Test
	fun `a new boot clock domain rotates even when runtime suppression is unchanged`() = runTest {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = 17L,
				automaticControlEnabled = true,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 50L,
				lastRotationReason = "TEST_SEED",
			),
		)

		val current = activityAutomationEpochAuthority("boot-2").currentForValidation()

		current.epoch shouldBe 18L
		current.bootClockDomainId shouldBe "boot-2"
		current.effectiveElapsedRealtimeNanos shouldBe clock.elapsedRealtimeNanos()
	}

	private fun activityAutomationEpochAuthority(bootId: String = BOOT_ID) =
		ActivityAutomationEpochAuthority(
		database,
		application,
		lockManager,
		clock,
		BootClockDomainProvider { bootId },
	)

	private companion object {
		const val BOOT_ID = "boot-1"
	}

}
