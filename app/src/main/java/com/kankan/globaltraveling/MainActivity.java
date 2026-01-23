package com.kankan.globaltraveling;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import java.io.DataOutputStream;

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private AMap aMap;
    private TextView tvStatus;

    // 【核心】数据交换文件路径
    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";

    private double selectLat = 0;
    private double selectLng = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // --- 1. 高德隐私合规 (必须在 super 前) ---
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);

        if (aMap == null) {
            aMap = mapView.getMap();
        }

        // 默认视角设为北京 (或者你喜欢的任何地方)
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));

        // --- 2. 地图长按选点逻辑 ---
        aMap.setOnMapLongClickListener(latLng -> {
            aMap.clear(); // 清除旧标记
            aMap.addMarker(new MarkerOptions().position(latLng).title("模拟目标"));
            selectLat = latLng.latitude;
            selectLng = latLng.longitude;
            tvStatus.setText(String.format("目标坐标: %.6f, %.6f", selectLat, selectLng));
        });

        // --- 3. 穿越按钮逻辑 ---
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            if (selectLat == 0 || selectLng == 0) {
                Toast.makeText(this, "请长按地图选择一个位置", Toast.LENGTH_SHORT).show();
                return;
            }
            // 写入格式：纬度,经度,开关(1)
            String content = selectLat + "," + selectLng + ",1";
            writeToSystemTmp(content);
        });

        // --- 4. 停止按钮逻辑 ---
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            writeToSystemTmp("0,0,0");
        });
    }

    /**
     * 使用 Root 权限将数据写入公共临时目录
     * 并修复权限和 SELinux 上下文，确保所有 App (QQ/JD) 都能读取
     */
    private void writeToSystemTmp(String content) {
        new Thread(() -> {
            try {
                // 请求 su 权限
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());

                // A. 写入文件
                os.writeBytes("echo \"" + content + "\" > " + FILE_PATH + "\n");

                // B. 修改权限为 666 (全员读写)
                os.writeBytes("chmod 666 " + FILE_PATH + "\n");

                // C. 【关键】修改 SELinux 上下文为 shell 数据文件，防止被系统拦截读取
                os.writeBytes("chcon u:object_r:shell_data_file:s0 " + FILE_PATH + "\n");

                os.writeBytes("exit\n");
                os.flush();
                int ret = p.waitFor();

                runOnUiThread(() -> {
                    if (ret == 0) {
                        Toast.makeText(this, "🚀 曼巴意志：坐标已锁定！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ 写入失败，请检查 Root 授权", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "异常: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // --- 5. 地图生命周期管理 ---
    @Override protected void onDestroy() { super.onDestroy(); if(mapView != null) mapView.onDestroy(); }
    @Override protected void onResume() { super.onResume(); if(mapView != null) mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); if(mapView != null) mapView.onPause(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if(mapView != null) mapView.onSaveInstanceState(outState); }
}
