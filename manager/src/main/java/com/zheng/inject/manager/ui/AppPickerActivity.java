package com.zheng.inject.manager.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.zheng.inject.manager.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppPickerActivity extends AppCompatActivity {
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private AppInfoAdapter adapter;
    private List<ApplicationInfo> appList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        progressBar = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new AppInfoAdapter(this, appList, appInfo -> {
            Intent intent = new Intent();
            intent.putExtra("package", appInfo.packageName);
            setResult(RESULT_OK, intent);
            finish();
        });
        recyclerView.setAdapter(adapter);

        loadApps();
    }

    private void loadApps() {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> filteredApps = new ArrayList<>();
            
            for (ApplicationInfo info : installedApps) {
                // 过滤掉系统核心应用（可选）
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    filteredApps.add(info);
                }
            }

            // 按名称排序
            Collections.sort(filteredApps, (a, b) -> 
                pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString()));

            runOnUiThread(() -> {
                appList.clear();
                appList.addAll(filteredApps);
                adapter.notifyDataSetChanged();
                progressBar.setVisibility(View.GONE);
            });
        }).start();
    }
}
