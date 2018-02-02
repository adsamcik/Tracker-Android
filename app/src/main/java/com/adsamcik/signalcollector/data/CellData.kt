package com.adsamcik.signalcollector.data

import android.support.annotation.RequiresApi
import android.telephony.*
import com.vimeo.stag.UseStag
import java.io.Serializable


@UseStag
class CellData
/**
 * CellData constructor
 *
 * @param operatorName [CellData.operatorName]
 * @param type         [CellData.type]
 * @param id           [CellData.id]
 * @param mcc          [CellData.mcc]
 * @param mnc          [CellData.mnc]
 * @param dbm          [CellData.dbm]
 * @param asu          [CellData.asu]
 * @param level        [CellData.level]
 */(operatorName: String,
        /**
         * Network type. Can have values: GSM {@value #GSM}, CDMA {@value #CDMA}, WCDMA {@value #WCDMA}, LTE {@value #LTE}
         */
    var type: Int,
        /**
         * Cell id
         * GSM - cid
         * CDMA - baseStationId
         * WCDMA - cid
         * LTE - ci
         */
    var id: Int,
        /**
         * Mobile country code
         * Replaced with System ID on CDMA
         */
    var mcc: Int,
        /**
         * Mobile network code
         * Replaced with Network ID on CDMA
         */
    var mnc: Int,
        /**
         * Strength of signal in decibels
         */
    var dbm: Int,
        /**
         * Strength of signal in asu
         */
    var asu: Int,
        /**
         * Signal strength as int 0...4 calculated by device
         */
    var level: Int) : Serializable {

    /**
     * Network operator name
     */
    var operatorName: String? = operatorName

    /**
     * Converts int type to string
     *
     * @return type of network as string
     */
    fun getType(): String = when (type) {
        GSM -> "GSM"
        CDMA -> "CDMA"
        WCDMA -> "WCDMA"
        LTE -> "LTE"
        else -> "UNKNOWN"
    }

    companion object {
        const val GSM = 0
        const val CDMA = 1
        const val WCDMA = 2
        const val LTE = 3

        /**
         * Finds carrier name in subscriptions
         *
         * @param mnc                  Mobile network code
         * @param mcc                  Mobile country code
         * @param subscriptionInfoList Subscribed sim cards
         * @return carrier name or null if not found
         */
        @RequiresApi(22)
        private fun getCarrierName(mnc: Int, mcc: Int, subscriptionInfoList: List<SubscriptionInfo>): String? {
            if (mcc == Integer.MAX_VALUE)
                return null

            return subscriptionInfoList
                        .firstOrNull { it.mcc == mcc && it.mnc == mnc }?.carrierName?.toString()
        }

        @RequiresApi(22)
        private fun getCarrierNameAndRemove(mnc: Int, mcc: Int, siList: MutableList<SubscriptionInfo>): String? {
            if (mcc == Integer.MAX_VALUE)
                return null

            val iter = siList.iterator()
            while (iter.hasNext()) {
                val si = iter.next()
                if (si.mcc == mcc && si.mnc == mnc) {
                    val carrierName = si.carrierName.toString()
                    iter.remove()
                    return carrierName
                }
            }

            return null
        }

        /**
         * Creates new instance of CellData from GSM cell info
         *
         * @param cing         GSM cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cing: CellInfoGsm, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cig = cing.cellIdentity
            val cssg = cing.cellSignalStrength
            return CellData(operatorName, GSM, cig.cid, cig.mcc, cig.mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cing: CellInfoGsm, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            val cig = cing.cellIdentity
            return newInstance(cing, getCarrierNameAndRemove(cig.mnc, cig.mcc, subscriptionInfoList))
        }

        /**
         * Creates new instance of CellData from CDMA cell info
         *
         * @param cinc         CDMA cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinc: CellInfoCdma, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cic = cinc.cellIdentity
            val cssg = cinc.cellSignalStrength
            return CellData(operatorName, CDMA, cic.basestationId, cic.systemId, cic.networkId, cssg.dbm, cssg.asuLevel, cssg.level)
        }

        @RequiresApi(22)
        fun newInstance(cinc: CellInfoCdma, subscriptionInfoList: List<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinc, subscriptionInfoList[0].carrierName.toString())
            else
                null
        }

        /**
         * Creates new instance of CellData from WCDMA cell info
         *
         * @param cinl         WCDMA cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinl: CellInfoWcdma, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cil = cinl.cellIdentity
            val cssg = cinl.cellSignalStrength
            return CellData(operatorName, WCDMA, cil.cid, cil.mcc, cil.mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cinl: CellInfoWcdma, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinl, subscriptionInfoList[0].carrierName.toString())
            else {
                val cil = cinl.cellIdentity
                newInstance(cinl, getCarrierNameAndRemove(cil.mnc, cil.mcc, subscriptionInfoList))
            }
        }


        /**
         * Creates new instance of CellData from LTE cell info
         *
         * @param cinl         LTE Cell Info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinl: CellInfoLte, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cil = cinl.cellIdentity
            val cssg = cinl.cellSignalStrength
            return CellData(operatorName, LTE, cil.ci, cil.mcc, cil.mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cinl: CellInfoLte, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinl, subscriptionInfoList[0].carrierName.toString())
            else {
                val cil = cinl.cellIdentity
                newInstance(cinl, getCarrierNameAndRemove(cil.mnc, cil.mcc, subscriptionInfoList))
            }
        }
    }
}
