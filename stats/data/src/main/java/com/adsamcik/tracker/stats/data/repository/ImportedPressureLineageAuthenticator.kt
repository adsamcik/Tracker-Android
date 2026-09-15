package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedPressureLineageAuthenticator as CoreAuthenticator
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.shared.base.database.toPortablePressureWindow as toCorePressureWindow

internal typealias AuthenticatedImportedPressureLineage =
	com.adsamcik.tracker.shared.base.database.AuthenticatedImportedPressureLineage
internal typealias AuthenticatedImportedPressureRevision =
	com.adsamcik.tracker.shared.base.database.AuthenticatedImportedPressureRevision
internal typealias ImportedPressureLineageFailureReason =
	com.adsamcik.tracker.shared.base.database.ImportedPressureLineageFailureReason
internal typealias ImportedPressureLineageFailure =
	com.adsamcik.tracker.shared.base.database.ImportedPressureLineageFailure

/** Admission, history and synchronous full clear share the same complete semantic verifier. */
internal object ImportedPressureLineageAuthenticator {
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedPressureEntryRevisionEntity>,
		receipts: List<ImportedPressureReceiptEntity>,
		runs: List<ImportedPressureRunEntity>,
		windows: List<ImportedPressureWindowEntity>,
	): AuthenticatedImportedPressureLineage = CoreAuthenticator.authenticate(
		identity,
		expectedCollectedDataEpoch,
		headers,
		receipts,
		runs,
		windows,
	)
}

internal fun ImportedPressureWindowEntity.toPortablePressureWindow() = toCorePressureWindow()
