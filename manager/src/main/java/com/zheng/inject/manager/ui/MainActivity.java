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
        
        // 初始化 libsu (Root)
        Shell.getShell();

        repository = new ConfigRepository();
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        adapter = new AppConfigAdapter(this, configs, this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v -> {
            startActivityForResult(new Intent(this, AppPickerActivity.class), 100);
        });

        loadData();
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

    @Override
    public void onToggleChange(InjectConfig config, boolean isChecked) {
        repository.saveConfigs(configs);
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
                repository.saveConfigs(configs);
                adapter.notifyDataSetChanged();
            })
            .setNegativeButton("Delete", (dialog, which) -> {
                configs.remove(config);
                repository.saveConfigs(configs);
                adapter.notifyDataSetChanged();
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
            repository.saveConfigs(configs);
            adapter.notifyDataSetChanged();
        }
    }
}
