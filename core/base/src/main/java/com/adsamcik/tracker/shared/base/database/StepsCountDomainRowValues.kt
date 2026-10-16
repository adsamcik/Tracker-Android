package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity

internal fun StepsCountDomainReceiptEntity.bindArguments(): Array<Any?> = arrayOf(
	receiptIdentity,
	domainIdentity,
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	registrationGeneration,
	collectedDataEpoch,
	authorityRevision,
	authorityFingerprint,
	coverageKind,
	coverageVersion,
	countDomainVersion,
	effectChecksum,
	completionEvidenceChecksum,
)

internal fun StepsCountDomainOwnerRevisionEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	operation,
	receiptIdentity,
	ownerEffectChecksum,
	linkedAtMs,
)

internal fun StepsCountDomainCompletenessMarkerEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	ownerIdentity,
	ownerRevision,
	terminalState,
	lastAdmissionOrdinal,
	lastSourceSequence,
	providerFlushOutcome,
	registrationRemovalOutcome,
	registrationTimelineChecksum,
	evidenceChecksum,
)

internal fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion


internal fun SupportSQLiteDatabase.longForQuery(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	if (!cursor.moveToFirst()) {
		throw CountDomainStoredEvidenceException()
	}
	val value = cursor.requiredLong(0)
	if (cursor.moveToNext()) {
		throw CountDomainStoredEvidenceException()
	}
	value
}

internal fun android.database.Cursor.toStepsCountDomainOwner() =
	authenticatedCountDomainRow {
		StepsCountDomainOwnerRevisionEntity(
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			operation = requiredText("operation"),
			receiptIdentity = nullableText("receipt_identity"),
			ownerEffectChecksum = requiredText("owner_effect_checksum"),
			linkedAtMs = requiredLong("linked_at_ms"),
		)
	}

internal fun android.database.Cursor.toStepsCountDomainReceipt() =
	authenticatedCountDomainRow {
		StepsCountDomainReceiptEntity(
			receiptIdentity = requiredText("receipt_identity"),
			domainIdentity = requiredText("domain_identity"),
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			registrationGeneration = requiredLong("registration_generation"),
			collectedDataEpoch = requiredLong("collected_data_epoch"),
			authorityRevision = requiredLong("authority_revision"),
			authorityFingerprint = requiredText("authority_fingerprint"),
			coverageKind = requiredText("coverage_kind"),
			coverageVersion = requiredInt("coverage_version"),
			countDomainVersion = requiredInt("count_domain_version"),
			effectChecksum = requiredText("effect_checksum"),
			completionEvidenceChecksum = nullableText("completion_evidence_checksum"),
		)
	}

internal fun android.database.Cursor.toStepsCountDomainCompletenessMarker() =
	authenticatedCountDomainRow {
		StepsCountDomainCompletenessMarkerEntity(
			ownerKind = requiredText("owner_kind"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			terminalState = requiredText("terminal_state"),
			lastAdmissionOrdinal = nullableLong("last_admission_ordinal"),
			lastSourceSequence = nullableLong("last_source_sequence"),
			providerFlushOutcome = requiredText("provider_flush_outcome"),
			registrationRemovalOutcome = requiredText("registration_removal_outcome"),
			registrationTimelineChecksum = requiredText("registration_timeline_checksum"),
			evidenceChecksum = requiredText("evidence_checksum"),
		)
	}

private inline fun <T> authenticatedCountDomainRow(block: () -> T): T =
	try {
		block()
	} catch (error: CountDomainStoredEvidenceException) {
		throw error
	} catch (error: IllegalArgumentException) {
		throw CountDomainStoredEvidenceException(error)
	} catch (error: IllegalStateException) {
		throw CountDomainStoredEvidenceException(error)
	}

internal fun android.database.Cursor.requiredText(column: String): String =
	requiredText(getColumnIndexOrThrow(column))

internal fun android.database.Cursor.requiredText(index: Int): String {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_STRING) {
		throw CountDomainStoredEvidenceException()
	}
	return getString(index) ?: throw CountDomainStoredEvidenceException()
}

internal fun android.database.Cursor.nullableText(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_STRING ->
			getString(index) ?: throw CountDomainStoredEvidenceException()
		else -> throw CountDomainStoredEvidenceException()
	}
}

internal fun android.database.Cursor.requiredLong(column: String): Long =
	requiredLong(getColumnIndexOrThrow(column))

internal fun android.database.Cursor.requiredLong(index: Int): Long {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_INTEGER) {
		throw CountDomainStoredEvidenceException()
	}
	return getLong(index)
}

private fun android.database.Cursor.requiredInt(column: String): Int {
	val value = requiredLong(column)
	if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
		throw CountDomainStoredEvidenceException()
	}
	return value.toInt()
}

internal fun android.database.Cursor.nullableLong(column: String): Long? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_INTEGER -> getLong(index)
		else -> throw CountDomainStoredEvidenceException()
	}
}

