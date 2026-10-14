package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as PublicStepsHistoryCoverage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room metadata/membership; sensor payload rows are qualified-leaf fixtures. All NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredSessionNamesRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var context: Application
    @Before fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
    }
    @After fun close() = database.close()

    @Test fun `point session labels only the reading at its original timestamp`() = runTest {
        annotation("point", 1_200, 1_200)
        val keys = listOf(key("ambient_wifi", "LOCAL", "BACKGROUND", "at", 1_200),
            key("ambient_wifi", "LOCAL", "BACKGROUND", "after", 1_201))
        decorate(keys.map(::wifi), keys).rows.map { it.sessionNames } shouldBe
            listOf(listOf("point"), emptyList())
        database.organizationAnnotationDao().byId("point")?.endTimeMs shouldBe 1_200L
    }

    @Test fun `overlapping retrospective sessions add both names to one background reading`() = runTest {
        annotation("morning", 900, 1_400)
        annotation("commute", 1_100, 1_600)
        val key = key("ambient_wifi", "LOCAL", "BACKGROUND", "reading", 1_200)
        val original = wifi(key)
        val result = decorate(listOf(original), listOf(key))
        result.rows.size shouldBe 1
        result.rows.single().sessionNames shouldBe listOf("commute", "morning")
        result.rows.single().measurement shouldBe original.measurement
        result.rows.single().purpose shouldBe HistoryPurpose.BACKGROUND
        result.problems shouldBe emptySet()
    }

    @Test fun `metadata paging does not drop the hundred and first overlapping name`() = runTest {
        repeat(105) { annotation("session-$it", 900, 1_600) }
        val key = key("ambient_wifi", "LOCAL", "BACKGROUND", "reading", 1_200)
        val names = decorate(listOf(wifi(key)), listOf(key)).rows.single().sessionNames
        names.size shouldBe 105
        names.toSet() shouldBe (0..104).map { "session-$it" }.toSet()
    }

    @Test fun `edited bounds rename only contained points and never delete the observations`() = runTest {
        annotation("walk", 900, 1_600)
        val keys = listOf(key("ambient_wifi", "LOCAL", "BACKGROUND", "outside", 1_050),
            key("ambient_wifi", "LOCAL", "BACKGROUND", "inside", 1_300))
        val rows = keys.map(::wifi)
        decorate(rows, keys).rows.map { it.sessionNames } shouldBe listOf(listOf("walk"), listOf("walk"))
        val saved = requireNotNull(database.organizationAnnotationDao().byId("walk"))
        database.organizationAnnotationDao().update(saved.copy(revision = 2, startTimeMs = 1_200, name = "Lunch")) shouldBe 1
        val updated = decorate(rows, keys)
        updated.rows.map { it.sessionNames } shouldBe listOf(emptyList(), listOf("Lunch"))
        updated.rows.map { it.measurement } shouldBe rows.map { it.measurement }
        database.organizationAnnotationDao().delete("walk") shouldBe 1
        decorate(rows, keys).rows.map { it.sessionNames } shouldBe listOf(emptyList(), emptyList())
    }

    @Test fun `a real crossing Steps window keeps its whole count with both overlapping labels`() = runTest {
        annotation("complete", 900, 1_800)
        annotation("part", 1_200, 1_800)
        val key = key("ambient_steps", "LOCAL", "BACKGROUND", "steps", 1_000, 1_500)
        val count = StepsHistory(42L, HistoryAvailability.AVAILABLE, HistoryEvidence.RECORDED,
            HistoryProductState.READY, PublicStepsHistoryCoverage.COMPLETE)
        val row = UnifiedHistoryRow(measuredHistoryPosition(key), 1_500, setOf(HistoryOrigin.LOCAL),
            HistoryPurpose.BACKGROUND, sections = emptyList(), measurement = HistoryMeasurement.Steps(count))
        val displayed = decorate(listOf(row), listOf(key)).rows.single()
        displayed.sessionNames shouldBe listOf("part", "complete")
        displayed.toExclusiveMs shouldBe 1_500L
        displayed.measurement shouldBe HistoryMeasurement.Steps(count)
    }

    @Test fun `radio labels use measured time without treating uncertainty as a permission fence`() = runTest {
        annotation("bounded", 1_000, 1_500)
        val key = key("ambient_wifi", "LOCAL", "BACKGROUND", "reading", 1_010)
        val row = wifi(key).copy(measurement = HistoryMeasurement.Wifi(WifiObservedNetwork(1_010, 20,
            "ap", "Cafe", 2412, -45)))
        decorate(listOf(row), listOf(key)).rows.single().sessionNames shouldBe listOf("bounded")
        decorate(listOf(wifi(key)), listOf(key)).rows.single().sessionNames shouldBe listOf("bounded")
    }

    @Test fun `hidden and malformed imported annotations do not borrow ordinary interval membership`() = runTest {
        annotation("kept", 900, 1_600)
        annotation("hidden", 900, 1_600, hidden = true)
        annotation("invalid", 900, 1_600, encoded = "not-json")
        val key = key("ambient_wifi", "LOCAL", "BACKGROUND", "reading", 1_200)
        val result = decorate(listOf(wifi(key)), listOf(key))
        result.rows.single().sessionNames shouldBe listOf("kept")
        result.problems shouldBe setOf(HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
    }

    @Test fun `Location bundle membership uses exact saved IDs rather than equal timestamps`() = runTest {
        val first = point("first", 1_200)
        val unrelated = point("other", 1_200)
        val identity = "a".repeat(64)
        database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity(identity,
            LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 0, 1))
        database.locationFileIdentityDao().recordArchivePoints(listOf(LocationFileArchivePointEntity(identity, 0, "first", first)))
        annotation("bundle", 900, 1_600, encoded = OrganizationSourceSelectionCodec.encode(
            listOf(OrganizationSourceSelector.LocationFile(identity))))
        val keys = listOf(first, unrelated).map { key("location", "IMPORTED", "UNKNOWN", "location:$it", 1_200, sample = it) }
        val rows = keys.map { UnifiedHistoryRow(measuredHistoryPosition(it), null, setOf(HistoryOrigin.IMPORTED),
            HistoryPurpose.UNKNOWN, sections = emptyList(), measurement = HistoryMeasurement.Location(
                HistoryLocationPoint(1_200, 500_000_000, 140_000_000, null, null, it.rowId), HistoryLocationCoverage.LEGACY_UNVERIFIABLE)) }
        decorate(rows, keys).rows.map { it.sessionNames } shouldBe listOf(listOf("bundle"), emptyList())
    }

    @Test fun `typed imported memberships select only their original source parent`() = runTest {
        val entry = "b".repeat(64)
        val archive = "c".repeat(64)
        annotation("capture", 900, 1_600, encoded = OrganizationSourceSelectionCodec.encode(
            listOf(OrganizationSourceSelector.CapturedEntry(HistorySource.WIFI, entry))))
        annotation("background", 900, 1_600, encoded = OrganizationSourceSelectionCodec.encode(
            listOf(OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.WIFI, archive))))
        val keys = listOf(key("imported_wifi", "IMPORTED", "CAPTURE", "capture", 1_200, parent = entry),
            key("imported_ambient_wifi", "IMPORTED", "BACKGROUND", "background", 1_200, parent = archive),
            key("imported_wifi", "IMPORTED", "CAPTURE", "unrelated", 1_200, parent = "d".repeat(64)))
        decorate(keys.map(::wifi), keys).rows.map { it.sessionNames } shouldBe
            listOf(listOf("capture"), listOf("background"), emptyList())
    }

    @Test fun `real measured facade decorates one saved point without adding organized rows`() = runTest {
        point("saved", 1_200)
        annotation("morning", 900, 1_600)
        annotation("commute", 1_100, 1_500)
        val reader = rangeReaderForRoomTest(database, context)
        val range = HistoryRange(900, 1_600, "UTC", sources = setOf(HistorySource.LOCATION),
            origins = setOf(HistoryOrigin.IMPORTED), purposes = setOf(HistoryPurpose.UNKNOWN), mode = HistoryBrowseMode.OBSERVATIONS)
        val page = reader.read(HistoryRangeRequest(range)).shouldBeInstanceOf<HistoryRangeRead.Page>()
        page.rows.size shouldBe 1
        page.rows.single().sessionNames shouldBe listOf("commute", "morning")
        page.rows.single().sections shouldBe emptyList()
        page.next shouldBe null
        page.problems shouldBe emptySet()
    }

    @Test fun `unnamed session contents select six original measurements without changing their purpose`() = runTest {
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("unnamed", 1, 0, 0, null,
            1_100, 1_400, "UTC", null, null, false))
        val count = StepsHistory(42L, HistoryAvailability.AVAILABLE, HistoryEvidence.RECORDED,
            HistoryProductState.READY, PublicStepsHistoryCoverage.COMPLETE)
        val values = listOf<HistoryMeasurement>(
            HistoryMeasurement.Location(HistoryLocationPoint(1_200, 500_000_000, 140_000_000, null, null),
                HistoryLocationCoverage.LEGACY_UNVERIFIABLE),
            HistoryMeasurement.Wifi(WifiObservedNetwork(1_200, 200, "ap", "Cafe", 2412, -45)),
            HistoryMeasurement.Cell(CellObservedTower(1_200, 200, "tower", CellHistoryTechnology.LTE, true, -70)),
            HistoryMeasurement.LegacyActivity(0, 90, true),
            HistoryMeasurement.LegacyPressure(1_010f, 1, null, null, null, null, null, null),
            HistoryMeasurement.Steps(count),
        )
        val keys = values.map { value -> key(value.source.name.lowercase(), "LOCAL", "BACKGROUND",
            value.source.name, if (value is HistoryMeasurement.Steps) 1_000 else 1_200,
            if (value is HistoryMeasurement.Steps) 1_500 else null) }
        val rows = values.zip(keys).map { (value, key) -> UnifiedHistoryRow(measuredHistoryPosition(key),
            key.endTimeMs, setOf(HistoryOrigin.LOCAL), HistoryPurpose.BACKGROUND, sections = emptyList(), measurement = value) }
        val result = database.withTransaction { MeasuredSessionNamesReader(database, measuredCapturedRadioFixture(database))
            .decorate(rows, keys, HistoryRange(1_100, 1_400, "UTC", mode = HistoryBrowseMode.OBSERVATIONS,
                organizationSessionId = OrganizationSessionId.Annotation("unnamed"))) }
        result.rows.map { it.measurement } shouldBe values
        result.rows.map { it.purpose }.toSet() shouldBe setOf(HistoryPurpose.BACKGROUND)
        result.rows.last().position.startTimeMs shouldBe 1_000L
        result.rows.last().toExclusiveMs shouldBe 1_500L
        result.rows.map { it.sessionNames }.toSet() shouldBe setOf(emptyList())
    }

    @Test fun `imported session contents keep only exact original archive members`() = runTest {
        val saved = point("member", 1_200)
        val unrelated = point("other", 1_200)
        val identity = "e".repeat(64)
        database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity(identity,
            LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 0, 1))
        database.locationFileIdentityDao().recordArchivePoints(listOf(LocationFileArchivePointEntity(identity, 0, "member", saved)))
        annotation("bundle", 900, 1_600, encoded = OrganizationSourceSelectionCodec.encode(
            listOf(OrganizationSourceSelector.LocationFile(identity))))
        val reader = rangeReaderForRoomTest(database, context)
        val range = HistoryRange(900, 1_600, "UTC", sources = setOf(HistorySource.LOCATION),
            origins = setOf(HistoryOrigin.IMPORTED), purposes = setOf(HistoryPurpose.UNKNOWN), mode = HistoryBrowseMode.OBSERVATIONS,
            organizationSessionId = OrganizationSessionId.Annotation("bundle"))
        val page = reader.read(HistoryRangeRequest(range)).shouldBeInstanceOf<HistoryRangeRead.Page>()
        page.rows.map { (it.measurement as HistoryMeasurement.Location).point.sampleId } shouldBe listOf(saved)
        database.locationSampleDao().byIds(listOf(unrelated)).single().id shouldBe unrelated
        database.organizationAnnotationDao().delete("bundle") shouldBe 1
        reader.read(HistoryRangeRequest(range)).shouldBeInstanceOf<HistoryRangeRead.Page>().rows shouldBe emptyList()
        database.locationSampleDao().byIds(listOf(saved)).single().id shouldBe saved
    }

    @Test fun `session contents page beyond one hundred saved points without excluding an unnamed session`() = runTest {
        for (number in 1..205) point("point-$number", number.toLong())
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("many", 1, 0, 0, null,
            0, 300, "UTC", null, null, false))
        val reader = rangeReaderForRoomTest(database, context)
        val range = HistoryRange(0, 300, "UTC", sources = setOf(HistorySource.LOCATION),
            origins = setOf(HistoryOrigin.IMPORTED), purposes = setOf(HistoryPurpose.UNKNOWN), mode = HistoryBrowseMode.OBSERVATIONS,
            organizationSessionId = OrganizationSessionId.Annotation("many"))
        var next: HistoryContinuation? = null
        val times = mutableListOf<Long>()
        do {
            val page = reader.read(HistoryRangeRequest(range, 73, next)).shouldBeInstanceOf<HistoryRangeRead.Page>()
            times += page.rows.map { it.position.startTimeMs }
            next = page.next
        } while (next != null)
        times shouldBe (205L downTo 1L).toList()
        database.organizationAnnotationDao().delete("many") shouldBe 1
        reader.read(HistoryRangeRequest(range.copy(organizationSessionId = null))).shouldBeInstanceOf<HistoryRangeRead.Page>()
            .rows.size shouldBe 100
    }

    private suspend fun decorate(rows: List<UnifiedHistoryRow>, keys: List<MeasuredHistoryCandidate>) =
        database.withTransaction { MeasuredSessionNamesReader(database, measuredCapturedRadioFixture(database)).decorate(rows, keys,
            HistoryRange(0, Long.MAX_VALUE, "UTC", mode = HistoryBrowseMode.OBSERVATIONS)) }
    private fun key(kind: String, origin: String, purpose: String, id: String, start: Long,
        end: Long? = null, parent: String? = null, sample: Long? = null) =
        MeasuredHistoryCandidate(kind, origin, purpose, id, start, end, sample, parent, null, null, null, null)
    private fun wifi(key: MeasuredHistoryCandidate) = UnifiedHistoryRow(measuredHistoryPosition(key), key.endTimeMs,
        setOf(HistoryOrigin.valueOf(key.origin)), HistoryPurpose.valueOf(key.purpose), sections = emptyList(),
        measurement = HistoryMeasurement.Wifi(WifiObservedNetwork(key.startTimeMs, 0, "ap", "Cafe", 2412, -45)))
    private suspend fun annotation(id: String, from: Long, to: Long, hidden: Boolean = false, encoded: String? = null) {
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(id, 1, 0, 0, null,
            from, to, "UTC", id, null, hidden, encoded))
    }
    private suspend fun point(id: String, time: Long): Long {
        val sample = database.locationSampleDao().insert(LocationSample(timeMs = time, elapsedRealtimeNanos = 0,
            latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null, hAccM = null,
            vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "import", quality = SampleQuality.HIGH,
            motionState = null, policy = null, bucketId = null, createdAt = time))
        database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity(id, sample, true, "unknown"))
        return sample
    }
}
