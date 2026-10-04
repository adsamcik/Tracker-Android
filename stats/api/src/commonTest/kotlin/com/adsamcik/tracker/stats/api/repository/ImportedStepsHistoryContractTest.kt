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
	fun importedMemberNavigationRequiresDistinctPositivePhysicalIdentitiesInsideOriginalEnvelope() {
		val member = ImportedStepsHistoryMember(1L, EpochMs(10L), EpochMs(20L), steps())
		val entry = ImportedStepsHistoryEntry(TrackingHistoryEntryKey("imported"), EpochMs(10L), EpochMs(20L), listOf(member))
		assertFailsWith<IllegalArgumentException> { member.copy(segmentId = 0L) }
		assertFailsWith<IllegalArgumentException> { entry.copy(physicalMembers = listOf(member, member)) }
		assertEquals(EpochMs(9L), entry.copy(startTime = EpochMs(9L)).startTime)
		assertEquals(EpochMs(30L), entry.copy(endTime = EpochMs(30L)).endTime)
		assertFailsWith<IllegalArgumentException> { entry.copy(startTime = EpochMs(11L)) }
		assertFailsWith<IllegalArgumentException> { entry.copy(endTime = EpochMs(19L)) }
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
