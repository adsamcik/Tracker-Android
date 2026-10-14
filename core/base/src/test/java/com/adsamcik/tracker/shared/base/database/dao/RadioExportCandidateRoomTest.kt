package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Discovery-only fixtures exercise real Room queries; they grant no source payload authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioExportCandidateRoomTest {
	private lateinit var database: AppDatabase

	@Before fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After fun close() = database.close()

	@Test fun `source and dates exclude unrelated completed and active sessions before qualification`() = runTest {
		for (source in RADIO_SOURCES) {
			val prefix = source.toString()
			seed("$prefix:old", 100, 200, source)
			seed("$prefix:current", 1_100, 1_500, source)
			seed("$prefix:active", 1_100, 1_500, source, state = "ACTIVE")
			seed("$prefix:other-source", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_STEPS)
			page(source, 1_000, 2_000) shouldBe listOf("$prefix:current")
			page(source, null, null) shouldBe listOf("$prefix:current", "$prefix:old")
		}
	}

	@Test fun `restored unfinished radio recordings are candidates while ordinary failures remain excluded`() = runTest {
		for (source in RADIO_SOURCES) {
			seed("$source:restored", 1_100, 1_500, source, state = "FAILED")
			seed("$source:failed", 1_100, 1_500, source, state = "FAILED")
			database.openHelper.writableDatabase.execSQL(
				"UPDATE logical_tracking_session SET failure_code='RESTORED_INTERRUPTED_SESSION', completed_at_ms=NULL " +
					"WHERE logical_tracking_id=?", arrayOf("$source:restored"))
			page(source, 1_000, 2_000) shouldBe listOf("$source:restored")
		}
	}

	@Test fun `gap-only source intent keeps crossing runs and zero-length boundary events`() = runTest {
		for (source in RADIO_SOURCES) {
			val prefix = source.toString()
			seed("$prefix:crossing", 500, 1_500, source)
			seed("$prefix:at-from", 1_000, 1_000, source)
			seed("$prefix:ended-at-from", 500, 1_000, source)
			seed("$prefix:at-to", 2_000, 2_000, source)
			page(source, 1_000, 2_000) shouldBe listOf("$prefix:at-from", "$prefix:crossing")
		}
	}

	@Test fun `unknown missing reversed and malformed owner timing is never silently outside dates`() = runTest {
		for (source in RADIO_SOURCES) {
			val prefix = source.toString()
			seed("$prefix:missing", 100, 200, source, withRun = false)
			seed("$prefix:reversed", 100, 200, source)
			seed("$prefix:malformed", 100, 200, source)
			seed("$prefix:foreign-segment", 100, 200, source)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE session_segment SET end_time_ms = 50 WHERE logical_tracking_id = ?",
				arrayOf("$prefix:reversed"))
			database.openHelper.writableDatabase.execSQL(
				"UPDATE session_segment SET start_time_ms = 'unknown' WHERE logical_tracking_id = ?",
				arrayOf("$prefix:malformed"))
			database.openHelper.writableDatabase.execSQL(
				"UPDATE session_segment SET service_run_id = ? WHERE logical_tracking_id = ?",
				arrayOf("$prefix:another-owner", "$prefix:foreign-segment"))
			page(source, 1_000, 2_000) shouldBe listOf(
				"$prefix:foreign-segment", "$prefix:malformed", "$prefix:missing", "$prefix:reversed")
		}
	}

	@Test fun `more than one hundred keys page once in stable logical ID order without date widening`() = runTest {
		for (source in RADIO_SOURCES) {
			val expected = (0 until 205).map { "$source:inside:${it.toString().padStart(3, '0')}" }
			for (id in expected.reversed()) seed(id, 1_100, 1_500, source)
			seed("$source:outside", 100, 200, source)
			val seen = mutableListOf<String>()
			var after: String? = null
			while (true) {
				val values = page(source, 1_000, 2_000, after)
				if (values.isEmpty()) break
				seen += values
				after = values.last()
			}
			seen shouldBe expected
			seen.distinct().size shouldBe 205
		}
	}

	private suspend fun page(source: Int, from: Long?, to: Long?, after: String? = null) =
		if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			database.wifiCapturedFactDao().completedExportCandidateIdsPage(source, from, to, after, 100)
		} else {
			database.cellCapturedFactDao().completedExportCandidateIdsPage(source, from, to, after, 100)
		}

	private suspend fun seed(
		id: String, start: Long, end: Long, source: Int, state: String = "FINALIZED",
		withRun: Boolean = true,
	) {
		val dao = database.sourceSessionDao()
		dao.insertSession(LogicalTrackingSessionEntity(id, state, 1, 1, 1, "USER", "boot",
			start, 1, end, 2, end, 1, null))
		dao.saveCompleteness(SourceSessionCompletenessEntity(id, "run:$id", source, "provider:$id",
			1, 1, 1, true, "COMPLETE", "COMPLETE", null, null, end))
		if (!withRun) return
		val segment = database.sessionSegmentDao().insert(SessionSegment(
			startTimeMs = start, endTimeMs = end, distanceM = 0f, steps = null,
			primaryActivity = null, activityConfidence = null, sampleCount = 0,
			source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = end,
			logicalTrackingId = id, serviceRunId = "run:$id"))
		dao.insertServiceRun(SourceServiceRunEntity("run:$id", id, state, 1, 1, 0,
			start, 1, end, "COMPLETE", sessionSegmentId = segment))
	}

	private companion object {
		val RADIO_SOURCES = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL)
	}
}
