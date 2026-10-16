package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.error.StatsError
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual saved rows, exact run ownership and production reader; source authored, not executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedTripDistanceReaderRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var reader: SavedTripDistanceReader

	@Before fun setup(): Unit = runBlocking {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		database.sourceEvidenceStateDao().ensure()
		reader = SavedTripDistanceReader(database, TestDispatchersProvider(Dispatchers.Unconfined))
	}
	@After fun close() = database.close()

	@Test fun `January counts all replacement runs of a trip starting in January and excludes February starts`(): Unit = runBlocking {
		val januaryEnd = Instant.parse("2026-01-31T23:59:59.999Z").toEpochMilli()
		val firstStart = januaryEnd - 30_000
		val secondStart = januaryEnd + 30_000
		logical("whole", firstStart, secondStart + 10_000)
		val first = run("whole", "first", firstStart, firstStart + 10_000)
		val second = run("whole", "second", secondStart, secondStart + 10_000)
		nativePoint(first, firstStart + 1_000, 0, 1)
		nativePoint(first, firstStart + 2_000, 100_000, 2)
		nativePoint(second, secondStart + 1_000, 300_000, 1)
		nativePoint(second, secondStart + 2_000, 400_000, 2)
		logical("february", secondStart, secondStart + 10_000)
		val february = run("february", "february-run", secondStart, secondStart + 10_000)
		nativePoint(february, secondStart + 1_000, 0, 1)
		nativePoint(february, secondStart + 2_000, 900_000, 2)
		assertEquals((distance(0, 100_000) + distance(300_000, 400_000)).toLong(),
			reader.longestDistanceMeters(firstStart, januaryEnd).getOrNull())
		assertEquals(distance(0, 900_000).toLong(),
			reader.longestDistanceMeters(0, Long.MAX_VALUE).getOrNull())
		// The presentation fields are deliberately wrong; no physical distance aggregate is trusted.
		assertEquals(9_000_000f, requireNotNull(database.sessionSegmentDao().getById(first.id)).distanceM)
	}

	@Test fun `equal original starts paginate logical identities without grouping distinct trips`(): Unit = runBlocking {
		for (index in 0..64) {
			val identity = "trip:${index.toString().padStart(3, '0')}"
			logical(identity, 1_000, 10_000)
			val member = run(identity, "run:$index", 1_000, 10_000)
			nativePoint(member, 2_000, 0, 1)
			nativePoint(member, 3_000, if (index == 64) {
				300_000
			} else {
				100_000
			}, 2)
		}
		assertEquals(distance(0, 300_000).toLong(), reader.longestDistanceMeters(1_000, 1_000).getOrNull())
	}

	@Test fun `Steps-only and active logical siblings do not change a valid Location maximum`(): Unit = runBlocking {
		logical("steps-only", 1_000, 9_000)
		logical("location", 1_000, 9_000)
		val member = run("location", "location-run", 1_000, 9_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		val active = logical("active", 1_000, 9_000).copy(state = "ACTIVE", completedAtMs = null)
		database.sourceSessionDao().updateSession(active)
		val activeMember = run("active", "active-run", 1_000, 9_000)
		nativePoint(activeMember, 2_000, 0, 1)
		nativePoint(activeMember, 3_000, 1_000_000, 2)
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())
	}

	@Test fun `legacy and imported saved points recalculate after correction and deletion without native proof`(): Unit = runBlocking {
		legacySegment(1_000, 10_000)
		val first = savedPoint(2_000, 0)
		val second = savedPoint(3_000, 100_000)
		database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("owner-edited-point", second.id, true, "UNKNOWN"))
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())
		database.locationSampleDao().update(second.copy(lonE7 = 300_000))
		assertEquals(distance(0, 300_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())
		database.locationSampleDao().deleteExactIds(listOf(first.id))
		assertIs<StatsError.NotFound>(reader.longestDistanceMeters(0, 10_000).leftOrNull())
	}

	@Test fun `point pages preserve the anchor while candidates continue beyond a history page`(): Unit = runBlocking {
		for (index in 0..64) {
			val start = 1_000L + index * 1_000_000L
			legacySegment(start, start + 300_000)
			val count = if (index == 64) {
				270
			} else {
				2
			}
			for (point in 0 until count) savedPoint(start + point * 1_000L, point * 1_000)
		}
		assertEquals((distance(0, 1_000) * 269).toLong(), reader.longestDistanceMeters(0, Long.MAX_VALUE).getOrNull())
	}

	@Test fun `zero legacy summary cycles cannot hide its saved Location measurements`(): Unit = runBlocking {
		database.sessionSegmentDao().insert(segment(1_000, 10_000).copy(sampleCount = 0))
		savedPoint(2_000, 0)
		savedPoint(3_000, 100_000)
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())
	}

	@Test fun `old imported archives withhold points until an exact current archive copy exists`(): Unit = runBlocking {
		legacySegment(1_000, 10_000)
		val points = listOf(savedPoint(2_000, 0), savedPoint(3_000, 100_000))
		val identities = points.mapIndexed { index, point ->
			LocationFileIdentityEntity("import:point:$index", point.id, true, "imported")
		}
		identities.forEach { database.locationFileIdentityDao().insertIfAbsent(it) }
		val oldArchive = "a".repeat(64)
		database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity(
			oldArchive, LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 0, 2,
		))
		database.locationFileIdentityDao().recordArchivePoints(identities.mapIndexed { index, point ->
			LocationFileArchivePointEntity(oldArchive, index, point.portableId, point.sampleId)
		})
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())

		database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 0, 10_001)
		assertIs<StatsError.NotFound>(reader.longestDistanceMeters(0, 10_000).leftOrNull())
		val currentArchive = "b".repeat(64)
		database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity(
			currentArchive, LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 1, 2,
		))
		database.locationFileIdentityDao().recordArchivePoints(identities.mapIndexed { index, point ->
			LocationFileArchivePointEntity(currentArchive, index, point.portableId, point.sampleId)
		})
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 10_000).getOrNull())
	}

	@Test fun `missing sequences and separately allowed ambient points never bridge a native route`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 900_000, 3)
		nativePoint(member, 4_000, 1_000_000, 4)
		val ambient = savedPoint(3_500, 9_000_000)
		insertReceipt(ambient, "ambient-instance", 1)
		assertEquals(distance(900_000, 1_000_000).toLong(), reader.longestDistanceMeters(0, 30_000).getOrNull())
	}

	@Test fun `broken reciprocal membership is unavailable rather than a smaller maximum`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		val owned = requireNotNull(database.sourceSessionDao().serviceRun("native-run"))
		database.sourceSessionDao().updateServiceRun(owned.copy(sessionSegmentId = member.id + 99))
		assertIs<StatsError.ValidationError>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
	}

	@Test fun `native saved coordinates are current user data and a deleted fix breaks its sequence`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		nativePoint(member, 4_000, 300_000, 3)
		val points = database.locationPurposeDao().savedLogicalTripPointPage("native", listOf("native-run"), null, null, 256)
		val middle = points[1].sample
		database.locationSampleDao().update(middle.copy(lonE7 = 200_000))
		assertEquals((distance(0, 200_000) + distance(200_000, 300_000)).toLong(),
			reader.longestDistanceMeters(0, 30_000).getOrNull())
		database.locationSampleDao().deleteExactIds(listOf(middle.id))
		assertIs<StatsError.NotFound>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
	}

	@Test fun `retention truncation and old native data epochs are not complete trip distance`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		database.sourceEvidenceStateDao().updateLifecycle(0, 2_500, 30_000)
		assertIs<StatsError.ValidationError>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
		database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 100, 30_001)
		assertIs<StatsError.NotFound>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
	}

	@Test fun `erased native purpose does not borrow another saved purpose or invent a zero`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(1, SourceBrokerPurpose.SESSION_CAPTURE, "native", "native-run")
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createForOriginalRunDigest(1,
			SourceBrokerPurpose.SESSION_CAPTURE, digest, 1, 0, 30_000))
		assertIs<StatsError.NotFound>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
	}

	@Test fun `exact saved session point keeps its descriptive background origin without changing distance`(): Unit = runBlocking {
		logical("native", 1_000, 20_000)
		val member = run("native", "native-run", 1_000, 20_000)
		nativePoint(member, 2_000, 0, 1)
		nativePoint(member, 3_000, 100_000, 2)
		val before = database.locationPurposeDao().savedLogicalTripPointPage("native", listOf("native-run"), null, null, 256)
		assertEquals(false, before[0].hasVisibleBackgroundPurpose)
		val capture = requireNotNull(database.locationPurposeDao().purpose(before[0].sample.id,
			SourceBrokerPurpose.SESSION_CAPTURE, "native-run"))
		database.locationPurposeDao().insertPurposeIfAbsent(capture.copy(
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, authorityId = "background-purpose",
			logicalTrackingId = null, serviceRunId = null, sessionSegmentId = null,
			sessionManifestRevision = null, lifecycleLeaseGeneration = null,
			ambientIntentId = "background-intent", retentionPolicyId = "saved-retention", retentionApprovalRevision = 1,
		))
		val after = database.locationPurposeDao().savedLogicalTripPointPage("native", listOf("native-run"), null, null, 256)
		assertEquals(true, after[0].hasSessionPurpose)
		assertEquals(true, after[0].hasVisibleBackgroundPurpose)
		assertEquals(false, after[1].hasVisibleBackgroundPurpose)
		assertEquals(distance(0, 100_000).toLong(), reader.longestDistanceMeters(0, 30_000).getOrNull())
		savedPoint(5_000, 900_000)
		val exact = database.locationPurposeDao().savedFileSamples(listOf(after[1].sample.id,
			after[0].sample.id, after[1].sample.id))
		assertEquals(after, exact)
		database.sourceEvidenceStateDao().updateLifecycle(0, 2_500, 30_000)
		assertEquals(listOf(after[1]), database.locationPurposeDao().savedFileSamples(after.map { it.sample.id }))
	}

	@Test fun `membership overflow and storage failure remain typed instead of becoming a maximum`(): Unit = runBlocking {
		logical("many", 1_000, 30_000)
		val first = run("many", "run:0", 1_000, 20_000)
		nativePoint(first, 2_000, 0, 1)
		nativePoint(first, 3_000, 100_000, 2)
		for (index in 1..256) run("many", "run:$index", 1_000, 20_000)
		assertIs<StatsError.ValidationError>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
		database.close()
		assertIs<StatsError.DatabaseError>(reader.longestDistanceMeters(0, 30_000).leftOrNull())
	}

	private suspend fun logical(id: String, start: Long, end: Long): LogicalTrackingSessionEntity {
		val session = LogicalTrackingSessionEntity(id, "FINALIZED", 1, 1, 1, "USER", "boot",
			start, start * 1_000_000, end, end * 1_000_000, end, null, null)
		database.sourceSessionDao().insertSession(session)
		return session
	}
	private suspend fun run(logical: String, id: String, start: Long, end: Long): SessionSegment {
		val segment = segment(start, end).copy(logicalTrackingId = logical, serviceRunId = id)
		val sampleId = database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertServiceRun(SourceServiceRunEntity(id, logical, "FINALIZED", 1, 1, 0,
			start, start * 1_000_000, end, "STOPPED", sessionSegmentId = sampleId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = end))
		return segment.copy(id = sampleId)
	}
	private suspend fun legacySegment(start: Long, end: Long) = database.sessionSegmentDao().insert(segment(start, end))
	private fun segment(start: Long, end: Long) = SessionSegment(startTimeMs = start, endTimeMs = end,
		distanceM = 9_000_000f, steps = null, primaryActivity = null, activityConfidence = null,
		sampleCount = 2, source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = end)
	private suspend fun savedPoint(time: Long, longitude: Int): LocationSample {
		val sample = LocationSample(timeMs = time, elapsedRealtimeNanos = time * 1_000_000, latE7 = 0, lonE7 = longitude,
			altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null,
			speedAccuracyMps = null, provider = "saved", quality = SampleQuality.COARSE,
			motionState = null, policy = null, bucketId = null, createdAt = time, clockDomainId = "boot")
		return sample.copy(id = database.locationSampleDao().insert(sample))
	}
	private suspend fun nativePoint(member: SessionSegment, time: Long, longitude: Int, sequence: Long) {
		val point = savedPoint(time, longitude)
		val run = requireNotNull(member.serviceRunId)
		insertReceipt(point, run, sequence)
		database.locationPurposeDao().insertPurposeIfAbsent(LocationSamplePurposeAuthorityEntity(point.id,
			SourceBrokerPurpose.SESSION_CAPTURE, run, 1, 0, 0, Long.MAX_VALUE, member.logicalTrackingId,
			run, member.id, 1, 1, null, null, null, 0, "UTC"))
	}
	private suspend fun insertReceipt(point: LocationSample, instance: String, sequence: Long) {
		database.locationPurposeDao().insertReceiptIfAbsent(LocationAcceptedReceiptEntity(point.id,
			"event:${point.id}", "signal:${point.id}", point.id, "a".repeat(64),
			LocationAcceptedReceiptIntegrity.canonicalChecksum(point), instance, 1, sequence,
			"delivery:${point.id}", 0, 1, "saved-writer", 1, 1, "physical", 1, "authorization",
			"boot", "boot", point.elapsedRealtimeNanos, point.elapsedRealtimeNanos,
			point.timeMs, point.timeMs, 0, 0))
	}
	private fun distance(first: Int, second: Int) = Location.distance(0.0, first / 1e7, 0.0, second / 1e7, LengthUnit.Meter)
}
