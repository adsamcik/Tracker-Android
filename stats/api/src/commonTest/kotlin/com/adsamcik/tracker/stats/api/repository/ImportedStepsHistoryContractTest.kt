package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class ImportedStepsHistoryContractTest {
	@Test
	fun importedMembershipNeverClaimsOriginalFullCaptureSelection() {
		val history = SessionHistory(1L, importedCapture(), setOf(HistorySource.STEPS), steps())
		assertFalse(history.capturesOnlySteps)
		assertEquals(HistoryAvailability.RETAINED_IMPORTED, history.steps.availability)
		assertFailsWith<IllegalArgumentException> {
			history.copy(qualifiedSources = setOf(HistorySource.LOCATION))
		}
	}

	@Test
	fun importedMemberNavigationRequiresDistinctPositivePhysicalIdentitiesAndExactEnvelope() {
		val member = ImportedStepsHistoryMember(1L, EpochMs(10L), EpochMs(20L), steps())
		val entry = ImportedStepsHistoryEntry(TrackingHistoryEntryKey("imported"), EpochMs(10L), EpochMs(20L), listOf(member))
		assertFailsWith<IllegalArgumentException> { member.copy(segmentId = 0L) }
		assertFailsWith<IllegalArgumentException> { entry.copy(physicalMembers = listOf(member, member)) }
		assertFailsWith<IllegalArgumentException> { entry.copy(startTime = EpochMs(9L)) }
		assertFailsWith<IllegalArgumentException> { entry.copy(physicalMembers = emptyList()) }
		assertFailsWith<IllegalArgumentException> {
			entry.copy(physicalMembers = (1L..65L).map { member.copy(segmentId = it) })
		}
	}

	private fun importedCapture() = HistoryCapture.ImportedSteps(listOf(ImportedStepsCaptureRevision(1L, EpochMs(10L))))
	private fun steps() = StepsHistory(
		0L, HistoryAvailability.RETAINED_IMPORTED, HistoryEvidence.ACTIVE,
		HistoryProductState.READY, StepsHistoryCoverage.COMPLETE,
	)
}
