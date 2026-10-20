package com.kankan.globaltraveling;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements Inputtips.InputtipsListener {

    private MapView mapView;
    private AMap aMap;
    private TextView tvStatus;
    private AutoCompleteTextView etSearch;
    private LinearLayout historyContainer;

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String PREF_NAME = "IdentityConfig";
    private static final String PREF_HISTORY = "LocHistory";
    private static final String KEY_HISTORY = "history_list";
    private static final String KEY_DEF_LAT = "def_lat";
    private static final String KEY_DEF_LNG = "def_lng";
    private static final String KEY_DEF_NAME = "def_name";

    // 免责声明 Key
    private static final String PREF_APP = "AppConfig";
    private static final String KEY_AGREED = "is_agreed_disclaimer";

    // 高德地图 API Key（用户在弹窗中填写并保存）
    private static final String KEY_AMAP = "amap_api_key";
    // 动态取当前应用 ID（改包名后无需改代码）
    private static final String AMAP_PKG = BuildConfig.APPLICATION_ID;

    private String fixedMac;
    private String fixedSSID;
    private double selectLat = 0;
    private double selectLng = 0;
    private String currentName = "手动选点";

    // 防抖
    private Handler searchHandler = new Handler();
    private Runnable searchRunnable;

    // 记录最近一次搜索词（供高德接口失败时的备用搜索使用）
    private String lastQuery = "";

    // 保存 SavedInstanceState 供延迟初始化使用
    private Bundle savedState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 1. 高德合规 (必须最先执行)
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Throwable t) {}

        super.onCreate(savedInstanceState);

        // --- 核心修改：完全透明 + 黑色图标 ---

        // 1. 把背景色设为【完全透明】，去掉任何白色/浅蓝色的遮挡
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        // 2. 组合标志位：
        // LAYOUT_FULLSCREEN: 让地图顶上去
        // LIGHT_STATUS_BAR:  让图标变黑 (因为地图通常是浅色的)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        );

        setContentView(R.layout.activity_main);

        // 3. 检查免责声明
        if (checkDisclaimer()) {
            initApp();
        }
    }

    /**
     * 检查并显示免责声明
     */
    private boolean checkDisclaimer() {
        SharedPreferences prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE);
        boolean isAgreed = prefs.getBoolean(KEY_AGREED, false);

        if (isAgreed) {
            return true; // 已同意，继续
        }

        // 显示弹窗
        new AlertDialog.Builder(this)
                .setTitle("使用须知")
                .setMessage("欢迎使用 Shadow！\n\n" +
                        "1. 本软件仅供技术研究与学习使用，禁止用于任何非法用途（如考勤作弊、游戏外挂等）。\n" +
                        "2. 使用本软件产生的任何后果由用户自行承担，开发者不承担任何责任。\n" +
                        "3. 本软件需要 Root 权限才能运行，请确保您了解 Root 的风险。\n\n" +
                        "点击“同意并继续”即表示您已阅读并接受上述条款。")
                .setCancelable(false)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_AGREED, true).apply();
                    initApp(); // 用户点击同意后，才初始化 App
                })
                .setNegativeButton("拒绝并退出", (dialog, which) -> {
                    finish();
                    System.exit(0);
                })
                .show();

        return false; // 暂停初始化
    }

    /**
     * 核心初始化逻辑 (布局加载、地图初始化)
     */
    private void initApp() {
        // 必须在 MapView 初始化之前把 Key 注入 SDK
        String amapKey = applyAmapKey();

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        etSearch = findViewById(R.id.et_search);
        historyContainer = findViewById(R.id.history_container);

        mapView = findViewById(R.id.map);
        // 使用保存的 Bundle 初始化地图
        mapView.onCreate(savedState);
        if (aMap == null) aMap = mapView.getMap();

        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));

        initIdentity();
        loadHistory();
        loadDefaultLocation();

        aMap.setOnMapLongClickListener(latLng -> updateSelection(latLng.latitude, latLng.longitude, "手动选点"));

        // 搜索逻辑 (带防抖)
        etSearch.setThreshold(1);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
            }
            @Override public void afterTextChanged(Editable s) {
                searchRunnable = () -> {
                    String newText = s.toString().trim();
                    if (newText.length() > 0) {
                        lastQuery = newText;
                        InputtipsQuery inputquery = new InputtipsQuery(newText, "");
                        inputquery.setCityLimit(false);
                        Inputtips inputTips = new Inputtips(MainActivity.this, inputquery);
                        inputTips.setInputtipsListener(MainActivity.this);
                        inputTips.requestInputtipsAsyn();
                    }
                };
                searchHandler.postDelayed(searchRunnable, 600);
            }
        });

        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = (Tip) parent.getItemAtPosition(position);
            if (tip.getPoint() != null) {
                double lat = tip.getPoint().getLatitude();
                double lon = tip.getPoint().getLongitude();
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 16));
                updateSelection(lat, lon, tip.getName());
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                etSearch.setText("");
                etSearch.clearFocus();
            }
        });

        // 按钮事件
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            if (selectLat == 0) {
                Toast.makeText(this, "请先在地图上长按或搜索选点", Toast.LENGTH_SHORT).show();
                return;
            }
            writeToSystemTmp(selectLat + "," + selectLng + ",1," + fixedMac + "," + fixedSSID, true);
            saveHistory(currentName, selectLat, selectLng);
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> writeToSystemTmp("0,0,0,0,0", false));

        findViewById(R.id.btn_set_default).setOnClickListener(v -> {
            if (selectLat == 0) {
                Toast.makeText(this, "请先选点", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE).edit()
                    .putFloat(KEY_DEF_LAT, (float) selectLat)
                    .putFloat(KEY_DEF_LNG, (float) selectLng)
                    .putString(KEY_DEF_NAME, currentName)
                    .apply();
            Toast.makeText(this, "✅ 已设为默认位置", Toast.LENGTH_SHORT).show();
        });

        // 长按顶部状态文字可重新填写高德 Key
        tvStatus.setOnLongClickListener(v -> {
            showApiKeyDialog();
            return true;
        });

        // 未填 Key 时主动提示（地图瓦片与高德搜索需要 Key；
        // 搜索同时内置了免 Key 备用通道，不填也能搜，但地图无法加载）
        if (amapKey.isEmpty()) {
            tvStatus.post(this::showApiKeyDialog);
        }
    }

    /** 读取用户保存的高德 Key 并注入地图/搜索 SDK（必须在 MapView 创建前调用） */
    private String applyAmapKey() {
        String key = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE).getString(KEY_AMAP, "");
        if (!key.isEmpty()) {
            try { MapsInitializer.setApiKey(key); } catch (Throwable t) {
                Log.w("Shadow", "地图 setApiKey 失败: " + t);
            }
            try { ServiceSettings.getInstance().setApiKey(key); } catch (Throwable t) {
                Log.w("Shadow", "搜索 setApiKey 失败: " + t);
            }
        }
        return key;
    }

    /** 高德 API Key 填写弹窗（恢复原版的功能）。弹窗内展示本包名与当前签名 SHA1，
     *  方便用户在高德控制台创建/修改 Key 时直接复制。 */
    private void showApiKeyDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("AMap-xxxxxxxxxxxxxxxxxxxxxxxx");
        input.setText(getSharedPreferences(PREF_APP, Context.MODE_PRIVATE).getString(KEY_AMAP, ""));
        input.setSelection(input.getText().length());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp2px(24), dp2px(8), dp2px(24), 0);
        TextView tip = new TextView(this);
        tip.setText("到 lbs.amap.com 控制台创建应用并创建 Key：\n" +
                "包名：" + AMAP_PKG + "\n" +
                "SHA1：" + getSignatureSha1() + "\n\n" +
                "把 Key 粘贴到下方保存即可。\n" +
                "（提示：不填 Key 也能搜索，走备用通道；但地图瓦片必须有 Key 才能显示。\n" +
                "之后可长按顶部「已选」状态文字重新填写）");
        tip.setTextSize(12);
        tip.setTextColor(Color.parseColor("#666666"));
        box.addView(tip);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("高德地图 API Key")
                .setView(box)
                .setCancelable(true)
                .setPositiveButton("保存", (dialog, which) -> {
                    String k = input.getText().toString().trim();
                    getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
                            .edit().putString(KEY_AMAP, k).apply();
                    Toast.makeText(this, "已保存，正在重新加载地图…", Toast.LENGTH_SHORT).show();
                    recreate(); // 重新走初始化流程，让新 Key 生效
                })
                .setNegativeButton("暂不填写", null)
                .show();
    }

    /** 获取当前安装包签名的 SHA1（高德 Key 绑定 包名+SHA1） */
    private String getSignatureSha1() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            Signature sig = info.signatures[0];
            MessageDigest md = MessageDigest.getInstance("SHA1");
            byte[] digest = md.digest(sig.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02X", digest[i]));
                if (i < digest.length - 1) sb.append(":");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "获取失败";
        }
    }

    // --- 辅助逻辑保持不变 ---

    private void loadDefaultLocation() {
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        float lat = prefs.getFloat(KEY_DEF_LAT, 0);
        float lng = prefs.getFloat(KEY_DEF_LNG, 0);
        String name = prefs.getString(KEY_DEF_NAME, "默认位置");
        if (lat != 0 && lng != 0) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 16));
            updateSelection(lat, lng, name);
        } else {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));
        }
    }

    private void loadHistory() {
        historyContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        if (raw.isEmpty()) return;
        String[] list = raw.split(",");
        for (String item : list) {
            String[] p = item.split("\\|");
            if (p.length < 3) continue;
            addHistoryChip(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        }
    }

    private void saveHistory(String name, double lat, double lng) {
        if (name.equals("手动选点")) return;
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        String newItem = name + "|" + lat + "|" + lng;
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) { for (String s : raw.split(",")) list.add(s); }
        for (int i = 0; i < list.size(); i++) { if (list.get(i).startsWith(name + "|")) { list.remove(i); break; } }
        list.add(0, newItem);
        if (list.size() > 8) list = list.subList(0, 8);
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        loadHistory();
    }

    private void deleteHistory(String name) {
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        List<String> list = new ArrayList<>();
        for (String s : raw.split(",")) { if (!s.startsWith(name + "|")) list.add(s); }
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        loadHistory();
    }

    private void addHistoryChip(String name, double lat, double lng) {
        CardView card = new CardView(this);
        card.setCardBackgroundColor(Color.parseColor("#F0F7FF"));
        card.setRadius(dp2px(20));
        card.setCardElevation(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp2px(4), dp2px(12), dp2px(4));
        card.setLayoutParams(params);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp2px(16), dp2px(8), dp2px(12), dp2px(8));
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(Color.parseColor("#333333"));
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setOnClickListener(v -> {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 16));
            updateSelection(lat, lng, name);
        });
        TextView btnDel = new TextView(this);
        btnDel.setText("✕");
        btnDel.setTextColor(Color.parseColor("#AABBCF"));
        btnDel.setTextSize(12);
        btnDel.setPadding(dp2px(10), 0, 0, 0);
        btnDel.setOnClickListener(v -> deleteHistory(name));
        layout.addView(tv);
        layout.addView(btnDel);
        card.addView(layout);
        historyContainer.addView(card);
    }

    @Override
    public void onGetInputtips(List<Tip> tipList, int rCode) {
        if (rCode == 1000 && tipList != null && !tipList.isEmpty()) {
            showTips(tipList);
        } else {
            // 高德接口失败（未填/无效 Key、QPS 限流等）→ 自动切换免 Key 备用搜索
            Log.w("Shadow", "高德 Inputtips 失败 rCode=" + rCode + "，切换备用搜索");
            fallbackSearch(lastQuery);
        }
    }

    /** 把结果展示到搜索框下拉列表 */
    private void showTips(List<Tip> tips) {
        ArrayAdapter<Tip> adapter = new ArrayAdapter<Tip>(this, android.R.layout.simple_list_item_1, tips) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view.findViewById(android.R.id.text1);
                text.setText(getItem(position).getName());
                return view;
            }
        };
        etSearch.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        runOnUiThread(() -> {
            if (etSearch.hasFocus() && etSearch.getText().length() > 0) etSearch.showDropDown();
        });
    }

    /** 免 Key 备用搜索：优先 Photon，失败再试 Nominatim（均为 OpenStreetMap 公共服务） */
    private void fallbackSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String q = keyword.trim();
        new Thread(() -> {
            List<Tip> tips = searchPhoton(q);
            if (tips.isEmpty()) tips = searchNominatim(q);
            final List<Tip> finalTips = tips;
            runOnUiThread(() -> {
                if (!finalTips.isEmpty()) {
                    showTips(finalTips);
                } else {
                    Toast.makeText(MainActivity.this,
                            "搜索失败：请检查网络，或在高德控制台确认 Key 有效",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private List<Tip> searchPhoton(String q) {
        List<Tip> result = new ArrayList<>();
        try {
            String url = "https://photon.komoot.io/api/?limit=6&lang=zh&q=" + URLEncoder.encode(q, "UTF-8");
            JSONObject root = new JSONObject(httpGet(url));
            JSONArray features = root.optJSONArray("features");
            if (features != null) {
                for (int i = 0; i < features.length(); i++) {
                    JSONObject f = features.optJSONObject(i);
                    if (f == null) continue;
                    JSONObject geom = f.optJSONObject("geometry");
                    JSONObject p = f.optJSONObject("properties");
                    JSONArray c = geom == null ? null : geom.optJSONArray("coordinates");
                    if (c == null || p == null || c.length() < 2) continue;
                    double lng = c.optDouble(0, Double.NaN);
                    double lat = c.optDouble(1, Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                    String name = joinNonEmpty(p.optString("name"), p.optString("city"),
                            p.optString("state"), p.optString("country"));
                    if (name.isEmpty()) continue;
                    // Photon 返回 WGS-84，转成高德地图使用的 GCJ-02 坐标系
                    double[] gcj = wgs84ToGcj02(lng, lat);
                    result.add(makeTip(name, gcj[1], gcj[0]));
                }
            }
        } catch (Throwable t) {
            Log.w("Shadow", "Photon 搜索失败: " + t);
        }
        return result;
    }

    private List<Tip> searchNominatim(String q) {
        List<Tip> result = new ArrayList<>();
        try {
            String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&accept-language=zh&limit=6&q="
                    + URLEncoder.encode(q, "UTF-8");
            JSONArray arr = new JSONArray(httpGet(url));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", Double.NaN);
                double lng = o.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                String name = o.optString("display_name", "");
                if (name.isEmpty()) continue;
                double[] gcj = wgs84ToGcj02(lng, lat);
                result.add(makeTip(name, gcj[1], gcj[0]));
            }
        } catch (Throwable t) {
            Log.w("Shadow", "Nominatim 搜索失败: " + t);
        }
        return result;
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "GlobalTraveling/1.1 (Android app)");
        try {
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private Tip makeTip(String name, double lat, double lng) {
        Tip tip = new Tip();
        tip.setName(name);
        // 注意：高德 SDK 该方法名拼写有历史错误，是 setPostion（不是 setPoint）
        tip.setPostion(new LatLonPoint(lat, lng));
        return tip;
    }

    private String joinNonEmpty(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    // ==================== WGS-84 → GCJ-02 坐标转换（开源标准算法） ====================
    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    private static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    /** WGS-84 转 GCJ-02（火星坐标）。境外坐标原样返回。返回 [lng, lat] */
    private static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) return new double[]{lng, lat};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    private void updateSelection(double lat, double lng, String title) {
        selectLat = lat;
        selectLng = lng;
        currentName = title;
        aMap.clear();
        aMap.addMarker(new MarkerOptions().position(new LatLng(lat, lng)).title(title));
        tvStatus.setText("已选: " + title);
    }

    private void initIdentity() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        fixedMac = prefs.getString("mac", "");
        fixedSSID = prefs.getString("ssid", "");
        if (fixedMac.isEmpty()) {
            fixedMac = generateRandomMac();
            fixedSSID = generateRandomSSID();
            prefs.edit().putString("mac", fixedMac).putString("ssid", fixedSSID).apply();
        }
    }

    private String generateRandomMac() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        sb.append("ac");
        for (int i = 0; i < 5; i++) {
            sb.append(":");
            String s = Integer.toHexString(r.nextInt(256));
            if (s.length() < 2) sb.append("0");
            sb.append(s);
        }
        return sb.toString().toLowerCase();
    }

    private String generateRandomSSID() {
        String[] brands = {"TP-LINK", "Xiaomi", "HUAWEI", "Office-Guest"};
        return brands[new Random().nextInt(brands.length)] + "_" + (1000 + new Random().nextInt(8999));
    }

    private void writeToSystemTmp(String content, boolean isStart) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("echo \"" + content + "\" > " + FILE_PATH + "\n");
                os.writeBytes("chmod 666 " + FILE_PATH + "\n");
                os.writeBytes("chcon u:object_r:shell_data_file:s0 " + FILE_PATH + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int ret = p.waitFor();
                runOnUiThread(() -> {
                    if (ret == 0) {
                        String msg = isStart ? "模拟已启动" : "模拟已结束";
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Root授权失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private int dp2px(float dp) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    @Override protected void onDestroy() { super.onDestroy(); if(mapView!=null) mapView.onDestroy(); }
    @Override protected void onResume() { super.onResume(); if(mapView!=null) mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); if(mapView!=null) mapView.onPause(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if(mapView!=null) mapView.onSaveInstanceState(outState); }
}
