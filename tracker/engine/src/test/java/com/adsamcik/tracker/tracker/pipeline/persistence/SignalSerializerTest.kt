package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.LocationObservationSignal
import com.adsamcik.tracker.stats.api.signal.ObservationStamp
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SignalSerializerTest {

	// region Test signal factories

	private fun createObservationStamp() = ObservationStamp(
		sourceEpochMs = 1_699_999_999_950L,
		sourceElapsedRealtimeNanos = 123_450_000L,
		sourceFirstElapsedRealtimeNanos = 123_400_000L,
		receivedEpochMs = 1_700_000_000_000L,
		receivedElapsedRealtimeNanos = 123_456_789L,
		sourceSequence = 52L,
		sourceFirstSequence = 50L,
		clockDomainId = "session-clock",
		bootClockDomainId = "boot-clock",
		callbackId = "callback-1",
		batchId = "batch-1",
		sourceAgeMs = 50L,
		timeUncertaintyMs = 10L,
		capabilityFlags = setOf("source_elapsed", "sequence"),
		permissionPrecision = "PRECISE",
	)

	private fun createFullSignal() = TrackingSignal(
		timestampMs = EpochMs(1_700_000_000_000L),
		elapsedRealtimeNanos = 123_456_789L,
		clockDomainId = "session-clock",
		bootClockDomainId = "boot-clock",
		locationObservation = LocationObservationSignal(
			rawFixTimeMs = 1_699_999_999_950L,
			coordinate = CoordinateE7(lat = LatE7(488_566_000), lon = LonE7(23_522_000)),
			horizontalAccuracyM = 5.0f,
			altitudeM = 248.0f,
			verticalAccuracyM = 3.0f,
			speedMps = 2.4f,
			speedAccuracyMps = 0.6f,
			bearingDeg = 91.0f,
			bearingAccuracyDeg = 4.0f,
			provider = "gps",
			receivedAtMs = 1_700_000_000_000L,
			receivedElapsedRealtimeNanos = 123_456_789L,
			acquisitionMode = "GNSS",
			requestPriority = "HIGH_ACCURACY",
			permissionPrecision = "PRECISE",
			batchIndex = 1,
			batchSize = 2,
			callbackId = "callback-1",
			sourceEventId = "event-1",
		),
		location = LocationSignal(
			coordinate = CoordinateE7(lat = LatE7(488_566_000), lon = LonE7(23_522_000)),
			horizontalAccuracyM = 5.0f,
			speed = SpeedMps(2.5f),
			rawPlatformSpeedMps = 2.4f,
			rawPlatformSpeedAccuracyMps = 0.6f,
			bearingDeg = 91.0f,
			bearingAccuracyDeg = 4.0f,
			altitudeM = 250.0f,
			rawGpsAltitudeM = 248.0f,
			altitudeDatum = AltitudeDatum.FUSED_ANDROID_MODEL_MSL,
			altitudeSource = AltitudeSource.FUSED_GPS_BAROMETER,
			altitudeConversionStatus = AltitudeConversionStatus.SUCCESS,
			rawGpsAltitudeDatum = AltitudeDatum.WGS84_ELLIPSOID,
			altitudeModelVersion = 1,
			altitudeEstimatorVersion = 1,
			altitudeCalibrationVersion = 1,
			verticalAccuracyM = 3.0f,
			speedAccuracyMps = 0.5f,
			provider = "gps",
		),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(85),
			stamp = createObservationStamp(),
		),
		steps = StepSignal(
			stepDelta = StepCount(42),
			totalStepsSinceBoot = 10_000L,
			sensorValueStart = 9_958,
			sensorValueEnd = 10_000,
			sensorReset = false,
			stamp = createObservationStamp(),
		),
		cells = CellSignal(
			towers = listOf(
				CellTowerReading(
					cellId = 12345678L,
					areaCode = 4321,
					mcc = "230",
					mnc = "01",
					networkType = 13,
					signalStrength = -85,
				),
				CellTowerReading(
					cellId = 87654321L,
					areaCode = 876,
					mcc = "230",
					mnc = "02",
					networkType = 13,
					signalStrength = -95,
				),
			),
			stamp = createObservationStamp(),
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
			stamp = createObservationStamp(),
		),
		pressure = PressureSignal(
			pressureHpa = 1013.25f,
			altitudeM = 250.0f,
			sampleCount = 4,
			minPressureHpa = 1013.0f,
			maxPressureHpa = 1013.5f,
			standardDeviationHpa = 0.2f,
			windowStartElapsedRealtimeNanos = 123_400_000L,
			windowEndElapsedRealtimeNanos = 123_450_000L,
			stamp = createObservationStamp(),
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

	private fun roundTrip(signal: TrackingSignal): TrackingSignal {
		val encoded = SignalSerializer.encode(signal)
		return SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = encoded.payloadJson,
			payloadChecksum = encoded.payloadChecksum,
		).shouldBeInstanceOf<PendingSignalDecodeResult.Valid>().signal
	}

	private fun decodeCurrentPayload(payloadJson: String): PendingSignalDecodeResult {
		val envelopeJson = """{"type":"tracking_signal","payload":$payloadJson}"""
		return SignalSerializer.decode(
			envelopeVersion = SignalSerializer.CURRENT_ENVELOPE_VERSION,
			payloadJson = envelopeJson,
			payloadChecksum = SignalSerializer.payloadChecksum(envelopeJson),
		)
	}

	// endregion

	@Test
	fun `current envelope round trips with a verified checksum`() {
		val original = createFullSignal()
		val encoded = SignalSerializer.encode(original)

		encoded.envelopeVersion shouldBe SignalSerializer.CURRENT_ENVELOPE_VERSION
		encoded.payloadChecksum shouldBe SignalSerializer.payloadChecksum(encoded.payloadJson)

		val decoded = SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = encoded.payloadJson,
			payloadChecksum = encoded.payloadChecksum,
		).shouldBeInstanceOf<PendingSignalDecodeResult.Valid>()

		decoded.signal shouldBe original
	}

	@Test
	fun `current envelope rejects a payload checksum mismatch`() {
		val encoded = SignalSerializer.encode(createMinimalSignal())
		val alteredPayload = encoded.payloadJson.replace("1700000000000", "1700000000001")

		val result = SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = alteredPayload,
			payloadChecksum = encoded.payloadChecksum,
		).shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.PAYLOAD_CHECKSUM_MISMATCH
	}

	@Test
	fun `current envelope requires a payload checksum`() {
		val encoded = SignalSerializer.encode(createMinimalSignal())

		val result = SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = encoded.payloadJson,
			payloadChecksum = null,
		).shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.MISSING_PAYLOAD_CHECKSUM
	}

	@Test
	fun `future envelope version is surfaced as unsupported`() {
		val encoded = SignalSerializer.encode(createMinimalSignal())

		val result = SignalSerializer.decode(
			envelopeVersion = SignalSerializer.CURRENT_ENVELOPE_VERSION + 1,
			payloadJson = encoded.payloadJson,
			payloadChecksum = encoded.payloadChecksum,
		).shouldBeInstanceOf<PendingSignalDecodeResult.Unsupported>()

		result.reason shouldBe PendingSignalDecodeFailure.UNSUPPORTED_ENVELOPE_VERSION
	}

	@Test
	fun `unknown current signal type is surfaced as unsupported`() {
		val encoded = SignalSerializer.encode(createMinimalSignal())
		val futurePayload = encoded.payloadJson.replace("tracking_signal", "future_signal")

		val result = SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = futurePayload,
			payloadChecksum = SignalSerializer.payloadChecksum(futurePayload),
		).shouldBeInstanceOf<PendingSignalDecodeResult.Unsupported>()

		result.reason shouldBe PendingSignalDecodeFailure.UNSUPPORTED_SIGNAL_TYPE
	}

	@Test
	fun `unknown current activity code is surfaced as unsupported`() {
		val encoded = SignalSerializer.encode(createActivityOnlySignal())
		val futurePayload = encoded.payloadJson.replace("\"running\"", "\"hoverboard\"")

		val result = SignalSerializer.decode(
			envelopeVersion = encoded.envelopeVersion,
			payloadJson = futurePayload,
			payloadChecksum = SignalSerializer.payloadChecksum(futurePayload),
		).shouldBeInstanceOf<PendingSignalDecodeResult.Unsupported>()

		result.reason shouldBe PendingSignalDecodeFailure.UNSUPPORTED_ACTIVITY_TYPE
	}

	@Test
	fun `serializer writes stable enum codes rather than ordinals`() {
		val json = SignalSerializer.encode(
			TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				activity = ActivitySignal(DetectedActivityType.RUNNING, ActivityConfidence(90)),
				policy = PolicySignal(PolicyTier.PRECISION),
			),
		).payloadJson

		json.contains("\"t\":\"running\"") shouldBe true
		json.contains("\"tier\":\"precision\"") shouldBe true
	}


	@Test
	fun fullSignal() {
		val original = createFullSignal()
		val restored = roundTrip(original)

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
		resLoc.altitudeDatum shouldBe origLoc.altitudeDatum
		resLoc.altitudeSource shouldBe origLoc.altitudeSource
		resLoc.altitudeConversionStatus shouldBe origLoc.altitudeConversionStatus
		resLoc.rawGpsAltitudeDatum shouldBe origLoc.rawGpsAltitudeDatum
		resLoc.altitudeModelVersion shouldBe origLoc.altitudeModelVersion
		resLoc.altitudeEstimatorVersion shouldBe origLoc.altitudeEstimatorVersion
		resLoc.altitudeCalibrationVersion shouldBe origLoc.altitudeCalibrationVersion
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
	fun minimalSignal() {
		val original = createMinimalSignal()
		val restored = roundTrip(original)

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
	fun locationOnly() {
		val original = createLocationOnlySignal()
		val restored = roundTrip(original)

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
	fun `raw provider observation preserves replay metadata`() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_123L),
			elapsedRealtimeNanos = 987_654_321L,
			locationObservation = LocationObservationSignal(
				coordinate = CoordinateE7(lat = LatE7(500_123_456), lon = LonE7(140_654_321)),
				horizontalAccuracyM = 17.5f,
				altitudeM = 312.25f,
				verticalAccuracyM = 8.0f,
				speedMps = 2.75f,
				speedAccuracyMps = 0.8f,
				provider = "provider\"raw",
				receivedAtMs = 1_700_000_000_456L,
				receivedElapsedRealtimeNanos = 1_234_567_890L,
				acquisitionMode = "FUSED",
				requestPriority = "BALANCED",
				permissionPrecision = "PRECISE",
				batchIndex = 2,
				batchSize = 4,
				isMock = true,
				ingressDisposition = "DELIVERED_VALID",
			),
		)

		val restored = roundTrip(original)

		restored.timestampMs shouldBe original.timestampMs
		restored.elapsedRealtimeNanos shouldBe original.elapsedRealtimeNanos
		restored.locationObservation shouldBe original.locationObservation
		restored.location.shouldBeNull()
	}

	@Test
	fun `raw invalid-coordinate observation round trips without invented coordinates`() {
		val original = TrackingSignal(
			timestampMs = EpochMs(0L),
			locationObservation = LocationObservationSignal(
				coordinate = null,
				provider = "fused",
				receivedAtMs = 1_700_000_000_000L,
				ingressDisposition = "REJECTED_INVALID_COORDINATE",
			),
		)

		val restored = roundTrip(original)
		val observation = restored.locationObservation.shouldNotBeNull()

		observation.coordinate.shouldBeNull()
		observation.ingressDisposition shouldBe "REJECTED_INVALID_COORDINATE"
	}

	@Test
	fun activityOnly() {
		val original = createActivityOnlySignal()
		val restored = roundTrip(original)

		restored.timestampMs shouldBe original.timestampMs
		restored.activity.shouldNotBeNull()
		restored.activity!!.type shouldBe DetectedActivityType.RUNNING
		restored.activity!!.confidence shouldBe ActivityConfidence(92)
		restored.location.shouldBeNull()
		restored.steps.shouldBeNull()
	}

	@Test
	fun policyWithoutName() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			policy = PolicySignal(tier = PolicyTier.PRECISION, policyName = null),
		)
		val restored = roundTrip(original)

		restored.policy.shouldNotBeNull()
		restored.policy!!.tier shouldBe PolicyTier.PRECISION
		restored.policy!!.policyName.shouldBeNull()
	}

	@Test
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
		val restored = roundTrip(original)

		val s = restored.steps.shouldNotBeNull()
		s.sensorReset shouldBe true
		s.sensorValueStart shouldBe 65535
		s.sensorValueEnd shouldBe 0
	}


	@Test
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
		val restored = roundTrip(original)

		val net = restored.wifi.shouldNotBeNull().networks.first()
		net.ssid shouldBe original.wifi!!.networks.first().ssid
	}

	@Test
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
		val restored = roundTrip(original)

		val tower = restored.cells.shouldNotBeNull().towers.first()
		tower.mnc shouldBe "0\\1"
	}

	@Test
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
		val restored = roundTrip(original)

		restored.location.shouldNotBeNull().provider shouldBe "test\"provider"
	}

	@Test
	fun policyNameSpecialChars() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			policy = PolicySignal(
				tier = PolicyTier.ACTIVE,
				policyName = "outdoor\\walk\ting",
			),
		)
		val restored = roundTrip(original)

		restored.policy.shouldNotBeNull().policyName shouldBe "outdoor\\walk\ting"
	}


	@Test
	fun `current envelope rejects malformed JSON`() {
		val payload = "{not valid json!!!}"

		val result = SignalSerializer.decode(
			envelopeVersion = SignalSerializer.CURRENT_ENVELOPE_VERSION,
			payloadJson = payload,
			payloadChecksum = SignalSerializer.payloadChecksum(payload),
		).shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.MALFORMED_ENVELOPE
	}

	@Test
	fun missingTimestamp() {
		val result = decodeCurrentPayload("""{"ern":123}""")
			.shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.MALFORMED_PAYLOAD
	}

	@Test
	fun partialJson() {
		val restored = decodeCurrentPayload("""{"ts":1700000000000}""")
			.shouldBeInstanceOf<PendingSignalDecodeResult.Valid>().signal

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
	fun `current location payload defaults missing altitude contract to unknown`() {
		val restored = decodeCurrentPayload(
			"""{"ts":1700000000000,"loc":{"lat":500000000,"lon":140000000,"hAcc":5.0,"alt":250.0,"rAlt":248.0,"prov":"gps"}}""",
		).shouldBeInstanceOf<PendingSignalDecodeResult.Valid>().signal
		val location = restored.location.shouldNotBeNull()

		location.altitudeM shouldBe 250.0f
		location.rawGpsAltitudeM shouldBe 248.0f
		location.altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		location.altitudeSource shouldBe AltitudeSource.UNKNOWN_LEGACY
		location.altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
		location.rawGpsAltitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		location.altitudeModelVersion shouldBe 0
		location.altitudeEstimatorVersion shouldBe 0
		location.altitudeCalibrationVersion shouldBe 0
	}

	@Test
	fun `unknown altitude contract codes decode conservatively`() {
		val restored = decodeCurrentPayload(
			"""{"ts":1700000000000,"loc":{"lat":500000000,"lon":140000000,"hAcc":5.0,"altDatum":"future_datum","altSource":"future_source","altStatus":"future_status","rawAltDatum":"future_raw","prov":"gps"}}""",
		).shouldBeInstanceOf<PendingSignalDecodeResult.Valid>().signal
		val location = restored.location.shouldNotBeNull()

		location.altitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
		location.altitudeSource shouldBe AltitudeSource.UNKNOWN_LEGACY
		location.altitudeConversionStatus shouldBe AltitudeConversionStatus.UNKNOWN_LEGACY
		location.rawGpsAltitudeDatum shouldBe AltitudeDatum.UNKNOWN_LEGACY
	}

	@Test
	fun `current activity type rejects numeric ordinal`() {
		val result = decodeCurrentPayload(
			"""{"ts":1700000000000,"act":{"t":1,"c":50}}""",
		).shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.MALFORMED_PAYLOAD
	}

	@Test
	fun `current policy tier rejects numeric ordinal`() {
		val result = decodeCurrentPayload(
			"""{"ts":1700000000000,"pol":{"tier":1}}""",
		).shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.MALFORMED_PAYLOAD
	}


	@Test
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
				val restored = roundTrip(original)

				val resLoc = restored.location.shouldNotBeNull()
				resLoc.coordinate.lat.raw shouldBe lat.raw
				resLoc.coordinate.lon.raw shouldBe lon.raw
			}
		}
	}

	@Test
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
		val restored = roundTrip(original)

		val resLoc = restored.location.shouldNotBeNull()
		// The raw int must come back bit-identical
		resLoc.coordinate.lat.raw shouldBe 488_566_123
		resLoc.coordinate.lon.raw shouldBe 23_522_456
	}


	@Test
	fun emptyCellTowers() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			cells = CellSignal(towers = emptyList()),
		)
		val restored = roundTrip(original)

		restored.cells.shouldNotBeNull().towers.size shouldBe 0
	}

	@Test
	fun emptyWifiNetworks() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			wifi = WifiSignal(networks = emptyList()),
		)
		val restored = roundTrip(original)

		restored.wifi.shouldNotBeNull().networks.size shouldBe 0
	}

	@Test
	fun zeroSpeed() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			location = LocationSignal(
				coordinate = CoordinateE7(lat = LatE7(0), lon = LonE7(0)),
				horizontalAccuracyM = 1.0f,
				speed = SpeedMps(0.0f),
			),
		)
		val restored = roundTrip(original)

		restored.location.shouldNotBeNull().speed shouldBe SpeedMps(0.0f)
	}

	@Test
	fun maxConfidence() {
		val original = TrackingSignal(
			timestampMs = EpochMs(1_700_000_000_000L),
			activity = ActivitySignal(
				type = DetectedActivityType.STILL,
				confidence = ActivityConfidence(100),
			),
		)
		val restored = roundTrip(original)

		restored.activity.shouldNotBeNull().confidence shouldBe ActivityConfidence(100)
	}

	@Test
	fun allActivityTypes() {
		DetectedActivityType.entries.forEach { type ->
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				activity = ActivitySignal(
					type = type,
					confidence = ActivityConfidence(50),
				),
			)
			val restored = roundTrip(original)

			restored.activity.shouldNotBeNull().type shouldBe type
		}
	}

	@Test
	fun allPolicyTiers() {
		PolicyTier.entries.forEach { tier ->
			val original = TrackingSignal(
				timestampMs = EpochMs(1_700_000_000_000L),
				policy = PolicySignal(tier = tier, policyName = "test-$tier"),
			)
			val restored = roundTrip(original)

			restored.policy.shouldNotBeNull().tier shouldBe tier
		}
	}
}
