package com.adsamcik.tracker.tracker.component.producer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.Reporter
import com.adsamcik.tracker.common.data.CellInfo
import com.adsamcik.tracker.common.data.NetworkOperator
import com.adsamcik.tracker.common.extension.getSystemServiceTyped
import com.adsamcik.tracker.common.extension.hasReadPhonePermission
import com.adsamcik.tracker.common.extension.telephonyManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.CellScanData
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import java.util.*

internal class CellDataProducer(changeReceiver: TrackerDataProducerObserver) : TrackerDataProducerComponent(
		changeReceiver) {
	override val keyRes: Int = R.string.settings_cell_enabled_key
	override val defaultRes: Int = R.string.settings_cell_enabled_default

	private var telephonyManager: TelephonyManager? = null
	private var subscriptionManager: SubscriptionManager? = null

	private var context: Context? = null

	@SuppressLint("MissingPermission")
	override fun onDataRequest(tempData: MutableCollectionTempData) {
		val context = context
		checkNotNull(context)
		if (!Assist.isAirplaneModeEnabled(context)) {
			val telephonyManager = telephonyManager
			checkNotNull(telephonyManager)

			val scanData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && context.hasReadPhonePermission) {
				val subscriptionManager = subscriptionManager
				checkNotNull(subscriptionManager)

				//Requires suppress missing permission because lint does not properly work with context.hasReadPhonePermission
				getScanData(telephonyManager, subscriptionManager)
			} else {
				getScanData(telephonyManager)
			}

			if (scanData != null) {
				tempData.setCellData(scanData)
			}
		}
	}

	@Suppress("MagicNumber")
	private fun getScanData(telephonyManager: TelephonyManager): CellScanData? {
		val networkOperator = telephonyManager.networkOperator
		return if (networkOperator.isNotEmpty()) {
			val mcc = networkOperator.substring(0, 3)
			val mnc = networkOperator.substring(3)

			val registeredOperator = NetworkOperator(mcc, mnc, telephonyManager.networkOperatorName)

			getScanData(telephonyManager, listOf(registeredOperator))
		} else {
			null
		}
	}

	@RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
	@RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
	private fun getScanData(telephonyManager: TelephonyManager,
	                        subscriptionManager: SubscriptionManager): CellScanData? {
		val list = mutableListOf<NetworkOperator>()
		subscriptionManager.activeSubscriptionInfoList.forEach {
			val mcc: String?
			val mnc: String?

			if (Build.VERSION.SDK_INT >= 29) {
				mcc = it.mccString
				mnc = it.mncString
			} else {
				@Suppress("deprecation")
				mcc = it.mcc.toString()
				@Suppress("deprecation")
				mnc = it.mnc.toString()
			}

			if (mcc != null && mnc != null) {
				list.add(NetworkOperator(mcc, mnc, it.carrierName.toString()))
			}
		}

		return if (list.isNotEmpty()) {
			getScanData(telephonyManager, list)
		} else {
			null
		}
	}

	private fun getScanData(telephonyManager: TelephonyManager,
	                        registeredOperators: List<NetworkOperator>): CellScanData? {
		//Annoying lint bug CoarseLocation permission is not required when android.permission.ACCESS_FINE_LOCATION is present
		@SuppressLint("MissingPermission")
		val cellInfo = telephonyManager.allCellInfo ?: return null

		val phoneCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) telephonyManager.phoneCount else 1
		val registeredCells = ArrayList<CellInfo>(phoneCount)

		cellInfo.forEach {
			if (it.isRegistered) {
				convertToCellInfo(it, registeredOperators)?.let { cellInfo ->
					registeredCells.add(cellInfo)
				}

				if (registeredCells.size == phoneCount - 1) return@forEach
			}
		}

		return CellScanData(registeredOperators, cellInfo, registeredCells)
	}

	@Suppress("ComplexMethod")
	private fun convertToCellInfo(cellInfo: android.telephony.CellInfo,
	                              registeredOperator: List<NetworkOperator>): CellInfo? {
		return if (cellInfo is CellInfoLte) {
			registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
				CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
			}
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
			registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
				CellInfo(cellInfo.cellIdentity as CellIdentityNr, cellInfo.cellSignalStrength as CellSignalStrengthNr,
						it)
			}
		} else if (cellInfo is CellInfoGsm) {
			registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
				CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
			}
		} else if (cellInfo is CellInfoWcdma) {
			registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
				CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
			}
		} else if (cellInfo is CellInfoCdma) {
			registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
				CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
			}
		} else {
			Reporter.report(Throwable("UNKNOWN CELL TYPE ${cellInfo.javaClass.simpleName}"))
			null
		}
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		this.context = context
		telephonyManager = context.telephonyManager

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
			subscriptionManager = context.getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		this.context = null
		telephonyManager = null
		subscriptionManager = null
	}
}
