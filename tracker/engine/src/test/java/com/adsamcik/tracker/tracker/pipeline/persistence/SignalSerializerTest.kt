package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("SignalSerializer")
class SignalSerializerTest {

	// region Test signal factories

	private fun createFullSignal() = TrackingSignal(
		timestampMs = EpochMs(1_700_000_000_000L),
		elapsedRealtimeNanos = 123_456_789L,
		location = LocationSignal(
			coordinate = CoordinateE7(lat = LatE7(488_566_000), lon = LonE7(23_522_000)),
			horizontalAccuracyM = 5.0f,
			speed = SpeedMps(2.5f),
			altitudeM = 250.0f,
			rawGpsAltitudeM = 248.0f,
			verticalAccuracyM = 3.0f,
			speedAccuracyMps = 0.5f,
			provider = "gps",
		),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(85),
		),
		steps = StepSignal(
			stepDelta = StepCount(42),
			totalStepsSinceBoot = 10_000L,
			sensorValueStart = 9_958,
			sensorValueEnd = 10_000,
			sensorReset = false,
		),
		cells = CellSignal(
			towers = listOf(
				CellTowerReading(
					cellId = 12345678L,
					mcc = "230",
					mnc = "01",
					networkType = 13,
					signalStrength = -85,
				),
				CellTowerReading(
					cellId = 87654321L,
					mcc = "230",
					mnc = "02",
					networkType = 13,
					signalStrength = -95,
				),
			),
		),
		wifi = WifiSignal(
			networks = listOf(
				WifiNetworkReading(
					bssid = "aa:bb:cc:dd:ee:ff",
					ssid = "MyNetwork",
					capabilities = "[WPA2-PSK-CCMP][ESS]",
					frequency = 2437,
					level = -65,
				),
				WifiNetworkReading(
					bssid = "11:22:33:44:55:66",
					ssid = "CafeWiFi",
					capabilities = "[WPA2-PSK-CCMP+TKIP][ESS]",
					frequency = 5180,
					level = -72,
				),
			),
		),
		pressure = PressureSignal(
			pressureHpa = 1013.25f,
			altitudeM = 250.0f,
		),
		policy = PolicySignal(
			tier = PolicyTier.ACTIVE,
			policyName = "outdoor-walking",
		),
	)

	private fun createMinimalSignal() = TrackingSignal(
		timestampMs = EpochMs(1_700_000_000_000L),
	)

	private fun createLocationOnlySignal() = TrackingSignal(
		timestampMs = EpochMs(1_700_000_000_000L),
		elapsedRealtimeNanos = 999L,
		location = LocationSignal(
			coordinate = CoordinateE7(lat = LatE7(500_000_000), lon = LonE7(140_000_000)),
			horizontalAccuracyM = 10.0f,
			speed = null,
			provider = "fused",
		),
	)

	private fun createActivityOnlySignal() = TrackingSignal(
		timestampMs = EpochMs(1_700_000_000_000L),
		activity = ActivitySignal(
			type = DetectedActivityType.RUNNING,
			confidence = ActivityConfidence(92),
		),
	)

	// endregion

	@Nested
	@DisplayName("Round-trip serialization")
	inner class RoundTrip {

		@Test
		@DisplayName("full signal with all fields populated")
		fun fullSignal() {
			val original = createFullSignal()
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.timestampMs shouldBe original.timestampMs
			restored.elapsedRealtimeNanos shouldBe original.elapsedRealtimeNanos

			// Location
			val origLoc = original.location!!
			val resLoc = restored.location.shouldNotBeNull()
			resLoc.coordinate.lat shouldBe origLoc.coordinate.lat
			resLoc.coordinate.lon shouldBe origLoc.coordinate.lon
			resLoc.horizontalAccuracyM shouldBe origLoc.horizontalAccuracyM
			resLoc.speed shouldBe origLoc.speed
			resLoc.altitudeM shouldBe origLoc.altitudeM
			resLoc.rawGpsAltitudeM shouldBe origLoc.rawGpsAltitudeM
			resLoc.verticalAccuracyM shouldBe origLoc.verticalAccuracyM
			resLoc.speedAccuracyMps shouldBe origLoc.speedAccuracyMps
			resLoc.provider shouldBe origLoc.provider

			// Activity
			val origAct = original.activity!!
			val resAct = restored.activity.shouldNotBeNull()
			resAct.type shouldBe origAct.type
			resAct.confidence shouldBe origAct.confidence

			// Steps
			val origStp = original.steps!!
			val resStp = restored.steps.shouldNotBeNull()
			resStp.stepDelta shouldBe origStp.stepDelta
			resStp.totalStepsSinceBoot shouldBe origStp.totalStepsSinceBoot
			resStp.sensorValueStart shouldBe origStp.sensorValueStart
			resStp.sensorValueEnd shouldBe origStp.sensorValueEnd
			resStp.sensorReset shouldBe origStp.sensorReset

			// Cells
			val origCells = original.cells!!
			val resCells = restored.cells.shouldNotBeNull()
			resCells.towers.size shouldBe origCells.towers.size
			resCells.towers.forEachIndexed { i, tower ->
				tower.cellId shouldBe origCells.towers[i].cellId
				tower.mcc shouldBe origCells.towers[i].mcc
				tower.mnc shouldBe origCells.towers[i].mnc
				tower.networkType shouldBe origCells.towers[i].networkType
				tower.signalStrength shouldBe origCells.towers[i].signalStrength
			}

			// WiFi
			val origWifi = original.wifi!!
			val resWifi = restored.wifi.shouldNotBeNull()
			resWifi.networks.size shouldBe origWifi.networks.size
			resWifi.networks.forEachIndexed { i, net ->
				net.bssid shouldBe origWifi.networks[i].bssid
				net.ssid shouldBe origWifi.networks[i].ssid
				net.capabilities shouldBe origWifi.networks[i].capabilities
				net.frequency shouldBe origWifi.networks[i].frequency
				net.level shouldBe origWifi.networks[i].level
			}

			// Pressure
			val origPrs = original.pressure!!
			val resPrs = restored.pressure.shouldNotBeNull()
			resPrs.pressureHpa shouldBe origPrs.pressureHpa
			resPrs.altitudeM shouldBe origPrs.altitudeM

			// Policy
			val origPol = original.policy!!
			val resPol = restored.policy.shouldNotBeNull()
			resPol.tier shouldBe origPol.tier
			resPol.policyName shouldBe origPol.policyName
		}

		@Test
		@DisplayName("minimal signal with only timestamp")
		fun minimalSignal() {
			val original = createMinimalSignal()
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.timestampMs shouldBe original.timestampMs
			restored.elapsedRealtimeNanos shouldBe 0L
			restored.location.shouldBeNull()
			restored.activity.shouldBeNull()
			restored.steps.shouldBeNull()
			restored.cells.shouldBeNull()
			restored.wifi.shouldBeNull()
			restored.pressure.shouldBeNull()
			restored.policy.shouldBeNull()
		}

		@Test
		@DisplayName("location-only signal")
		fun locationOnly() {
			val original = createLocationOnlySignal()
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.timestampMs shouldBe original.timestampMs
			restored.location.shouldNotBeNull()
			restored.location!!.coordinate shouldBe original.location!!.coordinate
			restored.location!!.horizontalAccuracyM shouldBe original.location!!.horizontalAccuracyM
			restored.location!!.speed.shouldBeNull()
			restored.location!!.altitudeM.shouldBeNull()
			restored.location!!.provider shouldBe "fused"
			restored.activity.shouldBeNull()
			restored.steps.shouldBeNull()
			restored.cells.shouldBeNull()
			restored.wifi.shouldBeNull()
			restored.pressure.shouldBeNull()
			restored.policy.shouldBeNull()
		}

		@Test
		@DisplayName("activity-only signal")
		fun activityOnly() {
			val original = createActivityOnlySignal()
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.timestampMs shouldBe original.timestampMs
			restored.activity.shouldNotBeNull()
			restored.activity!!.type shouldBe DetectedActivityType.RUNNING
			restored.activity!!.confidence shouldBe ActivityConfidence(92)
			restored.location.shouldBeNull()
			restored.steps.shouldBeNull()
		}

		@Test
		@DisplayName("policy signal without optional policyName")
		fun policyWithoutName() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				policy = PolicySignal(tier = PolicyTier.PRECISION, policyName = null),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.policy.shouldNotBeNull()
			restored.policy!!.tier shouldBe PolicyTier.PRECISION
			restored.policy!!.policyName.shouldBeNull()
		}

		@Test
		@DisplayName("steps signal with sensorReset = true")
		fun stepsWithReset() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				steps = StepSignal(
					stepDelta = StepCount(0),
					totalStepsSinceBoot = 0L,
					sensorValueStart = 65535,
					sensorValueEnd = 0,
					sensorReset = true,
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			val s = restored.steps.shouldNotBeNull()
			s.sensorReset shouldBe true
			s.sensorValueStart shouldBe 65535
			s.sensorValueEnd shouldBe 0
		}
	}

	@Nested
	@DisplayName("Special characters")
	inner class SpecialCharacters {

		@Test
		@DisplayName("WiFi SSID with quotes, backslashes, and newlines")
		fun wifiSsidSpecialChars() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(
							bssid = "aa:bb:cc:dd:ee:ff",
							ssid = "Café \"Free\\WiFi\"\nGuest",
							capabilities = "[WPA2]",
							frequency = 2437,
							level = -60,
						),
					),
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			val net = restored.wifi.shouldNotBeNull().networks.first()
			net.ssid shouldBe original.wifi!!.networks.first().ssid
		}

		@Test
		@DisplayName("cell MCC/MNC with special characters")
		fun cellSpecialChars() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(
							cellId = 1L,
							mcc = "230",
							mnc = "0\\1",
							networkType = 13,
							signalStrength = -80,
						),
					),
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			val tower = restored.cells.shouldNotBeNull().towers.first()
			tower.mnc shouldBe "0\\1"
		}

		@Test
		@DisplayName("location provider with special characters")
		fun providerSpecialChars() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				location = LocationSignal(
					coordinate = CoordinateE7(lat = LatE7(0), lon = LonE7(0)),
					horizontalAccuracyM = 1.0f,
					speed = null,
					provider = "test\"provider",
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.location.shouldNotBeNull().provider shouldBe "test\"provider"
		}

		@Test
		@DisplayName("policy name with tabs and backslashes")
		fun policyNameSpecialChars() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				policy = PolicySignal(
					tier = PolicyTier.ACTIVE,
					policyName = "outdoor\\walk\ting",
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.policy.shouldNotBeNull().policyName shouldBe "outdoor\\walk\ting"
		}
	}

	@Nested
	@DisplayName("Deserialization error handling")
	inner class ErrorHandling {

		@Test
		@DisplayName("corrupted JSON returns null")
		fun corruptedJson() {
			SignalSerializer.deserialize("{not valid json!!!}").shouldBeNull()
		}

		@Test
		@DisplayName("empty string returns null")
		fun emptyString() {
			SignalSerializer.deserialize("").shouldBeNull()
		}

		@Test
		@DisplayName("completely invalid input returns null")
		fun randomGarbage() {
			SignalSerializer.deserialize("abc123!@#").shouldBeNull()
		}

		@Test
		@DisplayName("missing required timestamp field returns null")
		fun missingTimestamp() {
			SignalSerializer.deserialize("""{"ern":123}""").shouldBeNull()
		}

		@Test
		@DisplayName("partial JSON with only timestamp gets defaults for missing fields")
		fun partialJson() {
			val json = """{"ts":1700000000000}"""
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.timestampMs shouldBe EpochMs(1_700_000_000_000L)
			restored.elapsedRealtimeNanos shouldBe 0L
			restored.location.shouldBeNull()
			restored.activity.shouldBeNull()
			restored.steps.shouldBeNull()
			restored.cells.shouldBeNull()
			restored.wifi.shouldBeNull()
			restored.pressure.shouldBeNull()
			restored.policy.shouldBeNull()
		}

		@Test
		@DisplayName("invalid activity ordinal falls back to UNKNOWN")
		fun invalidActivityOrdinal() {
			val json = """{"ts":1700000000000,"act":{"t":999,"c":50}}"""
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.activity.shouldNotBeNull()
			restored.activity!!.type shouldBe DetectedActivityType.UNKNOWN
			restored.activity!!.confidence shouldBe ActivityConfidence(50)
		}

		@Test
		@DisplayName("invalid policy tier ordinal falls back to OFF")
		fun invalidPolicyTierOrdinal() {
			val json = """{"ts":1700000000000,"pol":{"tier":999}}"""
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.policy.shouldNotBeNull()
			restored.policy!!.tier shouldBe PolicyTier.OFF
			restored.policy!!.policyName.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("E7 coordinate precision")
	inner class CoordinatePrecision {

		@Test
		@DisplayName("E7 integer coordinates round-trip exactly")
		fun e7IntegersExact() {
			val latValues = listOf(
				LatE7(488_566_000),   // Prague ~48.8566
				LatE7(0),             // Equator
				LatE7(-338_686_100),  // Sydney ~-33.8686
				LatE7(900_000_000),   // Max latitude
				LatE7(-900_000_000),  // Min latitude
			)
			val lonValues = listOf(
				LonE7(23_522_000),     // Prague ~2.3522
				LonE7(0),              // Prime meridian
				LonE7(-1_800_000_000), // Antimeridian west
				LonE7(1_800_000_000),  // Antimeridian east
				LonE7(1_511_980_000),  // Sydney ~151.198
			)

			latValues.forEach { lat ->
				lonValues.forEach { lon ->
					val original = TrackingSignal(
						timestampMs = EpochMs(1_700_000_000_000L),
						location = LocationSignal(
							coordinate = CoordinateE7(lat = lat, lon = lon),
							horizontalAccuracyM = 1.0f,
							speed = null,
						),
					)
					val json = SignalSerializer.serialize(original)
					val restored = SignalSerializer.deserialize(json)

					restored.shouldNotBeNull()
					val resLoc = restored.location.shouldNotBeNull()
					resLoc.coordinate.lat.raw shouldBe lat.raw
					resLoc.coordinate.lon.raw shouldBe lon.raw
				}
			}
		}

		@Test
		@DisplayName("E7 coordinate values not corrupted by floating point")
		fun noFloatingPointCorruption() {
			// Use values that would be lossy if converted through Double
			val lat = LatE7(488_566_123)
			val lon = LonE7(23_522_456)
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				location = LocationSignal(
					coordinate = CoordinateE7(lat = lat, lon = lon),
					horizontalAccuracyM = 1.0f,
					speed = null,
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			val resLoc = restored.location.shouldNotBeNull()
			// The raw int must come back bit-identical
			resLoc.coordinate.lat.raw shouldBe 488_566_123
			resLoc.coordinate.lon.raw shouldBe 23_522_456
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {

		@Test
		@DisplayName("empty cell towers list round-trips")
		fun emptyCellTowers() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				cells = CellSignal(towers = emptyList()),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.cells.shouldNotBeNull().towers.size shouldBe 0
		}

		@Test
		@DisplayName("empty wifi networks list round-trips")
		fun emptyWifiNetworks() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				wifi = WifiSignal(networks = emptyList()),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.wifi.shouldNotBeNull().networks.size shouldBe 0
		}

		@Test
		@DisplayName("zero speed round-trips")
		fun zeroSpeed() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				location = LocationSignal(
					coordinate = CoordinateE7(lat = LatE7(0), lon = LonE7(0)),
					horizontalAccuracyM = 1.0f,
					speed = SpeedMps(0.0f),
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.location.shouldNotBeNull().speed shouldBe SpeedMps(0.0f)
		}

		@Test
		@DisplayName("max ActivityConfidence round-trips")
		fun maxConfidence() {
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				activity = ActivitySignal(
					type = DetectedActivityType.STILL,
					confidence = ActivityConfidence(100),
				),
			)
			val json = SignalSerializer.serialize(original)
			val restored = SignalSerializer.deserialize(json)

			restored.shouldNotBeNull()
			restored.activity.shouldNotBeNull().confidence shouldBe ActivityConfidence(100)
		}

		@Test
		@DisplayName("all DetectedActivityType values round-trip correctly")
		fun allActivityTypes() {
			DetectedActivityType.entries.forEach { type ->
				val original = TrackingSignal(
					timestampMs = EpochMs(1_700_000_000_000L),
					activity = ActivitySignal(
						type = type,
						confidence = ActivityConfidence(50),
					),
				)
				val json = SignalSerializer.serialize(original)
				val restored = SignalSerializer.deserialize(json)

				restored.shouldNotBeNull()
				restored.activity.shouldNotBeNull().type shouldBe type
			}
		}

		@Test
		@DisplayName("all PolicyTier values round-trip correctly")
		fun allPolicyTiers() {
			PolicyTier.entries.forEach { tier ->
				val original = TrackingSignal(
					timestampMs = EpochMs(1_700_000_000_000L),
					policy = PolicySignal(tier = tier, policyName = "test-$tier"),
				)
				val json = SignalSerializer.serialize(original)
				val restored = SignalSerializer.deserialize(json)

				restored.shouldNotBeNull()
				restored.policy.shouldNotBeNull().tier shouldBe tier
			}
		}
	}
}
