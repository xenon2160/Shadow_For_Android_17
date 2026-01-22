package com.kankan.globaltraveling;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import java.io.DataOutputStream;

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private AMap aMap;
    private TextView tvStatus;

    // 存储在系统数据库中的 Key
    private static final String SYS_KEY_LOC = "kankan_mock_loc";

    private double selectLat = 0;
    private double selectLng = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

        // 初始化地图
        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        if (aMap == null) aMap = mapView.getMap();

        // 设置默认视角 (例如北京)
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));

        // 地图长按事件：选点
        aMap.setOnMapLongClickListener(latLng -> {
            aMap.clear(); // 清除旧标记
            aMap.addMarker(new MarkerOptions().position(latLng).title("目标位置"));
            selectLat = latLng.latitude;
            selectLng = latLng.longitude;
            tvStatus.setText("已选: " + String.format("%.4f", selectLat) + ", " + String.format("%.4f", selectLng));
        });

        // 按钮：开始模拟
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            if (selectLat == 0 || selectLng == 0) {
                Toast.makeText(this, "请先在地图上长按选点", Toast.LENGTH_SHORT).show();
                return;
            }
            // 格式: "纬度|经度|1" (1表示开启)
            String data = selectLat + "|" + selectLng + "|1";
            saveToSystem(data);
            Toast.makeText(this, "🚀 模拟已开启！", Toast.LENGTH_SHORT).show();
        });

        // 按钮：停止模拟
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            // 格式: "0|0|0" (0表示关闭)
            saveToSystem("0|0|0");
            Toast.makeText(this, "🛑 模拟已停止，恢复真实定位", Toast.LENGTH_SHORT).show();
        });
    }

    // Root 写入系统设置
    private void saveToSystem(String value) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("settings put global " + SYS_KEY_LOC + " \"" + value + "\"\n");
                os.writeBytes("exit\n");
                os.flush();
                p.waitFor();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "❌ Root 授权失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 地图生命周期管理 (必须写)
    @Override protected void onDestroy() { super.onDestroy(); mapView.onDestroy(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); mapView.onPause(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState); }
}
