package com.zheng.inject.manager.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.topjohnwu.superuser.Shell;
import com.zheng.inject.manager.R;
import com.zheng.inject.manager.model.InjectConfig;
import com.zheng.inject.manager.repository.ConfigRepository;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements AppConfigAdapter.OnItemClickListener {
    private static final int REQUEST_PICK_APP = 100;

    private ConfigRepository repository;
    private final List<InjectConfig> configs = new ArrayList<>();
    private AppConfigAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new ConfigRepository();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvEmpty = findViewById(R.id.tvEmpty);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppConfigAdapter(this, configs, this);
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

        FloatingActionButton fab = findViewById(R.id.fabAdd);
        fab.setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQUEST_PICK_APP));

        checkEnvironment();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@androidx.annotation.NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_refresh) {
            loadData();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showFilterDialog() {
        String[] items = {
                getString(R.string.filter_all),
                getString(R.string.filter_enabled),
                getString(R.string.filter_disabled)
        };
        int checked = adapter.getFilter() == AppConfigAdapter.Filter.ENABLED ? 1
                : adapter.getFilter() == AppConfigAdapter.Filter.DISABLED ? 2 : 0;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_filter)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    AppConfigAdapter.Filter f = which == 1 ? AppConfigAdapter.Filter.ENABLED
                            : which == 2 ? AppConfigAdapter.Filter.DISABLED
                            : AppConfigAdapter.Filter.ALL;
                    adapter.setFilter(f);
                    updateEmpty();
                    dialog.dismiss();
                })
                .show();
    }

    private void checkEnvironment() {
        Shell.getShell(shell -> {
            if (!shell.isRoot()) {
                showCriticalError(getString(R.string.error_root_title), getString(R.string.error_root_msg));
                return;
            }
            if (!repository.isModuleInstalled()) {
                showCriticalError(getString(R.string.error_module_title), getString(R.string.error_module_msg));
                return;
            }
            runOnUiThread(this::loadData);
        });
    }

    private void showCriticalError(String title, String message) {
        runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.action_exit, (d, w) -> finish())
                .setNeutralButton(R.string.action_retry, (d, w) -> checkEnvironment())
                .show());
    }

    private void loadData() {
        configs.clear();
        configs.addAll(repository.loadConfigs());
        adapter.refresh();
        updateEmpty();
    }

    private void updateEmpty() {
        tvEmpty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        tvEmpty.setText(configs.isEmpty() ? R.string.empty_no_config : R.string.empty_no_match);
    }

    @Override
    public void onItemClick(InjectConfig config) {
        showEditDialog(config);
    }

    @Override
    public void onToggleChange(InjectConfig config, boolean isChecked) {
        config.loadSo = isChecked;
        saveAndNotify();
    }

    private void saveAndNotify() {
        if (!repository.saveConfigs(configs)) {
            Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_LONG).show();
        }
        adapter.refresh();
        updateEmpty();
    }

    private void showEditDialog(InjectConfig config) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_config, null);
        TextInputLayout tilSoName = view.findViewById(R.id.tilSoName);
        TextInputEditText etSoName = view.findViewById(R.id.etSoName);
        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggleModel);
        MaterialButton btnMemfd = view.findViewById(R.id.btnMemfd);
        MaterialButton btnLinker = view.findViewById(R.id.btnLinker);
        MaterialButton btnJit = view.findViewById(R.id.btnJit);

        etSoName.setText(config.soName == null ? "" : config.soName);

        String model = config.injectModel == null ? "memfd" : config.injectModel;
        switch (model) {
            case "custom_linker": toggle.check(R.id.btnLinker); break;
            case "memfd_jit": toggle.check(R.id.btnJit); break;
            default: toggle.check(R.id.btnMemfd);
        }

        tilSoName.setEndIconOnClickListener(v -> showTmpFilePicker(etSoName));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_edit)
                .setView(view)
                .setPositiveButton(R.string.dialog_action_save, null)
                .setNeutralButton(R.string.dialog_action_delete, (d, w) -> {
                    configs.remove(config);
                    saveAndNotify();
                })
                .setNegativeButton(R.string.dialog_action_cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String newSo = etSoName.getText() == null ? "" : etSoName.getText().toString().trim();
                    if (TextUtils.isEmpty(newSo)) {
                        // 允许清空 SO 名（关闭注入），直接保存
                        applyAndSave(config, "", toggle);
                        dialog.dismiss();
                        return;
                    }
                    if (!ConfigRepository.isValidSoName(newSo)) {
                        Toast.makeText(this, R.string.toast_invalid_so_name, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!repository.tmpFileExists(newSo)) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.error_so_missing_title)
                                .setMessage(getString(R.string.error_so_missing_msg, newSo))
                                .setPositiveButton(R.string.action_ok, null)
                                .show();
                        return;
                    }
                    if (!isLikelySoName(newSo)) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.warn_not_so_title)
                                .setMessage(getString(R.string.warn_not_so_msg, newSo))
                                .setPositiveButton(R.string.warn_not_so_continue, (dd, ww) -> {
                                    applyAndSave(config, newSo, toggle);
                                    dialog.dismiss();
                                })
                                .setNegativeButton(R.string.dialog_action_cancel, null)
                                .show();
                        return;
                    }
                    applyAndSave(config, newSo, toggle);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void applyAndSave(InjectConfig config, String soName, MaterialButtonToggleGroup toggle) {
        config.soName = soName;
        int checkedId = toggle.getCheckedButtonId();
        if (checkedId == R.id.btnLinker) config.injectModel = "custom_linker";
        else if (checkedId == R.id.btnJit) config.injectModel = "memfd_jit";
        else config.injectModel = "memfd";
        saveAndNotify();
    }

    private static boolean isLikelySoName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".so");
    }

    private void showTmpFilePicker(TextInputEditText target) {
        new Thread(() -> {
            List<String> files;
            try {
                files = repository.listTmpFiles();
            } catch (Exception e) {
                files = null;
            }
            final List<String> finalFiles = files;
            runOnUiThread(() -> {
                if (finalFiles == null) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.picker_tmp_title)
                            .setMessage(R.string.picker_tmp_failed)
                            .setPositiveButton(R.string.action_ok, null)
                            .show();
                    return;
                }
                if (finalFiles.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.picker_tmp_title)
                            .setMessage(R.string.picker_tmp_empty)
                            .setPositiveButton(R.string.action_ok, null)
                            .show();
                    return;
                }
                String[] arr = finalFiles.toArray(new String[0]);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.picker_tmp_title)
                        .setItems(arr, (dialog, which) -> {
                            String picked = arr[which];
                            target.setText(picked);
                            target.setSelection(picked.length());
                            if (!isLikelySoName(picked)) {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle(R.string.warn_not_so_title)
                                        .setMessage(getString(R.string.warn_not_so_msg, picked))
                                        .setPositiveButton(R.string.action_ok, null)
                                        .show();
                            }
                        })
                        .setNegativeButton(R.string.dialog_action_cancel, null)
                        .show();
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_APP && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra("package");
            if (TextUtils.isEmpty(pkg)) return;
            for (InjectConfig c : configs) {
                if (pkg.equals(c.packageName)) return;
            }
            configs.add(new InjectConfig(pkg));
            saveAndNotify();
        }
    }
}
