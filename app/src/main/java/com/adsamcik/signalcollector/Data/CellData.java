package com.adsamcik.signalcollector.Data;

import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;

import java.io.Serializable;


@SuppressWarnings("WeakerAccess")
public class CellData implements Serializable {
    public final int type;
    //GSM
    public int cid;
    public int lac;
    //LTE
    public int ci;
    public int mcc;
    public int mnc;
    public int pci;
    public int tac;
    public int timing;
    //CDMA
    public int baseStationId;
    public int latitude;
    public int longitude;
    public int networkId;
    public int systemId;
    //global
    public final int dbm;
    public final int asu;
    public final int level;
    public final boolean isRegistered;


    //GSM constructor
    public CellData(CellInfoGsm cing) {
        this.type = 0;
        this.isRegistered = cing.isRegistered();
        CellIdentityGsm cig = cing.getCellIdentity();
        this.cid = cig.getCid();
        this.lac = cig.getLac();
        this.mcc = cig.getMcc();
        this.mnc = cig.getMnc();

        CellSignalStrengthGsm cssg = cing.getCellSignalStrength();
        this.dbm = cssg.getDbm();
        this.asu = cssg.getAsuLevel();
        this.level = cssg.getLevel();
    }

    //LTE constructor
    public CellData(CellInfoLte cinl) {
        this.type = 3;
        this.isRegistered = cinl.isRegistered();
        CellIdentityLte cil = cinl.getCellIdentity();
        this.ci = cil.getCi();
        this.pci = cil.getPci();
        this.mcc = cil.getMcc();
        this.mnc = cil.getMnc();
        this.tac = cil.getTac();

        CellSignalStrengthLte cssl = cinl.getCellSignalStrength();
        this.dbm = cssl.getDbm();
        this.asu = cssl.getAsuLevel();
        this.level = cssl.getLevel();
        this.timing = cssl.getTimingAdvance();
    }

    //CDMA constructor
    public CellData(CellInfoCdma cinc) {
        this.type = 1;
        this.isRegistered = cinc.isRegistered();
        CellIdentityCdma cil = cinc.getCellIdentity();
        this.baseStationId = cil.getBasestationId();
        this.latitude = cil.getLatitude();
        this.longitude = cil.getLongitude();
        this.networkId = cil.getNetworkId();
        this.systemId = cil.getSystemId();

        CellSignalStrengthCdma cssc = cinc.getCellSignalStrength();
        this.dbm = cssc.getDbm();
        this.asu = cssc.getAsuLevel();
        this.level = cssc.getLevel();
    }

    //WCDMA constructor
    public CellData(CellInfoWcdma cinw) {
        this.type = 2;
        this.isRegistered = cinw.isRegistered();
        CellIdentityWcdma ciw = cinw.getCellIdentity();
        this.cid = ciw.getCid();
        this.lac = ciw.getLac();
        this.mcc = ciw.getMcc();
        this.mnc = ciw.getMnc();

        CellSignalStrengthWcdma cssw = cinw.getCellSignalStrength();
        this.dbm = cssw.getDbm();
        this.asu = cssw.getAsuLevel();
        this.level = cssw.getLevel();
    }

    public String getType() {
        switch (type) {
            case 0:
                return "GSM";
            case 1:
                return "CDMA";
            case 2:
                return "WCDMA";
            case 3:
                return "LTE";
            default:
                return "UNKNOWN";
        }
    }
}
