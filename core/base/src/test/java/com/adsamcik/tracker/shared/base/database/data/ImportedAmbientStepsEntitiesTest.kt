package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Test

class ImportedAmbientStepsEntitiesTest {
	@Test
	fun `structural DST day and covered zero retain exact portable authority`() {
		val day = day(LocalDate.of(2026, 10, 25), 0L)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))
		val stored = ImportedAmbientStepsDayRevisionEntity(
			dayIdentity = day.identity.value,
			importRevision = 1L,
			supersedesImportRevision = null,
			archiveIdentity = archive.identity.value,
			dayContentChecksum = day.contentChecksum.value,
			deletionScopeIdentity = day.deletionScopeIdentity.value,
			structuralEpochDay = day.structuralEpochDay,
			storedZoneId = day.storedZoneId,
			structuralDayStartTimeMs = day.structuralDayStartTimeMs,
			structuralDayEndTimeMs = day.structuralDayEndTimeMs,
			retainedFromTimeMs = null,
			coverage = day.coverage.name,
			partialCauses = ImportedAmbientStepsIdentity.encodePartialCauses(day.partialCauses),
			retainedStepCount = 0L,
			factCount = 1,
			gapCount = 0,
			collectedDataEpoch = 7L,
			receivedAtMs = day.structuralDayEndTimeMs,
		)

		stored.structuralDayEndTimeMs - stored.structuralDayStartTimeMs shouldBe 25L * 60L * 60L * 1_000L
		stored.retainedStepCount shouldBe 0L
	}

	@Test
	fun `retention fence checksum authenticates the complete protected owner set`() {
		val day = day(LocalDate.of(2026, 3, 29), 5L)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))
		val markers = listOf(
			ImportedAmbientStepsProtectedIdentityEntity(
				archive.identity.value,
				day.identity.value,
				ImportedAmbientStepsProtectedIdentityEntity.ARCHIVE,
			),
			ImportedAmbientStepsProtectedIdentityEntity(
				day.identity.value,
				day.identity.value,
				ImportedAmbientStepsProtectedIdentityEntity.DAY,
			),
			ImportedAmbientStepsProtectedIdentityEntity(
				day.deletionScopeIdentity.value,
				day.identity.value,
				ImportedAmbientStepsProtectedIdentityEntity.DELETION_SCOPE,
			),
			ImportedAmbientStepsProtectedIdentityEntity(
				day.facts.single().identity.value,
				day.identity.value,
				ImportedAmbientStepsProtectedIdentityEntity.FACT,
			),
		)
		val fence = ImportedAmbientStepsDayFenceEntity.create(
			dayIdentity = day.identity.value,
			deletionScopeIdentity = day.deletionScopeIdentity.value,
			fenceKind = ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION,
			collectedDataEpoch = 7L,
			sourceEvidenceRevision = 3L,
			fencedAtMs = day.structuralDayEndTimeMs + 1L,
			retainedFromMs = day.structuralDayEndTimeMs,
			latestImportRevision = 1L,
			latestContentChecksum = day.contentChecksum.value,
			structuralEpochDay = day.structuralEpochDay,
			storedZoneId = day.storedZoneId,
			structuralDayStartTimeMs = day.structuralDayStartTimeMs,
			structuralDayEndTimeMs = day.structuralDayEndTimeMs,
			revisionCount = 1,
			archiveCount = 1,
			factRowCount = 1,
			gapRowCount = 0,
			protectedIdentities = markers,
			lineageChecksum = archive.contentChecksum.value,
		)

		shouldThrow<IllegalArgumentException> {
			fence.copy(latestImportRevision = 2L)
		}
	}

	@Test
	fun `partial cause encoding is canonical and rejects duplicate provenance`() {
		val encoded = ImportedAmbientStepsIdentity.encodePartialCauses(
			listOf(
				PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY,
				PortableAmbientStepsPartialCause.EXPLICIT_GAP,
			),
		)
		ImportedAmbientStepsIdentity.decodePartialCauses(encoded) shouldBe listOf(
			PortableAmbientStepsPartialCause.EXPLICIT_GAP,
			PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY,
		)
		shouldThrow<IllegalArgumentException> {
			ImportedAmbientStepsIdentity.decodePartialCauses(
				"OUTSIDE_AUTHORITY,OUTSIDE_AUTHORITY",
			)
		}
	}

	private fun day(date: LocalDate, count: Long): PortableAmbientStepsDayV1 {
		val zone = ZoneId.of("Europe/Prague")
		val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"fact-${date.toEpochDay()}",
			),
			start,
			end,
			count,
		)
		return PortableAmbientStepsDayV1.create(
			identity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"${date.toEpochDay()}|Europe/Prague|$start|$end",
			),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = zone.id,
			structuralDayStartTimeMs = start,
			structuralDayEndTimeMs = end,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = count,
			facts = listOf(fact),
			gaps = emptyList(),
		)
	}
}
