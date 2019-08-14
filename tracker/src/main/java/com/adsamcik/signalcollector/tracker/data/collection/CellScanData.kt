package com.adsamcik.signalcollector.tracker.data.collection

import android.telephony.CellInfo
import com.adsamcik.signalcollector.common.data.NetworkOperator

internal data class CellScanData(val registeredOperators: List<NetworkOperator>,
                                 val cellScanData: List<CellInfo>,
                                 val registeredCells: List<com.adsamcik.signalcollector.common.data.CellInfo>)