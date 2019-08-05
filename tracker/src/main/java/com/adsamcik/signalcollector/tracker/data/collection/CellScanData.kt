package com.adsamcik.signalcollector.tracker.data.collection

import android.telephony.CellInfo

internal data class CellScanData(val registeredOperators: List<RegisteredOperator>,
                                 val cellScanData: List<CellInfo>,
                                 val registeredCells: List<com.adsamcik.signalcollector.common.data.CellInfo>)