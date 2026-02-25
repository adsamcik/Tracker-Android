package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.CellInfoLte
import android.telephony.CellIdentityLte
import android.telephony.CellSignalStrengthLte
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CellDataProducer")
@OptIn(ExperimentalCoroutinesApi::class)
class CellDataProducerTest {

	private lateinit var observer: TrackerDataProducerObserver
	private lateinit var producer: CellDataProducer
	private lateinit var mockContext: Context
	private lateinit var mockTelephonyManager: TelephonyManager
	private lateinit var mockSubscriptionManager: SubscriptionManager

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		observer = mockk(relaxed = true)
		producer = CellDataProducer(observer)
		mockContext = mockk(relaxed = true)
		mockTelephonyManager = mockk(relaxed = true)
		mockSubscriptionManager = mockk(relaxed = true)

		// Inject fields via reflection since onEnable requires full Android context
		setPrivateField("context", mockContext)
		setPrivateField("telephonyManager", mockTelephonyManager)
		setPrivateField("subscriptionManager", mockSubscriptionManager)
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
		Dispatchers.resetMain()
	}

	private fun setPrivateField(name: String, value: Any?) {
		val field = CellDataProducer::class.java.getDeclaredField(name)
		field.isAccessible = true
		field.set(producer, value)
	}

	private fun getPrivateField(name: String): Any? {
		val field = CellDataProducer::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.get(producer)
	}

	private fun createTempData(): MutableCollectionTempData {
		return MutableCollectionTempData(System.currentTimeMillis(), System.nanoTime())
	}

	private fun mockAirplaneMode(enabled: Boolean) {
		val mockContentResolver = mockk<android.content.ContentResolver>(relaxed = true)
		every { mockContext.contentResolver } returns mockContentResolver
		mockkStatic(Settings.Global::class)
		every {
			Settings.Global.getInt(mockContentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
		} returns if (enabled) 1 else 0
	}

	private fun mockReadPhonePermission(granted: Boolean) {
		mockkStatic(ContextCompat::class)
		every {
			ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.READ_PHONE_STATE)
		} returns if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
	}

	@Nested
	@DisplayName("onDataRequest")
	inner class OnDataRequest {

		@Test
		fun `clears cached data and returns nothing in airplane mode`() {
			mockAirplaneMode(enabled = true)

			val tempData = createTempData()
			producer.onDataRequest(tempData)

			getPrivateField("lastCellScanData").shouldBeNull()
			tempData.tryGet<Any>("CELL").shouldBeNull()
		}

		@Test
		fun `returns null when network operator is empty`() {
			mockAirplaneMode(enabled = false)
			mockReadPhonePermission(granted = false)
			every { mockTelephonyManager.networkOperator } returns ""

			val tempData = createTempData()
			producer.onDataRequest(tempData)

			tempData.tryGet<Any>("CELL").shouldBeNull()
		}

		@Test
		fun `returns null when allCellInfo is null`() {
			mockAirplaneMode(enabled = false)
			mockReadPhonePermission(granted = false)
			every { mockTelephonyManager.networkOperator } returns "310260"
			every { mockTelephonyManager.networkOperatorName } returns "T-Mobile"
			every { mockTelephonyManager.allCellInfo } returns null

			val tempData = createTempData()
			producer.onDataRequest(tempData)

			tempData.tryGet<Any>("CELL").shouldBeNull()
		}

		@Test
		fun `produces cell data when cell info is available`() {
			mockAirplaneMode(enabled = false)
			mockReadPhonePermission(granted = false)
			every { mockTelephonyManager.networkOperator } returns "310260"
			every { mockTelephonyManager.networkOperatorName } returns "T-Mobile"

			val mockCellInfoLte = mockk<CellInfoLte>(relaxed = true)
			every { mockCellInfoLte.isRegistered } returns true
			val mockCellIdentity = mockk<CellIdentityLte>(relaxed = true)
			every { mockCellIdentity.mccString } returns "310"
			every { mockCellIdentity.mncString } returns "260"
			every { mockCellInfoLte.cellIdentity } returns mockCellIdentity
			every { mockCellInfoLte.cellSignalStrength } returns mockk<CellSignalStrengthLte>(relaxed = true)

			every { mockTelephonyManager.allCellInfo } returns listOf(mockCellInfoLte)

			val tempData = createTempData()
			producer.onDataRequest(tempData)

			tempData.tryGet<Any>("CELL").shouldNotBeNull()
		}
	}

	@Nested
	@DisplayName("onDisable")
	inner class OnDisable {

		@Test
		fun `clears all state on disable`() {
			// Set canBeEnabled and isEnabled so onDisable doesn't assert
			producer.canBeEnabled = true
			val isEnabledField = producer.javaClass.superclass.getDeclaredField("isEnabled")
			isEnabledField.isAccessible = true
			isEnabledField.set(producer, true)

			producer.onDisable(mockContext)

			getPrivateField("context").shouldBeNull()
			getPrivateField("telephonyManager").shouldBeNull()
			getPrivateField("subscriptionManager").shouldBeNull()
			getPrivateField("lastCellScanData").shouldBeNull()
		}
	}

	@Nested
	@DisplayName("cache behavior")
	inner class CacheBehavior {

		@Test
		fun `uses cached data within TTL window`() {
			mockAirplaneMode(enabled = false)
			mockReadPhonePermission(granted = false)
			every { mockTelephonyManager.networkOperator } returns "310260"
			every { mockTelephonyManager.networkOperatorName } returns "T-Mobile"

			val mockCellInfoLte = mockk<CellInfoLte>(relaxed = true)
			every { mockCellInfoLte.isRegistered } returns true
			val mockCellIdentity = mockk<CellIdentityLte>(relaxed = true)
			every { mockCellIdentity.mccString } returns "310"
			every { mockCellIdentity.mncString } returns "260"
			every { mockCellInfoLte.cellIdentity } returns mockCellIdentity
			every { mockCellInfoLte.cellSignalStrength } returns mockk<CellSignalStrengthLte>(relaxed = true)

			every { mockTelephonyManager.allCellInfo } returns listOf(mockCellInfoLte)

			// First request populates cache
			val tempData1 = createTempData()
			producer.onDataRequest(tempData1)
			tempData1.tryGet<Any>("CELL").shouldNotBeNull()

			// Immediately return empty allCellInfo to prove cache is used
			every { mockTelephonyManager.allCellInfo } returns null

			// Second request should use cached data (within TTL)
			val tempData2 = createTempData()
			producer.onDataRequest(tempData2)
			tempData2.tryGet<Any>("CELL").shouldNotBeNull()
		}
	}
}
