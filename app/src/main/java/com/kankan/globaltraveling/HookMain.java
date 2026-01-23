package com.kankan.globaltraveling;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String TAG = "Irest-Ultimate";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.kankan.globaltraveling")) return;

        // --- 1. 通用 Location 类劫持 (Getter/Setter/Constructor) ---
        XC_MethodHook universalLocHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                applyLocationFix((Location) param.thisObject);
            }
        };
        XposedHelpers.findAndHookConstructor(Location.class, String.class, universalLocHook);
        XposedHelpers.findAndHookMethod(Location.class, "set", Location.class, universalLocHook);

        XC_MethodHook getterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                double[] c = readFromTmp();
                if (c == null) return;
                double d = getDrift();
                if (param.method.getName().equals("getLatitude")) param.setResult(c[0] + d);
                else if (param.method.getName().equals("getLongitude")) param.setResult(c[1] + d);
                else if (param.method.getName().equals("getAccuracy")) param.setResult(5.0f);
            }
        };
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", XC_MethodReplacement.returnConstant(false));

        // --- 2. 腾讯 SDK 专用 Hook (解决 QQ/JD 定位核心) ---
        try {
            // A. Hook 腾讯的结果对象
            Class<?> tencentLoc = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(tencentLoc, "getLatitude", getterHook);
            XposedHelpers.findAndHookMethod(tencentLoc, "getLongitude", getterHook);
            XposedHelpers.findAndHookMethod(tencentLoc, "getProvider", XC_MethodReplacement.returnConstant("gps"));
            XposedHelpers.findAndHookMethod(tencentLoc, "getVerifyCode", XC_MethodReplacement.returnConstant(null));

            // B. Hook 腾讯的监听器 (防止异步跳回)
            XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName().contains("LocationListener")) {
                            XposedHelpers.findAndHookMethod(arg.getClass(), "onLocationChanged", Location.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                    applyLocationFix((Location) p.args[0]);
                                }
                            });
                        }
                    }
                }
            });
        } catch (Throwable t) {}

        // --- 3. 环境伪造 (不再返回空，而是返回 1 个假数据) ---
        // 伪造 1 个 Wi-Fi
        XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (readFromTmp() != null) {
                    List<ScanResult> list = new ArrayList<>();
                    try {
                        Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        ScanResult w = ctor.newInstance();
                        w.SSID = "Mandba-Rest-WiFi";
                        w.BSSID = "00:11:22:33:44:55";
                        w.level = -45;
                        list.add(w);
                    } catch (Exception ignored) {}
                    param.setResult(list);
                }
            }
        });

        // 伪造 1 个基站
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (readFromTmp() != null) {
                    List<CellInfo> cells = new ArrayList<>();
                    try {
                        CellInfoGsm cell = (CellInfoGsm) XposedHelpers.newInstance(CellInfoGsm.class);
                        cells.add(cell);
                    } catch (Exception ignored) {}
                    param.setResult(cells);
                }
            }
        });
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", XC_MethodReplacement.returnConstant(null));

        // --- 4. 封锁 NMEA (京东/QQ 反查利器) ---
        XposedBridge.hookAllMethods(LocationManager.class, "addNmeaListener", XC_MethodReplacement.returnConstant(true));
    }

    private void applyLocationFix(Location loc) {
        if (loc == null) return;
        double[] c = readFromTmp();
        if (c != null) {
            double d = getDrift();
            loc.setLatitude(c[0] + d);
            loc.setLongitude(c[1] + d);
            loc.setAccuracy(5.0f);
            loc.setProvider(LocationManager.GPS_PROVIDER);
            loc.setTime(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= 17) {
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
        }
    }

    private double getDrift() {
        return (new Random().nextDouble() - 0.5) * 0.00002;
    }

    private double[] readFromTmp() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) return null;
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            br.close();
            if (line == null) return null;
            String[] p = line.split(",");
            if (p.length < 3 || !"1".equals(p[2])) return null;
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (Exception e) { return null; }
    }
}
