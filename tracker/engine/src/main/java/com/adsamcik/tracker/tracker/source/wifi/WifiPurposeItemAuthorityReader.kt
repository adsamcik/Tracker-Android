package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyWifiPurposeItems as qualifyStoredWifiPurposeItems
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload

/** Compatibility for the existing source-local adapter; authority is shared with retained readers. */
internal suspend fun AppDatabase.qualifyWifiPurposeItems(
	wal: SourceEventWalEntity,
	payload: WifiWalPayload,
	providerStartInclusiveNanos: Long,
	providerEndExclusiveNanos: Long,
): RadioPurposeItems? = qualifyStoredWifiPurposeItems(
	wal, payload, providerStartInclusiveNanos, providerEndExclusiveNanos,
)
