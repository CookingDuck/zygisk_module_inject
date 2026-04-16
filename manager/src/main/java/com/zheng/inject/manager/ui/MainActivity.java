package com.zheng.inject.manager.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.topjohnwu.superuser.Shell;
import com.zheng.inject.manager.R;
import com.zheng.inject.manager.model.InjectConfig;
import com.zheng.inject.manager.repository.ConfigRepository;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements AppConfigAdapter.OnItemClickListener {
    private ConfigRepository repository;
    private List<InjectConfig> configs = new ArrayList<>();
    private AppConfigAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new ConfigRepository();
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new AppConfigAdapter(this, configs, this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v -> {
            startActivityForResult(new Intent(this, AppPickerActivity.class), 100);
        });

        checkEnvironment();
    }

    private void checkEnvironment() {
        // 使用 libsu 的异步任务执行检查
        Shell.getShell(shell -> {
            if (!shell.isRoot()) {
                showCriticalError("Root提醒", "获取Root失败");
                return;
            }

            if (!repository.isModuleInstalled()) {
                showCriticalError("模块提醒", "Zygisk模块没有找到对应目录，检查是否安装模块");
                return;
            }

            // 环境正常，加载数据
            runOnUiThread(this::loadData);
        });
    }

    private void showCriticalError(String title, String message) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Exit", (dialog, which) -> finish())
                    .setNeutralButton("Retry", (dialog, which) -> checkEnvironment())
                    .show();
        });
    }

    private void loadData() {
        configs.clear();
        configs.addAll(repository.loadConfigs());
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onItemClick(InjectConfig config) {
        showEditDialog(config);
    }

    private void saveAndNotify() {
        if (!repository.saveConfigs(configs)) {
            Toast.makeText(this, "Failed to save config to Magisk module!", Toast.LENGTH_LONG).show();
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onToggleChange(InjectConfig config, boolean isChecked) {
        config.loadSo = isChecked; // 确保状态同步到模型
        saveAndNotify();
    }

    private void showEditDialog(InjectConfig config) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_config, null);
        EditText etSoName = view.findViewById(R.id.etSoName);
        Spinner spinnerModel = view.findViewById(R.id.spinnerModel);

        etSoName.setText(config.soName);

        String[] models = {"memfd", "custom_linker", "memfd_jit"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(spinnerAdapter);

        for (int i = 0; i < models.length; i++) {
            if (models[i].equals(config.injectModel)) {
                spinnerModel.setSelection(i);
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Edit Injection Settings")
                .setView(view)
                .setPositiveButton("Save", (dialog, which) -> {
                    config.soName = etSoName.getText().toString();
                    config.injectModel = spinnerModel.getSelectedItem().toString();
                    saveAndNotify();
                })
                .setNegativeButton("Delete", (dialog, which) -> {
                    configs.remove(config);
                    saveAndNotify();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra("package");
            // 检查是否已存在
            for (InjectConfig c : configs) {
                if (c.packageName.equals(pkg)) return;
            }
            configs.add(new InjectConfig(pkg));
            saveAndNotify();
        }
    }
}
