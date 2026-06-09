package com.zheng.inject.manager.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zheng.inject.manager.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppPickerActivity extends AppCompatActivity {
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private AppInfoAdapter adapter;
    private final List<AppInfoAdapter.Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new AppInfoAdapter(this, items, appInfo -> {
            Intent data = new Intent();
            data.putExtra("package", appInfo.packageName);
            setResult(RESULT_OK, data);
            finish();
        });
        recyclerView.setAdapter(adapter);

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
                updateEmpty();
            }
        });

        loadApps();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_picker, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            showFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showFilterDialog() {
        String[] options = {
                getString(R.string.filter_user_apps),
                getString(R.string.filter_system_apps),
                getString(R.string.filter_all)
        };
        int checked = adapter.getFilter() == AppInfoAdapter.Filter.SYSTEM ? 1
                : adapter.getFilter() == AppInfoAdapter.Filter.ALL ? 2 : 0;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_filter)
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    AppInfoAdapter.Filter f = which == 1 ? AppInfoAdapter.Filter.SYSTEM
                            : which == 2 ? AppInfoAdapter.Filter.ALL
                            : AppInfoAdapter.Filter.USER;
                    adapter.setFilter(f);
                    updateEmpty();
                    dialog.dismiss();
                })
                .show();
    }

    private void loadApps() {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
            List<AppInfoAdapter.Item> built = new ArrayList<>(installedApps.size());
            for (ApplicationInfo info : installedApps) {
                if (info.packageName == null) continue;
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                String label;
                try {
                    label = pm.getApplicationLabel(info).toString();
                } catch (Exception e) {
                    label = info.packageName;
                }
                built.add(new AppInfoAdapter.Item(info, label, isSystem));
            }
            Collections.sort(built, (a, b) -> a.label.compareToIgnoreCase(b.label));

            runOnUiThread(() -> {
                items.clear();
                items.addAll(built);
                adapter.rebuild();
                progressBar.setVisibility(View.GONE);
                updateEmpty();
            });
        }).start();
    }

    private void updateEmpty() {
        tvEmpty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
