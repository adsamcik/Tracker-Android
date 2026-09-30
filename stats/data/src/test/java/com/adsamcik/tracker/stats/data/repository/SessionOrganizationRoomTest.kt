package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.stats.api.repository.OrganizationEditProblem
import com.adsamcik.tracker.stats.api.repository.OrganizationInterval
import com.adsamcik.tracker.stats.api.repository.OrganizationMutationResult
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored Room regressions. IMPLEMENTED_UNVALIDATED: no host/device result is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOrganizationRoomTest {
	private lateinit var db: AppDatabase
	private lateinit var repository: RoomSessionOrganizationRepository
	private val lifecycle = OrganizationTestLifecycle()
	private val clock = FixedClock(10_000L)
	@Before fun setup() {
		val context: Application = ApplicationProvider.getApplicationContext()
		db = AppDatabase.testDatabase(context)
		repository = RoomSessionOrganizationRepository(db, context, clock, lifecycle,
			RetentionAuthorityOperationLease(), Dispatchers.IO)
	}
	@After fun close() = db.close()

	@Test fun `empty install supports overlapping retrospective groups without acquisition or metrics`() = runTest {
		repository.create(0, OrganizationInterval(100, 200), "Europe/Prague", "  Morning  ", null) shouldBe OrganizationMutationResult.Saved
		repository.create(0, OrganizationInterval(150, 250), "UTC", null, null) shouldBe OrganizationMutationResult.Saved
		content().sessions.map { it.name }.toSet() shouldBe setOf("Morning", null)
		listOf("logical_tracking_session", "source_service_run", "session_segment", "source_event_wal").forEach { table ->
			// Annotation writes do not authorize acquisition or copy source facts.
			count(table) shouldBe 0L
		}
		repository.read(200, 251).let { (it as SessionOrganizationRead.Content).sessions.size } shouldBe 1
	}

	@Test fun `first custom label remains reference data and can be used before tracking`() = runTest {
		val label = db.activityDao().insert(SessionActivity(name = "Dog walk"))
		repository.create(0, OrganizationInterval(100, 200), "UTC", "Walk", label) shouldBe OrganizationMutationResult.Saved
		content().sessions.single().activityLabelName shouldBe "Dog walk"
		db.activityDao().delete(label)
		val changed = content().sessions.single()
		changed.activityLabelId shouldBe null
		changed.revision shouldBe 2L
		count("session_segment") shouldBe 0L
	}

	@Test fun `closed recording inherits cutoff not drain completion and saved creation zone`() = runTest {
		guard()
		db.sourceSessionDao().insertSession(logical("FINALIZED"))
		db.sourceSessionDao().insertManifest(SessionManifestVersionEntity("logical", 1, "run", "MANUAL", 1, 1, 1,
			"USER", "boot", 100, 100, "Europe/Prague", null, "START", "fixture-metadata-only"))
		val session = content().sessions.single()
		session.toMs shouldBe 200L
		session.displayZoneId shouldBe "Europe/Prague"
		session.isActive shouldBe false
		repository.edit(session, null, "UTC", "Named", -2L) shouldBe OrganizationMutationResult.Saved
		content().sessions.single().fromMs shouldBe 100L
		content().sessions.single().toMs shouldBe 200L
		db.sourceSessionDao().session("logical")?.completedAtMs shouldBe 900L
	}

	@Test fun `live edits materialize metadata only and cannot move or remove recording`() = runTest {
		guard(); db.sourceSessionDao().insertSession(logical("ACTIVE").copy(cutoffAtMs = null, cutoffElapsedNanos = null))
		val selection = content().sessions.single()
		repository.edit(selection, null, selection.displayZoneId, "Live name", null) shouldBe OrganizationMutationResult.Saved
		val fresh = content().sessions.single()
		repository.edit(fresh, OrganizationInterval(100, 200), fresh.displayZoneId, null, null) shouldBe rejected(OrganizationEditProblem.END_SESSION_FIRST)
		repository.remove(fresh) shouldBe rejected(OrganizationEditProblem.END_SESSION_FIRST)
		count("logical_tracking_session") shouldBe 1L
		count("organization_annotation") shouldBe 1L
		db.sourceSessionDao().session("logical")?.state shouldBe "ACTIVE"
	}

	@Test fun `moving override suppresses original before range selection and removal preserves recording`() = runTest {
		guard(); val id = segment()
		val original = content().sessions.single()
		repository.edit(original, OrganizationInterval(300, 400), "UTC", "Moved", null) shouldBe OrganizationMutationResult.Saved
		(repository.read(100, 200) as SessionOrganizationRead.Content).sessions shouldBe emptyList()
		val moved = (repository.read(300, 400) as SessionOrganizationRead.Content).sessions.single()
		moved.id shouldBe original.id
		repository.remove(moved) shouldBe OrganizationMutationResult.Saved
		content().sessions shouldBe emptyList()
		db.sessionSegmentDao().getById(id)?.steps shouldBe 25
		val hidden = db.organizationAnnotationDao().byAdapter("segment:$id")!!
		hidden.hidden shouldBe true
		listOf(hidden.name, hidden.startTimeMs, hidden.endTimeMs, hidden.displayZoneId, hidden.activityLabelId).all { it == null } shouldBe true
	}

	@Test fun `orphan linked metadata remains editable and removes without leaving a tombstone`() = runTest {
		guard(); val id = segment()
		repository.edit(content().sessions.single(), OrganizationInterval(100, 200), "UTC", "Keep", null)
		db.sessionSegmentDao().delete(db.sessionSegmentDao().getById(id)!!)
		val orphan = content().sessions.single()
		repository.edit(orphan, OrganizationInterval(200, 300), "UTC", "Still here", null) shouldBe OrganizationMutationResult.Saved
		repository.remove(content().sessions.single()) shouldBe OrganizationMutationResult.Saved
		count("organization_annotation") shouldBe 0L
	}

	@Test fun `optimistic revision and epoch checks reject stale edits without resurrection`() = runTest {
		repository.create(0, OrganizationInterval(100, 200), "UTC", "Original", null)
		val old = content().sessions.single()
		repository.edit(old, null, "UTC", "New", null) shouldBe OrganizationMutationResult.Saved
		repository.edit(old, null, "UTC", "Stale", null) shouldBe rejected(OrganizationEditProblem.STALE)
		repository.remove(old) shouldBe rejected(OrganizationEditProblem.STALE)
		lifecycle.beginFullDeletion(1_000)
		// Matching guard installed by parent full-clear; actual row-clear test lives in core/base.
		db.openHelper.writableDatabase.execSQL("UPDATE source_evidence_state SET collected_data_epoch = 1")
		repository.edit(old, null, "UTC", "Resurrection", null) shouldBe rejected(OrganizationEditProblem.STALE)
		repository.create(0, OrganizationInterval(100, 200), "UTC", "Old draft", null) shouldBe rejected(OrganizationEditProblem.STALE)
	}

	@Test fun `clock uncertain recording is not silently repaired by name-only edit`() = runTest {
		guard(); db.sourceSessionDao().insertSession(logical("FINALIZED").copy(cutoffAtMs = 90))
		val selection = content().sessions.single()
		selection.timeUncertain shouldBe true
		selection.toMs shouldBe null
		repository.edit(selection, null, selection.displayZoneId, "Name only", null) shouldBe OrganizationMutationResult.Saved
		content().sessions.single().timeUncertain shouldBe true
		db.organizationAnnotationDao().byAdapter("logical:logical")?.endTimeMs shouldBe null
		repository.edit(content().sessions.single(), OrganizationInterval(100, 200), "UTC", "Corrected", null) shouldBe OrganizationMutationResult.Saved
		content().sessions.single().timeUncertain shouldBe false
		db.sourceSessionDao().session("logical")?.cutoffAtMs shouldBe 90L
	}

	@Test fun `input checks preserve previous values and Unicode limit counts code points`() = runTest {
		repository.create(0, OrganizationInterval(100, 100), "UTC", null, null) shouldBe rejected(OrganizationEditProblem.INVALID_INTERVAL)
		repository.create(0, OrganizationInterval(100, 10_001), "UTC", null, null) shouldBe rejected(OrganizationEditProblem.FUTURE_END)
		repository.create(0, OrganizationInterval(100, 200), "+01:00", null, null) shouldBe rejected(OrganizationEditProblem.INVALID_ZONE)
		repository.create(0, OrganizationInterval(100, 200), "UTC", null, 999) shouldBe rejected(OrganizationEditProblem.MISSING_ACTIVITY_LABEL)
		repository.create(0, OrganizationInterval(100, 200), "UTC", "😀".repeat(201), null) shouldBe rejected(OrganizationEditProblem.NAME_TOO_LONG)
		repository.create(0, OrganizationInterval(100, 200), "UTC", "😀".repeat(200), null) shouldBe OrganizationMutationResult.Saved
	}

	@Test fun `paging traverses dense tied groups once and prepend handles a larger refresh`() = runTest {
		repeat(125) { repository.create(0, OrganizationInterval(100, 200), "UTC", "Group $it", null) }
		val source = repository.pagingSource(null, null)
		val ids = mutableListOf<OrganizationSessionId>()
		var key: Int? = null
		do {
			val params = if (key == null) PagingSource.LoadParams.Refresh<Int>(null, 50, false)
				else PagingSource.LoadParams.Append(key!!, 50, false)
			val page = source.load(params) as PagingSource.LoadResult.Page<Int, OrganizedSession>
			ids += page.data.map { it.id }
			key = page.nextKey
		} while (key != null)
		ids.size shouldBe 125
		ids.distinct().size shouldBe 125
		val refresh = source.load(PagingSource.LoadParams.Refresh(50, 100, false)) as PagingSource.LoadResult.Page<Int, OrganizedSession>
		val prepend = source.load(PagingSource.LoadParams.Prepend(refresh.prevKey!!, 50, false)) as PagingSource.LoadResult.Page<Int, OrganizedSession>
		val mergedIds = (prepend.data + refresh.data).map { session -> session.id }
		mergedIds.toSet().size shouldBe 125
		source.invalidate()
	}

	@Test fun `exact editor lookup is independent of first page and metadata edits invalidate pages`() = runTest {
		repeat(125) { repository.create(0, OrganizationInterval(it.toLong() + 100, it.toLong() + 200), "UTC", "Group $it", null) }
		val oldest = db.organizationAnnotationDao().visible(null, null, 1, 124).single()
		val id = OrganizationSessionId.Annotation(oldest.localId!!)
		content().sessions.none { it.id == id } shouldBe true
		val selected = (repository.find(id) as SessionOrganizationRead.Content).sessions.single()
		val source = repository.pagingSource(null, null)
		source.load(PagingSource.LoadParams.Refresh(null, 50, false))
		val invalidated = CompletableDeferred<Unit>()
		source.registerInvalidatedCallback { invalidated.complete(Unit) }
		repository.edit(selected, null, "UTC", "Changed outside first page", null) shouldBe OrganizationMutationResult.Saved
		db.invalidationTracker.refreshAsync()
		withContext(Dispatchers.IO) { withTimeout(5_000) { invalidated.await() } }
		source.invalid shouldBe true
		(repository.find(id) as SessionOrganizationRead.Content).sessions.single().name shouldBe "Changed outside first page"
		repository.edit(selected, null, "UTC", "Stale", null) shouldBe rejected(OrganizationEditProblem.STALE)
	}

	@Test fun `linked annotation cannot be reclassified as a free group by its internal local id`() = runTest {
		guard(); val segment = segment()
		repository.edit(content().sessions.single(), null, "UTC", "Linked", null)
		val linked = content().sessions.single()
		val local = db.organizationAnnotationDao().byAdapter("segment:$segment")!!.localId
		val wrongId = linked.copy(id = OrganizationSessionId.Annotation(local))
		(repository.find(wrongId.id) as SessionOrganizationRead.Content).sessions shouldBe emptyList()
		repository.edit(wrongId, null, "UTC", "Wrong namespace", null) shouldBe rejected(OrganizationEditProblem.NOT_FOUND)
		repository.remove(wrongId) shouldBe rejected(OrganizationEditProblem.NOT_FOUND)
		content().sessions.single().id shouldBe linked.id
	}

	@Test fun `imported exact selection survives interval name and zone edits without claiming nearby records`() = runTest {
		guard()
		val selectors = listOf(
			OrganizationSourceSelector.LocationFile("a".repeat(64)),
			OrganizationSourceSelector.CapturedEntry(HistorySource.WIFI, "b".repeat(64)),
			OrganizationSourceSelector.BackgroundStepsDay("sha256:" + "c".repeat(64)),
			OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.CELL, "d".repeat(64)),
		)
		imported(selectors, "Hike")
		val unrelatedSegment = segment()
		val session = (repository.find(OrganizationSessionId.Annotation("imported")) as SessionOrganizationRead.Content).sessions.single()
		session.importedSourceSelection shouldBe selectors
		session.activityLabelName shouldBe "Hike"
		session.activityLabelId shouldBe null
		repository.edit(session, OrganizationInterval(300, 400), "Europe/Prague", "New name", null) shouldBe OrganizationMutationResult.Saved
		val edited = (repository.find(session.id) as SessionOrganizationRead.Content).sessions.single()
		edited.fromMs shouldBe 300L
		edited.importedSourceSelection shouldBe selectors
		edited.activityLabelName shouldBe "Hike"
		db.sessionSegmentDao().getById(unrelatedSegment)?.steps shouldBe 25
		count("source_event_wal") shouldBe 0L
	}

	@Test fun `metadata only imported group stays different from a local interval and creates no global label`() = runTest {
		guard()
		imported(emptyList(), "Holiday")
		repository.create(0, OrganizationInterval(100, 200), "UTC", "Local", null) shouldBe OrganizationMutationResult.Saved
		val sessions = content().sessions
		sessions.single { it.id == OrganizationSessionId.Annotation("imported") }.importedSourceSelection shouldBe emptyList()
		sessions.single { it.name == "Local" }.importedSourceSelection shouldBe null
		db.activityDao().getAllUser() shouldBe emptyList()
		count("source_service_run") shouldBe 0L
	}

	@Test fun `malformed or unknown stored selection is unavailable rather than interval grouping`() = runTest {
		guard()
		imported(emptyList())
		listOf("not json", "null", "[{\"kind\":\"future\",\"source\":\"WIFI\",\"identity\":\"${"a".repeat(64)}\"}]")
			.forEach { malformed ->
				val row = db.organizationAnnotationDao().byId("imported")!!
				db.organizationAnnotationDao().update(row.copy(importedSourceSelection = malformed)) shouldBe 1
				repository.find(OrganizationSessionId.Annotation("imported")) shouldBe SessionOrganizationRead.Unavailable
			}
	}

	@Test fun `choosing a local activity replaces descriptive import text and removal preserves source records`() = runTest {
		guard()
		imported(listOf(OrganizationSourceSelector.CapturedEntry(HistorySource.STEPS, "a".repeat(64))), "Walking")
		val segment = segment()
		val localActivity = db.activityDao().insert(SessionActivity(name = "Dog walk"))
		val selected = (repository.find(OrganizationSessionId.Annotation("imported")) as SessionOrganizationRead.Content).sessions.single()
		repository.edit(selected, null, "UTC", null, localActivity) shouldBe OrganizationMutationResult.Saved
		val changed = (repository.find(selected.id) as SessionOrganizationRead.Content).sessions.single()
		changed.activityLabelName shouldBe "Dog walk"
		db.organizationAnnotationDao().byId("imported")?.importedActivityLabel shouldBe null
		repository.remove(changed) shouldBe OrganizationMutationResult.Saved
		db.sessionSegmentDao().getById(segment)?.steps shouldBe 25
	}

	private suspend fun imported(selectors: List<OrganizationSourceSelector>, label: String? = null) {
		db.organizationAnnotationDao().insert(OrganizationAnnotationEntity("imported", 1, 100, 100, null,
			100, 200, "UTC", "Imported", null, false,
			OrganizationSourceSelectionCodec.encode(selectors), label))
	}

	private suspend fun guard() { db.sourceEvidenceStateDao().ensure(SourceEvidenceState()) }
	private suspend fun content() = repository.read() as SessionOrganizationRead.Content
	private fun rejected(problem: OrganizationEditProblem) = OrganizationMutationResult.Rejected(problem)
	private suspend fun segment(): Long = db.sessionSegmentDao().insert(SessionSegment(startTimeMs = 100,
		endTimeMs = 200, distanceM = 50f, steps = 25, primaryActivity = 7, activityConfidence = 90,
		sampleCount = 5, source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = 100))
	private fun logical(state: String) = LogicalTrackingSessionEntity("logical", state, 1, 1, 1, "USER", "boot",
		100, 100, 200, 200, 900, null, null)
	private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
		check(it.moveToFirst()); it.getLong(0)
	}
}

private class OrganizationTestLifecycle : CollectedDataLifecycleStore {
	override val snapshots = MutableStateFlow(CollectedDataLifecycleSnapshot(0, null))
	override suspend fun snapshot() = snapshots.value
	override suspend fun beginFullDeletion(deletedAtMs: Long) = snapshots.value.copy(epoch = snapshots.value.epoch + 1)
		.also { snapshots.value = it }
	override suspend fun advanceRetainedFrom(retainedFromMs: Long) = snapshots.value.copy(retainedFromMs = retainedFromMs)
		.also { snapshots.value = it }
}
