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
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.bluetooth.BluetoothAdapter;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
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
        if (TENCENT_SDK_PKGS.contains(lpparam.packageName)) {
            safe("Application.attach(TencentSDK watcher)", () ->
                    XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            installTencentSdkWatcher((Context) param.args[0]);
                        }
                    }));
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
        final ClassLoader appCl = appContext.getClassLoader();

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
            if (!name.startsWith("com.tencent.map.geolocation")) return;
            if (!tencentHooked.add(clazz)) return;
            String simple = clazz.getSimpleName();
            if (simple.equals("TencentLocation")) {
                patchTencentLocation(clazz);
            } else if (simple.equals("TencentLocationManager")) {
                patchTencentManager(clazz);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "watchTencentClass failed: " + t);
        }
    }

    /** 类级别 Hook 腾讯定位对象的坐标出口：所有实例生效，与 SDK 内部链路无关 */
    private void patchTencentLocation(final Class<?> clazz) {
        safe("TencentLocation.getLatitude", () ->
                XposedBridge.hookAllMethods(clazz, "getLatitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        double[] c = readFromTmp();
                        if (c != null) p.setResult(c[0] + getDrift());
                    }
                }));
        safe("TencentLocation.getLongitude", () ->
                XposedBridge.hookAllMethods(clazz, "getLongitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        double[] c = readFromTmp();
                        if (c != null) p.setResult(c[1] + getDrift());
                    }
                }));
        safe("TencentLocation.getProvider", () ->
                XposedBridge.hookAllMethods(clazz, "getProvider", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (readFromTmp() != null) p.setResult("gps");
                    }
                }));
        XposedBridge.log(TAG + "TencentLocation hooked: " + clazz.getName());
    }

    /** Hook 腾讯定位管理器：顺藤摸瓜补挂代理类（字段可能不存在，逐个容错） */
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
        XposedBridge.log(TAG + "TencentLocationManager hooked: " + clazz.getName());
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
