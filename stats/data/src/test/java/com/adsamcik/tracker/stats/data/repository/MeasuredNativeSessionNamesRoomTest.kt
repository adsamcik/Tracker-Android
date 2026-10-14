package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room metadata queries; original session bounds are constructed fixtures. All NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredNativeSessionNamesRoomTest {
    private lateinit var database: AppDatabase
    @Before fun setUp() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
    @After fun close() = database.close()

    @Test fun `edited recording names contained points and overlapping original windows`() = runTest {
        seed(annotation(from = 1_300L, to = 1_600L))
        val names = read()
        val visible = database.organizationAnnotationDao().visible(null, null, 1, selectedAdapterKey = "logical:recording").single()
        visible.fromMs shouldBe 1_300L
        visible.toMs shouldBe 1_600L
        names.point("recording", 1_299L) shouldBe null
        names.point("recording", 1_300L) shouldBe "Walk"
        names.point("recording", 1_600L) shouldBe null
        names.window("recording", 1_350L, 1_500L) shouldBe "Walk"
        names.window("recording", 1_200L, 1_500L) shouldBe "Walk"
        names.window("recording", 1_500L, 1_700L) shouldBe "Walk"
    }

    @Test fun `missing edits reuse original recorded bounds without inventing a name or end`() = runTest {
        seed(annotation())
        val names = read()
        names.point("recording", 1_000L) shouldBe "Walk"
        names.point("recording", 2_000L) shouldBe null
        names.window("recording", 1_100L, 1_900L) shouldBe "Walk"
        names.point("other", 1_500L) shouldBe null
    }

    @Test fun `active open recording keeps its valid current name from the real start`() = runTest {
        seed(annotation(), session(state = "ACTIVE", cutoff = null))
        val names = read()
        names.point("recording", 999L) shouldBe null
        names.point("recording", 4_000L) shouldBe "Walk"
        names.window("recording", 1_500L, 4_000L) shouldBe "Walk"
    }

    @Test fun `a stopping cutoff bounds the name even before finalization`() = runTest {
        seed(annotation(), session(state = "STOPPING", cutoff = 1_600L))
        val names = read()
        names.point("recording", 1_599L) shouldBe "Walk"
        names.point("recording", 1_600L) shouldBe null
        names.window("recording", 1_500L, 1_700L) shouldBe "Walk"
    }

    @Test fun `hidden malformed or absent metadata supplies no recording name`() = runTest {
        seed(annotation(hidden = true))
        read().point("recording", 1_500L) shouldBe null
        database.organizationAnnotationDao().update(annotation(from = 1_600L, to = 1_300L)) shouldBe 1
        read().point("recording", 1_500L) shouldBe null
        database.organizationAnnotationDao().update(annotation(name = "")) shouldBe 1
        read().point("recording", 1_500L) shouldBe null
        MeasuredNativeSessionNames.load(database, listOf("missing")).point("missing", 1_500L) shouldBe null
    }

    @Test fun `overlapping windows retain their original endpoints and zero-length points use measured time`() = runTest {
        seed(annotation())
        val names = read()
        names.window("recording", 1_500L, 2_000L) shouldBe "Walk"
        names.window("recording", 2_000L, 2_000L) shouldBe null
        names.window("recording", 1_500L, 1_500L) shouldBe "Walk"
        names.window("recording", 1_000L, 1_500L) shouldBe "Walk"
        names.window("recording", 2_000L, 2_200L) shouldBe null
        names.window("recording", 1_500L, 1_400L) shouldBe "Walk"
    }

    private suspend fun read() = database.withTransaction { MeasuredNativeSessionNames.load(database, listOf("recording")) }
    private suspend fun seed(annotation: OrganizationAnnotationEntity, session: LogicalTrackingSessionEntity = session()) {
        database.sourceSessionDao().insertSession(session)
        database.organizationAnnotationDao().insert(annotation)
    }
    private fun annotation(from: Long? = null, to: Long? = null, hidden: Boolean = false, name: String? = "Walk") =
        OrganizationAnnotationEntity("edit", 1L, 1L, 1L, "logical:recording", from, to, "UTC", name, null, hidden)
    private fun session(state: String = "FINALIZED", cutoff: Long? = 2_000L) = LogicalTrackingSessionEntity(
        logicalTrackingId = "recording", state = state, lifecycleRevision = 1L, desiredPlanRevision = 1L,
        rolloutRevision = 0L, startOrigin = "MANUAL", clockDomainId = "fixture-boot", startedAtMs = 1_000L,
        startedElapsedNanos = 1_000_000L, cutoffAtMs = cutoff, cutoffElapsedNanos = cutoff?.times(1_000L),
        completedAtMs = cutoff, finalAdmissionOrdinal = null, failureCode = null)
}
