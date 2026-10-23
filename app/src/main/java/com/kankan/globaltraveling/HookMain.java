package com.kankan.globaltraveling;

import android.app.Application;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.telephony.CellInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.bluetooth.BluetoothAdapter;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Shadow 核心 Hook 入口（Android 10 - 17 / 澎湃OS 4 适配版）
 *
 * Android 17 适配要点：
 * 1. 每个 Hook 独立 try/catch —— 新系统移除任何废弃 API（如 TelephonyManager.getNetworkType、
 *    CellLocation、NetworkInfo 等）都不会导致整个模块初始化中断（这是旧版在 Android 17 上
 *    完全失效的主要原因）。
 * 2. Location 优先使用公开 setter（setLatitude/setLongitude）修改，私有字段仅作回退，
 *    避免新系统字段布局变化导致反射失败。
 * 3. WifiInfo.mBSSID 自 Android 12 起是 MacAddress 类型而非 String，按字段实际类型赋值。
 * 4. 补充 Location.isMock()（Android 12+ 新检测途径）的屏蔽。
 * 5. requestLocationUpdates 使用 instanceof 判断监听器（旧版按类名 contains 判断，
 *    Lambda/函数式实现会漏判），并对每个监听器只 Hook 一次，防止重复挂载。
 * 6. 微信小程序定位劫持：小程序的 wx.getLocation 走微信内置腾讯定位 SDK
 *    （com.tencent.map.geolocation），不经过系统 LocationManager，单独用类加载监听
 *    + 类级别 Hook SDK 坐标出口（getLatitude/getLongitude）重定向到系统模拟坐标，
 *    不依赖 SDK 内部字段名/方法签名，SDK 更新后依然稳定（取代第三方
 *    HookWXMAppLocation 模块的脆弱内部链路 Hook）。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String TAG = "[Shadow] ";

    // 缓存当前的指纹信息 (默认为保底值)
    private static String cachedMac = "00:11:22:33:44:55";
    private static String cachedSSID = "Mandba-WiFi";

    // 配置文件读取缓存（500ms），避免高频文件 IO
    private long lastReadTime = 0;
    private double[] lastConfig = null;

    // 已挂载过的监听器/回调，防止重复 Hook
    private final Set<Object> hookedCallbacks =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private boolean loggedFileMissing = false;

    // 小程序定位劫持：内置腾讯定位 SDK 的宿主 App（小程序定位发生在这些进程里）
    private static final java.util.List<String> TENCENT_SDK_PKGS =
            java.util.Arrays.asList("com.tencent.mm", "com.tencent.wework", "com.tencent.mobileqq");

    // 已处理过的腾讯 SDK 类，防止重复 Hook
    private final Set<Class<?>> tencentHooked =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private volatile boolean tencentWatcherInstalled = false;
    // 诊断日志写文件用（澎湃OS 4 封锁 logcat 读取，改走文件通道）
    private static volatile Context diagCtx = null;
    private static volatile long lastGetterLogTime = 0;

    /** 诊断日志：写入宿主 App 外部存储 shadowtp.log（adb 可直读） + logcat（万一能用） */
    private static void dlog(String msg) {
        String line = System.currentTimeMillis() % 100000000 + " " + msg;
        Log.i("ShadowTP", msg);
        try {
            Context c = diagCtx;
            if (c == null) return;
            File f = new File(c.getExternalFilesDir(null), "shadowtp.log");
            FileWriter w = new FileWriter(f, true);
            w.write(line + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 所有 Hook 统一走这里：单个 Hook 失败只记日志，不影响其余 Hook（Android 17 兼容关键） */
    private void safe(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook skipped [" + name + "]: " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 跳过模块自身（用 BuildConfig 动态读取，改包名后无需改代码）
        if (lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) return;

        // ==========================================
        // 1. 定位核心劫持 (GPS)
        // ==========================================
        XC_MethodHook universalLocHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                applyLocationFix((Location) param.thisObject);
            }
        };
        safe("Location.<init>", () ->
                XposedHelpers.findAndHookConstructor(Location.class, String.class, universalLocHook));
        safe("Location.set", () ->
                XposedHelpers.findAndHookMethod(Location.class, "set", Location.class, universalLocHook));

        XC_MethodHook getterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                double[] c = readFromTmp();
                if (c == null) return;

                double d = getDrift();
                String name = param.method.getName();

                if (name.equals("getLatitude")) param.setResult(c[0] + d);
                else if (name.equals("getLongitude")) param.setResult(c[1] + d);
                else if (name.equals("getAccuracy")) param.setResult(3.0f + new Random().nextFloat());
                else if (name.equals("getSpeed")) param.setResult(0.0f); // 强制静止
                else if (name.equals("getAltitude")) param.setResult(50.0d);
            }
        };
        safe("Location.getLatitude", () -> XposedHelpers.findAndHookMethod(Location.class, "getLatitude", getterHook));
        safe("Location.getLongitude", () -> XposedHelpers.findAndHookMethod(Location.class, "getLongitude", getterHook));
        safe("Location.getAccuracy", () -> XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", getterHook));
        safe("Location.getSpeed", () -> XposedHelpers.findAndHookMethod(Location.class, "getSpeed", getterHook));
        safe("Location.getAltitude", () -> XposedHelpers.findAndHookMethod(Location.class, "getAltitude", getterHook));

        // Mock 检测屏蔽：isFromMockProvider（旧）+ isMock（Android 12+ 新途径）
        safe("Location.isFromMockProvider", () ->
                XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", XC_MethodReplacement.returnConstant(false)));
        safe("Location.isMock", () ->
                XposedHelpers.findAndHookMethod(Location.class, "isMock", XC_MethodReplacement.returnConstant(false)));
        safe("LocationManager.isFromMockProvider", () ->
                XposedHelpers.findAndHookMethod(LocationManager.class, "isFromMockProvider", Location.class,
                        XC_MethodReplacement.returnConstant(false)));
        safe("LocationManager.isProviderFromMockProvider", () ->
                XposedHelpers.findAndHookMethod(LocationManager.class, "isProviderFromMockProvider", String.class,
                        XC_MethodReplacement.returnConstant(false)));

        // ==========================================
        // 2. 解决“定位中”：劫持 LastKnownLocation
        // ==========================================
        safe("LocationManager.getLastKnownLocation", () ->
                XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Location loc = (Location) param.getResult();
                        if (loc == null && readFromTmp() != null) {
                            loc = new Location(LocationManager.GPS_PROVIDER);
                            param.setResult(loc);
                        }
                        if (loc != null) applyLocationFix(loc);
                    }
                }));

        safe("LocationManager.getCurrentLocation", () ->
                XposedBridge.hookAllMethods(LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        if (r instanceof Location) applyLocationFix((Location) r);
                    }
                }));

        // ==========================================
        // 3. 动态监听劫持 (防跳回)
        // ==========================================
        safe("LocationManager.requestLocationUpdates", () ->
                XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (readFromTmp() == null) return;
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            // instanceof 判断：Lambda / 方法引用 / 匿名类均可正确识别
                            if (arg instanceof LocationListener) {
                                hookListenerOnce((LocationListener) arg);
                            } else if (arg instanceof Consumer) {
                                hookConsumerOnce((Consumer<?>) arg);
                            }
                        }
                        // 瞬时注入：给已注册的监听器立即推一次假位置，防止"定位中"转圈
                        double[] c = readFromTmp();
                        if (c != null) {
                            for (Object arg : param.args) {
                                if (arg instanceof LocationListener) {
                                    try {
                                        Location fastLoc = new Location(LocationManager.GPS_PROVIDER);
                                        applyLocationFix(fastLoc);
                                        ((LocationListener) arg).onLocationChanged(fastLoc);
                                    } catch (Throwable ignored) {}
                                    break;
                                }
                            }
                        }
                    }
                }));

        // ==========================================
        // 4. Wi-Fi 动态伪造 (读取 cachedMac 和 cachedSSID)
        // ==========================================
        // 伪装网络类型为 WIFI
        safe("TelephonyManager.getNetworkType", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkType",
                        XC_MethodReplacement.returnConstant(TelephonyManager.NETWORK_TYPE_UNKNOWN)));
        safe("ConnectivityManager.getActiveNetworkInfo", () ->
                XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (readFromTmp() != null) {
                            NetworkInfo info = (NetworkInfo) param.getResult();
                            if (info != null) {
                                try { XposedHelpers.setIntField(info, "mNetworkType", ConnectivityManager.TYPE_WIFI); } catch (Throwable ignored) {}
                                try { XposedHelpers.setObjectField(info, "mTypeName", "WIFI"); } catch (Throwable ignored) {}
                                try { XposedHelpers.setObjectField(info, "mState", NetworkInfo.State.CONNECTED); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }));

        // 伪造连接信息（BSSID 按字段类型赋值：老系统 String，Android 12+ 为 MacAddress）
        safe("WifiManager.getConnectionInfo", () ->
                XposedHelpers.findAndHookMethod(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (diagCtx != null) dlog("Wifi.getConnectionInfo called");
                        if (readFromTmp() == null) return;
                        WifiInfo info = (WifiInfo) param.getResult();
                        if (info == null) return;
                        setWifiInfoField(info, "mSSID", "\"" + cachedSSID + "\"");
                        setWifiInfoField(info, "mBSSID", cachedMac);
                        setWifiInfoField(info, "mMacAddress", "02:00:00:00:00:00");
                        setWifiInfoField(info, "mRssi", -45 - new Random().nextInt(10));
                    }
                }));

        // 伪造扫描列表
        safe("WifiManager.getScanResults", () ->
                XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (diagCtx != null) dlog("Wifi.getScanResults called -> " + (param.getResult() == null ? "null" : ((List<?>) param.getResult()).size() + " APs"));
                        if (readFromTmp() != null) {
                            List<ScanResult> list = new ArrayList<>();
                            try {
                                Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
                                ctor.setAccessible(true);
                                ScanResult w = ctor.newInstance();
                                w.SSID = cachedSSID;
                                w.BSSID = cachedMac;
                                w.level = -45 - new Random().nextInt(10);
                                w.capabilities = "[WPA2-PSK-CCMP][ESS]";
                                w.frequency = 2412;
                                w.timestamp = SystemClock.elapsedRealtime() * 1000;
                                list.add(w);
                            } catch (Exception ignored) {}
                            param.setResult(list);
                        }
                    }
                }));

        // ==========================================
        // 5. 虚拟飞行模式 (基站/SIM/蓝牙 彻底封杀)
        // ==========================================
        safe("TelephonyManager.getSimState", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState",
                        XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT)));
        safe("TelephonyManager.getSimState(int)", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState", int.class,
                        XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT)));
        safe("TelephonyManager.getAllCellInfo", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (diagCtx != null) dlog("Telephony.getAllCellInfo called");
                            }
                        }));
        safe("TelephonyManager.getAllCellInfo(fake)", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo",
                        XC_MethodReplacement.returnConstant(new ArrayList<CellInfo>())));
        safe("TelephonyManager.getCellLocation", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation",
                        XC_MethodReplacement.returnConstant(null)));
        safe("TelephonyManager.getNeighboringCellInfo", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNeighboringCellInfo",
                        XC_MethodReplacement.returnConstant(new ArrayList<NeighboringCellInfo>())));
        safe("TelephonyManager.getNetworkOperator", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkOperator",
                        XC_MethodReplacement.returnConstant("")));

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "requestCellInfoUpdate",
                        Executor.class, TelephonyManager.CellInfoCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (readFromTmp() != null) {
                                    Executor ex = (Executor) param.args[0];
                                    TelephonyManager.CellInfoCallback cb = (TelephonyManager.CellInfoCallback) param.args[1];
                                    if (ex != null && cb != null) ex.execute(() -> cb.onCellInfo(new ArrayList<>()));
                                    param.setResult(null);
                                }
                            }
                        });
            } catch (Throwable t) {}
        }

        safe("TelephonyManager.listen", () ->
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "listen", PhoneStateListener.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (readFromTmp() != null) param.args[1] = PhoneStateListener.LISTEN_NONE;
                            }
                        }));

        // 屏蔽蓝牙
        safe("BluetoothAdapter.getBondedDevices", () ->
                XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "getBondedDevices", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (readFromTmp() != null) param.setResult(Collections.emptySet());
                    }
                }));
        safe("BluetoothAdapter.startDiscovery", () ->
                XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "startDiscovery",
                        XC_MethodReplacement.returnConstant(false)));

        // ==========================================
        // 6. 辅助伪造 & SDK 兼容
        // ==========================================
        safe("LocationManager.addNmeaListener", () ->
                XposedBridge.hookAllMethods(LocationManager.class, "addNmeaListener",
                        XC_MethodReplacement.returnConstant(true)));
        if (Build.VERSION.SDK_INT >= 24) {
            safe("GnssStatus.getSatelliteCount", () ->
                    XposedHelpers.findAndHookMethod(GnssStatus.class, "getSatelliteCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (readFromTmp() != null) param.setResult(15);
                        }
                    }));
        }

        // ==========================================
        // 7. 小程序定位劫持（微信小程序的 wx.getLocation 走内置腾讯定位 SDK，
        //    不经系统 LocationManager，需要把 SDK 坐标出口重定向到系统模拟坐标）
        // ==========================================
        // ==========================================
        // 8. 诊断：系统定位调用全记录（揪出绕过腾讯 SDK 的取位置路径，
        //    如小程序自带原生插件直调 LocationManager）
        // ==========================================
        if (TENCENT_SDK_PKGS.contains(lpparam.packageName)) {
            safe("Application.attach(TencentSDK watcher)", () ->
                    XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            installTencentSdkWatcher((Context) param.args[0]);
                        }
                    }));

            safe("LocationManager diagnostics", () -> {
                for (java.lang.reflect.Method m : LocationManager.class.getDeclaredMethods()) {
                    final String mn = m.getName();
                    if (!mn.contains("Location") && !mn.contains("Gnss") && !mn.contains("location")) continue;
                    // GNSS 原始数据通道（测量值/卫星状态/NMEA）：模拟期间切断注册
                    // （原始卫星观测值会被打卡类应用提交给服务端解算真实位置，无法伪造，只能断供）
                    if (mn.startsWith("registerGnss") || mn.contains("Nmea")) {
                        XposedBridge.hookAllMethods(LocationManager.class, mn, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                if (readFromTmp() != null) {
                                    dlog("BLOCK " + mn + " -> false | " + shortStack());
                                    p.setResult(false);
                                } else {
                                    dlog(mn + " allowed (sim off)");
                                }
                            }
                        });
                        continue;
                    }
                    XposedBridge.hookAllMethods(LocationManager.class, mn, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            Object r = p.getResult();
                            String rv = "";
                            if (r instanceof Location) {
                                Location l = (Location) r;
                                try { rv = " -> " + l.getLatitude() + "," + l.getLongitude(); } catch (Throwable ignored) {}
                            } else if (r != null) {
                                rv = " -> " + r.getClass().getSimpleName();
                            }
                            dlog("LM." + mn + "(" + argClasses(p.args) + ")" + rv);
                        }
                    });
                }
                dlog("LocationManager diagnostics installed (proc=" + Application.getProcessName() + ")");
            });

            // 网络层侦查：记录小程序/微信发出的 URL（打卡时刻访问了哪些服务器一目了然）
            safe("Network URL diagnostics", () -> {
                XposedHelpers.findAndHookConstructor(java.net.URL.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object u = p.args[0];
                        if (u instanceof String) {
                            String s = (String) u;
                            if (s.startsWith("http")) dlog("URL " + s);
                        }
                    }
                });
                dlog("Network URL diagnostics installed (proc=" + Application.getProcessName() + ")");
            });

            // extras 兜底网：任何 Location 对象的 getExtras 读取/写入都清洗一遍，
            // 即使对象逃过了 applyLocationFix（如原生层创建），GPS 硬件写的真实坐标也出不去
            safe("Location.extras catch-all", () -> {
                XposedHelpers.findAndHookMethod(Location.class, "getExtras", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object b = p.getResult();
                        if (b instanceof Bundle && readFromTmp() != null) scrubBundle((Bundle) b);
                    }
                });
                XposedHelpers.findAndHookMethod(Location.class, "setExtras", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        if (p.args[0] instanceof Bundle && readFromTmp() != null) scrubBundle((Bundle) p.args[0]);
                    }
                });
                dlog("Location.extras catch-all installed (proc=" + Application.getProcessName() + ")");
            });
        }


        try {
            Class<?> amap = XposedHelpers.findClass("com.amap.api.location.AMapLocation", lpparam.classLoader);
            hookSDK(amap, getterHook);
            safe("AMapLocation.getLocationType", () ->
                    XposedHelpers.findAndHookMethod(amap, "getLocationType", XC_MethodReplacement.returnConstant(1)));
        } catch (Throwable t) {}

        try {
            Class<?> tencent = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            hookSDK(tencent, getterHook);
            safe("TencentLocation.getProvider", () ->
                    XposedHelpers.findAndHookMethod(tencent, "getProvider", XC_MethodReplacement.returnConstant("gps")));
        } catch (Throwable t) {}

        XposedBridge.log(TAG + "Hooks installed for " + lpparam.packageName);
    }

    /** 按 WifiInfo 字段的实际类型赋值：BSSID 在 Android 12+ 是 MacAddress，其余为 String */
    private void setWifiInfoField(WifiInfo info, String fieldName, Object strValue) {
        try {
            Field f = XposedHelpers.findField(WifiInfo.class, fieldName);
            Object value = strValue;
            if (f.getType() == MacAddress.class && strValue instanceof String) {
                value = MacAddress.fromString((String) strValue);
            }
            f.set(info, value);
        } catch (Throwable ignored) {}
    }

    /** 对监听器只 Hook 一次（旧版每次 requestLocationUpdates 都重复挂载，会无限膨胀） */
    private void hookListenerOnce(LocationListener listener) {
        if (!hookedCallbacks.add(listener)) return;
        try {
            // hookAllMethods：同时覆盖 onLocationChanged(Location) 及 Android 12+ 的 List 重载
            XposedBridge.hookAllMethods(listener.getClass(), "onLocationChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    for (Object arg : p.args) {
                        if (arg instanceof Location) applyLocationFix((Location) arg);
                    }
                    if (p.args.length == 1 && p.args[0] instanceof List) {
                        for (Object o : (List<?>) p.args[0]) {
                            if (o instanceof Location) applyLocationFix((Location) o);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook listener failed: " + t);
        }
    }

    /** Android 12+ 的 getCurrentLocation / requestLocationUpdates(Executor, Consumer) 路径 */
    private void hookConsumerOnce(Consumer<?> consumer) {
        if (!hookedCallbacks.add(consumer)) return;
        try {
            XposedHelpers.findAndHookMethod(consumer.getClass(), "accept", Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.args.length > 0 && p.args[0] instanceof Location) applyLocationFix((Location) p.args[0]);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook consumer failed: " + t);
        }
    }

    private void hookSDK(Class<?> clazz, XC_MethodHook hook) {
        try { XposedHelpers.findAndHookMethod(clazz, "getLatitude", hook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(clazz, "getLongitude", hook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(clazz, "getAccuracy", hook); } catch (Throwable t) {}
    }

    // ==================== 小程序定位劫持（腾讯定位 SDK） ====================

    /** 安装腾讯定位 SDK 类加载监听（每进程一次），并主动预载已知类名 */
    private void installTencentSdkWatcher(final Context appContext) {
        if (tencentWatcherInstalled) return;
        tencentWatcherInstalled = true;
        diagCtx = appContext.getApplicationContext();
        final ClassLoader appCl = appContext.getClassLoader();
        dlog("watcher installed, proc=" + Application.getProcessName());

        // 主动加载已知类名：SDK 类若在监听安装前已加载，这里补挂
        String[] known = {
                "com.tencent.map.geolocation.sapp.TencentLocation",
                "com.tencent.map.geolocation.sapp.TencentLocationManager",
                "com.tencent.map.geolocation.TencentLocation",
                "com.tencent.map.geolocation.TencentLocationManager",
        };
        for (String cn : known) {
            try { watchTencentClass(appCl.loadClass(cn)); } catch (Throwable ignored) {}
        }

        // 类加载监听：SDK 类名/包名未来怎么变，只要仍带 geolocation 包前缀即可自动捕获
        XC_MethodHook loaderHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Object r = p.getResult();
                if (r instanceof Class) watchTencentClass((Class<?>) r);
            }
        };
        safe("ClassLoader.loadClass(watch Tencent)", () -> {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, loaderHook);
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, loaderHook);
        });
        XposedBridge.log(TAG + "Tencent SDK watcher installed (小程序定位劫持已就绪)");
    }

    /** 判断并处理腾讯定位 SDK 类：只认包前缀，按简单类名分派（不依赖完整类名） */
    private void watchTencentClass(Class<?> clazz) {
        try {
            String name = clazz.getName();
            boolean tencent = name.startsWith("com.tencent.map.geolocation");
            // 模糊定位（wx.getFuzzyLocation）兜底：凡类名带 FuzzyLocation 且有坐标 getter 的一律接管
            boolean fuzzy = name.contains("FuzzyLocation");
            if (!tencent && !fuzzy) return;
            if (!tencentHooked.add(clazz)) return;
            String simple = clazz.getSimpleName();
            if (simple.equals("TencentLocation")) {
                patchTencentLocation(clazz);
            } else if (simple.equals("TencentLocationManager")) {
                patchTencentManager(clazz);
            } else if (fuzzy) {
                dlog("fuzzy class caught: " + name);
                patchTencentLocation(clazz); // 有 getLatitude 就 Hook，没有则 hookAllMethods 空转
            }
        } catch (Throwable t) {
            dlog("watchTencentClass failed: " + t);
        }
    }

    /** 类级别 Hook 腾讯定位对象的坐标出口：getter + 构造器/setter 字段级覆写，
     *  覆盖"读 getter"和"直接读字段"两类消费链路 */
    private void patchTencentLocation(final Class<?> clazz) {
        safe("TencentLocation.getLatitude", () ->
                XposedBridge.hookAllMethods(clazz, "getLatitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        double[] c = readFromTmp();
                        if (c != null) {
                            p.setResult(c[0] + getDrift());
                            dlog("getLatitude -> " + p.getResult());
                        }
                    }
                }));
        safe("TencentLocation.getLongitude", () ->
                XposedBridge.hookAllMethods(clazz, "getLongitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        double[] c = readFromTmp();
                        if (c != null) {
                            p.setResult(c[1] + getDrift());
                            dlog("getLongitude -> " + p.getResult());
                        }
                    }
                }));
        safe("TencentLocation.getProvider", () ->
                XposedBridge.hookAllMethods(clazz, "getProvider", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null) p.setResult("gps");
                    }
                }));
        // 精度假装成真实 GPS 水平（accuracy=0 会被服务端当作不可信坐标拒绝）
        safe("TencentLocation.getAccuracy", () ->
                XposedBridge.hookAllMethods(clazz, "getAccuracy", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null) p.setResult(5.0f + new Random().nextFloat() * 10);
                    }
                }));
        // 坐标系类型固定为 GCJ-02（注入的坐标来自高德地图即火星坐标；
        // 若对象谎报 WGS84，小程序侧换算会引入 ~500 米系统性偏差 → "距离过远"）
        safe("TencentLocation.getCoordinateType", () ->
                XposedBridge.hookAllMethods(clazz, "getCoordinateType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null) p.setResult(1); // COORDINATE_TYPE_GCJ02
                    }
                }));
        // 字段级覆写：对象刚构造出来就把 mLatitude/mLongitude 改成假坐标，
        // 后续任何直接读字段的内部链路（微信把结果转给小程序 JS 的桥接代码）都拿到假值
        safe("TencentLocation.<init>(field overwrite)", () ->
                XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        overwriteTencentFields(p.thisObject, "ctor");
                    }
                }));
        for (String s : new String[]{"setLatitude", "setLongitude", "setLat", "setLng"}) {
            safe("TencentLocation." + s + "(field overwrite)", () ->
                    XposedBridge.hookAllMethods(clazz, s, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            overwriteTencentFields(p.thisObject, "set");
                        }
                    }));
        }
        XposedBridge.log(TAG + "TencentLocation hooked: " + clazz.getName());
        dlog("TencentLocation hooked: " + clazz.getName());
    }

    /** 已 dump 过类结构的（防日志刷屏） */
    private final Set<String> dumpedTencentClasses =
            Collections.synchronizedSet(new HashSet<>());

    /** 运行时自省 + 字段级覆写：dump 出对象真实类/字段/值（每次类只 dump 一次），
     *  再按字段名（lat/lon）启发式覆写——壳类无字段，真实数据在运行时子类里，只能运行时看 */
    private void overwriteTencentFields(Object loc, String where) {
        if (loc == null) return;
        double[] cfg = readFromTmp();
        try {
            Class<?> c = loc.getClass();
            if (dumpedTencentClasses.add(c.getName())) {
                StringBuilder sb = new StringBuilder("loc@" + where + " class=" + c.getName());
                Class<?> k = c;
                int lv = 0;
                while (k != null && lv++ < 5 && !k.getName().startsWith("java.")) {
                    sb.append(" | ").append(k.getName()).append("{");
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        sb.append(f.getName()).append(":").append(f.getType().getSimpleName());
                        try {
                            f.setAccessible(true);
                            Object val = f.get(loc);
                            if (val instanceof double[]) sb.append("=").append(java.util.Arrays.toString((double[]) val));
                            else if (val instanceof float[]) sb.append("=").append(java.util.Arrays.toString((float[]) val));
                            else if (val instanceof int[]) sb.append("=").append(java.util.Arrays.toString((int[]) val));
                            else if (val instanceof long[]) sb.append("=").append(java.util.Arrays.toString((long[]) val));
                            else if (val instanceof Object[]) sb.append("=").append(java.util.Arrays.toString((Object[]) val));
                            else {
                                sb.append("=").append(val);
                                // 嵌套对象一层展开（审计盲区：ev 等对象的内部字段）
                                if (val != null && val != loc && !val.getClass().getName().startsWith("java.")
                                        && !val.getClass().getName().startsWith("android.")) {
                                    for (java.lang.reflect.Field f2 : val.getClass().getDeclaredFields()) {
                                        try {
                                            f2.setAccessible(true);
                                            sb.append(" {").append(f2.getName()).append("=").append(f2.get(val)).append("}");
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        sb.append(", ");
                    }
                    sb.append("}");
                    k = k.getSuperclass();
                }
                dlog(sb.toString());
            }
            if (cfg == null) return;
            // 按字段名启发式覆写（lat / lon / lng），逐字段容错；
            // 递归一层嵌套对象（ez 包着 TxLocation，经纬度/精度在嵌套体里）
            double lat = cfg[0] + getDrift(), lng = cfg[1] + getDrift();
            overwriteInObject(loc, lat, lng, 0);
        } catch (Throwable t) {
            dlog("dump/overwrite fail: " + t);
        }
    }

    /** 清洗 android Location 对象：坐标（getter 已 Hook）+ 其 extras Bundle 里的原始卫星数据 */
    private void scrubLocationExtras(Object locObj) {
        try {
            Location sl = (Location) locObj;
            applyLocationFix(sl);
            scrubBundle(sl.getExtras());
        } catch (Throwable ignored) {}
    }

    /** 递归清洗 Bundle：raw_gps 等 Location 值、嵌套 Bundle、lat/lng 原始 double 全部替换为假坐标。
     *  这是打卡类应用服务端交叉校验的真凶：坐标 getter 被改了，但 Bundle 里的原始 GNSS 值一直是真的 */
    private void scrubBundle(Bundle b) {
        if (b == null) return;
        double[] cfg = readFromTmp();
        if (cfg == null) return;
        double lat = cfg[0] + getDrift(), lng = cfg[1] + getDrift();
        for (String key : new ArrayList<>(b.keySet())) {
            try {
                Object v = b.get(key);
                if (v instanceof Location) {
                    scrubLocationExtras(v);
                    dlog("bundle scrub raw Location@" + key);
                } else if (v instanceof Bundle) {
                    scrubBundle((Bundle) v);
                } else if (v instanceof Double) {
                    String k = key.toLowerCase();
                    double dv = (Double) v;
                    if (k.contains("lat") && dv > -90 && dv < 90) {
                        b.putDouble(key, lat);
                        dlog("bundle " + key + " -> " + lat);
                    } else if ((k.contains("lng") || k.contains("lon")) && dv > -180 && dv < 180) {
                        b.putDouble(key, lng);
                        dlog("bundle " + key + " -> " + lng);
                    }
                } else if (key.equals("locationId") || key.equals("locationId".toLowerCase())) {
                    // 腾讯 LBS 服务端位置索引：打卡服务器可凭它向腾讯反查真实位置 → 清空断链
                    b.putString(key, "");
                    dlog("bundle locationId cleared");
                } else if (key.equals("KEY_IS_HIGH_ACCURACY")) {
                    // 高精模式标记：置 false，降低服务端走腾讯 LBS 校验的概率
                    b.putBoolean(key, false);
                    dlog("bundle high_accuracy -> false");
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 在对象及其嵌套对象（同 SDK 包内，深度≤2）里按字段名覆写 lat/lon/accuracy */
    private void overwriteInObject(Object obj, double lat, double lng, int depth) {
        if (obj == null || depth > 2) return;
        Class<?> k = obj.getClass();
        int lv = 0;
        while (k != null && lv++ < 5 && !k.getName().startsWith("java.")) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                try {
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    if ((ft == double.class || ft == Double.class)) {
                        if (n.contains("lat")) { f.set(obj, lat); dlog("set " + k.getSimpleName() + "." + f.getName() + "=" + lat); }
                        else if (n.contains("lon") || n.contains("lng")) { f.set(obj, lng); dlog("set " + k.getSimpleName() + "." + f.getName() + "=" + lng); }
                        else {
                            // 占位字段兜底：u=-91.0/v=-181.0 是经纬度的非法默认值标记
                            Object cur = f.get(obj);
                            if (cur instanceof Double) {
                                double dv = (Double) cur;
                                if (dv > -91.6 && dv < -90.4) { f.set(obj, lat); dlog("set " + k.getSimpleName() + "." + f.getName() + " (lat slot) =" + lat); }
                                else if (dv > -181.6 && dv < -180.4) { f.set(obj, lng); dlog("set " + k.getSimpleName() + "." + f.getName() + " (lng slot) =" + lng); }
                            }
                        }
                    } else if ((ft == float.class || ft == Float.class) && n.contains("accuracy")) {
                        f.set(obj, 5.0f + new Random().nextFloat() * 10);
                        dlog("set " + k.getSimpleName() + "." + f.getName() + " accuracy");
                    } else if ((ft == int.class || ft == Integer.class) && n.contains("coordinatetype")) {
                        f.set(obj, 1); // GCJ02
                    } else if (ft == Location.class) {
                        // android Location 字段（如 ez.n / raw_gps）：连同其 extras 里的原始卫星数据一起洗
                        scrubLocationExtras(f.get(obj));
                    } else if (ft == Bundle.class) {
                        Object v = f.get(obj);
                        if (v instanceof Bundle) scrubBundle((Bundle) v);
                    } else if (!ft.isPrimitive() && ft != String.class && depth < 2) {
                        // 递归嵌套对象（限制 SDK 包内，防误伤）
                        Object nested = f.get(obj);
                        if (nested != null && nested.getClass().getName().startsWith("c.t.m.")
                                || nested != null && nested.getClass().getName().startsWith("com.tencent.map")) {
                            overwriteInObject(nested, lat, lng, depth + 1);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            k = k.getSuperclass();
        }
    }

    /** Hook 腾讯定位管理器：顺藤摸瓜补挂代理类 + 拦截 request/get 系列方法里的监听器 */
    private void patchTencentManager(Class<?> clazz) {
        safe("TencentLocationManager.getInstance", () ->
                XposedBridge.hookAllMethods(clazz, "getInstance", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object mgr = p.getResult();
                        if (mgr == null) return;
                        for (String f : new String[]{"mProxyClass", "mProxyObj", "mProxy"}) {
                            try {
                                Object v = XposedHelpers.getObjectField(mgr, f);
                                if (v instanceof Class) watchTencentClass((Class<?>) v);
                                else if (v != null) watchTencentClass(v.getClass());
                            } catch (Throwable ignored) {}
                        }
                    }
                }));
        // 通杀 request*/get* 系列：日志记录调用了哪条路，参数里的监听器自动挂 onLocationChanged，
        // 返回值若是腾讯定位对象立即字段级覆写
        safe("TencentLocationManager request/get hooks", () -> {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                final String n = m.getName();
                if (!n.startsWith("request") && !n.startsWith("get")) continue;
                XposedBridge.hookAllMethods(clazz, n, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        dlog("tencent." + n + "(" + argClasses(p.args) + ")");
                        for (Object arg : p.args) hookTencentListenerOnce(arg);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object r = p.getResult();
                        if (r != null && r.getClass().getName().startsWith("com.tencent.map.geolocation")) {
                            overwriteTencentFields(r, "mgr." + n);
                            dlog("tencent." + n + " returned Tencent obj -> overwritten");
                        }
                    }
                });
            }
        });
        // 坐标系管道锁定：无论微信请求什么模式，SDK 输出一律按 GCJ-02 对待，
        // 防止 WGS-84 模式下注入的火星坐标被二次换算（~500 米偏差）
        safe("TencentLocationManager.setCoordinateType", () ->
                XposedBridge.hookAllMethods(clazz, "setCoordinateType", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null && p.args.length > 0 && p.args[0] instanceof Integer) {
                            dlog("setCoordinateType requested=" + p.args[0] + " -> force GCJ02(1)");
                            p.args[0] = 1;
                        }
                    }
                }));
        safe("TencentLocationManager.getCoordinateType", () ->
                XposedBridge.hookAllMethods(clazz, "getCoordinateType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null) p.setResult(1);
                    }
                }));
        XposedBridge.log(TAG + "TencentLocationManager hooked: " + clazz.getName());
        dlog("TencentLocationManager hooked: " + clazz.getName());
    }

    /** 给 request 方法参数里带 onLocationChanged 的对象挂回调监听（微信自己的监听器类也覆盖） */
    private void hookTencentListenerOnce(Object arg) {
        if (arg == null) return;
        Class<?> lc = arg.getClass();
        try {
            boolean has = false;
            for (java.lang.reflect.Method m : lc.getDeclaredMethods()) {
                if (m.getName().equals("onLocationChanged")) { has = true; break; }
            }
            if (!has) return;
            if (!tencentHooked.add(lc)) return;
            XposedBridge.hookAllMethods(lc, "onLocationChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    for (Object a : p.args) {
                        if (a == null) continue;
                        // 具体类若重写了 getter（壳类的运行时子类），现场补挂
                        try {
                            boolean overrides = false;
                            for (java.lang.reflect.Method m : a.getClass().getDeclaredMethods()) {
                                if (m.getName().equals("getLatitude") || m.getName().equals("getLongitude")) { overrides = true; break; }
                            }
                            if (overrides && tencentHooked.add(a.getClass())) {
                                patchTencentLocation(a.getClass());
                            }
                        } catch (Throwable ignored) {}
                        overwriteTencentFields(a, "listener");
                    }
                    dlog("onLocationChanged fired: " + lc.getName());
                }
            });
            dlog("listener hooked: " + lc.getName());
        } catch (Throwable t) {
            dlog("listener hook failed: " + t);
        }
    }

    /** 参数类名拼接（诊断日志用） */
    private String argClasses(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) sb.append(",");
            sb.append(a == null ? "null" : a.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 精简调用栈（诊断日志用）：只留应用/SDK 帧，最多 6 层，识别采集方是谁 */
    private String shortStack() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            String s = el.toString();
            if (s.contains("android.") || s.contains("java.") || s.contains("de.robv")
                    || s.contains("HookMain") || s.contains("com.android.internal")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(s);
            if (++n >= 6) break;
        }
        return sb.toString();
    }

    private void applyLocationFix(Location loc) {
        if (loc == null) return;
        double[] c = readFromTmp();
        if (c == null) return;

        double d = getDrift();
        // 优先公开 API（各 Android 版本稳定），失败再回退私有字段
        try {
            loc.setLatitude(c[0] + d);
            loc.setLongitude(c[1] + d);
        } catch (Throwable t) {
            try {
                XposedHelpers.setDoubleField(loc, "mLatitude", c[0] + d);
                XposedHelpers.setDoubleField(loc, "mLongitude", c[1] + d);
            } catch (Throwable ignored) {}
        }
        try { loc.setAccuracy(3.0f + new Random().nextFloat()); } catch (Throwable ignored) {}
        try { loc.setProvider(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
        try { loc.setSpeed(0.0f); } catch (Throwable ignored) {}
        try { loc.setAltitude(50.0d); } catch (Throwable ignored) {}
        try { loc.setTime(System.currentTimeMillis()); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 17) {
            try { loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()); } catch (Throwable ignored) {}
        }
        // 旧系统 mock 标记字段（新系统上可能不存在，忽略失败）
        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false); } catch (Throwable ignored) {}
        // 关键：清洗 extras 里 GPS 硬件（HAL）写入的原始坐标！
        // getLatitude() 被 Hook 了，但厂商在 extras 里写的 lat/lng 原始值会被腾讯 SDK
        // 直读并上传到腾讯 LBS（lbs.map.qq.com），经 locationId 反查暴露真实位置
        try {
            Bundle ex = loc.getExtras();
            if (ex != null) scrubBundle(ex);
        } catch (Throwable ignored) {}
    }

    private double getDrift() { return (new Random().nextDouble() - 0.5) * 0.00002; }

    private double[] readFromTmp() {
        // 500ms 内复用上次结果，降低文件 IO 频率
        long now = System.currentTimeMillis();
        if (now - lastReadTime < 500) return lastConfig;

        double[] result = null;
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) throw new RuntimeException("config missing");
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            String line = raf.readLine();
            raf.close();
            if (line != null) {
                String[] p = line.split(",");
                if (p.length >= 3 && "1".equals(p[2])) {
                    // 读取文件中的动态身份
                    if (p.length >= 5) {
                        cachedMac = p[3];
                        cachedSSID = p[4];
                    }
                    result = new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
                }
            }
        } catch (Throwable e) {
            if (!loggedFileMissing) {
                loggedFileMissing = true;
                XposedBridge.log(TAG + "Cannot read " + FILE_PATH
                        + " (请在 Shadow 中点击\"开始模拟\"后重试): " + e);
            }
        }
        lastReadTime = now;
        lastConfig = result;
        return result;
    }
}
