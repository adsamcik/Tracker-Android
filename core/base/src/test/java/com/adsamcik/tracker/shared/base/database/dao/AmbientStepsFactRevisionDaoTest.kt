package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsFactRevisionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: AmbientStepsFactRevisionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.ambientStepsFactRevisionDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `latest day and range reads retain covered zero and suppress redacted revisions`() = runTest {
		val zero = providerFact(1_000L, 2_000L, stepCount = 0L)
		val positive = providerFact(3_000L, 4_000L, stepCount = 8L)
		dao.insert(zero) shouldBe 1L
		dao.insert(positive) shouldBe 2L

		dao.latestEffectiveForDay(WRITER_ID, WRITER_VERSION, 0L, "UTC") shouldContainExactly
			listOf(zero, positive)
		dao.latestEffectiveOverlapping(WRITER_ID, WRITER_VERSION, 1_500L, 3_500L, 10) shouldContainExactly
			listOf(zero, positive)
		dao.discoverStructuralDays(WRITER_ID, WRITER_VERSION, 10) shouldContainExactly listOf(
			AmbientStepsStructuralDayRow(0L, "UTC", 0L, 86_400_000L, 4_000L),
		)
		dao.latestEffectiveForStructuralDayRange(
			WRITER_ID,
			WRITER_VERSION,
			0L,
			0L,
			10,
		) shouldContainExactly listOf(zero, positive)

		val correctedUnsigned = positive.copy(
			semanticRevision = 2L,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				positive.logicalFactId,
				2L,
				AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			),
			stepCount = 10L,
			observedAtMs = 5_000L,
			appliedAtMs = 5_000L,
		)
		val corrected = signed(correctedUnsigned)
		dao.insert(corrected) shouldBe 3L
		dao.latest(WRITER_ID, WRITER_VERSION, positive.logicalFactId) shouldBe corrected
		dao.revisions(WRITER_ID, WRITER_VERSION, positive.logicalFactId) shouldContainExactly
			listOf(positive, corrected)

		val deleted = retraction(zero, semanticRevision = 2L)
		dao.insert(deleted) shouldBe 4L
		dao.latest(WRITER_ID, WRITER_VERSION, zero.logicalFactId) shouldBe deleted
		dao.latestEffectiveForDay(WRITER_ID, WRITER_VERSION, 0L, "UTC") shouldContainExactly
			listOf(corrected)
		dao.discoverStructuralDays(WRITER_ID, WRITER_VERSION, 10) shouldContainExactly listOf(
			AmbientStepsStructuralDayRow(0L, "UTC", 0L, 86_400_000L, 4_000L),
		)
		dao.insert(deleted) shouldBe -1L
	}

	@Test
	fun `full clear removes provider facts and retained retractions together`() = runTest {
		val fact = providerFact(1_000L, 2_000L, stepCount = 1L)
		dao.insert(fact)
		dao.insert(retraction(fact, semanticRevision = 2L))

		dao.deleteAll()

		dao.countAll() shouldBe 0L
		dao.revisions(WRITER_ID, WRITER_VERSION, fact.logicalFactId).shouldBeEmpty()
	}

	@Test
	fun `structural day keyset page continues after the last accepted row`() = runTest {
		val day0 = providerFact(1_000L, 2_000L, 1L)
		val day1 = providerFact(86_401_000L, 86_402_000L, 2L, epochDay = 1L)
		val day2 = providerFact(172_801_000L, 172_802_000L, 3L, epochDay = 2L)
		listOf(day0, day1, day2).forEach { dao.insert(it) }

		val first = dao.discoverStructuralDayPage(
			WRITER_ID, WRITER_VERSION, null, null, null, limit = 2,
		)
		first shouldContainExactly listOf(
			AmbientStepsStructuralDayRow(2L, "UTC", 172_800_000L, 259_200_000L, 172_802_000L),
			AmbientStepsStructuralDayRow(1L, "UTC", 86_400_000L, 172_800_000L, 86_402_000L),
		)
		val tail = first.last()
		dao.discoverStructuralDayPage(
			WRITER_ID,
			WRITER_VERSION,
			tail.latestWindowEndTimeMs,
			tail.structuralEpochDay,
			tail.storedZoneId,
			limit = 2,
		) shouldContainExactly listOf(
			AmbientStepsStructuralDayRow(0L, "UTC", 0L, 86_400_000L, 2_000L),
		)
	}

	@Test
	fun `maintenance audit pages and exact lineage deletion stay bounded`() = runTest {
		val first = providerFact(1_000L, 2_000L, 1L)
		val second = providerFact(3_000L, 4_000L, 2L)
		val correctedUnsigned = first.copy(
			semanticRevision = 2L,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				first.logicalFactId,
				2L,
				AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			),
			stepCount = 3L,
			observedAtMs = 5_000L,
			appliedAtMs = 5_000L,
		)
		val corrected = signed(correctedUnsigned)
		listOf(first, corrected, second).forEach { dao.insert(it) }
		val ordered = listOf(first, corrected, second).sortedWith(
			compareBy<AmbientStepsFactRevisionEntity>(
				AmbientStepsFactRevisionEntity::logicalFactId,
				AmbientStepsFactRevisionEntity::semanticRevision,
			),
		)

		val page = dao.maintenanceRevisionPage(WRITER_ID, WRITER_VERSION, null, null, 2)
		page shouldContainExactly ordered.take(2)
		dao.maintenanceRevisionPage(
			WRITER_ID,
			WRITER_VERSION,
			page.last().logicalFactId,
			page.last().semanticRevision,
			2,
		) shouldContainExactly ordered.drop(2)

		dao.deleteExactLineages(WRITER_ID, WRITER_VERSION, listOf(first.logicalFactId)) shouldBe 2
		dao.countUpserts() shouldBe 1L
		val redaction = retraction(second, semanticRevision = 2L)
		dao.insert(redaction)
		dao.deleteUpsertsForLogicalFacts(
			WRITER_ID,
			WRITER_VERSION,
			listOf(second.logicalFactId),
		) shouldBe 1
		dao.revisions(WRITER_ID, WRITER_VERSION, second.logicalFactId) shouldContainExactly
			listOf(redaction)
	}

	private fun providerFact(
		startTimeMs: Long,
		endTimeMs: Long,
		stepCount: Long,
		epochDay: Long = 0L,
	): AmbientStepsFactRevisionEntity {
		val dayStartTimeMs = epochDay * 86_400_000L
		val dayEndTimeMs = dayStartTimeMs + 86_400_000L
		val provider = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			provider,
			1L,
			1L,
			"ambient-instance",
			startTimeMs,
			epochDay,
			"UTC",
			7L,
		)
		return signed(
			AmbientStepsFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = 1L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					logicalFactId,
					1L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				writerId = WRITER_ID,
				writerVersion = WRITER_VERSION,
				writerOwnerGeneration = 1L,
				operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
				provider = provider,
				registrationGeneration = 1L,
				continuitySegmentGeneration = 1L,
				sourceInstanceId = "ambient-instance",
				authorizationRevision = 1L,
				authorizationFingerprint = "a".repeat(64),
				windowStartTimeMs = startTimeMs,
				windowEndTimeMs = endTimeMs,
				observedAtMs = endTimeMs,
				structuralEpochDay = epochDay,
				storedZoneId = "UTC",
				structuralDayStartTimeMs = dayStartTimeMs,
				structuralDayEndTimeMs = dayEndTimeMs,
				stepCount = stepCount,
				purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
				sourcePolicyRevision = 1L,
				ambientConsentEpoch = 1L,
				collectedDataEpoch = 7L,
				scopeDeletionGeneration = 0L,
				effectChecksum = "0".repeat(64),
				appliedAtMs = endTimeMs,
				retentionScope = "LIVE_AMBIENT",
				retentionPolicyId = "test-retention",
				retentionApprovalRevision = 1L,
			),
		)
	}

	private fun retraction(
		fact: AmbientStepsFactRevisionEntity,
		semanticRevision: Long,
	): AmbientStepsFactRevisionEntity = signed(
		fact.copy(
			semanticRevision = semanticRevision,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				fact.logicalFactId,
				semanticRevision,
				AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			),
			operation = AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			provider = null,
			registrationGeneration = null,
			continuitySegmentGeneration = null,
			sourceInstanceId = null,
			authorizationRevision = null,
			authorizationFingerprint = null,
			windowStartTimeMs = null,
			windowEndTimeMs = null,
			observedAtMs = null,
			structuralEpochDay = null,
			storedZoneId = null,
			structuralDayStartTimeMs = null,
			structuralDayEndTimeMs = null,
			stepCount = null,
			sourcePolicyRevision = null,
			ambientConsentEpoch = null,
			scopeDeletionGeneration = 1L,
			appliedAtMs = 5_000L,
			retentionScope = null,
			retentionPolicyId = null,
			retentionApprovalRevision = null,
		),
	)

	private fun signed(fact: AmbientStepsFactRevisionEntity): AmbientStepsFactRevisionEntity =
		fact.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(fact))

	private companion object {
		const val WRITER_ID = AmbientStepsFactRevisionEntity.WRITER_ID
		const val WRITER_VERSION = AmbientStepsFactRevisionEntity.WRITER_VERSION
	}
}
