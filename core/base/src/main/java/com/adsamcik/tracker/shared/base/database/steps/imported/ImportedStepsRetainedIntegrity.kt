package com.adsamcik.tracker.shared.base.database.steps.imported

import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import java.security.MessageDigest

/** Per-member admission receipt, independent of deleted sibling payload; not a second wire format. */
object ImportedStepsRetainedIntegrity {
	/** Uses the existing wire canonicalizer, then binds the original entry and exact local receipt. */
	fun runChecksum(
		entry: ImportedStepsEntryEntity,
		run: ImportedStepsRunEntity,
		portable: PortableStepsRunV1,
	): String {
		require(run.entryIdentity == entry.identity && run.identity == portable.identity.value)
		val memberChecksum = PortableStepsEntryV1.create(
			identity = PortableStepsOpaqueIdentity(entry.identity),
			sessionMode = PortableStepsSessionMode.valueOf(entry.sessionMode),
			startTimeMs = portable.startTimeMs,
			endTimeMs = portable.endTimeMs,
			runs = listOf(portable),
		).contentChecksum.value
		val values = listOf(
			"steps-imported-retained-member-v1", entry.identity, entry.contentChecksum, entry.sessionMode,
			entry.startTimeMs, entry.endTimeMs, entry.collectedDataEpoch, entry.writerOwnerGeneration,
			run.identity, run.entryIdentity, run.sessionSegmentId, memberChecksum,
		)
		val canonical = values.joinToString("") { value ->
			val text = value?.toString()
			if (text == null) { "-1:" } else { "${text.length}:$text" }
		}
		return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}
