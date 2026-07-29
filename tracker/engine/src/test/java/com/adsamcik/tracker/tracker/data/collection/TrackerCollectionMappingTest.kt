package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.data.ProcessedAltitudeData
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.AltitudeDatum
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class TrackerCollectionMappingTest {

	@Test
	fun `maps live fields while minimizing wifi and cell details`() {
		val location = testLocation()
		val source = mockCollectionData(
			time = 123L,
			location = location,
			activity = ActivityInfo(DetectedActivity.WALKING, confidence = 87),
			processedAltitude = ProcessedAltitudeData(
				altitudeM = 420f,
				datum = AltitudeDatum.ANDROID_MODEL_MSL,
			),
			wifi = WifiData(
				location = location,
				time = 456L,
				inRange = listOf(
					WifiInfo(
						bssid = "00:11:22:33:44:55",
						ssid = "Cafe",
						capabilities = "[WPA2]",
						frequency = 5_180,
						level = -61,
					),
				),
			),
			cell = CellData(
				registeredCells = listOf(
					CellInfo(
						networkOperator = NetworkOperator("230", "01", "Carrier"),
						cellId = 99L,
						type = CellType.LTE,
						asu = 10,
						dbm = -95,
						level = 3,
						areaCode = 42,
					),
				),
				totalCount = 5,
			),
		)

		source.toSnapshot() shouldBe TrackerCollectionSnapshot(
			time = 123L,
			location = location.toModel(),
			activity = TrackerActivitySnapshot(
				type = TrackerActivityType.WALKING,
				group = TrackerActivityGroup.ON_FOOT,
				confidence = 87,
			),
			androidModelMslAltitudeM = 420f,
			wifi = TrackerWifiSnapshot(
				time = 456L,
				inRange = listOf(
					TrackerWifiAccessPointSnapshot(ssid = "Cafe", level = -61),
				),
			),
			cell = TrackerCellSnapshot(
				totalCount = 5,
				registeredCells = listOf(
					TrackerCellInfoSnapshot(
						networkOperator = TrackerNetworkOperatorSnapshot(
							mcc = "230",
							mnc = "01",
							name = "Carrier",
						),
						type = TrackerCellType.LTE,
						dbm = -95,
					),
				),
			),
		)
	}

	@Test
	fun `preserves null versus empty wifi and cell observations`() {
		val absent = mockCollectionData(wifi = null, cell = null).toSnapshot()

		absent.wifi.shouldBeNull()
		absent.cell.shouldBeNull()

		val collectedButEmpty = mockCollectionData(
			wifi = WifiData(location = null, time = 10L, inRange = emptyList()),
			cell = CellData(registeredCells = emptyList(), totalCount = 0),
		).toSnapshot()

		collectedButEmpty.wifi.shouldNotBeNull().inRange.shouldBeEmpty()
		collectedButEmpty.cell.shouldNotBeNull().registeredCells.shouldBeEmpty()
	}

	@Test
	fun `publishes only finite Android model MSL altitude`() {
		mockCollectionData(
			processedAltitude = ProcessedAltitudeData(
				altitudeM = Float.NaN,
				datum = AltitudeDatum.ANDROID_MODEL_MSL,
			),
		).toSnapshot().androidModelMslAltitudeM.shouldBeNull()

		mockCollectionData(
			processedAltitude = ProcessedAltitudeData(
				altitudeM = 420f,
				datum = AltitudeDatum.WGS84_ELLIPSOID,
			),
		).toSnapshot().androidModelMslAltitudeM.shouldBeNull()
	}

	@Test
	fun `maps unrecognized activity values to unknown without throwing`() {
		val unknownActivity = mockk<ActivityInfo> {
			every { activityType } returns Int.MAX_VALUE
			every { confidence } returns 12
		}

		mockCollectionData(activity = unknownActivity).toSnapshot().activity shouldBe
			TrackerActivitySnapshot(
				type = TrackerActivityType.UNKNOWN,
				group = TrackerActivityGroup.UNKNOWN,
				confidence = 12,
			)
	}

	private fun mockCollectionData(
		time: Long = 0L,
		location: Location? = null,
		activity: ActivityInfo? = null,
		processedAltitude: ProcessedAltitudeData? = null,
		wifi: WifiData? = null,
		cell: CellData? = null,
	): CollectionData {
		val data = mockk<CollectionData>()
		every { data.time } returns time
		every { data.location } returns location
		every { data.activity } returns activity
		every { data.processedAltitude } returns processedAltitude
		every { data.wifi } returns wifi
		every { data.cell } returns cell
		return data
	}

	private fun testLocation() = Location(
		time = 100L,
		latitude = 50.0,
		longitude = 14.0,
		altitude = 430.0,
		horizontalAccuracy = 4f,
		verticalAccuracy = 8f,
		speed = 1.5f,
		speedAccuracy = 0.5f,
	)
}
