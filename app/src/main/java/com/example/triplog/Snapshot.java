package com.example.triplog;

/** قراءة واحدة لحالة السيارة. أي قيمة null = غير متوفرة. */
public final class Snapshot {
    public long timeMs;
    public Float speedKmh;      // VEHICLESPEEDVSOSIG
    public Integer gear;        // 1=P 2=R 3=N 4=D (VCU_1_G_PRNDGEARACT)
    public Float odometerKm;    // FLZCU_TOTALODOMETERBACKUP
    public Float socPct;        // BMS_SOCLIGHT
    public Float fuelPct;       // FUEL_LEVEL / INFO_FUEL_CAPACITY
    public Integer rangeKm;     // VCU_WLTC_RANGEAVAL
    public Float outsideTempC;  // EXTERNALTEMPERATURE_C
    public Float packPowerKw;   // BMS_44_PACKPOWERREALTIME

    public String gearLabel() {
        if (gear == null) return "–";
        switch (gear) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return "–";
        }
    }
}
