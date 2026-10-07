package com.example.triplog;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * الوصول لبيانات السيارة بنفس نهج Unlokit: reflection كامل بدون stubs.
 *  1) Autolink:  com.autolink.manager.Api.createApi(ctx).getManager("car")
 *  2) احتياطي:  android.car.Car.createCar(ctx).getCarManager("property")  (VHAL القياسي)
 */
public final class CarBridge {
    private static final String TAG = "TripCar";

    // معرّفات VHAL القياسية
    static final int PERF_VEHICLE_SPEED       = 0x11600207; // m/s
    static final int PERF_ODOMETER            = 0x11600204; // km
    static final int GEAR_SELECTION           = 0x11400400;
    static final int FUEL_LEVEL               = 0x11600307; // ml
    static final int INFO_FUEL_CAPACITY       = 0x11600104; // ml
    static final int EV_BATTERY_LEVEL         = 0x11600308; // Wh
    static final int INFO_EV_BATTERY_CAPACITY = 0x11600106; // Wh
    static final int ENV_OUTSIDE_TEMPERATURE  = 0x11600703; // °C

    private Object autolinkCar;
    private final Map<String, Method> cache = new HashMap<>();

    private Object propMgr;
    private Method getFloatProp, getIntProp;
    private Float fuelCapacity, evCapacity;

    private long lastConnectTry;

    public void connect(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastConnectTry < 10_000) return;
        lastConnectTry = now;
        Context app = ctx.getApplicationContext();

        if (autolinkCar == null) {
            try {
                Object api = Class.forName("com.autolink.manager.Api")
                        .getMethod("createApi", Context.class).invoke(null, app);
                if (api != null) {
                    autolinkCar = api.getClass().getMethod("getManager", String.class).invoke(api, "car");
                    if (autolinkCar != null) Log.i(TAG, "Autolink CarManager متصل");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Autolink غير متوفر: " + t);
            }
        }

        if (propMgr == null) {
            try {
                Class<?> car = Class.forName("android.car.Car");
                Object c = car.getMethod("createCar", Context.class).invoke(null, app);
                if (c != null) {
                    Object pm = car.getMethod("getCarManager", String.class).invoke(c, "property");
                    if (pm != null) {
                        getFloatProp = pm.getClass().getMethod("getFloatProperty", int.class, int.class);
                        getIntProp = pm.getClass().getMethod("getIntProperty", int.class, int.class);
                        propMgr = pm;
                        Log.i(TAG, "CarPropertyManager متصل");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "android.car غير متوفر: " + t);
            }
        }
    }

    public boolean connected() { return autolinkCar != null || propMgr != null; }

    public String source() {
        if (autolinkCar != null && propMgr != null) return "Autolink + VHAL";
        if (autolinkCar != null) return "Autolink";
        if (propMgr != null) return "VHAL";
        return "غير متصل";
    }

    public Snapshot read() {
        Snapshot s = new Snapshot();
        s.timeMs = System.currentTimeMillis();

        // السرعة
        s.speedKmh = range(al("getVEHICLESPEEDVSOSIG"), 0, 300);
        if (s.speedKmh == null) {
            Float ms = vf(PERF_VEHICLE_SPEED);
            if (ms != null) s.speedKmh = range(Math.abs(ms) * 3.6f, 0, 300);
        }

        // ناقل الحركة
        Float g = al("getVCU_1_G_PRNDGEARACT");
        if (g != null) s.gear = g.intValue();
        else {
            Integer vg = vi(GEAR_SELECTION); // VHAL: N=1 R=2 P=4 D=8+
            if (vg != null) s.gear = vg == 4 ? 1 : vg == 2 ? 2 : vg == 1 ? 3 : vg >= 8 ? 4 : null;
        }

        // العداد
        s.odometerKm = range(al("getFLZCU_TOTALODOMETERBACKUP"), 0.1f, 2_000_000);
        if (s.odometerKm == null) s.odometerKm = range(vf(PERF_ODOMETER), 0.1f, 2_000_000);

        // البطارية
        s.socPct = range(al("getBMS_SOCLIGHT"), 0, 100);
        if (s.socPct == null) {
            if (evCapacity == null) evCapacity = vf(INFO_EV_BATTERY_CAPACITY);
            Float lvl = vf(EV_BATTERY_LEVEL);
            if (lvl != null && evCapacity != null && evCapacity > 0) s.socPct = range(lvl / evCapacity * 100f, 0, 100);
        }

        // الوقود (VHAL فقط، كما يفعل Unlokit)
        if (fuelCapacity == null) fuelCapacity = vf(INFO_FUEL_CAPACITY);
        Float fuel = vf(FUEL_LEVEL);
        if (fuel != null && fuelCapacity != null && fuelCapacity > 0) s.fuelPct = range(fuel / fuelCapacity * 100f, 0, 100);

        Float r = range(al("getVCU_WLTC_RANGEAVAL"), 0, 3000);
        if (r != null) s.rangeKm = r.intValue();

        s.outsideTempC = range(al("getEXTERNALTEMPERATURE_C"), -50, 70);
        if (s.outsideTempC == null) s.outsideTempC = range(vf(ENV_OUTSIDE_TEMPERATURE), -50, 70);

        s.packPowerKw = range(al("getBMS_44_PACKPOWERREALTIME"), -500, 500);
        return s;
    }

    // ---------- Autolink: استدعاء getter بالاسم ----------
    private Float al(String name) {
        Object car = autolinkCar;
        if (car == null) return null;
        Method m;
        if (cache.containsKey(name)) m = cache.get(name);
        else {
            try { m = car.getClass().getMethod(name); } catch (Throwable t) { m = null; }
            cache.put(name, m);
        }
        if (m == null) return null;
        try {
            Object v = m.invoke(car);
            return v instanceof Number ? ((Number) v).floatValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- VHAL القياسي ----------
    private Float vf(int id) {
        if (propMgr == null) return null;
        try {
            Object v = getFloatProp.invoke(propMgr, id, 0);
            return v instanceof Float ? (Float) v : null;
        } catch (Throwable t) {
            return null; // غالباً: صلاحية ناقصة أو الخاصية غير مدعومة
        }
    }

    private Integer vi(int id) {
        if (propMgr == null) return null;
        try {
            Object v = getIntProp.invoke(propMgr, id, 0);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Float range(Float v, float min, float max) {
        if (v == null || v.isNaN() || v < min || v > max) return null;
        return v;
    }
}
