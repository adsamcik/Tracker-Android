package com.adsamcik.tracker.tracker.data.collection

import android.telephony.CellInfo
import com.adsamcik.tracker.shared.base.data.NetworkOperator

internal data class CellScanData(
		val registeredOperators: List<NetworkOperator>,
		val cellScanData: List<CellInfo>,
		val registeredCells: List<com.adsamcik.tracker.shared.base.data.CellInfo>
)
