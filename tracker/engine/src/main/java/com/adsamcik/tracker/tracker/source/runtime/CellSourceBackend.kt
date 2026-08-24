package com.adsamcik.tracker.tracker.source.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

internal data class CellBackendSnapshot(
	val subscriptionId: Int?,
	val observations: List<CellBackendObservation>,
	val providerItemCount: Int = observations.size,
)

internal data class CellBackendObservation(
	val identity: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
)

internal enum class CellRefreshRequestOutcome { REQUESTED, NOT_SUPPORTED, PERMISSION_BLOCKED, PROVIDER_FAILED }

@Singleton
internal class AndroidCellSourceBackend internal constructor(
	private val baseManager: TelephonyManager?,
	private val subscriptionManager: SubscriptionManager?,
	private val executor: Executor,
	private val registrationFactory: (
		subscriptionId: Int?,
		manager: TelephonyManager,
		executor: Executor,
		callback: (CellBackendSnapshot) -> Unit,
	) -> CellProviderRegistration,
) {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		baseManager = context.applicationContext.getSystemService(TelephonyManager::class.java),
		subscriptionManager = context.applicationContext.getSystemService(SubscriptionManager::class.java),
		executor = ContextCompat.getMainExecutor(context.applicationContext),
		registrationFactory = ::registerAndroidCellProvider,
	)

	private val registrations = linkedMapOf<Int?, CellProviderRegistration>()

	@Synchronized
	@SuppressLint("MissingPermission")
	fun start(requestedSubscriptionIds: Set<Int>, callback: (CellBackendSnapshot) -> Unit): Boolean {
		if (baseManager == null) return false
		// Removal is an explicit runtime operation because RETIRING must be durable first. A failed
		// unregister therefore blocks start without implicitly retrying or losing its exact handle.
		if (registrations.isNotEmpty()) return false
		return runCatching {
			resolveSubscriptionIds(requestedSubscriptionIds).forEach { subscriptionId ->
				val manager = managerFor(subscriptionId)
				registrations[subscriptionId] = registrationFactory(
					subscriptionId,
					manager,
					executor,
					callback,
				)
			}
			registrations.isNotEmpty()
		}.getOrElse {
			// The runtime must persist its RETIRING cutoff before cleanup. Completed registrations
			// stay retained here so that the exact handles can be removed in that order.
			false
		}
	}

	@Synchronized
	fun stop(): Boolean {
		var success = true
		val iterator = registrations.iterator()
		while (iterator.hasNext()) {
			val registration = iterator.next().value
			if (runCatching(registration::unregister).isSuccess) {
				iterator.remove()
			} else {
				success = false
			}
		}
		return success
	}

	@Synchronized
	@SuppressLint("MissingPermission")
	fun requestRefresh(callback: (CellBackendSnapshot) -> Unit): CellRefreshRequestOutcome {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CellRefreshRequestOutcome.NOT_SUPPORTED
		return try {
			registrations.forEach { (subscriptionId, registration) ->
				registration.manager.requestCellInfoUpdate(
					executor,
					object : TelephonyManager.CellInfoCallback() {
						override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
							callback(cellInfo.toBackendSnapshot(subscriptionId))
						}
					},
				)
			}
			CellRefreshRequestOutcome.REQUESTED
		} catch (_: SecurityException) {
			CellRefreshRequestOutcome.PERMISSION_BLOCKED
		} catch (_: RuntimeException) {
			CellRefreshRequestOutcome.PROVIDER_FAILED
		}
	}

	@SuppressLint("MissingPermission")
	private fun resolveSubscriptionIds(requested: Set<Int>): Set<Int?> {
		val active = runCatching {
			subscriptionManager?.activeSubscriptionInfoList
				?.mapTo(linkedSetOf()) { it.subscriptionId }
		}.getOrNull().orEmpty()
		return resolveCellSubscriptionScope(requested, active)
	}

	private fun managerFor(subscriptionId: Int?): TelephonyManager =
		if (subscriptionId == null) requireNotNull(baseManager)
		else requireNotNull(baseManager).createForSubscriptionId(subscriptionId)
}

/**
 * The exact provider object registered for one subscription. The unregister closure captures the
 * same [callback] or [legacyListener]; the backend retains this object until removal succeeds.
 */
internal class CellProviderRegistration(
	val manager: TelephonyManager,
	val callback: TelephonyCallback?,
	val legacyListener: PhoneStateListener?,
	private val unregisterAction: () -> Unit,
) {
	fun unregister() = unregisterAction()
}

