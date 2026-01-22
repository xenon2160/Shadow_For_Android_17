package com.kankan.globaltraveling;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String SYS_KEY_LOC = "kankan_mock_loc";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 不 Hook 系统进程和地图应用 (防止导航失效)
        if (lpparam.packageName.equals("android")) return;
        if (lpparam.packageName.contains("map") || lpparam.packageName.contains("nav")) return;
        // 不 Hook 自己
        if (lpparam.packageName.equals("com.kankan.globaltraveling")) return;

        // 1. Hook Location 获取经纬度
        XC_MethodHook locHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 获取当前 Context
                Context context = AndroidAppHelper.currentApplication();
                if (context == null) return;

                // 读取配置
                String data = Settings.Global.getString(context.getContentResolver(), SYS_KEY_LOC);
                if (data == null || data.isEmpty()) return;

                String[] parts = data.split("\\|");
                if (parts.length < 3) return;

                // 检查开关 (第三位是 "1" 才生效)
                if (!"1".equals(parts[2])) return;

                double lat = Double.parseDouble(parts[0]);
                double lng = Double.parseDouble(parts[1]);

                // 加入微小随机抖动 (防检测)
                double drift = (new Random().nextDouble() - 0.5) * 0.00002;

                if (param.method.getName().equals("getLatitude")) {
                    param.setResult(lat + drift);
                } else if (param.method.getName().equals("getLongitude")) {
                    param.setResult(lng + drift);
                }
            }
        };

        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", locHook);
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", locHook);

        // 2. 抹除模拟痕迹 (反检测核心)
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", XC_MethodReplacement.returnConstant(false));

        // 3. 屏蔽 Wi-Fi 定位 (防钉钉/企业微信通过Wi-Fi反查)
        XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (isMockingEnabled()) param.setResult(new ArrayList<>());
            }
        });

        // 4. 屏蔽基站定位
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (isMockingEnabled()) param.setResult(null);
            }
        });

        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (isMockingEnabled()) param.setResult(null);
            }
        });
    }

    // 辅助方法：检查是否开启
    private boolean isMockingEnabled() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) return false;
            String data = Settings.Global.getString(context.getContentResolver(), SYS_KEY_LOC);
            return data != null && data.endsWith("|1");
        } catch (Exception e) { return false; }
    }
}
