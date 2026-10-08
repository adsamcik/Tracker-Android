package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room original/retry lookup; lifecycle rows are fixture-authored, no provider is activated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedSessionCutoffRoomTest {
    private lateinit var database: AppDatabase
    @Before fun setup() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
    @After fun close() = database.close()

    @Test fun `original Stop retains its clock after a retry copied its times into a new boot`() = runBlocking {
        val session = session()
        install(session)
        insertRetry()
        val cutoff = database.withTransaction { database.savedSessionCutoff(session) }
        assertEquals(SavedSessionCutoff("measurement-boot", 500), cutoff)
        assertEquals(500L, cutoff.endForBoot("measurement-boot"))
        assertEquals(Long.MAX_VALUE, cutoff.endForBoot("retry-boot"))
        assertFalse(cutoff.clockUnknown)
        Unit
    }

    @Test fun `missing original Stop cannot promote the retry to original clock`() = runBlocking {
        val session = session()
        install(session)
        insertRetry()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM session_lifecycle_intent_version WHERE logical_tracking_id = ? AND intent_revision = 2",
            arrayOf<Any>(session.logicalTrackingId))
        val cutoff = database.withTransaction { database.savedSessionCutoff(session) }
        assertNull(cutoff.bootId)
        assertTrue(cutoff.clockUnknown)
        assertEquals(Long.MAX_VALUE, cutoff.endForBoot("retry-boot"))
        assertEquals(450L, cutoff.retainedEnd("measurement-boot", 450))
        Unit
    }

    @Test fun `missing predecessor and corrupt original checksum keep cutoff clock unknown`() = runBlocking {
        val session = session()
        install(session)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE session_lifecycle_intent_version SET intent_checksum = ? WHERE logical_tracking_id = ? AND intent_revision = 2",
            arrayOf<Any>("0".repeat(64), session.logicalTrackingId))
        assertTrue(database.withTransaction { database.savedSessionCutoff(session) }.clockUnknown)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM session_lifecycle_intent_version WHERE logical_tracking_id = ? AND intent_revision = 1",
            arrayOf<Any>(session.logicalTrackingId))
        assertTrue(database.withTransaction { database.savedSessionCutoff(session) }.clockUnknown)
        Unit
    }

    @Test fun `retained timeline follows immutable prepared clock while physical admission remains exact`() = runBlocking {
        val session = session()
        install(session)
        val run = SourceServiceRunEntity("run", session.logicalTrackingId, "FINALIZED", 1, 1, 0,
            1_000, 100, 2_000, "USER_STOP", bootId = "retry-boot", leaseGeneration = 3,
            startOrigin = "MANUAL_FOREGROUND_START", runRevision = 3, startDeliveryToken = "delivery",
            startCommandGeneration = 1, preparedManifestRevision = 1, preparedIntentRevision = 1)
        database.sourceSessionDao().insertServiceRun(run)
        val saved = requireNotNull(database.sourceSessionDao().serviceRun("run"))
        val manifests = database.sourceSessionDao().manifestsForServiceRun("run")
        assertTrue(SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(saved, manifests))
        assertFalse(SessionManifestIntegrity.hasValidServiceRunTimeline(saved, manifests))
        assertTrue(SessionManifestIntegrity.hasRetainedLeaseGeneration(saved, 1))
        assertTrue(SessionManifestIntegrity.hasRetainedLeaseGeneration(saved, 3))
        assertFalse(SessionManifestIntegrity.hasRetainedLeaseGeneration(saved, 4))
        assertFalse(SessionManifestIntegrity.hasRetainedLeaseGeneration(saved, 0))
        assertFalse(SessionManifestIntegrity.hasRetainedLeaseGeneration(saved, null))
        assertFalse(SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(saved,
            manifests.map { it.copy(serviceRunId = "foreign") }))
        Unit
    }

    @Test fun `cutoff fields and inactive predecessor cannot relabel original Stop`() = runBlocking {
        val session = session()
        install(session)
        assertTrue(database.withTransaction { database.savedSessionCutoff(session.copy(cutoffAtMs = 2_001)) }.clockUnknown)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE session_lifecycle_intent_version SET desired_state = 'FINALIZED' WHERE logical_tracking_id = ? AND intent_revision = 1",
            arrayOf<Any>(session.logicalTrackingId))
        assertTrue(database.withTransaction { database.savedSessionCutoff(session) }.clockUnknown)
        Unit
    }

    @Test fun `shared lifecycle encoder preserves its existing separators and null byte contract`() {
        assertEquals("5493a7dce0ce7b1b11fa5c2b00512d436686bd7cd930085fb7e3510431be4a7f", SessionLifecycleIntegrity.checksum("a", listOf("b", null), null))
    }

    @Test fun `original Stop mutation invalidates actual shared tracking History dependency flow`() = kotlinx.coroutines.test.runTest {
        val session = session()
        install(session)
        val initial = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observer = async(kotlinx.coroutines.Dispatchers.Unconfined) {
            database.invalidationTracker.createFlow(*TRACKING_HISTORY_INVALIDATION_TABLES.toTypedArray(),
                emitInitialState = true).map { tables -> initial.complete(Unit); tables }.take(2).toList()
        }
        try {
            kotlinx.coroutines.withTimeout(5_000L) { initial.await() }
            database.openHelper.writableDatabase.execSQL(
                "UPDATE session_lifecycle_intent_version SET intent_checksum = ? WHERE logical_tracking_id = ? AND intent_revision = 2",
                arrayOf<Any>("0".repeat(64), session.logicalTrackingId))
            database.invalidationTracker.refreshAsync()
            val emissions = kotlinx.coroutines.withTimeout(5_000L) { observer.await() }
            assertTrue("session_lifecycle_intent_version" in emissions.last())
        } finally { observer.cancel() }
    }

    private fun session() = LogicalTrackingSessionEntity("logical", "FINALIZED", 3, 1, 1,
        "MANUAL_FOREGROUND_START", "measurement-boot", 1_000, 100, 2_000, 500, 2_000, 1, null,
        currentManifestRevision = 1, currentIntentRevision = 3, lifecycleLeaseGeneration = 3,
        lifecycleBootId = "retry-boot")

    private suspend fun install(session: LogicalTrackingSessionEntity) {
        database.sourceSessionDao().insertSession(session)
        val member = SessionManifestSourceEntity(session.logicalTrackingId, 1, 1, "SESSION_CAPTURE", 0, true, 1)
        val original = SessionManifestVersionEntity(session.logicalTrackingId, 1, "run", "MANUAL", 1, 1, 1,
            "MANUAL_FOREGROUND_START", "measurement-boot", 100, 1_000, "UTC", null, "START", "")
        val manifest = original.copy(manifestChecksum = SessionManifestIntegrity.compute(original, listOf(member)))
        database.sourceSessionDao().insertManifest(manifest)
        database.sourceSessionDao().insertManifestSources(listOf(member))
        database.installOriginalStopFixture(session, manifest)
    }

    private suspend fun insertRetry() {
        val original = requireNotNull(database.sourceSessionDao().firstStopIntent("logical"))
        val retry = original.copy(intentRevision = 3, requestBootId = "retry-boot", stopDeadlineBootId = "retry-boot")
        database.sourceSessionDao().insertLifecycleIntent(retry.copy(intentChecksum = SessionLifecycleIntegrity.checksum(
            retry.logicalTrackingId, retry.intentRevision, retry.manifestRevision, retry.desiredState,
            retry.stopReason, retry.requestBootId, retry.requestedElapsedRealtimeNanos, retry.stopDeadlineElapsedRealtimeNanos)))
    }
}