private fun registerAndroidCellProvider(
	subscriptionId: Int?,
	manager: TelephonyManager,
	executor: Executor,
	callback: (CellBackendSnapshot) -> Unit,
): CellProviderRegistration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
	val listener = Api31CellInfoCallback(subscriptionId, callback)
	manager.registerTelephonyCallback(executor, listener)
	CellProviderRegistration(manager, listener, null) {
		manager.unregisterTelephonyCallback(listener)
	}
} else {
	@Suppress("DEPRECATION")
	val listener = LegacyCellInfoListener(subscriptionId, callback)
	@Suppress("DEPRECATION")
	manager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
	CellProviderRegistration(manager, null, listener) {
		@Suppress("DEPRECATION")
		manager.listen(listener, PhoneStateListener.LISTEN_NONE)
	}
}

@RequiresApi(Build.VERSION_CODES.S)
private class Api31CellInfoCallback(
	private val subscriptionId: Int?,
	private val callback: (CellBackendSnapshot) -> Unit,
) : TelephonyCallback(), TelephonyCallback.CellInfoListener {
	override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
		callback(cellInfo.toBackendSnapshot(subscriptionId))
	}
}

@Suppress("DEPRECATION")
private class LegacyCellInfoListener(
	private val subscriptionId: Int?,
	private val callback: (CellBackendSnapshot) -> Unit,
) : PhoneStateListener() {
	override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
		cellInfo?.let { callback(it.toBackendSnapshot(subscriptionId)) }
	}
}

private fun List<CellInfo>.toBackendSnapshot(subscriptionId: Int?) = CellBackendSnapshot(
	subscriptionId = subscriptionId,
	observations = mapNotNull(CellInfo::toBackendObservation)
		.sortedWith(compareBy(CellBackendObservation::radioType, CellBackendObservation::identity)),
	providerItemCount = size,
)

@Suppress("DEPRECATION")
private fun CellInfo.toBackendObservation(): CellBackendObservation? {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoTdscdma) {
		val identity = cellIdentity
		return backendObservation(
			identity = "${identity.mccString}:${identity.mncString}:${identity.lac}:" +
				"${identity.cid}:${identity.cpid}",
			radioType = "TDSCDMA",
			signalLevelDbm = cellSignalStrength.dbm,
		)
	}
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoNr) {
		val identity = cellIdentity as CellIdentityNr
		return backendObservation(
			identity = "${identity.mccString}:${identity.mncString}:${identity.tac}:" +
				"${identity.nci}:${identity.pci}",
			radioType = "NR",
			signalLevelDbm = cellSignalStrength.dbm,
		)
	}
	val identityAndType = when (this) {
		is CellInfoGsm -> (cellIdentity as CellIdentityGsm).let {
			"${it.operatorToken()}:${it.lac}:${it.cid}" to "GSM"
		}
		is CellInfoWcdma -> (cellIdentity as CellIdentityWcdma).let {
			"${it.operatorToken()}:${it.lac}:${it.cid}:${it.psc}" to "WCDMA"
		}
		is CellInfoLte -> (cellIdentity as CellIdentityLte).let {
			"${it.operatorToken()}:${it.tac}:${it.ci}:${it.pci}" to "LTE"
		}
		is CellInfoCdma -> (cellIdentity as CellIdentityCdma).let {
			"${it.systemId}:${it.networkId}:${it.basestationId}" to "CDMA"
		}
		else -> return null
	}
	val signal = when (this) {
		is CellInfoGsm -> cellSignalStrength.dbm
		is CellInfoWcdma -> cellSignalStrength.dbm
		is CellInfoLte -> cellSignalStrength.dbm
		is CellInfoCdma -> cellSignalStrength.dbm
		else -> null
	}
	return backendObservation(
		identity = identityAndType.first,
		radioType = identityAndType.second,
		signalLevelDbm = signal,
	)
}

@Suppress("DEPRECATION")
private fun CellInfo.backendObservation(
	identity: String,
	radioType: String,
	signalLevelDbm: Int?,
): CellBackendObservation {
	val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		timestampMillis.takeIf { it > 0L }?.times(NANOS_PER_MILLISECOND)
	} else {
		timeStamp.takeIf { it > 0L }
	}
	return CellBackendObservation(identity, radioType, isRegistered, signalLevelDbm, timestamp)
}

@Suppress("DEPRECATION")
private fun CellIdentityGsm.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

@Suppress("DEPRECATION")
private fun CellIdentityWcdma.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

@Suppress("DEPRECATION")
private fun CellIdentityLte.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
