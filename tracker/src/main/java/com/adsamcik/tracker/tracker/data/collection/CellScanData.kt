package com.adsamcik.tracker.tracker.data.collection

import android.telephony.CellInfo
import com.adsamcik.tracker.common.data.NetworkOperator

internal data class CellScanData(
		val registeredOperators: List<NetworkOperator>,
		val cellScanData: List<CellInfo>,
		val registeredCells: List<com.adsamcik.tracker.common.data.CellInfo>
)
